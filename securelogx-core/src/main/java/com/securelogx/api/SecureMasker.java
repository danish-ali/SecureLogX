package com.securelogx.api;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
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

        long timestamp = CachedClock.now();
        List<LogEvent> events = new ArrayList<>(texts.size());

        for (String text : texts) {
            events.add(
                    new LogEvent(
                            text == null ? "" : text,
                            LogLevel.INFO,
                            false,
                            MASKER_TRACE_ID,
                            sequence.incrementAndGet(),
                            timestamp
                    )
            );
        }

        return engine.maskBatch(tokenizer, events);
    }

    @Override
    public void close() {
        engine.shutdown();
    }
}
