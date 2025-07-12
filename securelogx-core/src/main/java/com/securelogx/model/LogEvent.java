package com.securelogx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securelogx.model.LogLevel;
import com.securelogx.util.CachedClock;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class LogEvent {
    private final String message;
    private final LogLevel level;
    private final boolean showLastFour;
    private final String traceId;
    private final int sequenceNumber;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private long timestamp;
    private String id;

    public LogEvent(String message, LogLevel level, boolean showLastFour, String traceId, int sequenceNumber) {
        this.message = message;
        this.level = level;
        this.showLastFour = showLastFour;
        this.traceId = traceId;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = CachedClock.now(); // 🔥 Efficient timestamping
        this.id = level + "-" + timestamp + "-" + sequenceNumber; // Stable unique ID

    }

    private static final Pattern RAW_PATTERN = Pattern.compile(
            "timestamp=\\S+ level=(\\S+) traceId=(\\S+) seq=(\\d+) message=\\\"(.*)\\\""
    );

    public String getMessage() {
        return message;
    }

    public LogLevel getLevel() {
        return level;
    }

    public boolean shouldShowLastFour() {
        return showLastFour;
    }

    public boolean requiresNER() {
        return level == LogLevel.SECURE;
    }

    public String getTraceId() {
        return traceId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTimestamp() { return timestamp; }

    public String getId() { return id; }

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
        Matcher m = RAW_PATTERN.matcher(raw);
        if (m.matches()) {
            LogLevel lvl = LogLevel.valueOf(m.group(1));
            String trace = m.group(2);
            int seq = Integer.parseInt(m.group(3));
            String msg = m.group(4);
            return new LogEvent(msg, lvl, false, trace, seq);
        } else {
            // Fallback: treat the entire raw string as the message
            return new LogEvent(
                    raw,
                    LogLevel.INFO,
                    false,
                    UUID.randomUUID().toString(),
                    0
            );
        }
    }


}
