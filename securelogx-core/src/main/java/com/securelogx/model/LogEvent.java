package com.securelogx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securelogx.util.CachedClock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable SecureLogX event envelope.
 *
 * Severity is preserved from the source logging framework. Protection is not
 * inferred from severity; standard INFO/WARN/ERROR/etc. events are eligible
 * for SecureLogX inspection just like the legacy SECURE level.
 */
public class LogEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROCESS_INSTANCE_ID =
            UUID.randomUUID().toString();

    private static final Pattern RAW_PATTERN = Pattern.compile(
            "timestamp=(\\S+) level=(\\S+) traceId=(\\S+) seq=(\\d+)"
                    + "(?: instanceId=(\\S+))? message=\\\"(.*)\\\""
    );

    private final String message;
    private final LogLevel level;
    private final boolean showLastFour;
    private final String traceId;
    private final long sequenceNumber;
    private final long eventTimestamp;
    private final long ingestTimestamp;
    private final String instanceId;
    private final String id;

    /**
     * Compatibility constructor. The event timestamp is the current time.
     */
    public LogEvent(
            String message,
            LogLevel level,
            boolean showLastFour,
            String traceId,
            long sequenceNumber
    ) {
        this(
                message,
                level,
                showLastFour,
                traceId,
                sequenceNumber,
                CachedClock.now()
        );
    }

    /**
     * Constructor for appender/framework integration where the original event
     * timestamp is already known.
     */
    public LogEvent(
            String message,
            LogLevel level,
            boolean showLastFour,
            String traceId,
            long sequenceNumber,
            long eventTimestamp
    ) {
        this(
                message,
                level,
                showLastFour,
                traceId,
                sequenceNumber,
                eventTimestamp,
                PROCESS_INSTANCE_ID
        );
    }

    private LogEvent(
            String message,
            LogLevel level,
            boolean showLastFour,
            String traceId,
            long sequenceNumber,
            long eventTimestamp,
            String instanceId
    ) {
        this.message = message;
        this.level = level;
        this.showLastFour = showLastFour;
        this.traceId = traceId;
        this.sequenceNumber = sequenceNumber;
        this.eventTimestamp = eventTimestamp;
        this.ingestTimestamp = CachedClock.now();
        this.instanceId = instanceId;
        this.id = instanceId + "-" + sequenceNumber;
    }

    public String getMessage() {
        return message;
    }

    public LogLevel getLevel() {
        return level;
    }

    public boolean shouldShowLastFour() {
        return showLastFour;
    }

    /**
     * All application severities are eligible for protection. H1 determines
     * whether the record is deterministic-only or requires ML.
     */
    public boolean requiresProtection() {
        return true;
    }

    /**
     * Compatibility alias retained for older integrations.
     */
    @Deprecated(forRemoval = false)
    public boolean requiresNER() {
        return requiresProtection();
    }

    public String getTraceId() {
        return traceId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Backward-compatible timestamp accessor. This is now the original event
     * timestamp rather than the later masking/write completion time.
     */
    public long getTimestamp() {
        return eventTimestamp;
    }

    public long getEventTimestamp() {
        return eventTimestamp;
    }

    public long getIngestTimestamp() {
        return ingestTimestamp;
    }

    public String getId() {
        return id;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String formatWithMessage(String outputMessage) {
        return String.format(
                "timestamp=%s level=%s traceId=%s seq=%d instanceId=%s message=\"%s\"",
                Instant.ofEpochMilli(eventTimestamp),
                level,
                traceId,
                sequenceNumber,
                instanceId,
                outputMessage
        );
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize LogEvent", e);
        }
    }

    public static LogEvent fromJson(String json) {
        try {
            return MAPPER.readValue(json, LogEvent.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize LogEvent", e);
        }
    }

    public static LogEvent fromRaw(String raw) {
        Matcher matcher = RAW_PATTERN.matcher(raw);
        if (matcher.matches()) {
            long timestamp = parseTimestampMillis(matcher.group(1));
            LogLevel level = LogLevel.valueOf(matcher.group(2));
            String traceId = matcher.group(3);
            long sequence = Long.parseLong(matcher.group(4));
            String parsedInstanceId = matcher.group(5);
            String message = matcher.group(6);

            return new LogEvent(
                    message,
                    level,
                    false,
                    traceId,
                    sequence,
                    timestamp,
                    parsedInstanceId == null
                            ? PROCESS_INSTANCE_ID
                            : parsedInstanceId
            );
        }

        // Compatibility fallback for unstructured input. A proper SecureLogX
        // ingress path should replace sequence 0 with a process-wide sequence.
        return new LogEvent(
                raw,
                LogLevel.INFO,
                false,
                UUID.randomUUID().toString(),
                0L
        );
    }

    private static long parseTimestampMillis(String rawTimestamp) {
        try {
            return Instant.parse(rawTimestamp).toEpochMilli();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(rawTimestamp)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (Exception ignoredAgain) {
                return CachedClock.now();
            }
        }
    }
}
