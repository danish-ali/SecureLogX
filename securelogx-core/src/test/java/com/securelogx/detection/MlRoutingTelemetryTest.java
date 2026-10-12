package com.securelogx.detection;

import com.securelogx.ner.impl.LabelAwareMaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MlRoutingTelemetryTest {

    @Test
    void proposesOnlyShapeAndNeverRetainsRawValueOrPrefix() {
        MlRoutingTelemetry telemetry = new MlRoutingTelemetry("ORG42", true);
        String text = "customerReference=CUST-834729";
        int start = text.indexOf('=') + 1;

        telemetry.recordApprovedRuleMatch();
        telemetry.recordMlRoute("unknown-key:customerReference");
        telemetry.observeMlResolved(
                text,
                List.of(new LabelAwareMaskingEngine.EntitySpan(
                        start, text.length(), "BUSINESS_ID"
                ))
        );
        telemetry.observeMlResolved(
                text,
                List.of(new LabelAwareMaskingEngine.EntitySpan(
                        start, text.length(), "BUSINESS_ID"
                ))
        );

        MlRoutingTelemetry.Snapshot snapshot = telemetry.snapshot();
        assertTrue(snapshot.enabled());
        assertEquals(1L, snapshot.approvedRuleMatches());
        assertEquals("ORG42", snapshot.organizationId());
        assertEquals(2L, snapshot.proposedCandidates().get(0).observations());
        assertEquals(
                "PREFIXED_DIGITS_P5_N6",
                snapshot.proposedCandidates().get(0).shape()
        );
        assertEquals(1L, snapshot.routingReasons().get("unknown-key").longValue());
        assertFalse(snapshot.toString().contains("CUST-"));
        assertFalse(snapshot.toString().contains("834729"));
    }

    @Test
    void disabledTelemetryCollectsNothing() {
        MlRoutingTelemetry telemetry = new MlRoutingTelemetry("ORG42", false);
        String text = "customerReference=CUST-834729";
        telemetry.recordMlRoute("unknown-key:customerReference");
        telemetry.observeMlResolved(
                text,
                List.of(new LabelAwareMaskingEngine.EntitySpan(
                        text.indexOf('=') + 1,
                        text.length(),
                        "BUSINESS_ID"
                ))
        );
        assertTrue(telemetry.snapshot().proposedCandidates().isEmpty());
        assertTrue(telemetry.snapshot().routingReasons().isEmpty());
        assertEquals(0L, telemetry.snapshot().approvedRuleMatches());
    }

    @Test
    void partialSpanCannotSuggestWholeValueRule() {
        MlRoutingTelemetry telemetry = new MlRoutingTelemetry("ORG42", true);
        String text = "customerReference=CUST-834729";
        int start = text.indexOf('=') + 1;
        telemetry.observeMlResolved(
                text,
                List.of(new LabelAwareMaskingEngine.EntitySpan(
                        start + 5, text.length(), "BUSINESS_ID"
                ))
        );
        assertTrue(telemetry.snapshot().proposedCandidates().isEmpty());
    }

    @Test
    void doesNotPromoteFreeTextOrUnboundedValues() {
        MlRoutingTelemetry telemetry = new MlRoutingTelemetry("ORG42", true);
        telemetry.observeMlResolved(
                "customer John Doe",
                List.of(new LabelAwareMaskingEngine.EntitySpan(
                        9, 17, "PERSON_NAME"
                ))
        );
        telemetry.observeMlResolved(
                "customerReference=(.*)+",
                List.of(new LabelAwareMaskingEngine.EntitySpan(
                        18, 23, "BUSINESS_ID"
                ))
        );
        assertTrue(telemetry.snapshot().proposedCandidates().isEmpty());
    }
}
