package com.securelogx.engine;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.io.SecureFileAppender;
import com.securelogx.kafka.SecureLogXKafkaProducer;
import com.securelogx.model.LogEvent;
import com.securelogx.model.LogLevel;
import com.securelogx.ner.TokenizerEngine;
import com.securelogx.ner.impl.ONNXDynamicInferenceEngine;
import com.securelogx.ner.impl.ParallelTokenizer;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SecureLogX {
    private final TokenizerEngine tokenizer;
    private final ONNXDynamicInferenceEngine inference;
    private final SecureLogXConfig config;
    private final ExecutorService executor;
    private final BlockingQueue<LogEvent> inferenceQueue;
    private final AtomicInteger droppedLogs = new AtomicInteger(0);
    private final AtomicInteger writerIndex = new AtomicInteger(0);
    private final List<BlockingQueue<String>> writerBuffers = new ArrayList<>();
    private final List<Thread> writerThreads = new ArrayList<>();
    private final Map<String, SecureFileAppender> writerAppenders = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private Thread batchThread;
    private final SecureLogXKafkaProducer kafkaProducer;

    private static final int BATCH_SIZE = 8;
    private static final int INFERENCE_QUEUE_CAPACITY = 10000;
    private final int WRITER_THREAD_COUNT;

    // Use your own RequestContext, not Kafka's
    private static final ThreadLocal<RequestContext> requestContext = ThreadLocal.withInitial(RequestContext::new);

    public SecureLogX() throws Exception {
        String env = System.getenv().getOrDefault("SECURELOGX_ENV", "dev");
        this.config = new SecureLogXConfig(env);
        this.tokenizer = new ParallelTokenizer(config.getTokenizerPath());
        this.inference = new ONNXDynamicInferenceEngine(config.getModelPath(), config);

        int availableThreads = Runtime.getRuntime().availableProcessors();
        int threadCount = config.isCpuMultithreadingEnabled() ? Math.min(8, availableThreads) : 1;
        this.executor = threadCount > 1 ? Executors.newFixedThreadPool(threadCount) : null;
        this.inferenceQueue = new ArrayBlockingQueue<>(INFERENCE_QUEUE_CAPACITY);
        this.WRITER_THREAD_COUNT = threadCount > 1 ? Math.min(10, threadCount) : 1;

        for (int i = 0; i < WRITER_THREAD_COUNT; i++) {
            writerBuffers.add(new ArrayBlockingQueue<>(10000));
        }

        System.out.println("[SecureLogX INIT] is multithreading available: " + config.isCpuMultithreadingEnabled());
        System.out.println("[SecureLogX INIT] CPU Threads Available: " + availableThreads);

        if (config.isKafkaEnabled()) {
            System.out.println("[SecureLogX INIT] Kafka REMOTE mode enabled");
            this.kafkaProducer = new SecureLogXKafkaProducer(config.getKafkaProperties());
        } else {
            this.kafkaProducer = null;
            System.out.println("[SecureLogX INIT] Log Processing Mode: " + (threadCount > 1 ? "Multi-threaded (" + threadCount + " threads)" : "Single-threaded"));
            if (threadCount > 1) {
                startBatchInferenceThread();
                startWriterThreads();
            } else {
                writerAppenders.put("writer0", new SecureFileAppender(config.getLogFilePath()));
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdownExecutor();
                shutdownAppender();
                if (kafkaProducer != null) kafkaProducer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
    }

    public static void initRequest(String traceId) {
        requestContext.set(new RequestContext(traceId));
    }

    public void process(String message, LogLevel level, boolean showLastFour) {
        RequestContext ctx = requestContext.get();
        int seq = ctx.sequence.incrementAndGet();
        LogEvent log = new LogEvent(message, level, showLastFour, ctx.traceId, seq);
        process(log);
    }

    public void process(LogEvent log) {
        String timestamp = LocalDateTime.now().toString();

        if (config.isKafkaEnabled()) {
            kafkaProducer.sendLogEvent(log);
            return;
        }

        if (!log.requiresNER() || !config.isMaskingEnabled() || !config.shouldMaskInCurrentEnv()) {
            String output = formatLog(log, timestamp, log.getMessage());
            if (WRITER_THREAD_COUNT == 1) {
                writerAppenders.get("writer0").write(output);
            } else {
                int index = writerIndex.getAndIncrement() % WRITER_THREAD_COUNT;
                writerBuffers.get(index).offer(output);
            }
        } else {
            if (WRITER_THREAD_COUNT == 1) {
                String masked = inference.runBatch(tokenizer, List.of(log)).get(0);
                writerAppenders.get("writer0").write(masked);
            } else {
                inferenceQueue.offer(log);
            }
        }
    }

    private void startBatchInferenceThread() {
        batchThread = new Thread(() -> {
            List<LogEvent> batch = new ArrayList<>(BATCH_SIZE);
            long lastFlushTime = System.currentTimeMillis();

            while (running || !inferenceQueue.isEmpty()) {
                try {
                    LogEvent first = inferenceQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        batch.clear();
                        batch.add(first);
                        inferenceQueue.drainTo(batch, BATCH_SIZE - 1);
                    }

                    long now = System.currentTimeMillis();
                    if (!batch.isEmpty() && (batch.size() >= BATCH_SIZE || now - lastFlushTime > 300)) {
                        List<String> results = inference.runBatch(tokenizer, batch);
                        for (String r : results) {
                            int index = writerIndex.getAndIncrement() % WRITER_THREAD_COUNT;
                            writerBuffers.get(index).offer(r);
                        }
                        batch.clear();
                        lastFlushTime = now;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "SecureLogXBatcher");
        batchThread.start();
    }

    private void startWriterThreads() {
        for (int i = 0; i < WRITER_THREAD_COUNT; i++) {
            int threadIndex = i;
            Thread writerThread = new Thread(() -> {
                SecureFileAppender appender = getOrCreateAppender("writer" + threadIndex);
                BlockingQueue<String> buffer = writerBuffers.get(threadIndex);
                while (running || !buffer.isEmpty()) {
                    try {
                        String line = buffer.poll(100, TimeUnit.MILLISECONDS);
                        if (line != null) {
                            appender.write(line);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, "SecureLogXWriter-" + i);
            writerThread.start();
            writerThreads.add(writerThread);
        }
    }

    private SecureFileAppender getOrCreateAppender(String name) {
        return writerAppenders.computeIfAbsent(name, n -> {
            String base = config.getLogFilePath().replace(".log", "");
            return new SecureFileAppender(base + "." + n + ".log");
        });
    }

    public void shutdownExecutor() {
        try {
            if (executor != null) {
                executor.shutdown();
                executor.awaitTermination(15, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdownAppender() {
        try {
            running = false;
            inference.shutdown();
            if (batchThread != null) batchThread.join();
            for (Thread t : writerThreads) {
                t.join();
            }
            for (int i = 0; i < WRITER_THREAD_COUNT; i++) {
                BlockingQueue<String> buffer = writerBuffers.get(i);
                while (!buffer.isEmpty()) {
                    String line = buffer.poll();
                    if (line != null) {
                        writerAppenders.get("writer" + i).write(line);
                    }
                }
            }
            writerAppenders.values().forEach(SecureFileAppender::close);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isQueueEmpty() {
        return writerBuffers.stream().allMatch(BlockingQueue::isEmpty);
    }

    public int getDroppedLogCount() {
        return droppedLogs.get();
    }

    private String formatLog(LogEvent log, String timestamp, String message) {
        return String.format("timestamp=%s level=%s traceId=%s seq=%d message=\"%s\"",
                timestamp, log.getLevel().name(), log.getTraceId(), log.getSequenceNumber(), message);
    }

    // Your custom RequestContext for per-thread tracking
    private static class RequestContext {
        final String traceId;
        final AtomicInteger sequence = new AtomicInteger(0);

        RequestContext() {
            this.traceId = java.util.UUID.randomUUID().toString();
        }

        RequestContext(String traceId) {
            this.traceId = traceId;
        }
    }
}
