package com.securelogx.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveMaskingConfigTest {

    @Test
    void adaptiveRuleEnforcementAndTelemetryAreOffByDefault()
            throws Exception {
        String oldAdaptive = System.getProperty("securelogx.adaptive.enabled");
        String oldTelemetry = System.getProperty(
                "securelogx.adaptive.mlRoutingTelemetryEnabled"
        );
        try {
            System.clearProperty("securelogx.adaptive.enabled");
            System.clearProperty(
                    "securelogx.adaptive.mlRoutingTelemetryEnabled"
            );

            SecureLogXConfig config = new SecureLogXConfig("dev");
            assertFalse(config.isAdaptiveMaskingEnabled());
            assertFalse(config.isMlRuleTelemetryEnabled());
        } finally {
            restore("securelogx.adaptive.enabled", oldAdaptive);
            restore(
                    "securelogx.adaptive.mlRoutingTelemetryEnabled",
                    oldTelemetry
            );
        }
    }

    @Test
    void organizationCanOptInToRulesIndependentlyOfTelemetry()
            throws Exception {
        String oldAdaptive = System.getProperty("securelogx.adaptive.enabled");
        String oldTelemetry = System.getProperty(
                "securelogx.adaptive.mlRoutingTelemetryEnabled"
        );
        try {
            System.setProperty("securelogx.adaptive.enabled", "true");
            System.setProperty(
                    "securelogx.adaptive.mlRoutingTelemetryEnabled",
                    "false"
            );
            SecureLogXConfig config = new SecureLogXConfig("dev");
            assertTrue(config.isAdaptiveMaskingEnabled());
            assertFalse(config.isMlRuleTelemetryEnabled());

            System.setProperty("securelogx.adaptive.enabled", "false");
            System.setProperty(
                    "securelogx.adaptive.mlRoutingTelemetryEnabled",
                    "true"
            );
            SecureLogXConfig telemetryOnly = new SecureLogXConfig("dev");
            assertFalse(telemetryOnly.isAdaptiveMaskingEnabled());
            assertTrue(telemetryOnly.isMlRuleTelemetryEnabled());
        } finally {
            restore("securelogx.adaptive.enabled", oldAdaptive);
            restore(
                    "securelogx.adaptive.mlRoutingTelemetryEnabled",
                    oldTelemetry
            );
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
