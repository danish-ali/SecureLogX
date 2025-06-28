package com.securelogx.kafka;

import com.securelogx.engine.SecureLogX;
import com.securelogx.model.LogEvent;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public class SecureLogXKafkaListener {

    private final KafkaConsumer<String, String> consumer;
    private final SecureLogX secureLogX;

    public SecureLogXKafkaListener(Properties config, SecureLogX secureLogX) {
        this.secureLogX = secureLogX;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getProperty("kafka.bootstrap.servers"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getProperty("kafka.group.id", "securelogx-group"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Optional: TLS
        if ("SSL".equalsIgnoreCase(config.getProperty("kafka.security.protocol"))) {
            props.put("security.protocol", "SSL");
            props.put("ssl.truststore.location", config.getProperty("ssl.truststore.location"));
            props.put("ssl.truststore.password", config.getProperty("ssl.truststore.password"));
        }

        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Collections.singletonList(config.getProperty("kafka.topic", "securelog.masking.input")));
    }

    public void start() {
        System.out.println("[SecureLogX-KafkaListener] Starting listener loop...");

        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    LogEvent event = LogEvent.fromJson(record.value()); // You must implement fromJson()
                    secureLogX.process(event); // Reuse existing logic
                } catch (Exception e) {
                    System.err.println("[SecureLogX-KafkaListener] Failed to process message: " + e.getMessage());
                }
            }
        }
    }
}
