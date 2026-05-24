package com.securelogx.app;

import com.securelogx.api.SecureLogger;
import com.securelogx.config.SecureLogXConfig;
import com.securelogx.consumer.MaskingConsumer;
import com.securelogx.engine.SecureLogX;
import com.securelogx.model.LogLevel;

import java.util.Locale;

/**
 * Application launcher for SecureLogX demos.
 *
 * Reads mode and consumer flag from configuration, then:
 *  - In KAFKA mode with consumer enabled: starts MaskingConsumer in a daemon thread
 *  - In all modes: instantiates SecureLogX (producer and/or local masking)
 *  - Keeps main thread alive when consumer is active
 */
public class AppLauncher {
    public static void main(String[] args) throws Exception {
        // 1) Load environment-specific config
        String env = System.getenv().getOrDefault("SECURELOGX_ENV", "dev");
        SecureLogXConfig config = new SecureLogXConfig(env);
        String mode = config.getMode().toUpperCase(Locale.ROOT);

        // 2) Read consumer-enabled flag (default true)
        boolean consumerEnabled = Boolean.parseBoolean(
                config.getKafkaProperties().getOrDefault("consumer.enabled", "true").toString()
        );

        // 3) In KAFKA mode, optionally start the consumer
        if ("KAFKA".equals(mode)) {
            if (consumerEnabled) {
                Thread consumerThread = new Thread(() -> {
                    try {
                        MaskingConsumer consumer = new MaskingConsumer(env);
                        System.out.println("[AppLauncher] MaskingConsumer started on topic: " +
                                config.getKafkaProperties().get("topic"));
                        consumer.run();  // blocking loop
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, "MaskingConsumerThread");
                consumerThread.setDaemon(true);
                consumerThread.start();
            } else {
                System.out.println("[AppLauncher] Kafka consumer is disabled by configuration.");
            }
        }

        // 4) Always initialize SecureLogX (producer or local modes)
        SecureLogX logx = new SecureLogX();
        System.out.println("[AppLauncher] SecureLogX initialized in mode: " + mode);

        // Now use logger API anywhere:
        SecureLogger.log(LogLevel.INFO,   "Hello World");
        SecureLogger.log(LogLevel.SECURE, "User SSN: 123-45-6789", true);

        // 5) If consumer is active, prevent JVM exit
        if ("KAFKA".equals(mode) && consumerEnabled) {
            Thread.currentThread().join();
        }
    }
}
