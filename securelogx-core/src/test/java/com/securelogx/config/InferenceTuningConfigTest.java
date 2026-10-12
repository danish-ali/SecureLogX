package com.securelogx.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InferenceTuningConfigTest {

    @Test
    void systemOverridesAllowControlledCpuAndWindowTuning() throws Exception {
        String cpuKey = "securelogx.maxCpuThreads";
        String windowKey = "securelogx.model.maxInferenceWindowsPerBatch";
        String previousCpu = System.getProperty(cpuKey);
        String previousWindow = System.getProperty(windowKey);
        try {
            System.setProperty(cpuKey, "2");
            System.setProperty(windowKey, "4");

            SecureLogXConfig config = new SecureLogXConfig("dev");
            assertEquals(2, config.getMaxCpuThreads());
            assertEquals(4, config.getMaxInferenceWindowsPerBatch());

            System.setProperty(cpuKey, "0");
            assertThrows(
                    IllegalArgumentException.class,
                    config::getMaxCpuThreads
            );
            System.setProperty(cpuKey, "2");
            System.setProperty(windowKey, "0");
            assertThrows(
                    IllegalArgumentException.class,
                    config::getMaxInferenceWindowsPerBatch
            );
            System.setProperty(windowKey, "33");
            assertThrows(
                    IllegalArgumentException.class,
                    config::getMaxInferenceWindowsPerBatch
            );
        } finally {
            restore(cpuKey, previousCpu);
            restore(windowKey, previousWindow);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
