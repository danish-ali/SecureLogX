package com.securelogx.engine;

import com.securelogx.config.SecureLogXConfig;
import com.securelogx.consumer.MaskingConsumer;
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

/**
 * SecureLogX supports four modes:
 *  - CPU_SINGLE: single-threaded CPU masking and file write
 *  - CPU_MULTI:  multi-threaded CPU masking and file write
 *  - GPU:        GPU masking and file write
 *  - KAFKA:      raw-log producer only
 */
public class SecureLogX {

    private enum Mode { CPU_SINGLE, CPU_MULTI, GPU, KAFKA }

    private final SecureLogXConfig config;
    private final Mode mode;
    private final TokenizerEngine tokenizer;
    private final ONNXDynamicInferenceEngine inferenceEngine;
    private final SecureLogXKafkaProducer kafkaProducer;
    private final ExecutorService executor;
    private final BlockingQueue<LogEvent> inferenceQueue;

    private final int WRITER_THREAD_COUNT;
    private final List<BlockingQueue<String>> writerBuffers = new ArrayList<>();
    private final List<Thread> writerThreads = new ArrayList<>();
    private final Map<String, SecureFileAppender> writerAppenders = new ConcurrentHashMap<>();
    private final AtomicInteger writerIndex = new AtomicInteger(0);
    private volatile boolean running = true;
    private Thread batchThread;

    private static final int BATCH_SIZE = 8;
    private static final int INFERENCE_QUEUE_CAPACITY = 10_000;
    private static final ThreadLocal<RequestContext> requestContext = ThreadLocal.withInitial(RequestContext::new);

    /**
     * Initializes SecureLogX in one of four modes:
     *  - CPU_SINGLE: single-threaded masking + file write
     *  - CPU_MULTI:  multi-threaded masking + file write
     *  - GPU:        GPU masking + file write
     *  - KAFKA:      Kafka producer (+ optional consumer launched externally)
     *
     * In KAFKA mode, only the producer is initialized here; consumer startup
     * is driven by the external AppLauncher based on config.
     *
     * @throws Exception on initialization failures
     */
    public SecureLogX() throws Exception {
        // Load config and tokenizer
        this.config = new SecureLogXConfig(System.getenv().getOrDefault("SECURELOGX_ENV", "dev"));
        this.tokenizer = new ParallelTokenizer(config.getTokenizerPath());

        // Determine mode
        String m = config.getMode();
        Mode tmp;
        try {
            tmp = Mode.valueOf(m.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            tmp = Mode.CPU_SINGLE;
        }
        this.mode = tmp;

        switch (mode) {
            case KAFKA:
                // Start masking consumer
            /*    Thread consumerThread = new Thread(() -> {
                    try {
                        MaskingConsumer consumer = new MaskingConsumer(
                                System.getenv().getOrDefault("SECURELOGX_ENV", "dev")
                        );
                        System.out.println("[SecureLogX] Starting MaskingConsumer on topic: "
                                + config.getKafkaProperties().get("topic"));
                        consumer.run();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }, "SecureLogX-MaskingConsumer");
                consumerThread.setDaemon(true);
                consumerThread.start();  */
                // Producer only
                this.inferenceEngine = null;
                this.kafkaProducer   = new SecureLogXKafkaProducer(this.config.getKafkaProperties());
                this.executor        = null;
                this.inferenceQueue  = null;
                this.WRITER_THREAD_COUNT = 0;
                break;

            default:
                // Local masking
                this.kafkaProducer = null;
                this.inferenceEngine = new ONNXDynamicInferenceEngine(config.getModelPath(), config);

                boolean multiCpu = (mode == Mode.CPU_MULTI);
                boolean useGpu   = (mode == Mode.GPU);
                int cores = Runtime.getRuntime().availableProcessors();
                int threads = useGpu ? 1 : (multiCpu ? Math.min(config.getMaxCpuThreads(), cores) : 1);
                this.WRITER_THREAD_COUNT = threads;

                this.executor = multiCpu ? Executors.newFixedThreadPool(threads) : null;
                this.inferenceQueue = new ArrayBlockingQueue<>(INFERENCE_QUEUE_CAPACITY);

                for (int i = 0; i < WRITER_THREAD_COUNT; i++) {
                    writerBuffers.add(new ArrayBlockingQueue<>(INFERENCE_QUEUE_CAPACITY));
                }
                if (WRITER_THREAD_COUNT > 1) {
                    startBatchInferenceThread();
                    startWriterThreads();
                } else {
                    writerAppenders.put("writer0", new SecureFileAppender(config.getLogFilePath()));
                }
                break;
        }

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { shutdown(); } catch (Exception ignored) {}
        }));
    }

    public static void initRequest(String traceId) {
        requestContext.set(new RequestContext(traceId));
    }

    public void process(String message, LogLevel level, boolean showLastFour) {
        RequestContext ctx = requestContext.get();
        int seq = ctx.sequence.incrementAndGet();
        process(new LogEvent(message, level, showLastFour, ctx.traceId, seq));
    }

    public void process(LogEvent log) {
        System.out.println("[DEBUG] Entering process(), mode=" + mode);
        String ts = LocalDateTime.now().toString();
        if (mode == Mode.KAFKA) {
            System.out.println("[DEBUG] In KAFKA branch, about to sendRaw");
            kafkaProducer.sendRaw(formatLog(log, ts, log.getMessage()));
            System.out.println("[DEBUG] After sendRaw, returning");
            return;
        }

        boolean needMask = log.requiresNER()
                && config.isMaskingEnabled()
                && config.shouldMaskInCurrentEnv();

        // No masking → immediate write
        if (!needMask) {
            writeLine(formatLog(log, ts, log.getMessage()));
            return;
        }

        // Single-threaded: do inference synchronously
        if (WRITER_THREAD_COUNT <= 1) {
            List<String> masked = inferenceEngine.runBatch(tokenizer, List.of(log));
            writeLine(masked.get(0));
        }
        // Multi-threaded: enqueue for the batcher
        else {
            inferenceQueue.offer(log);
        }
    }


    private void startBatchInferenceThread() {
        batchThread = new Thread(() -> {
            List<LogEvent> batch = new ArrayList<>(BATCH_SIZE);
            long lastFlush = System.currentTimeMillis();
            while (running || !inferenceQueue.isEmpty()) {
                try {
                    LogEvent first = inferenceQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        batch.clear(); batch.add(first);
                        inferenceQueue.drainTo(batch, BATCH_SIZE - 1);
                    }
                    long now = System.currentTimeMillis();
                    if (!batch.isEmpty() && (batch.size() >= BATCH_SIZE || now - lastFlush > 500)) {
                        List<String> masked = inferenceEngine.runBatch(tokenizer, batch);
                        masked.forEach(this::writeLine);
                        batch.clear(); lastFlush = now;
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
            final int idx = i;
            Thread writer = new Thread(() -> {
                SecureFileAppender app = writerAppenders.computeIfAbsent(
                        "writer" + idx,
                        k -> new SecureFileAppender(
                                config.getLogFilePath().replace(".log", "") + "." + k + ".log"
                        )
                );
                BlockingQueue<String> buf = writerBuffers.get(idx);
                while (running || !buf.isEmpty()) {
                    try {
                        String line = buf.poll(200, TimeUnit.MILLISECONDS);
                        if (line != null) app.write(line);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, "SecureLogXWriter-" + idx);
            writer.start(); writerThreads.add(writer);
        }
    }

    private void writeLine(String line) {
        if (WRITER_THREAD_COUNT <= 1) {
            writerAppenders.get("writer0").write(line);
        } else {
            int idx = writerIndex.getAndIncrement() % WRITER_THREAD_COUNT;
            writerBuffers.get(idx).offer(line);
        }
    }

    private String formatLog(LogEvent log, String ts, String msg) {
        return String.format(
                "timestamp=%s level=%s traceId=%s seq=%d message=\"%s\"",
                ts, log.getLevel(), log.getTraceId(), log.getSequenceNumber(), msg
        );
    }

    public void shutdown() throws Exception {
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        running = false;
        if (batchThread != null) batchThread.join();
        writerThreads.forEach(t -> {
            try { t.join(); } catch (InterruptedException ignored) {}
        });
        writerAppenders.values().forEach(SecureFileAppender::close);
        if (kafkaProducer != null) kafkaProducer.close();
        if (inferenceEngine != null) inferenceEngine.shutdown();
    }

    public boolean isQueueEmpty() {
        if (mode == Mode.KAFKA) return true;
        return inferenceQueue.isEmpty() && writerBuffers.stream().allMatch(Queue::isEmpty);
    }

    private static class RequestContext {
        final String traceId;
        final AtomicInteger sequence = new AtomicInteger(0);
        RequestContext() { this.traceId = UUID.randomUUID().toString(); }
        RequestContext(String traceId) { this.traceId = traceId; }
    }

    /**
     * Stops any in-flight inference work and shuts down the thread pool (if CPU_MULTI).
     */
    public void shutdownExecutor() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Flushes and stops all writer threads and closes file appenders.
     */
    public void shutdownAppender() throws InterruptedException {
        running = false;
        if (batchThread != null) batchThread.join();
        for (Thread t : writerThreads) t.join();
        writerAppenders.values().forEach(SecureFileAppender::close);
    }

}
