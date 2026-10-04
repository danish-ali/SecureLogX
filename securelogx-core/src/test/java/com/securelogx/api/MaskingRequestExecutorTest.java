package com.securelogx.api;

import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MaskingRequestExecutorTest {

    @Test
    void queueSaturationRejectsFailClosedWithoutRawPayload()
            throws Exception {
        try (MaskingRequestExecutor executor =
                     new MaskingRequestExecutor(
                             1,
                             2000,
                             1000
                     )) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            LogEvent firstEvent = event("first-secret", 1L);
            LogEvent secondEvent = event("second-secret", 2L);
            LogEvent thirdEvent = event("third-secret", 3L);

            AtomicReference<List<MaskedResult>> firstResult =
                    new AtomicReference<>();
            AtomicReference<List<MaskedResult>> secondResult =
                    new AtomicReference<>();

            Thread firstCaller = new Thread(
                    () -> firstResult.set(
                            executor.execute(
                                    List.of(firstEvent),
                                    control -> {
                                        firstStarted.countDown();
                                        while (!releaseFirst.await(
                                                20,
                                                TimeUnit.MILLISECONDS
                                        )) {
                                            control.checkpoint();
                                        }
                                        return success(firstEvent);
                                    }
                            )
                    )
            );
            firstCaller.start();

            assertTrue(
                    firstStarted.await(
                            1,
                            TimeUnit.SECONDS
                    )
            );

            Thread secondCaller = new Thread(
                    () -> secondResult.set(
                            executor.execute(
                                    List.of(secondEvent),
                                    control -> success(secondEvent)
                            )
                    )
            );
            secondCaller.start();

            waitForQueuedRequest(executor);

            List<MaskedResult> rejected =
                    executor.execute(
                            List.of(thirdEvent),
                            control -> success(thirdEvent)
                    );

            assertEquals(1, rejected.size());
            MaskedResult rejectedResult = rejected.get(0);
            assertTrue(rejectedResult.failClosed());
            assertEquals(
                    MaskReasonCode.OVERLOAD_REJECTED,
                    rejectedResult.reasonCode()
            );
            assertFalse(
                    rejectedResult.maskedText()
                            .contains("third-secret")
            );

            MaskingRuntimeStats stats = executor.stats();
            assertEquals(1L, stats.overloadRejectedRequests());

            releaseFirst.countDown();
            firstCaller.join(1000);
            secondCaller.join(1000);

            assertFalse(firstCaller.isAlive());
            assertFalse(secondCaller.isAlive());
            assertNotNull(firstResult.get());
            assertNotNull(secondResult.get());
        }
    }

    @Test
    void deadlineFailsClosedAndInterruptsWorker() {
        try (MaskingRequestExecutor executor =
                     new MaskingRequestExecutor(
                             1,
                             50,
                             1000
                     )) {
            LogEvent event = event("deadline-secret", 10L);

            List<MaskedResult> result =
                    executor.execute(
                            List.of(event),
                            control -> {
                                while (true) {
                                    control.checkpoint();
                                    Thread.sleep(10);
                                }
                            }
                    );

            assertEquals(1, result.size());
            assertTrue(result.get(0).failClosed());
            assertEquals(
                    MaskReasonCode.DEADLINE_EXCEEDED,
                    result.get(0).reasonCode()
            );
            assertFalse(
                    result.get(0).maskedText()
                            .contains("deadline-secret")
            );

            MaskingRuntimeStats stats = executor.stats();
            assertEquals(1L, stats.deadlineExceededRequests());
            assertEquals(0L, stats.executionFailureRequests());
        }
    }

    private static void waitForQueuedRequest(
            MaskingRequestExecutor executor
    ) throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(1);

        while (System.nanoTime() < deadline) {
            if (executor.stats().queuedRequests() == 1) {
                return;
            }
            Thread.sleep(5);
        }

        fail("Second masking request was not queued");
    }

    private static List<MaskedResult> success(
            LogEvent event
    ) {
        return List.of(
                new MaskedResult(
                        "[MASKED]",
                        false,
                        false,
                        MaskReasonCode.DETERMINISTIC_RESOLVED,
                        event.getSequenceNumber(),
                        event.getInstanceId()
                )
        );
    }

    private static LogEvent event(
            String message,
            long sequence
    ) {
        return new LogEvent(
                message,
                LogLevel.INFO,
                false,
                "bounded-runtime-test",
                sequence,
                1_780_000_000_000L + sequence
        );
    }
}
