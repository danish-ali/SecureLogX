package com.securelogx.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;

/**
 * Configuration loader for SecureLogX.
 * Reads properties from classpath: securelogx-<env>.properties
 */
public class SecureLogXConfig {
    private final Properties props;


    public SecureLogXConfig(String env) throws IOException {
        props = new Properties();
        String resource = String.format("securelogx-%s.properties", env);

        // 1) Try loading from classpath
        InputStream in = getClass().getClassLoader().getResourceAsStream(resource);

        // 2) If not found there, fall back to the working directory
        if (in == null) {
            Path path = Paths.get(resource);
            if (Files.exists(path)) {
                in = Files.newInputStream(path);
            }
        }

        // 3) Bail if still null
        if (in == null) {
            throw new IOException("Configuration file not found: " + resource);
        }

        // 4) Load and close
        try (InputStream input = in) {
            props.load(input);
        }
    }

    public String getMode() {
        return props.getProperty("securelogx.mode", "CPU_SINGLE");
    }

    public int getMaxCpuThreads() {
        return Integer.parseInt(props.getProperty("securelogx.maxCpuThreads", "8"));
    }

    public boolean isMaskingEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.enableMasking", "true"));
    }

    public boolean shouldMaskInCurrentEnv() {
        return Boolean.parseBoolean(props.getProperty("securelogx.maskInEnvironments", "true"));
    }

    public boolean isCpuMultithreadingEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.cpu.multithreading.enabled", "false"));
    }

    public boolean isGpuInferenceEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.gpu.inference.enabled", "false"));
    }

    public String getTokenizerPath() {
        return props.getProperty("securelogx.tokenizerPath");
    }

    public String getModelPath() {
        return props.getProperty("securelogx.modelPath");
    }

    public String getLogFilePath() {
        return props.getProperty("securelogx.log.file", "application.log");
    }

    public Map<String, Object> getKafkaProperties() {
        Map<String, Object> map = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("securelogx.kafka.")) {
                String key = name.substring("securelogx.kafka.".length());
                map.put(key, props.getProperty(name));
            }
        }
        return map;
    }
}
