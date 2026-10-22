package com.securelogx.api;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.detection.MlRoutingTelemetry;
import com.securelogx.detection.PreQueueDeterministicRouter;
import com.securelogx.detection.HybridRuntimeStats;
import com.securelogx.detection.InferenceStageStats;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import com.securelogx.model.MaskingFieldOrigin;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;
import com.securelogx.util.ArtifactIntegrityVerifier;
import com.securelogx.util.CachedClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Framework-neutral SecureLogX masking API.
 *
 * This class performs no logging and owns no destination appender. It exists
 * specifically so logging-framework integrations can sanitize data before
 * forwarding events to their normal destinations.
 */
public final class SecureMasker implements AutoCloseable {

    private static final String MASKER_TRACE_ID = "securelogx-masker";

    private final SecureLogXConfig config;
    private final ParallelTokenizer tokenizer;
    private final ONNXDynamicInferenceEngine engine;
    private final MaskingRequestExecutor maskingRuntime;
    private final AtomicLong sequence = new AtomicLong();
    private final boolean preQueueEnabled;
    private final PreQueueDeterministicRouter preQueueRouter;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder preQueueBypassed = new LongAdder();
    private final LongAdder preQueueEscalated = new LongAdder();
    private final LongAdder preQueueFailClosedEvents = new LongAdder();

    public SecureMasker() throws Exception {
        this(System.getenv().getOrDefault("SECURELOGX_ENV", "dev"));
    }

    public SecureMasker(String environment) throws Exception {
        this.config = new SecureLogXConfig(environment);

        ArtifactIntegrityVerifier.verifySha256(
                "ML-v1.3 ONNX model",
                config.getModelPath(),
                config.getModelSha256()
        );
        ArtifactIntegrityVerifier.verifySha256(
                "ML-v1.3 tokenizer",
                config.getTokenizerPath(),
                config.getTokenizerSha256()
        );

        this.tokenizer = new ParallelTokenizer(
                config.getTokenizerPath(),
                config.getMaxSequenceLength()
        );
        this.engine = new ONNXDynamicInferenceEngine(
                config.getModelPath(),
                config
        );
        this.maskingRuntime = new MaskingRequestExecutor(
                config.getMaskingQueueCapacity(),
                config.getMaskingDeadlineMillis(),
                config.getMaskingShutdownWaitMillis()
        );
        this.preQueueEnabled =
                config.isPreQueueDeterministicRoutingEnabled();
        this.preQueueRouter = preQueueEnabled
                ? engine.preQueueDeterministicRouter()
                : null;
    }

    public MaskedResult mask(String text) {
        return maskAll(List.of(text)).get(0);
    }

    /**
     * Masks multiple independent text fields in one runtime batch. This is
     * intended for structured logging adapters that must sanitize message,
     * parameters, MDC values, and throwable text together.
     */
    public List<MaskedResult> maskAll(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("texts must not be null");
        }
        return maskAllWithOrigins(
                texts,
                java.util.Collections.nCopies(
                        texts.size(), MaskingFieldOrigin.OTHER
                )
        );
    }

    /**
     * Framework adapter entry point: origins come from the trusted adapter,
     * not an untrusted field in the log payload.
     */
    public List<MaskedResult> maskAllWithOrigins(
            List<String> texts,
            List<MaskingFieldOrigin> origins
    ) {
        if (texts == null || origins == null
                || origins.size() != texts.size()) {
            throw new IllegalArgumentException(
                    "texts and origins must have matching sizes"
            );
        }
        if (texts.isEmpty()) {
            return List.of();
        }

        if (!config.isMaskingEnabled()
                || !config.shouldMaskInCurrentEnv()) {
            List<MaskedResult> disabled =
                    new ArrayList<>(texts.size());
            for (String text : texts) {
                long seq = sequence.incrementAndGet();
                disabled.add(
                        new MaskedResult(
                                text == null ? "" : text,
                                false,
                                false,
                                MaskReasonCode.MASKING_DISABLED,
                                seq,
                                "masking-disabled"
                        )
                );
            }
            return List.copyOf(disabled);
        }

        // Deduplication is limited to one masking request (normally one
        // Log4j event). Keep a distinct sequence for each original field.
        // A deadline/overload failure on a unique input is propagated to
        // all corresponding positions and therefore still fails the event.
        MaskingRequestDeduplicator request =
                MaskingRequestDeduplicator.of(texts, origins);
        long[] originalSequences = new long[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            originalSequences[i] = sequence.incrementAndGet();
        }

        long timestamp = CachedClock.now();
        List<LogEvent> events =
                new ArrayList<>(request.uniqueTexts().size());
        for (int i = 0; i < request.uniqueTexts().size(); i++) {
            events.add(
                    new LogEvent(
                            request.uniqueTexts().get(i),
                            LogLevel.INFO,
                            false,
                            MASKER_TRACE_ID,
                            originalSequences[
                                    request.firstOriginalIndex(i)
                            ],
                            timestamp,
                            request.firstOrigins().get(i)
                    )
            );
        }

        List<MaskedResult> uniqueResults;
        if (!preQueueEnabled) {
            // Frozen default: preserve the existing whole-event admission
            // and ONNX routing path bit-for-bit.
            uniqueResults = maskingRuntime.execute(
                    events,
                    control -> engine.maskBatch(tokenizer, events, control)
            );
        } else {
            // Experimental, explicit opt-in. The adapter cannot forward
            // anything before *all* independent fields are protected.
            try {
                if (closed.get()) {
                    uniqueResults = failClosedAll(
                            events, MaskReasonCode.OVERLOAD_REJECTED
                    );
                } else {
                    PreQueueDeterministicRouter.Plan plan =
                            preQueueRouter.route(events);
                    preQueueBypassed.add(plan.deterministicFields());
                    preQueueEscalated.add(plan.unresolvedFields().size());
                    List<MaskedResult> unresolved = plan.unresolvedFields()
                            .isEmpty() ? List.of()
                            : maskingRuntime.execute(
                                    plan.unresolvedFields(),
                                    control -> engine.maskBatch(
                                            tokenizer,
                                            plan.unresolvedFields(),
                                            control
                                    )
                            );
                    uniqueResults = plan.merge(unresolved);
                    // A concurrent context shutdown may begin while
                    // deterministic fields are being resolved outside the
                    // native worker. Never release them after close is seen.
                    if (closed.get()) {
                        uniqueResults = plan.failClosed(
                                MaskReasonCode.OVERLOAD_REJECTED
                        );
                    }
                    if (uniqueResults.stream().anyMatch(
                            MaskedResult::failClosed)) {
                        preQueueFailClosedEvents.increment();
                    }
                }
            } catch (RuntimeException unexpected) {
                // No caught exception may release any original field.
                preQueueFailClosedEvents.increment();
                uniqueResults = failClosedAll(
                        events, MaskReasonCode.PROCESSING_FAILURE
                );
            }
        }
        engine.recordDuplicateFieldsSaved(request.savedFields());
        return request.expand(uniqueResults, originalSequences);
    }

    private static List<MaskedResult> failClosedAll(
            List<LogEvent> fields, MaskReasonCode reason
    ) {
        List<MaskedResult> failed = new ArrayList<>(fields.size());
        for (LogEvent field : fields) {
            failed.add(new MaskedResult(
                    "[SECURELOGX_REDACTED_PROCESSING_FAILURE]",
                    false, true, reason,
                    field.getSequenceNumber(), field.getInstanceId()
            ));
        }
        return List.copyOf(failed);
    }

    public PreQueueRoutingStats getPreQueueRoutingStats() {
        return new PreQueueRoutingStats(
                preQueueEnabled,
                preQueueBypassed.sum(),
                preQueueEscalated.sum(),
                preQueueFailClosedEvents.sum()
        );
    }

    public MaskingRuntimeStats getRuntimeStats() {
        return maskingRuntime.stats();
    }

    /**
     * Queue-start wait histogram from the bounded inference worker.
     * Aggregate timing only, without request payloads.
     */
    public MaskingQueueWaitStats getMaskingQueueWaitStats() {
        return maskingRuntime.queueWaitStats();
    }

    /**
     * Observational-only, post-warmup queue wait measurement.
     * Never changes masking or the bounded worker/queue.
     */
    public void beginQueueWaitMeasurementWindow() {
        maskingRuntime.beginQueueWaitMeasurementWindow();
    }

    public double getMaxMeasuredQueueWaitMillis() {
        return maskingRuntime.maxMeasuredQueueWaitMillis();
    }

    /**
     * Returns only aggregate rule-proposal metadata; never raw log values.
     * Opt-in telemetry is in-memory and scoped to this masker instance.
     */
    public MlRoutingTelemetry.Snapshot getMlRoutingTelemetrySnapshot() {
        return engine.getMlRoutingTelemetrySnapshot();
    }

    /**
     * Counts actual ONNX calls and inference windows (distinct from
     * field-level ML routing). Read after a completed benchmark workload.
     */
    public HybridRuntimeStats getHybridRuntimeStats() {
        return engine.getHybridRuntimeStats();
    }

    /**
     * Aggregate stage durations contain no application payload or spans.
     */
    public InferenceStageStats getInferenceStageStats() {
        return engine.getInferenceStageStats();
    }

    @Override
    public void close() {
        closed.set(true);
        maskingRuntime.close();
        engine.shutdown();
    }
}
