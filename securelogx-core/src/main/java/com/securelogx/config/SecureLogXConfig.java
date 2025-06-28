package com.securelogx.config;

import java.io.InputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Loads and manages environment-based config settings.
 * This class reads and holds configuration for SecureLogX.
 * It loads properties from a file like securelogx-dev.properties or securelogx-prod.properties,
 * depending on the environment.
 */


public class SecureLogXConfig {
    private Properties props;

    public SecureLogXConfig(String env) {
        String configPath = "/securelogx-" + env + ".properties";
        props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Could not load config: " + configPath, e);
        }
    }

    /**
     * 	Enable/disable masking for the whole app
     * @return
     */
    public boolean isMaskingEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.enableMasking", "true"));
    }

    public boolean isShowLastFourEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.showLastFour", "false"));
    }

    /**
     * Points to ONNX  files (e.g., resources/models)
     * @return
     */
    public String getModelPath() {
        return props.getProperty("securelogx.modelPath", "onnx-model/securelogx-ner.onnx");
    }

    /**
     * Points to tokenizer files (e.g., resources/models)
     * @return
     */
    public String getTokenizerPath() {
        return props.getProperty("securelogx.tokenizerPath", "onnx-model/tokenizer.json");
    }

    /**
     * Controls where your masked logs are written
     * @return
     */
    public String getLogFilePath() {
        return props.getProperty("securelogx.logFile", "logs/securelogx.log");
    }

    public String getEnvironment() {
        return props.getProperty("securelogx.environment", "dev");
    }

    /**
     * Prevent masking in dev, enable only in prod
     * @return
     */
    public boolean shouldMaskInCurrentEnv() {
        String onlyIn = props.getProperty("securelogx.masking.onlyIn", "prod");
        return getEnvironment().equalsIgnoreCase(onlyIn);
    }

    public boolean isCpuMultithreadingEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.cpu.multithreading.enabled", "false"));
    }

    public boolean isGpuInferenceEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.gpu.inference.enabled", "false"));
    }

    public boolean isKafkaEnabled() {
        return Boolean.parseBoolean(props.getProperty("securelogx.kafka", "false"));
    }

    /**
     * Expose all loaded properties so that Kafka producer/consumer
     * can pull out the kafka.* and ssl.* settings.
     */
    public Properties getKafkaProperties() {
        // Option A: just return everything (producer will only use kafka.* keys)
      //  return this.props;

        // Option B: if you want to be strict, filter only kafka.* and ssl.* keys:

    Properties kafkaProps = new Properties();
    for (String key : props.stringPropertyNames()) {
        if (key.startsWith("kafka.") || key.startsWith("ssl.")) {
            kafkaProps.setProperty(key, props.getProperty(key));
        }
    }
    return kafkaProps;

    }


}
