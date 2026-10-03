package com.securelogx.ner.impl;

import ai.onnxruntime.OrtSession;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cooperative deadline/cancellation state shared by SecureMasker and the
 * tokenizer/ONNX inference path.
 *
 * ONNX Runtime 1.23.2 RunOptions supports setTerminate(true), allowing a
 * deadline thread to request termination of an active native Session.run.
 */
public final class MaskingExecutionControl {

    public enum CancelReason {
        NONE,
        DEADLINE_EXCEEDED,
        CALLER_INTERRUPTED,
        SHUTDOWN
    }

    private final long deadlineNanos;
    private final AtomicReference<CancelReason> cancelReason =
            new AtomicReference<>(CancelReason.NONE);
    private final AtomicReference<OrtSession.RunOptions> activeRunOptions =
            new AtomicReference<>();
    private final AtomicBoolean nativeTerminationSignalled =
            new AtomicBoolean();
    private final CountDownLatch nativeRunStarted =
            new CountDownLatch(1);

    public MaskingExecutionControl(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static MaskingExecutionControl unbounded() {
        return new MaskingExecutionControl(Long.MAX_VALUE);
    }

    public void checkpoint() {
        if (Thread.currentThread().isInterrupted()) {
            cancel(CancelReason.CALLER_INTERRUPTED);
        } else if (isDeadlineExpired()) {
            cancel(CancelReason.DEADLINE_EXCEEDED);
        }

        CancelReason reason = cancelReason.get();
        if (reason != CancelReason.NONE) {
            throw new CancellationException(
                    "SecureLogX masking cancelled: " + reason
            );
        }
    }

    public void cancel(CancelReason reason) {
        if (reason == null || reason == CancelReason.NONE) {
            return;
        }

        cancelReason.compareAndSet(CancelReason.NONE, reason);

        OrtSession.RunOptions runOptions = activeRunOptions.get();
        if (runOptions != null) {
            try {
                runOptions.setTerminate(true);
                nativeTerminationSignalled.set(true);
            } catch (Exception ignored) {
                // The run may already have completed/closed. The worker still
                // observes the cancellation flag at its next checkpoint.
            }
        }
    }

    public void attachRunOptions(
            OrtSession.RunOptions runOptions
    ) {
        activeRunOptions.set(runOptions);
        nativeRunStarted.countDown();

        try {
            if (isDeadlineExpired()) {
                cancel(CancelReason.DEADLINE_EXCEEDED);
            }

            if (cancelReason.get() != CancelReason.NONE) {
                runOptions.setTerminate(true);
            }
        } catch (Exception e) {
            activeRunOptions.compareAndSet(runOptions, null);
            throw new IllegalStateException(
                    "Failed to configure ONNX run cancellation",
                    e
            );
        }
    }

    public void detachRunOptions(
            OrtSession.RunOptions runOptions
    ) {
        activeRunOptions.compareAndSet(runOptions, null);
    }

    /**
     * Validation/diagnostic hook: waits until an actual ONNX RunOptions has
     * been attached to session.run(...).
     */
    public boolean awaitNativeRunStarted(
            long timeout,
            TimeUnit unit
    ) throws InterruptedException {
        return nativeRunStarted.await(timeout, unit);
    }

    public boolean nativeTerminationSignalled() {
        return nativeTerminationSignalled.get();
    }

    public CancelReason cancelReason() {
        if (cancelReason.get() == CancelReason.NONE
                && isDeadlineExpired()) {
            cancel(CancelReason.DEADLINE_EXCEEDED);
        }
        return cancelReason.get();
    }

    public boolean isDeadlineExceeded() {
        return cancelReason() == CancelReason.DEADLINE_EXCEEDED;
    }

    private boolean isDeadlineExpired() {
        return deadlineNanos != Long.MAX_VALUE
                && System.nanoTime() >= deadlineNanos;
    }
}
