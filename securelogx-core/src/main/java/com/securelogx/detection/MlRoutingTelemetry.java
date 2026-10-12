package com.securelogx.detection;

import com.securelogx.ner.impl.LabelAwareMaskingEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in, in-memory, bounded ML routing telemetry and *proposals only*.
 *
 * Never retains log text, matched values, or a sampled payload. Proposals
 * cannot activate rules: a separately reviewed versioned policy is required.
 */
public final class MlRoutingTelemetry {
    private static final int MAX_CANDIDATE_GROUPS = 128;
    private static final int MAX_ROUTING_REASONS = 32;
    private static final Pattern SINGLE_FIELD = Pattern.compile(
            "\\A([A-Za-z][A-Za-z0-9_.-]{1,40})=([^\\r\\n]{4,64})\\z"
    );
    private static final Pattern DIGITS = Pattern.compile("[0-9]{4,20}");
    private static final Pattern PREFIXED_DIGITS =
            Pattern.compile("([A-Za-z]{2,12}[-_])([0-9]{4,20})");
    private static final Pattern SAFE_ENTITY =
            Pattern.compile("[A-Z][A-Z0-9_]{1,40}");
    private static final Pattern SAFE_REASON =
            Pattern.compile("[a-z][a-z0-9-]{0,40}");

    private final String organizationId;
    private final boolean enabled;
    private final Map<Bucket, Long> candidates = new LinkedHashMap<>();
    private final Map<String, Long> mlReasons = new LinkedHashMap<>();
    private long approvedRuleMatches;

    public MlRoutingTelemetry(String organizationId, boolean enabled) {
        this.organizationId = organizationId == null ? "" : organizationId;
        this.enabled = enabled;
    }

    public synchronized void recordApprovedRuleMatch() {
        if (enabled) {
            approvedRuleMatches++;
        }
    }

    public synchronized void recordMlRoute(String reason) {
        if (!enabled) {
            return;
        }
        // The original reason can contain an unknown field name. Only
        // retain its fixed category, never untrusted suffixes or payload.
        String category = reason == null
                ? "unknown"
                : reason.split(":", 2)[0];
        if (!SAFE_REASON.matcher(category).matches()) {
            category = "unknown";
        }
        if (mlReasons.containsKey(category)
                || mlReasons.size() < MAX_ROUTING_REASONS) {
            mlReasons.merge(category, 1L, Long::sum);
        }
    }

    /**
     * Called only after successful ML masking. Observes a candidate only if
     * a model span covers the entire value of a single structured field.
     */
    public synchronized void observeMlResolved(
            String text,
            List<LabelAwareMaskingEngine.EntitySpan> mlSpans
    ) {
        if (!enabled || text == null || mlSpans == null || mlSpans.isEmpty()) {
            return;
        }
        Matcher field = SINGLE_FIELD.matcher(text);
        if (!field.matches()) {
            return;
        }

        String value = field.group(2);
        String shape = shapeOf(value);
        if (shape == null) {
            return;
        }

        for (LabelAwareMaskingEngine.EntitySpan span : mlSpans) {
            if (span.start() > field.start(2)
                    || span.end() < field.end(2)
                    || !SAFE_ENTITY.matcher(span.entityType()).matches()) {
                continue;
            }
            Bucket bucket = new Bucket(
                    field.group(1),
                    shape,
                    span.entityType()
            );
            if (candidates.containsKey(bucket)
                    || candidates.size() < MAX_CANDIDATE_GROUPS) {
                candidates.merge(bucket, 1L, Long::sum);
            }
            return;
        }
    }

    private static String shapeOf(String value) {
        if (DIGITS.matcher(value).matches()) {
            return "DIGITS_" + value.length();
        }
        Matcher prefix = PREFIXED_DIGITS.matcher(value);
        if (prefix.matches()) {
            // Deliberately never store the prefix, which can be a secret.
            return "PREFIXED_DIGITS_P" + prefix.group(1).length()
                    + "_N" + prefix.group(2).length();
        }
        return null;
    }

    public synchronized Snapshot snapshot() {
        List<Candidate> proposed = new ArrayList<>();
        candidates.forEach((bucket, count) ->
                proposed.add(new Candidate(
                        bucket.field(),
                        bucket.shape(),
                        bucket.entityType(),
                        count
                )));
        proposed.sort(
                Comparator.comparingLong(Candidate::observations)
                        .reversed()
                        .thenComparing(Candidate::field)
        );
        return new Snapshot(
                organizationId,
                enabled,
                List.copyOf(proposed),
                Map.copyOf(mlReasons),
                approvedRuleMatches
        );
    }

    private record Bucket(String field, String shape, String entityType) {
    }

    public record Candidate(
            String field,
            String shape,
            String entityType,
            long observations
    ) {
    }

    public record Snapshot(
            String organizationId,
            boolean enabled,
            List<Candidate> proposedCandidates,
            Map<String, Long> routingReasons,
            long approvedRuleMatches
    ) {
    }
}
