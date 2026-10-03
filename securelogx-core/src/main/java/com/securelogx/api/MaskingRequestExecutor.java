package com.securelogx.api;

import com.securelogx.model.LogEvent;
import com.securelogx.ner.impl.MaskingExecutionControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Single-worker bounded admission layer for the production masking path.
 *
 * One worker is intentional for the pilot: the validated native-memory
 * envelope was measured with one active ONNX session path. Increasing active
 * masking concurrency requires separate load/memory validation.
 */
final class MaskingRequestExecutor implements AutoCloseable {

    private static final String FAILURE_TEXT =
            "[SECURELOGX_REDACTED_PROCESSING_FAILURE]";
    private static final int WORKER_COUNT = 1;

    @FunctionalInterface
    interface Operation {
        List<MaskedResult> execute(
                MaskingExecutionControl control
        ) throws Exception;
    }

    private final ThreadPoolExecutor executor;
    private final int queueCapacity;
    private final long deadlineMillis;
    private final long shutdownWaitMillis;

    private final LongAdder acceptedRequests = new LongAdder();
    private final LongAdder completedRequests = new LongAdder();
    private final LongAdder overloadRejectedRequests = new LongAdder();
    private final LongAdder deadlineExceededRequests = new LongAdder();
    private final LongAdder executionFailureRequests = new LongAdder();

    private final Map<
            MaskingExecutionControl,
            FutureTask<List<MaskedResult>>> liveRequests =
            new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    MaskingRequestExecutor(
            int queueCapacity,
            long deadlineMillis,
            long shutdownWaitMillis
    ) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException(
                    "queueCapacity must be at least 1"
            );
        }
        if (deadlineMillis < 1) {
            throw new IllegalArgumentException(
                    "deadlineMillis must be at least 1"
            );
        }
        if (shutdownWaitMillis < 1) {
            throw new IllegalArgumentException(
                    "shutdownWaitMillis must be at least 1"
            );
        }

        this.queueCapacity = queueCapacity;
        this.deadlineMillis = deadlineMillis;
        this.shutdownWaitMillis = shutdownWaitMillis;

        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "SecureLogX-Masking-Worker-"
                            + threadSequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };

        this.executor = new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    List<MaskedResult> execute(
            List<LogEvent> events,
            Operation operation
    ) {
        if (events.isEmpty()) {
            return List.of();
        }

        if (closed.get()) {
            overloadRejectedRequests.increment();
            return failClosed(
                    events,
                    MaskReasonCode.OVERLOAD_REJECTED
            );
        }

        long deadlineNanos = calculateDeadlineNanos();
        MaskingExecutionControl control =
                new MaskingExecutionControl(deadlineNanos);

        FutureTask<List<MaskedResult>> task =
                new FutureTask<>(() -> {
                    try {
                        control.checkpoint();
                        return operation.execute(control);
                    } finally {
                        liveRequests.remove(control);
                    }
                });

        liveRequests.put(control, task);

        try {
            executor.execute(task);
            acceptedRequests.increment();
        } catch (RejectedExecutionException e) {
            liveRequests.remove(control);
            overloadRejectedRequests.increment();
            return failClosed(
                    events,
                    MaskReasonCode.OVERLOAD_REJECTED
            );
        }

        try {
            List<MaskedResult> results =
                    task.get(deadlineMillis, TimeUnit.MILLISECONDS);
            completedRequests.increment();
            recordFailClosedOutcome(results);
            return results;
        } catch (TimeoutException e) {
            deadlineExceededRequests.increment();
            control.cancel(
                    MaskingExecutionControl.CancelReason
                            .DEADLINE_EXCEEDED
            );
            task.cancel(true);
            if (executor.remove(task)) {
                liveRequests.remove(control);
            }
            return failClosed(
                    events,
                    MaskReasonCode.DEADLINE_EXCEEDED
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executionFailureRequests.increment();
            control.cancel(
                    MaskingExecutionControl.CancelReason
                            .CALLER_INTERRUPTED
            );
            task.cancel(true);
            if (executor.remove(task)) {
                liveRequests.remove(control);
            }
            return failClosed(
                    events,
                    MaskReasonCode.PROCESSING_FAILURE
            );
        } catch (CancellationException e) {
            MaskingExecutionControl.CancelReason reason =
                    control.cancelReason();

            if (reason
                    == MaskingExecutionControl.CancelReason
                            .DEADLINE_EXCEEDED) {
                deadlineExceededRequests.increment();
                return failClosed(
                        events,
                        MaskReasonCode.DEADLINE_EXCEEDED
                );
            }

            executionFailureRequests.increment();
            return failClosed(
                    events,
                    MaskReasonCode.PROCESSING_FAILURE
            );
        } catch (ExecutionException e) {
            executionFailureRequests.increment();
            System.err.println(
                    "[SecureLogX] Masking request failed closed: "
                            + e.getCause().getClass().getSimpleName()
            );
            return failClosed(
                    events,
                    MaskReasonCode.PROCESSING_FAILURE
            );
        }
    }

    MaskingRuntimeStats stats() {
        return new MaskingRuntimeStats(
                acceptedRequests.sum(),
                completedRequests.sum(),
                overloadRejectedRequests.sum(),
                deadlineExceededRequests.sum(),
                executionFailureRequests.sum(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                queueCapacity,
                deadlineMillis
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (Map.Entry<
                MaskingExecutionControl,
                FutureTask<List<MaskedResult>>> entry
                : liveRequests.entrySet()) {
            entry.getKey().cancel(
                    MaskingExecutionControl.CancelReason.SHUTDOWN
            );
            entry.getValue().cancel(true);
        }

        List<Runnable> queued = executor.shutdownNow();
        for (Runnable runnable : queued) {
            if (runnable instanceof FutureTask<?> futureTask) {
                futureTask.cancel(false);
            }
        }

        try {
            if (!executor.awaitTermination(
                    shutdownWaitMillis,
                    TimeUnit.MILLISECONDS
            )) {
                System.err.println(
                        "[SecureLogX] Masking executor did not terminate "
                                + "within shutdown wait."
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            liveRequests.clear();
        }
    }

    private long calculateDeadlineNanos() {
        long timeoutNanos =
                TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        long now = System.nanoTime();

        if (Long.MAX_VALUE - now < timeoutNanos) {
            return Long.MAX_VALUE;
        }
        return now + timeoutNanos;
    }

    private void recordFailClosedOutcome(
            List<MaskedResult> results
    ) {
        boolean deadline = results.stream().anyMatch(
                result -> result.failClosed()
                        && result.reasonCode()
                                == MaskReasonCode.DEADLINE_EXCEEDED
        );
        if (deadline) {
            deadlineExceededRequests.increment();
            return;
        }

        boolean failed = results.stream().anyMatch(
                MaskedResult::failClosed
        );
        if (failed) {
            executionFailureRequests.increment();
        }
    }

    private static List<MaskedResult> failClosed(
            List<LogEvent> events,
            MaskReasonCode reasonCode
    ) {
        List<MaskedResult> results =
                new ArrayList<>(events.size());

        for (LogEvent event : events) {
            results.add(
                    new MaskedResult(
                            FAILURE_TEXT,
                            false,
                            true,
                            reasonCode,
                            event.getSequenceNumber(),
                            event.getInstanceId()
                    )
            );
        }
        return List.copyOf(results);
    }
}
