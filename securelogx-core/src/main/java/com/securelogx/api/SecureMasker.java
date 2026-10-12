package com.securelogx.api;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.detection.MlRoutingTelemetry;
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

        List<MaskedResult> uniqueResults = maskingRuntime.execute(
                events,
                control -> engine.maskBatch(
                        tokenizer,
                        events,
                        control
                )
        );
        // This metric is observational only; it does not alter decisions.
        engine.recordDuplicateFieldsSaved(request.savedFields());
        return request.expand(uniqueResults, originalSequences);
    }

    public MaskingRuntimeStats getRuntimeStats() {
        return maskingRuntime.stats();
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
        maskingRuntime.close();
        engine.shutdown();
    }
}
