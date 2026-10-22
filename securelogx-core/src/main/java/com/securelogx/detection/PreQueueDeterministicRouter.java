package com.securelogx.detection;

import com.securelogx.api.MaskReasonCode;
import com.securelogx.api.MaskedResult;
import com.securelogx.model.LogEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Experimental, caller-thread deterministic resolution ahead of the
 * single native masking worker. Uses the very same immutable hybrid
 * detector/resolver/policy instance as the ONNX worker.
 *
 * Only an entire field for which the existing gate says requiresMl=false
 * is resolved here. Unknown/semantic input always goes to the worker.
 * No raw field is forwarded to a destination until the complete plan
 * has been successfully and securely merged.
 */
public final class PreQueueDeterministicRouter {

    // Never execute unbounded regex/structured parsing on an application
    // logging caller. Long input stays on the existing bounded worker.
    private static final int MAX_PREQUEUE_FIELD_CHARS = 1024;

    private static final String FAILURE_MARKER =
            "[SECURELOGX_REDACTED_PROCESSING_FAILURE]";

    private final HybridMaskingPipeline pipeline;

    public PreQueueDeterministicRouter(HybridMaskingPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    public Plan route(List<LogEvent> fields) {
        Objects.requireNonNull(fields, "fields");
        List<LogEvent> all = List.copyOf(fields);
        MaskedResult[] direct = new MaskedResult[all.size()];
        List<LogEvent> unresolved = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        for (int i = 0; i < all.size(); i++) {
            LogEvent event = all.get(i);
            String original = event.getMessage();
            if (original == null
                    || original.length() > MAX_PREQUEUE_FIELD_CHARS
                    || original.indexOf('\n') >= 0
                    || original.indexOf('\r') >= 0) {
                // Preserve existing long/complex-input handling and
                // native deadline/cancellation semantics.
                unresolved.add(event);
                indices.add(i);
                continue;
            }
            DeterministicScanResult scan = pipeline.scan(original);
            if (scan.requiresMl()) {
                unresolved.add(event);
                indices.add(i);
                continue;
            }
            // The existing resolver applies MASK evidence and honors
            // precise ALLOW decisions. No new regex bypass is introduced.
            String protectedText = pipeline.maskWithoutMl(
                    event.getMessage(),
                    scan,
                    event.shouldShowLastFour()
            );
            direct[i] = new MaskedResult(
                    protectedText, false, false,
                    MaskReasonCode.DETERMINISTIC_RESOLVED,
                    event.getSequenceNumber(), event.getInstanceId()
            );
        }
        return new Plan(all, List.copyOf(unresolved),
                List.copyOf(indices), direct);
    }

    public static final class Plan {
        private final List<LogEvent> original;
        private final List<LogEvent> unresolved;
        private final List<Integer> unresolvedIndices;
        private final MaskedResult[] direct;

        private Plan(
                List<LogEvent> original,
                List<LogEvent> unresolved,
                List<Integer> unresolvedIndices,
                MaskedResult[] direct
        ) {
            this.original = original;
            this.unresolved = unresolved;
            this.unresolvedIndices = unresolvedIndices;
            this.direct = direct.clone();
        }

        public List<LogEvent> unresolvedFields() {
            return unresolved;
        }

        public int deterministicFields() {
            return original.size() - unresolved.size();
        }

        public List<MaskedResult> merge(List<MaskedResult> results) {
            if (results == null || results.size() != unresolved.size()) {
                return failClosed(MaskReasonCode.PROCESSING_FAILURE);
            }

            MaskedResult[] merged = direct.clone();
            MaskReasonCode failure = null;
            for (int i = 0; i < results.size(); i++) {
                MaskedResult output = results.get(i);
                LogEvent expected = unresolved.get(i);
                if (output == null || output.sequence() !=
                        expected.getSequenceNumber()
                        || !expected.getInstanceId().equals(
                                output.instanceId())) {
                    failure = MaskReasonCode.PROCESSING_FAILURE;
                    break;
                }
                // A malformed or inconsistent worker result must not
                // release a raw field. In particular MASKING_DISABLED
                // is not a successful sanitized outcome even when a
                // caller incorrectly supplies failClosed=false.
                MaskReasonCode reason = output.reasonCode();
                if (!output.failClosed()
                        && reason != MaskReasonCode.ML_RESOLVED
                        && reason != MaskReasonCode.DETERMINISTIC_RESOLVED) {
                    failure = MaskReasonCode.PROCESSING_FAILURE;
                    break;
                }
                if (output.failClosed() && failure == null) {
                    failure = reason;
                }
                merged[unresolvedIndices.get(i)] = output;
            }
            if (failure != null) {
                // Never return a partially successful event to an adapter.
                // An ML failure discards all deterministic fields too.
                return failClosed(failure);
            }
            for (MaskedResult output : merged) {
                if (output == null) {
                    return failClosed(MaskReasonCode.PROCESSING_FAILURE);
                }
            }
            return List.copyOf(Arrays.asList(merged));
        }

        public List<MaskedResult> failClosed(MaskReasonCode reason) {
            List<MaskedResult> protectedResults =
                    new ArrayList<>(original.size());
            for (LogEvent field : original) {
                protectedResults.add(new MaskedResult(
                        FAILURE_MARKER, false, true, reason,
                        field.getSequenceNumber(), field.getInstanceId()
                ));
            }
            return List.copyOf(protectedResults);
        }
    }
}
