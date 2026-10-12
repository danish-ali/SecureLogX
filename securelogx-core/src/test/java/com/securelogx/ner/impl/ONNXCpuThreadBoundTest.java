package com.securelogx.ner.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ONNXCpuThreadBoundTest {

    @Test
    void limitsNativeCpuThreadsToConfiguredMaximum() {
        assertEquals(
                4,
                ONNXDynamicInferenceEngine.resolvedCpuThreads(
                        true, 24, 4
                )
        );
        assertEquals(
                2,
                ONNXDynamicInferenceEngine.resolvedCpuThreads(
                        true, 2, 8
                )
        );
    }

    @Test
    void singleThreadModeNeverOversubscribes() {
        assertEquals(
                1,
                ONNXDynamicInferenceEngine.resolvedCpuThreads(
                        false, 32, 8
                )
        );
    }

    @Test
    void rejectsInvalidCpuThreadInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> ONNXDynamicInferenceEngine.resolvedCpuThreads(
                        true, 0, 4
                ));
        assertThrows(IllegalArgumentException.class,
                () -> ONNXDynamicInferenceEngine.resolvedCpuThreads(
                        true, 8, 0
                ));
    }
}
