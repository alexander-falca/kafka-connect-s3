package com.deviantart.kafka_connect_s3;

import java.util.HashMap;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;


public class S3SinkTask extends SinkTask {

  private static final Logger log = LoggerFactory.getLogger(S3SinkTask.class);

  private Map<String, String> config;

  private Map<TopicPartition, BlockGZIPFileWriter> tmpFiles;

  private long GZIPChunkThreshold = 67108864;

  private S3Writer s3;

  public S3SinkTask() {
    tmpFiles = new HashMap<>();
  }

  @Override
  public String version() {
    return S3SinkConnectorConstants.VERSION;
  }

  @Override
  public void start(Map<String, String> props) throws ConnectException {
    config = props;
    String chunkThreshold = config.get("compressed_block_size");
    if (chunkThreshold == null) {
      try {
        this.GZIPChunkThreshold = Long.parseLong(chunkThreshold);
      } catch (NumberFormatException nfe) {
        // keep default
      }
    }
    String bucket = config.get("s3.bucket");
    String prefix = config.get("s3.prefix");
    if (bucket == null || bucket == "") {
      throw new ConnectException("S3 bucket must be configured");
    }
    if (prefix == null || prefix == "") {
      prefix = "/";
    }
    s3 = new S3Writer(bucket, prefix);

    // Recover initial assignments
    Set<TopicPartition> assignment = context.assignment();
    recoverAssignment(assignment);
  }

  @Override
  public void stop() throws ConnectException {
    // TODO we could try to be smart and flush buffer files to be resumed
    // but for now we just start again from where we got to in S3 and overwrite any
    // buffers on disk.
  }

  @Override
  public void put(Collection<SinkRecord> records) throws ConnectException {
    for (SinkRecord record : records) {
      try {
        String topic = record.topic();
        int partition = record.kafkaPartition();
        TopicPartition tp = new TopicPartition(topic, partition);
        BlockGZIPFileWriter buffer = tmpFiles.get(tp);
        if (buffer == null) {
          log.error("Trying to put {} records to partition {} which doesn't exist yet", records.size(), tp);
          throw new ConnectException("Trying to put records for a topic partition that has not be assigned");
        }
        buffer.write(record.value().toString());
      } catch (IOException e) {
        throw new RetriableException("Failed to write to buffer", e);
      }
    }
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) throws ConnectException {
    for (TopicPartition tp : offsets.keySet()) {
      BlockGZIPFileWriter writer = tmpFiles.get(tp);
      if (writer == null) {
        throw new ConnectException("Trying to flush records for a topic partition that has not be assigned");
      }
      if (writer.getNumRecords() == 0) {
        // Not done anything yet
        log.info("No new records for partition {}", tp);
        continue;
      }
      try {
        writer.close();

        long nextOffset = s3.putChunk(writer.getDataFilePath(), writer.getIndexFilePath(), tp);

        OffsetAndMetadata om = offsets.get(tp);

        // Now reset writer to a new one
        tmpFiles.put(tp, this.createNextBlockWriter(tp, om.offset()));
        log.info("Successfully uploaded chunk for {} now at offset {}", tp, om.offset());
      } catch (FileNotFoundException fnf) {
        throw new ConnectException("Failed to find local dir for temp files", fnf);
      } catch (IOException e) {
        throw new RetriableException("Failed S3 upload", e);
      }
    }
  }

  private BlockGZIPFileWriter createNextBlockWriter(TopicPartition tp, long nextOffset) throws ConnectException, IOException {
    String name = String.format("%s-%05d", tp.topic(), tp.partition());
    String path = config.get("local.buffer.dir");
    if (path == null) {
      throw new ConnectException("No local buffer file path configured");
    }
    return new BlockGZIPFileWriter(name, path, nextOffset, this.GZIPChunkThreshold);
  }

  @Override
  public void onPartitionsAssigned(Collection<TopicPartition> partitions) throws ConnectException {
    recoverAssignment(partitions);
  }

  @Override
  public void onPartitionsRevoked(Collection<TopicPartition> partitions) throws ConnectException {
    for (TopicPartition tp : partitions) {
      // See if this is a new assignment
      BlockGZIPFileWriter writer = this.tmpFiles.remove(tp);
      if (writer != null) {
        log.info("Revoked partition {} deleting buffer", tp);
        try {
          writer.close();
          writer.delete();
        } catch (IOException ioe) {
          throw new ConnectException("Failed to resume TopicPartition form S3", ioe);
        }
      }
    }
  }

  private void recoverAssignment(Collection<TopicPartition> partitions) throws ConnectException {
    for (TopicPartition tp : partitions) {
      // See if this is a new assignment
      if (this.tmpFiles.get(tp) == null) {
        log.info("Assigned new partition {} creating buffer writer", tp);
        try {
          recoverPartition(tp);
        } catch (IOException ioe) {
          throw new ConnectException("Failed to resume TopicPartition from S3", ioe);
        }
      }
    }
  }

  private void recoverPartition(TopicPartition tp) throws IOException {
    this.context.pause(tp);

    // Recover last committed offset from S3
    long offset = s3.fetchOffset(tp);

    log.info("Recovering partition {} from offset {}", tp, offset);

    BlockGZIPFileWriter w = createNextBlockWriter(tp, offset);
    tmpFiles.put(tp, w);

    this.context.offset(tp, offset);
    this.context.resume(tp);
  }
}
