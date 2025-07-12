package com.securelogx.consumer;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.io.SecureFileAppender;
import com.securelogx.model.LogEvent;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Kafka consumer that masks SECURE-level messages and writes all logs to a file.
 * <p>
 * Subscribes to a Kafka topic, batches SECURE events for NER masking, and
 * outputs both masked and unmasked records to disk.
 */
public class MaskingConsumer {
    private static final int BATCH_SIZE = 8;
    private final KafkaConsumer<String, String> consumer;
    private final SecureFileAppender appender;
    private final ParallelTokenizer tokenizer;
    private final ONNXDynamicInferenceEngine engine;
    private final SecureLogXConfig config;

    public MaskingConsumer(String env) throws Exception {
        this.config = new SecureLogXConfig(env);

        // Prepare Kafka consumer properties
        Properties props = new Properties();
        Map<String, Object> kafkaProps = config.getKafkaProperties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaProps.getOrDefault("bootstrap.servers", "localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                kafkaProps.getOrDefault("group.id", "securelogx-consumer"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                kafkaProps.getOrDefault("auto.offset.reset", "earliest"));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                kafkaProps.getOrDefault("enable.auto.commit", "false"));

        // Create and subscribe
        this.consumer = new KafkaConsumer<>(props);
        String topic = kafkaProps.getOrDefault("topic", "secure-logx").toString();
        this.consumer.subscribe(Collections.singletonList(topic));

        // File writer and NER engine
        this.appender  = new SecureFileAppender(config.getLogFilePath());
        this.tokenizer = new ParallelTokenizer(config.getTokenizerPath());
        this.engine    = new ONNXDynamicInferenceEngine(config.getModelPath(), config);
    }

    /**
     * Starts the polling loop, processing messages until interrupted.
     */
    public void run() {
        List<LogEvent> secureBatch = new ArrayList<>(BATCH_SIZE);
        try {
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    LogEvent event = LogEvent.fromRaw(rec.value());
                    if (event.requiresNER()) {
                        secureBatch.add(event);
                        if (secureBatch.size() >= BATCH_SIZE) {
                            flushSecureBatch(secureBatch);
                        }
                    } else {
                        appender.write(rec.value());
                    }
                }
                if (!secureBatch.isEmpty()) {
                    flushSecureBatch(secureBatch);
                }
                consumer.commitSync();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void flushSecureBatch(List<LogEvent> batch) {
        try {
            List<String> masked = engine.runBatch(tokenizer, batch);
            for (String line : masked) {
                appender.write(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            batch.clear();
        }
    }

    /**
     * Closes consumer, NER engine, and file appender.
     */
    private void shutdown() {
        try {
            consumer.wakeup();
            appender.close();
            engine.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        String env = args.length > 0 ? args[0] : "dev";
        new MaskingConsumer(env).run();
    }
}
