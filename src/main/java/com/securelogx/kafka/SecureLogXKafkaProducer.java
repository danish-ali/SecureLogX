package com.securelogx.kafka;

import com.securelogx.model.LogEvent;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

public class SecureLogXKafkaProducer {

    private final KafkaProducer<String, String> producer;
    private final String topic;

    public SecureLogXKafkaProducer(Properties config) {
        this.topic = config.getProperty("kafka.topic", "securelog.masking.input");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getProperty("kafka.bootstrap.servers"));
        props.put(ProducerConfig.ACKS_CONFIG, config.getProperty("kafka.acks", "all"));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.getProperty("kafka.compression.type", "lz4"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Optional: TLS config (if needed)
        if ("SSL".equalsIgnoreCase(config.getProperty("kafka.security.protocol"))) {
            props.put("security.protocol", "SSL");
            props.put("ssl.truststore.location", config.getProperty("ssl.truststore.location"));
            props.put("ssl.truststore.password", config.getProperty("ssl.truststore.password"));
        }

        this.producer = new KafkaProducer<>(props);
    }

    public void sendLogEvent(LogEvent event) {
        String message = event.toJson(); // Implement toJson() in LogEvent
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.getId(), message);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                System.err.println("[SecureLogX-KafkaProducer] Failed to send log: " + exception.getMessage());
            }
        });
    }

    public void close() {
        producer.flush();
        producer.close();
    }
}
