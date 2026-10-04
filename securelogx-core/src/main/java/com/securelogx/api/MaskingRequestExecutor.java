package com.securelogx.api;

import com.securelogx.model.LogEvent;
import com.securelogx.ner.impl.MaskingExecutionControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private final LongAdder nativeTerminationSignals = new LongAdder();
    private final LongAdder executionFailureRequests = new LongAdder();

    private final Map<
            MaskingExecutionControl,
            LiveRequest> liveRequests =
            new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger peakActiveRequests = new AtomicInteger();
    private final AtomicInteger peakQueuedRequests = new AtomicInteger();

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

        AtomicReference<Thread> workerThread =
                new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        FutureTask<List<MaskedResult>> task =
                new FutureTask<>(() -> {
                    workerThread.set(Thread.currentThread());
                    updatePeak(
                            peakActiveRequests,
                            executor.getActiveCount()
                    );
                    try {
                        control.checkpoint();
                        return operation.execute(control);
                    } finally {
                        workerThread.set(null);
                        finished.countDown();
                        liveRequests.remove(control);
                    }
                });

        LiveRequest liveRequest = new LiveRequest(
                task,
                workerThread,
                finished
        );
        liveRequests.put(control, liveRequest);

        try {
            executor.execute(task);
            acceptedRequests.increment();
            updatePeak(
                    peakQueuedRequests,
                    executor.getQueue().size()
            );
            updatePeak(
                    peakActiveRequests,
                    executor.getActiveCount()
            );
        } catch (RejectedExecutionException e) {
            liveRequests.remove(control);
            task.cancel(false);
            finished.countDown();
            overloadRejectedRequests.increment();
            return failClosed(
                    events,
                    MaskReasonCode.OVERLOAD_REJECTED
            );
        }

        try {
            long remainingNanos =
                    deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException(
                        "SecureLogX masking deadline expired"
                );
            }

            List<MaskedResult> results =
                    task.get(
                            remainingNanos,
                            TimeUnit.NANOSECONDS
                    );
            completedRequests.increment();
            recordFailClosedOutcome(results);
            return results;
        } catch (TimeoutException e) {
            deadlineExceededRequests.increment();
            control.cancel(
                    MaskingExecutionControl.CancelReason
                            .DEADLINE_EXCEEDED
            );
            if (control.nativeTerminationSignalled()) {
                nativeTerminationSignals.increment();
            }

            boolean removedBeforeStart = executor.remove(task);
            if (removedBeforeStart) {
                task.cancel(false);
                liveRequests.remove(control);
                finished.countDown();
            } else {
                Thread worker = workerThread.get();
                if (worker != null) {
                    worker.interrupt();
                }
                awaitFinishedUninterruptibly(finished);
            }

            return failClosed(
                    events,
                    MaskReasonCode.DEADLINE_EXCEEDED
            );
        } catch (InterruptedException e) {
            executionFailureRequests.increment();
            control.cancel(
                    MaskingExecutionControl.CancelReason
                            .CALLER_INTERRUPTED
            );

            boolean removedBeforeStart = executor.remove(task);
            if (removedBeforeStart) {
                task.cancel(false);
                liveRequests.remove(control);
                finished.countDown();
            } else {
                Thread worker = workerThread.get();
                if (worker != null) {
                    worker.interrupt();
                }
                awaitFinishedUninterruptibly(finished);
            }

            Thread.currentThread().interrupt();
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
            Throwable cause = e.getCause();
            System.err.println(
                    "[SecureLogX] Masking request failed closed: "
                            + (cause == null
                                    ? "ExecutionException"
                                    : cause.getClass()
                                            .getSimpleName())
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
                nativeTerminationSignals.sum(),
                executionFailureRequests.sum(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                peakActiveRequests.get(),
                peakQueuedRequests.get(),
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
                LiveRequest> entry
                : liveRequests.entrySet()) {
            entry.getKey().cancel(
                    MaskingExecutionControl.CancelReason.SHUTDOWN
            );
            Thread worker = entry.getValue()
                    .workerThread()
                    .get();
            if (worker != null) {
                worker.interrupt();
            }
            entry.getValue().task().cancel(true);
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

    private static void updatePeak(
            AtomicInteger peak,
            int candidate
    ) {
        peak.accumulateAndGet(candidate, Math::max);
    }

    private static void awaitFinishedUninterruptibly(
            CountDownLatch finished
    ) {
        boolean interrupted = false;

        while (true) {
            try {
                finished.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record LiveRequest(
            FutureTask<List<MaskedResult>> task,
            AtomicReference<Thread> workerThread,
            CountDownLatch finished
    ) {
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
