package com.securelogx.detection;

import com.securelogx.ner.impl.LabelAwareMaskingEngine;
import com.securelogx.model.MaskingFieldOrigin;

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
    private static final int MAX_ROUTE_DETAILS = 128;
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
    private final Map<RouteBucket, Long> routeDetails = new LinkedHashMap<>();
    private long approvedRuleMatches;
    private long safeMetadataMatches;
    private long duplicateFieldsSaved;

    public MlRoutingTelemetry(String organizationId, boolean enabled) {
        this.organizationId = organizationId == null ? "" : organizationId;
        this.enabled = enabled;
    }

    public synchronized void recordApprovedRuleMatch() {
        if (enabled) {
            approvedRuleMatches++;
        }
    }

    public synchronized void recordSafeMetadataMatch() {
        if (enabled) {
            safeMetadataMatches++;
        }
    }

    public synchronized void recordDuplicateFieldsSaved(int count) {
        if (enabled && count > 0) {
            duplicateFieldsSaved += count;
        }
    }

    public void recordMlRoute(String reason) {
        recordMlRoute(reason, null, MaskingFieldOrigin.OTHER);
    }

    /**
     * Report why and where ML was invoked without retaining raw values.
     * The category and origin are restricted to fixed controlled vocabularies.
     */
    public synchronized void recordMlRoute(
            String reason,
            String text,
            MaskingFieldOrigin origin
    ) {
        if (!enabled) {
            return;
        }
        // The original reason may include unknown field names: discard
        // anything after ':' rather than persisting raw/untrusted suffixes.
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

        String fieldName = "UNSTRUCTURED";
        String valueShape = "OTHER";
        if (text != null) {
            Matcher field = SINGLE_FIELD.matcher(text);
            if (field.matches()) {
                fieldName = field.group(1);
                String inferred = shapeOf(field.group(2));
                valueShape = inferred == null ? "OTHER" : inferred;
            }
        }
        MaskingFieldOrigin trustedOrigin = origin == null
                ? MaskingFieldOrigin.OTHER : origin;
        RouteBucket bucket = new RouteBucket(
                category, trustedOrigin, fieldName, valueShape
        );
        if (routeDetails.containsKey(bucket)
                || routeDetails.size() < MAX_ROUTE_DETAILS) {
            routeDetails.merge(bucket, 1L, Long::sum);
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
        List<RouteDetail> details = new ArrayList<>();
        routeDetails.forEach((bucket, count) ->
                details.add(new RouteDetail(
                        bucket.reason(),
                        bucket.origin(),
                        bucket.field(),
                        bucket.shape(),
                        count
                ))
        );
        details.sort(
                Comparator.comparingLong(RouteDetail::observations)
                        .reversed()
                        .thenComparing(RouteDetail::field)
        );
        return new Snapshot(
                organizationId,
                enabled,
                List.copyOf(proposed),
                Map.copyOf(mlReasons),
                approvedRuleMatches,
                List.copyOf(details),
                safeMetadataMatches,
                duplicateFieldsSaved
        );
    }

    private record Bucket(String field, String shape, String entityType) {
    }

    private record RouteBucket(
            String reason,
            MaskingFieldOrigin origin,
            String field,
            String shape
    ) {
    }

    public record RouteDetail(
            String reason,
            MaskingFieldOrigin origin,
            String field,
            String shape,
            long observations
    ) {
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
            long approvedRuleMatches,
            List<RouteDetail> routingDetails,
            long safeMetadataMatches,
            long duplicateFieldsSaved
    ) {
    }
}
