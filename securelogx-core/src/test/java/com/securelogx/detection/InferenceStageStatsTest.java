package com.securelogx.detection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InferenceStageStatsTest {

    @Test
    void excludesWarmupFromStageTotals() {
        InferenceStageStats before = new InferenceStageStats(
                100, 200, 300, 400, 500, 250, 600, 700
        );
        InferenceStageStats after = new InferenceStageStats(
                160, 280, 310, 470, 680, 320, 630, 720
        );
        InferenceStageStats measured = after.minus(before);
        assertEquals(60, measured.deterministicNanos());
        assertEquals(80, measured.tokenizationNanos());
        assertEquals(10, measured.windowAssemblyNanos());
        assertEquals(70, measured.tensorPreparationNanos());
        assertEquals(180, measured.nativeOnnxRunNanos());
        assertEquals(70, measured.windowedNativeOnnxRunNanos());
        assertEquals(30, measured.decodingNanos());
        assertEquals(20, measured.resolutionAndMaskingNanos());
    }
}
