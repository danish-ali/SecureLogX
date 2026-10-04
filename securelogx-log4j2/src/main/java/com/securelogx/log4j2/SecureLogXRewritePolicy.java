package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;
import com.securelogx.api.MaskReasonCode;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.message.StringMapMessage;
import org.apache.logging.log4j.spi.MutableThreadContextStack;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.apache.logging.log4j.util.StringMap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SecureLogX RewritePolicy for Apache Log4j2's existing RewriteAppender.
 *
 * The policy returns a new immutable LogEvent whose payload-bearing fields are
 * sanitized before downstream file/console/Kafka appenders receive the event.
 */
@Plugin(
        name = "SecureLogXRewritePolicy",
        category = Core.CATEGORY_NAME,
        elementType = "rewritePolicy",
        printObject = true
)
public final class SecureLogXRewritePolicy implements RewritePolicy {

    private static final String FAILURE_TEXT =
            "[SECURELOGX_REDACTED_PROCESSING_FAILURE]";
    private static final int MAX_THROWABLE_DEPTH = 16;

    private final String environment;
    private final String expectedAppenderName;
    private final boolean strictConfiguration;
    private final boolean requirePreQueueSanitization;
    private final Configuration configuration;
    private final SecureMaskingService maskingServiceOverride;
    private final AtomicBoolean validated = new AtomicBoolean();

    private volatile List<String> configurationViolations = List.of();

    private SecureLogXRewritePolicy(
            String environment,
            String expectedAppenderName,
            boolean strictConfiguration,
            boolean requirePreQueueSanitization,
            Configuration configuration
    ) {
        this(
                environment,
                expectedAppenderName,
                strictConfiguration,
                requirePreQueueSanitization,
                configuration,
                null
        );
    }

    SecureLogXRewritePolicy(
            String environment,
            String expectedAppenderName,
            boolean strictConfiguration,
            boolean requirePreQueueSanitization,
            Configuration configuration,
            SecureMaskingService maskingServiceOverride
    ) {
        this.environment = normalize(environment, "dev");
        this.expectedAppenderName =
                normalize(expectedAppenderName, "SecureLogXRewrite");
        this.strictConfiguration = strictConfiguration;
        this.requirePreQueueSanitization =
                requirePreQueueSanitization;
        this.configuration = configuration;
        this.maskingServiceOverride = maskingServiceOverride;
    }

    @PluginFactory
    public static SecureLogXRewritePolicy createPolicy(
            @PluginAttribute("environment") String environment,
            @PluginAttribute("expectedAppenderName")
            String expectedAppenderName,
            @PluginAttribute("strictConfiguration")
            String strictConfiguration,
            @PluginAttribute("requirePreQueueSanitization")
            String requirePreQueueSanitization,
            @PluginConfiguration Configuration configuration
    ) {
        return new SecureLogXRewritePolicy(
                environment,
                expectedAppenderName,
                parseBoolean(strictConfiguration, true),
                parseBoolean(requirePreQueueSanitization, true),
                configuration
        );
    }

    @Override
    public LogEvent rewrite(LogEvent source) {
        if (source == null) {
            return null;
        }

        LogEvent original = source.toImmutable();
        long secureSequence =
                SecureLogXLog4j2Runtime.nextSequence();
        String secureInstanceId =
                SecureLogXLog4j2Runtime.instanceId();

        validateOnce();
        if (strictConfiguration
                && !configurationViolations.isEmpty()) {
            return failClosedEvent(
                    original,
                    secureSequence,
                    secureInstanceId,
                    "CONFIGURATION_INVALID"
            );
        }

        SecureMaskingService masker;
        try {
            masker = maskingServiceOverride != null
                    ? maskingServiceOverride
                    : SecureMaskerRegistry.get(
                            environment,
                            configuration
                    );
        } catch (Exception e) {
            StatusLogger.getLogger().error(
                    "SecureLogX masker initialization failed: {}",
                    e.getClass().getSimpleName()
            );
            return failClosedEvent(
                    original,
                    secureSequence,
                    secureInstanceId,
                    "MASKER_INITIALIZATION_FAILURE"
            );
        }

        try {
            SanitizationPlan plan =
                    SanitizationPlan.capture(original);
            List<MaskedResult> results =
                    masker.maskAll(plan.texts());

            MaskedResult failedField = results.stream()
                    .filter(MaskedResult::failClosed)
                    .findFirst()
                    .orElse(null);
            if (failedField != null) {
                String failureReason = switch (
                        failedField.reasonCode()
                ) {
                    case OVERLOAD_REJECTED ->
                            "OVERLOAD_REJECTED";
                    case DEADLINE_EXCEEDED ->
                            "DEADLINE_EXCEEDED";
                    default ->
                            "FIELD_PROCESSING_FAILURE";
                };

                StatusLogger.getLogger().error(
                        "SecureLogX field masking failed closed: {}",
                        failureReason
                );
                return failClosedEvent(
                        original,
                        secureSequence,
                        secureInstanceId,
                        failureReason
                );
            }

            boolean maskingDisabled = results.stream()
                    .anyMatch(
                            result -> result.reasonCode()
                                    == MaskReasonCode.MASKING_DISABLED
                    );
            if (maskingDisabled) {
                StatusLogger.getLogger().error(
                        "SecureLogX RewritePolicy is installed while "
                                + "masking is disabled; failing closed"
                );
                return failClosedEvent(
                        original,
                        secureSequence,
                        secureInstanceId,
                        "MASKING_DISABLED"
                );
            }

            return plan.rebuild(
                    original,
                    results,
                    secureSequence,
                    secureInstanceId
            );
        } catch (Exception e) {
            StatusLogger.getLogger().error(
                    "SecureLogX Log4j2 rewrite failed closed: {}",
                    e.getClass().getSimpleName()
            );
            return failClosedEvent(
                    original,
                    secureSequence,
                    secureInstanceId,
                    "REWRITE_FAILURE"
            );
        }
    }

    private void validateOnce() {
        if (!validated.compareAndSet(false, true)) {
            return;
        }

        // Package-private injected masking service is used only by unit tests.
        // It intentionally exercises rewrite semantics without constructing a
        // full Log4j configuration graph.
        if (configuration == null && maskingServiceOverride != null) {
            configurationViolations = List.of();
            return;
        }

        configurationViolations =
                SecureLogXLog4j2ConfigurationValidator.findViolations(
                        configuration,
                        expectedAppenderName,
                        requirePreQueueSanitization
                );

        if (!configurationViolations.isEmpty()) {
            StatusLogger.getLogger().error(
                    "SecureLogX Log4j2 configuration violations: {}",
                    String.join("; ", configurationViolations)
            );
        }
    }

    private LogEvent failClosedEvent(
            LogEvent source,
            long secureSequence,
            String secureInstanceId,
            String reason
    ) {
        StringMap context = new SortedArrayStringMap();
        addSecureMetadata(
                context,
                secureSequence,
                secureInstanceId,
                false,
                true,
                reason
        );
        context.freeze();

        Throwable sanitizedThrowable =
                source.getThrown() == null
                        ? null
                        : SanitizedThrowable.failure(
                                source.getThrown(),
                                FAILURE_TEXT
                        );

        return rebuildEvent(
                source,
                new SimpleMessage(FAILURE_TEXT),
                context,
                ThreadContext.EMPTY_STACK,
                sanitizedThrowable
        );
    }

    private static LogEvent rebuildEvent(
            LogEvent source,
            Message message,
            StringMap contextData,
            ThreadContext.ContextStack contextStack,
            Throwable thrown
    ) {
        return new Log4jLogEvent.Builder()
                .setLoggerName(source.getLoggerName())
                .setLoggerFqcn(source.getLoggerFqcn())
                .setMarker(source.getMarker())
                .setLevel(source.getLevel())
                .setMessage(message)
                .setThrown(thrown)
                .setContextData(contextData)
                .setContextStack(contextStack)
                .setThreadId(source.getThreadId())
                .setThreadName(source.getThreadName())
                .setThreadPriority(source.getThreadPriority())
                .setSource(source.getSource())
                .setInstant(source.getInstant())
                .setNanoTime(source.getNanoTime())
                .setIncludeLocation(source.isIncludeLocation())
                .setEndOfBatch(source.isEndOfBatch())
                .build();
    }

    private static void addSecureMetadata(
            StringMap context,
            long sequence,
            String instanceId,
            boolean mlInvoked,
            boolean failClosed,
            String reason
    ) {
        context.putValue(
                "securelogx.seq",
                Long.toString(sequence)
        );
        context.putValue("securelogx.instanceId", instanceId);
        context.putValue(
                "securelogx.mlInvoked",
                Boolean.toString(mlInvoked)
        );
        context.putValue(
                "securelogx.failClosed",
                Boolean.toString(failClosed)
        );
        context.putValue("securelogx.reason", reason);
    }

    private static boolean parseBoolean(
            String raw,
            boolean defaultValue
    ) {
        return raw == null || raw.isBlank()
                ? defaultValue
                : Boolean.parseBoolean(raw);
    }

    private static String normalize(
            String raw,
            String defaultValue
    ) {
        return raw == null || raw.isBlank()
                ? defaultValue
                : raw.trim();
    }

    private static final class SanitizationPlan {
        private final List<String> texts;
        private final MessagePlan messagePlan;
        private final ContextMapPlan contextMapPlan;
        private final ContextStackPlan contextStackPlan;
        private final ThrowablePlan throwablePlan;

        private SanitizationPlan(
                List<String> texts,
                MessagePlan messagePlan,
                ContextMapPlan contextMapPlan,
                ContextStackPlan contextStackPlan,
                ThrowablePlan throwablePlan
        ) {
            this.texts = texts;
            this.messagePlan = messagePlan;
            this.contextMapPlan = contextMapPlan;
            this.contextStackPlan = contextStackPlan;
            this.throwablePlan = throwablePlan;
        }

        static SanitizationPlan capture(LogEvent event) {
            List<String> texts = new ArrayList<>();

            MessagePlan messagePlan =
                    MessagePlan.capture(event.getMessage(), texts);
            ContextMapPlan contextMapPlan =
                    ContextMapPlan.capture(
                            event.getContextData().toMap(),
                            texts
                    );
            ContextStackPlan contextStackPlan =
                    ContextStackPlan.capture(
                            event.getContextStack().asList(),
                            texts
                    );
            ThrowablePlan throwablePlan =
                    ThrowablePlan.capture(
                            event.getThrown(),
                            texts,
                            new IdentityHashMap<>(),
                            0
                    );

            return new SanitizationPlan(
                    texts,
                    messagePlan,
                    contextMapPlan,
                    contextStackPlan,
                    throwablePlan
            );
        }

        List<String> texts() {
            return texts;
        }

        LogEvent rebuild(
                LogEvent source,
                List<MaskedResult> results,
                long secureSequence,
                String secureInstanceId
        ) {
            if (results.size() != texts.size()) {
                throw new IllegalStateException(
                        "SecureLogX field result count mismatch"
                );
            }

            Message message = messagePlan.rebuild(results);
            StringMap context =
                    contextMapPlan.rebuild(results);
            ThreadContext.ContextStack contextStack =
                    contextStackPlan.rebuild(results);
            Throwable throwable =
                    throwablePlan == null
                            ? null
                            : throwablePlan.rebuild(results);

            boolean mlInvoked = results.stream()
                    .anyMatch(MaskedResult::mlInvoked);
            boolean failClosed = results.stream()
                    .anyMatch(MaskedResult::failClosed);
            String reason = aggregateReason(results).name();

            addSecureMetadata(
                    context,
                    secureSequence,
                    secureInstanceId,
                    mlInvoked,
                    failClosed,
                    reason
            );
            context.freeze();

            return rebuildEvent(
                    source,
                    message,
                    context,
                    contextStack,
                    throwable
            );
        }

        private static MaskReasonCode aggregateReason(
                List<MaskedResult> results
        ) {
            if (results.stream().anyMatch(MaskedResult::failClosed)) {
                return MaskReasonCode.PROCESSING_FAILURE;
            }
            if (results.stream().anyMatch(MaskedResult::mlInvoked)) {
                return MaskReasonCode.ML_RESOLVED;
            }
            if (!results.isEmpty()
                    && results.stream().allMatch(
                            result -> result.reasonCode()
                                    == MaskReasonCode.MASKING_DISABLED
                    )) {
                return MaskReasonCode.MASKING_DISABLED;
            }
            return MaskReasonCode.DETERMINISTIC_RESOLVED;
        }
    }

    private interface MessagePlan {
        Message rebuild(List<MaskedResult> results);

        static MessagePlan capture(
                Message message,
                List<String> texts
        ) {
            if (message instanceof MapMessage<?, ?> mapMessage) {
                Map<String, ?> data = mapMessage.getData();
                Map<String, Integer> valueIndexes =
                        new LinkedHashMap<>();

                for (Map.Entry<String, ?> entry : data.entrySet()) {
                    valueIndexes.put(
                            entry.getKey(),
                            addText(
                                    texts,
                                    String.valueOf(entry.getValue())
                            )
                    );
                }
                return new MapMessagePlan(valueIndexes);
            }

            Message safeMessage =
                    message == null ? new SimpleMessage("") : message;

            int formattedIndex =
                    addText(
                            texts,
                            safeMessage.getFormattedMessage()
                    );
            int formatIndex =
                    addText(texts, safeMessage.getFormat());

            Object[] parameters = safeMessage.getParameters();
            int[] parameterIndexes =
                    new int[parameters == null
                            ? 0
                            : parameters.length];

            for (int i = 0; i < parameterIndexes.length; i++) {
                parameterIndexes[i] =
                        addText(
                                texts,
                                String.valueOf(parameters[i])
                        );
            }

            return new GenericMessagePlan(
                    formattedIndex,
                    formatIndex,
                    parameterIndexes
            );
        }
    }

    private record MapMessagePlan(
            Map<String, Integer> valueIndexes
    ) implements MessagePlan {
        @Override
        public Message rebuild(List<MaskedResult> results) {
            Map<String, String> sanitized =
                    new LinkedHashMap<>();

            for (Map.Entry<String, Integer> entry
                    : valueIndexes.entrySet()) {
                sanitized.put(
                        entry.getKey(),
                        masked(results, entry.getValue())
                );
            }
            return new StringMapMessage(sanitized);
        }
    }

    private record GenericMessagePlan(
            int formattedIndex,
            int formatIndex,
            int[] parameterIndexes
    ) implements MessagePlan {
        @Override
        public Message rebuild(List<MaskedResult> results) {
            Object[] parameters =
                    new Object[parameterIndexes.length];

            for (int i = 0; i < parameterIndexes.length; i++) {
                parameters[i] =
                        masked(results, parameterIndexes[i]);
            }

            return new SanitizedMessage(
                    masked(results, formattedIndex),
                    masked(results, formatIndex),
                    parameters
            );
        }
    }

    private record ContextMapPlan(
            Map<String, Integer> valueIndexes
    ) {
        static ContextMapPlan capture(
                Map<String, String> data,
                List<String> texts
        ) {
            Map<String, Integer> indexes =
                    new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : data.entrySet()) {
                indexes.put(
                        entry.getKey(),
                        addText(texts, entry.getValue())
                );
            }
            return new ContextMapPlan(indexes);
        }

        StringMap rebuild(List<MaskedResult> results) {
            StringMap sanitized =
                    new SortedArrayStringMap(valueIndexes.size() + 8);

            for (Map.Entry<String, Integer> entry
                    : valueIndexes.entrySet()) {
                sanitized.putValue(
                        entry.getKey(),
                        masked(results, entry.getValue())
                );
            }
            return sanitized;
        }
    }

    private record ContextStackPlan(
            int[] valueIndexes
    ) {
        static ContextStackPlan capture(
                List<String> stack,
                List<String> texts
        ) {
            int[] indexes = new int[stack.size()];
            for (int i = 0; i < stack.size(); i++) {
                indexes[i] = addText(texts, stack.get(i));
            }
            return new ContextStackPlan(indexes);
        }

        ThreadContext.ContextStack rebuild(
                List<MaskedResult> results
        ) {
            List<String> sanitized =
                    new ArrayList<>(valueIndexes.length);
            for (int index : valueIndexes) {
                sanitized.add(masked(results, index));
            }

            MutableThreadContextStack stack =
                    new MutableThreadContextStack(sanitized);
            stack.freeze();
            return stack;
        }
    }

    private static final class ThrowablePlan {
        private final String typeName;
        private final int messageIndex;
        private final StackTraceElement[] stackTrace;
        private final ThrowablePlan cause;
        private final List<ThrowablePlan> suppressed;

        private ThrowablePlan(
                String typeName,
                int messageIndex,
                StackTraceElement[] stackTrace,
                ThrowablePlan cause,
                List<ThrowablePlan> suppressed
        ) {
            this.typeName = typeName;
            this.messageIndex = messageIndex;
            this.stackTrace = stackTrace;
            this.cause = cause;
            this.suppressed = suppressed;
        }

        static ThrowablePlan capture(
                Throwable throwable,
                List<String> texts,
                IdentityHashMap<Throwable, Boolean> seen,
                int depth
        ) {
            if (throwable == null
                    || depth >= MAX_THROWABLE_DEPTH
                    || seen.put(throwable, Boolean.TRUE) != null) {
                return null;
            }

            ThrowablePlan cause = capture(
                    throwable.getCause(),
                    texts,
                    seen,
                    depth + 1
            );

            List<ThrowablePlan> suppressed =
                    new ArrayList<>();
            for (Throwable item : throwable.getSuppressed()) {
                ThrowablePlan captured =
                        capture(
                                item,
                                texts,
                                seen,
                                depth + 1
                        );
                if (captured != null) {
                    suppressed.add(captured);
                }
            }

            return new ThrowablePlan(
                    throwable.getClass().getName(),
                    addText(texts, throwable.getMessage()),
                    throwable.getStackTrace().clone(),
                    cause,
                    List.copyOf(suppressed)
            );
        }

        Throwable rebuild(List<MaskedResult> results) {
            SanitizedThrowable rebuilt =
                    new SanitizedThrowable(
                            typeName,
                            masked(results, messageIndex)
                    );
            rebuilt.setStackTrace(stackTrace.clone());

            if (cause != null) {
                rebuilt.initCause(cause.rebuild(results));
            }
            for (ThrowablePlan item : suppressed) {
                rebuilt.addSuppressed(item.rebuild(results));
            }
            return rebuilt;
        }
    }

    private static final class SanitizedMessage
            implements Message {
        private static final long serialVersionUID = 1L;

        private final String formatted;
        private final String format;
        private final Object[] parameters;

        private SanitizedMessage(
                String formatted,
                String format,
                Object[] parameters
        ) {
            this.formatted = formatted;
            this.format = format;
            this.parameters = parameters.clone();
        }

        @Override
        public String getFormattedMessage() {
            return formatted;
        }

        @Override
        public String getFormat() {
            return format;
        }

        @Override
        public Object[] getParameters() {
            return parameters.clone();
        }

        @Override
        public Throwable getThrowable() {
            return null;
        }
    }

    private static final class SanitizedThrowable
            extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String originalType;

        private SanitizedThrowable(
                String originalType,
                String sanitizedMessage
        ) {
            super(sanitizedMessage);
            this.originalType = originalType;
        }

        static SanitizedThrowable failure(
                Throwable original,
                String failureText
        ) {
            SanitizedThrowable sanitized =
                    new SanitizedThrowable(
                            original.getClass().getName(),
                            failureText
                    );
            sanitized.setStackTrace(original.getStackTrace().clone());
            return sanitized;
        }

        @Override
        public String toString() {
            String message = getMessage();
            return message == null || message.isBlank()
                    ? originalType
                    : originalType + ": " + message;
        }
    }

    private static int addText(
            List<String> texts,
            String value
    ) {
        texts.add(value == null ? "" : value);
        return texts.size() - 1;
    }

    private static String masked(
            List<MaskedResult> results,
            int index
    ) {
        return results.get(index).maskedText();
    }
}
