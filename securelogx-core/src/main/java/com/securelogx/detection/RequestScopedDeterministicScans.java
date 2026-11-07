package com.securelogx.detection;

import com.securelogx.model.LogEvent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, one-request handoff from caller-side routing to the native worker.
 *
 * Binds every scan to the exact immutable LogEvent object, its position and
 * the exact HybridMaskingPipeline instance. A null scan means the caller did
 * not scan the field (e.g. long or multiline input) and the worker MUST scan
 * it normally. Nothing is cached across requests or shared between tenants.
 *
 * A failed binding check is an exception, handled by the existing worker
 * request-level fail-closed path; it must never silently rescan with a
 * potentially different policy and accept an inconsistent partial plan.
 */
public final class RequestScopedDeterministicScans {
    private final HybridMaskingPipeline pipeline;
    private final List<LogEvent> events;
    private final List<Optional<DeterministicScanResult>> scans;

    RequestScopedDeterministicScans(
            HybridMaskingPipeline pipeline,
            List<LogEvent> events,
            List<Optional<DeterministicScanResult>> scans
    ) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.events = List.copyOf(Objects.requireNonNull(events, "events"));
        this.scans = List.copyOf(Objects.requireNonNull(scans, "scans"));
        if (this.events.size() != this.scans.size()) {
            throw new IllegalArgumentException("Scan and event counts differ");
        }
        for (Optional<DeterministicScanResult> candidate : this.scans) {
            Objects.requireNonNull(candidate, "scan slot");
            if (candidate.isPresent() && !candidate.get().requiresMl()) {
                throw new IllegalArgumentException(
                        "Only unresolved ML-routed scans may be reused"
                );
            }
        }
    }

    /**
     * Validate the entire worker request before processing any chunk.
     * Identity rather than content equality prevents substitution of an
     * equal-looking event from another request or policy context.
     */
    public void validate(
            HybridMaskingPipeline workerPipeline,
            List<LogEvent> workerEvents
    ) {
        if (pipeline != workerPipeline || workerEvents == null
                || workerEvents.size() != events.size()) {
            throw new IllegalStateException(
                    "Request-scoped scan policy or size mismatch"
            );
        }
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) != workerEvents.get(i)) {
                throw new IllegalStateException(
                        "Request-scoped scan event ordering mismatch"
                );
            }
        }
    }

    /**
     * Called by the worker with the original request index, including when
     * the native engine splits the request into smaller inference chunks.
     */
    public Optional<DeterministicScanResult> scanFor(
            HybridMaskingPipeline workerPipeline,
            LogEvent event,
            int originalIndex
    ) {
        if (pipeline != workerPipeline || originalIndex < 0
                || originalIndex >= events.size()
                || events.get(originalIndex) != event) {
            throw new IllegalStateException(
                    "Request-scoped scan identity mismatch"
            );
        }
        return scans.get(originalIndex);
    }

    public int reusedScanCandidates() {
        int count = 0;
        for (var scan : scans) {
            if (scan.isPresent()) {
                count++;
            }
        }
        return count;
    }
}
