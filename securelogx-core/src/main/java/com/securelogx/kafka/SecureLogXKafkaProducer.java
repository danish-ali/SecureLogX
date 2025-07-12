package com.securelogx.kafka;

import com.securelogx.model.LogEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * A Kafka producer for SecureLogX that publishes raw or formatted log entries to a specified topic.
 * <p>
 * This class handles configuration of serializers, topic resolution, and asynchronous sending of
 * log messages. It is designed for fire-and-forget logging with optional blocking on close.
 */
public class SecureLogXKafkaProducer {
    private final KafkaProducer<String, String> producer;
    private final String topic;

    /**
     * Constructs and configures a Kafka producer based on provided properties.
     * <p>
     * It copies all entries from the input map except the 'topic' key, which is reserved
     * for the name of the Kafka topic. If key or value serializers are not explicitly set,
     * this constructor defaults to using {@link StringSerializer} for both.
     *
     * @param kafkaProps a map of Kafka configuration entries prefixed by "securelogx.kafka." in properties
     *                   (e.g., "bootstrap.servers", "topic", etc.)
     * @throws IllegalArgumentException if the provided topic name is null or empty
     */
    public SecureLogXKafkaProducer(Map<String, Object> kafkaProps) {
        Properties props = new Properties();
        // Copy all config entries except 'topic'
        for (Map.Entry<String, Object> entry : kafkaProps.entrySet()) {
            if (!"topic".equals(entry.getKey())) {
                props.put(entry.getKey(), entry.getValue());
            }
        }
        // Ensure serializers are set
        props.putIfAbsent(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.putIfAbsent(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Resolve topic name
        this.topic = (String) kafkaProps.getOrDefault("topic", "secure-logx");
        if (this.topic == null || this.topic.isEmpty()) {
            throw new IllegalArgumentException("Kafka topic must be specified in configuration");
        }

        this.producer = new KafkaProducer<>(props);
    }

    /**
     * Sends a raw log line to Kafka asynchronously.
     * <p>
     * The returned Future can be used to block until the record is acknowledged and metadata is available,
     * or to handle exceptions if the send fails.
     *
     * @param logLine the raw log string to send
     * @return a {@link Future} containing {@link RecordMetadata} when the send completes
     */
    public Future<RecordMetadata> sendRaw(String logLine) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, logLine);
        return producer.send(record);
    }

    /**
     * Formats a {@link LogEvent} and sends it to Kafka asynchronously.
     * <p>
     * The message is serialized in the standard SecureLogX format:
     * <pre>
     * timestamp=YYYY-MM-DDTHH:mm:ss level=LEVEL traceId=ID seq=N message="..."
     * </pre>
     *
     * @param event the {@link LogEvent} to format and send
     * @return a {@link Future} containing {@link RecordMetadata} when the send completes
     */
    public Future<RecordMetadata> sendLogEvent(LogEvent event) {
        String formatted = String.format(
                "timestamp=%s level=%s traceId=%s seq=%d message=\"%s\"",
                LocalDateTime.now(),
                event.getLevel(),
                event.getTraceId(),
                event.getSequenceNumber(),
                event.getMessage()
        );
        return sendRaw(formatted);
    }

    /**
     * Flushes any pending records and closes the Kafka producer, releasing all resources.
     * <p>
     * This method blocks until all in-flight messages are completed.
     */
    public void close() {
        producer.flush();
        producer.close();
    }
}
