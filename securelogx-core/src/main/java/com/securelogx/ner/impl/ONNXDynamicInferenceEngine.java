
package com.securelogx.ner.impl;

import ai.onnxruntime.*;
import com.securelogx.detection.DeterministicScanResult;
import com.securelogx.detection.HybridMaskingPipeline;
import com.securelogx.detection.HybridRuntimeStats;
import com.securelogx.model.LogEvent;
import com.securelogx.ner.TokenizerEngine;
import com.securelogx.ner.TokenizedInput;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;


public class ONNXDynamicInferenceEngine {
    private static final int WINDOW_OVERLAP_CONTENT_TOKENS = 64;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final LabelAwareMaskingEngine maskingEngine =
            new LabelAwareMaskingEngine();
    private final BioConstrainedSpanDecoder constrainedDecoder =
            new BioConstrainedSpanDecoder();
    private final HybridMaskingPipeline hybridMaskingPipeline =
            new HybridMaskingPipeline();
    private final MlDecoderMode decoderMode;
    private volatile boolean running = true;
    private  boolean isGpuMode;
    private int optimalBatchSize;
    private int maxSeqLen;
    private final int maxInferenceWindowsPerBatch;

    // Enhanced tensor pooling for GPU memory reuse
    private final Map<String, Queue<OnnxTensor>> tensorPool = new ConcurrentHashMap<>();
    private final Object tensorLock = new Object();

    // Async tokenization pipeline
    private final ThreadPoolExecutor tokenizerExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Performance metrics
    private long totalInferenceTime = 0;
    private long totalBatches = 0;
    private long totalItems = 0;
    private long deterministicOnlyItems = 0;
    private long mlInferenceItems = 0;
    private long windowedMlItems = 0;
    private long mlInferenceWindows = 0;
    private long onnxInferenceCalls = 0;
    private long maxInferenceWindowsPerCallObserved = 0;
    private long truncatedFailClosedItems = 0;

    public ONNXDynamicInferenceEngine(String modelPath, com.securelogx.config.SecureLogXConfig config) throws Exception {
        // Check CUDA environment first
        checkCudaEnvironment();
        addCudaToLibraryPath();

        this.env = OrtEnvironment.getEnvironment();
        this.maxInferenceWindowsPerBatch =
                config.getMaxInferenceWindowsPerBatch();
        this.decoderMode = config.getMlDecoderMode();

        OrtSession createdSession;
        try (OrtSession.SessionOptions opts =
                     new OrtSession.SessionOptions()) {
            // Print all available providers
            EnumSet<OrtProvider> availableProviders =
                    OrtEnvironment.getAvailableProviders();
            System.out.println(
                    "[SecureLogX INIT] Available ONNX Providers: "
                            + availableProviders
            );

            // Check GPU configuration
            boolean gpuRequested = config.isGpuInferenceEnabled();
            boolean cudaAvailable =
                    availableProviders.contains(OrtProvider.CUDA);
            this.isGpuMode = gpuRequested && cudaAvailable;

            System.out.println(
                    "[SecureLogX INIT] GPU Inference Requested: "
                            + gpuRequested
            );
            System.out.println(
                    "[SecureLogX INIT] CUDA Provider Available: "
                            + cudaAvailable
            );

            if (isGpuMode) {
                try {
                    System.out.println(
                            "[SecureLogX INIT] Attempting to enable CUDA..."
                    );
                    opts.addCUDA();

                    long gpuMemory = estimateGpuMemory();
                    this.optimalBatchSize =
                            calculateOptimalBatchSize(gpuMemory);
                    this.maxSeqLen = config.getMaxSequenceLength();

                    System.out.println(
                            "[SecureLogX INIT] GPU Inference Mode Enabled "
                                    + "(CUDA)"
                    );
                    System.out.println(
                            "[SecureLogX INIT] Optimal batch size: "
                                    + optimalBatchSize
                    );
                    System.out.println(
                            "[SecureLogX INIT] Max sequence length: "
                                    + maxSeqLen
                    );
                } catch (Exception e) {
                    System.err.println(
                            "[SecureLogX INIT] Failed to enable CUDA: "
                                    + e.getMessage()
                    );
                    System.out.println(
                            "[SecureLogX INIT] Falling back to CPU mode"
                    );
                    setupCpuMode(config, opts);
                }
            } else {
                setupCpuMode(config, opts);
            }

            createdSession = env.createSession(
                    modelPath.replace("\\", "/"),
                    opts
            );
        }

        this.session = createdSession;

        // Initialize async tokenizer pool (smaller for GPU to reduce contention)
        int tokenizerThreads = isGpuMode ? 2 : Math.min(4, Runtime.getRuntime().availableProcessors());
        this.tokenizerExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(tokenizerThreads);

        // GPU warmup with optimal batch size
        if (isGpuMode) {
            warmupGPU();
        }

        System.out.println("[SecureLogX INIT] Session created successfully");
        System.out.println("[SecureLogX INIT] Model loaded from: " + modelPath);
        System.out.println(
                "[SecureLogX INIT] Max inference windows per ONNX call: "
                        + maxInferenceWindowsPerBatch
        );
        System.out.println(
                "[SecureLogX INIT] ML decoder mode: " + decoderMode
        );
    }

    private void setupCpuMode(com.securelogx.config.SecureLogXConfig config, OrtSession.SessionOptions opts) {
        this.isGpuMode = false;
        this.optimalBatchSize = 32; // Standard batch size for CPU
        this.maxSeqLen = config.getMaxSequenceLength();

        int threads = config.isCpuMultithreadingEnabled() ? Runtime.getRuntime().availableProcessors() : 1;
        System.out.println("[SecureLogX INIT] CPU Inference Mode Enabled");
        System.out.println("[SecureLogX INIT] CPU Threads Used: " + threads);
        try {
            opts.setIntraOpNumThreads(threads);
        } catch (ai.onnxruntime.OrtException e) {
            System.err.println("[SecureLogX INIT] Failed to set CPU thread count: " + e.getMessage());
            // Continue with default threading - this is not a fatal error
        }

    }

    private long estimateGpuMemory() {
        // This is a rough estimate - in production, you'd query actual GPU memory
        return 8_000_000_000L; // Default 8GB assumption
    }

    private int calculateOptimalBatchSize(long gpuMemory) {
        // Rough calculation: GPU memory / (avg_tokens * hidden_size * bytes_per_float)
        // For BERT-base: 512 tokens * 768 hidden * 4 bytes = ~1.5MB per sequence
        if (gpuMemory > 16_000_000_000L) return 128; // 16GB+
        if (gpuMemory > 8_000_000_000L) return 64;   // 8GB+
        if (gpuMemory > 4_000_000_000L) return 32;   // 4GB+
        return 16; // < 4GB
    }

    private OnnxTensor getTensorFromPool(String key, long[][] data) throws OrtException {
        String sizeKey = key + "_" + data.length + "_" + data[0].length;

        synchronized (tensorLock) {
            Queue<OnnxTensor> pool = tensorPool.get(sizeKey);
            if (pool != null && !pool.isEmpty()) {
                OnnxTensor tensor = pool.poll();
                if (tensor != null && !tensor.isClosed()) {
                    // Reuse existing tensor by updating its data
                    return OnnxTensor.createTensor(env, data); // Note: ONNX Runtime doesn't support tensor data updates, so we create new ones
                }
            }
        }

        return OnnxTensor.createTensor(env, data);
    }

    private void returnTensorToPool(String key, OnnxTensor tensor, int batchSize, int seqLen) {
        if (tensor == null || tensor.isClosed()) return;

        String sizeKey = key + "_" + batchSize + "_" + seqLen;

        synchronized (tensorLock) {
            tensorPool.computeIfAbsent(sizeKey, k -> new LinkedList<>()).offer(tensor);
        }
    }

    public List<String> runBatch(TokenizerEngine tokenizer, List<LogEvent> batch) {
        long batchStartTime = System.currentTimeMillis();

        // Adaptive batch sizing based on GPU memory pressure
        int actualBatchSize = Math.min(optimalBatchSize, batch.size());
        List<LogEvent> currentBatch = batch.subList(0, actualBatchSize);
        List<String> allResults = new ArrayList<>();

        // Process in optimal-sized chunks
        for (int i = 0; i < batch.size(); i += optimalBatchSize) {
            int endIdx = Math.min(i + optimalBatchSize, batch.size());
            List<LogEvent> subBatch = batch.subList(i, endIdx);
            allResults.addAll(processBatchChunk(tokenizer, subBatch, batchStartTime));
        }

        return allResults;
    }

    private List<String> processBatchChunk(
            TokenizerEngine tokenizer,
            List<LogEvent> batch,
            long overallStartTime
    ) {
        String[] orderedOutput = new String[batch.size()];

        try {
            List<DeterministicScanResult> scans = new ArrayList<>(batch.size());
            List<LogEvent> mlBatch = new ArrayList<>();
            List<Integer> mlOriginalIndices = new ArrayList<>();

            // 1) Cheap deterministic scan and conservative ML gate.
            for (int i = 0; i < batch.size(); i++) {
                LogEvent event = batch.get(i);
                DeterministicScanResult scan =
                        hybridMaskingPipeline.scan(event.getMessage());
                scans.add(scan);

                if (scan.requiresMl()) {
                    mlOriginalIndices.add(i);
                    mlBatch.add(event);
                } else {
                    String masked = hybridMaskingPipeline.maskWithoutMl(
                            event.getMessage(),
                            scan,
                            event.shouldShowLastFour()
                    );
                    orderedOutput[i] = formatMaskedEvent(event, masked);
                    deterministicOnlyItems++;
                }
            }

            // Every record was resolved without model inference.
            if (mlBatch.isEmpty()) {
                totalItems += batch.size();
                return Arrays.asList(orderedOutput);
            }

            // 2) Tokenize only unresolved records. Over-window inputs are
            // split into overlapping windows with original-text offsets.
            CompletableFuture<List<List<TokenizedInput>>> tokenizationFuture =
                    CompletableFuture.supplyAsync(
                            () -> mlBatch.parallelStream()
                                    .map(
                                            event -> tokenizer.tokenizeWindows(
                                                    event.getMessage(),
                                                    WINDOW_OVERLAP_CONTENT_TOKENS
                                            )
                                    )
                                    .collect(Collectors.toList()),
                            tokenizerExecutor
                    );

            List<List<TokenizedInput>> windowSets =
                    tokenizationFuture.get();

            List<TokenizedInput> inferenceEncoded = new ArrayList<>();
            List<Integer> inferenceOriginalIndices = new ArrayList<>();
            Set<Integer> windowedOriginalIndices = new HashSet<>();

            for (int mlIndex = 0; mlIndex < windowSets.size(); mlIndex++) {
                List<TokenizedInput> windows = windowSets.get(mlIndex);
                int originalIndex = mlOriginalIndices.get(mlIndex);
                LogEvent event = batch.get(originalIndex);

                if (windows == null || windows.isEmpty()) {
                    orderedOutput[originalIndex] =
                            formatMaskedEvent(
                                    event,
                                    "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                            );
                    truncatedFailClosedItems++;
                    continue;
                }

                boolean invalidWindow = windows.stream().anyMatch(
                        TokenizedInput::isTruncated
                );
                if (invalidWindow) {
                    orderedOutput[originalIndex] =
                            formatMaskedEvent(
                                    event,
                                    "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                            );
                    truncatedFailClosedItems++;
                    System.err.println(
                            "[WARN] SecureLogX fail-closed: tokenizer "
                                    + "implementation returned an incomplete "
                                    + "window for an ML-routed message."
                    );
                    continue;
                }

                if (windows.size() > 1) {
                    windowedOriginalIndices.add(originalIndex);
                }

                for (TokenizedInput window : windows) {
                    inferenceEncoded.add(window);
                    inferenceOriginalIndices.add(originalIndex);
                }
            }

            if (inferenceEncoded.isEmpty()) {
                totalItems += batch.size();
                return Arrays.asList(orderedOutput);
            }

            // 3) Run ONNX over bounded inference-window micro-batches.
            //
            // Record-level batching alone is not sufficient because one long
            // record may expand into many overlapping model windows. Bounding
            // windows per session.run(...) prevents native activation/tensor
            // memory from scaling with total expanded windows in the record
            // batch.
            long inferenceStart = System.currentTimeMillis();

            Map<Integer, List<LabelAwareMaskingEngine.EntitySpan>>
                    argmaxSpansByOriginal = new LinkedHashMap<>();
            Map<Integer, List<LabelAwareMaskingEngine.EntitySpan>>
                    constrainedSpansByOriginal = new LinkedHashMap<>();

            for (Integer originalIndex : inferenceOriginalIndices) {
                argmaxSpansByOriginal.computeIfAbsent(
                        originalIndex,
                        ignored -> new ArrayList<>()
                );
                if (decoderMode
                        == MlDecoderMode.BIO_VITERBI_SAFETY_SUPPLEMENT) {
                    constrainedSpansByOriginal.computeIfAbsent(
                            originalIndex,
                            ignored -> new ArrayList<>()
                    );
                }
            }

            for (int windowStart = 0;
                 windowStart < inferenceEncoded.size();
                 windowStart += maxInferenceWindowsPerBatch) {
                int windowEnd = Math.min(
                        windowStart + maxInferenceWindowsPerBatch,
                        inferenceEncoded.size()
                );

                List<TokenizedInput> windowBatch =
                        inferenceEncoded.subList(windowStart, windowEnd);

                int rawMax = windowBatch.stream()
                        .mapToInt(item -> item.getInputIds().length)
                        .max()
                        .orElse(0);
                int seqLen = Math.min(rawMax, maxSeqLen);
                int inferenceBatchSize = windowBatch.size();

                long[][] inputIds =
                        new long[inferenceBatchSize][seqLen];
                long[][] attentionMask =
                        new long[inferenceBatchSize][seqLen];
                long[][] tokenTypeIds =
                        new long[inferenceBatchSize][seqLen];

                for (int localIndex = 0;
                     localIndex < inferenceBatchSize;
                     localIndex++) {
                    TokenizedInput encoded =
                            windowBatch.get(localIndex);
                    int[] ids = encoded.getInputIds();
                    int[] mask = encoded.getAttentionMask();
                    int copyLength = Math.min(ids.length, seqLen);

                    for (int j = 0; j < copyLength; j++) {
                        inputIds[localIndex][j] = ids[j];
                        attentionMask[localIndex][j] = mask[j];
                    }
                }

                try (OnnxTensor inputTensor =
                             OnnxTensor.createTensor(env, inputIds);
                     OnnxTensor maskTensor =
                             OnnxTensor.createTensor(env, attentionMask);
                     OnnxTensor typeTensor =
                             OnnxTensor.createTensor(env, tokenTypeIds)) {

                    Map<String, OnnxTensor> inputs = Map.of(
                            "input_ids", inputTensor,
                            "attention_mask", maskTensor,
                            "token_type_ids", typeTensor
                    );

                    try (OrtSession.Result result = session.run(inputs)) {
                        float[][][] logits =
                                (float[][][]) result.get(0).getValue();

                        for (int localIndex = 0;
                             localIndex < inferenceBatchSize;
                             localIndex++) {
                            int flatWindowIndex =
                                    windowStart + localIndex;
                            int originalIndex =
                                    inferenceOriginalIndices.get(
                                            flatWindowIndex
                                    );
                            LogEvent event =
                                    batch.get(originalIndex);
                            TokenizedInput tokenized =
                                    windowBatch.get(localIndex);

                            List<int[]> offsets =
                                    tokenized.getOffsets();
                            List<int[]> truncatedOffsets =
                                    offsets.size() > seqLen
                                            ? offsets.subList(0, seqLen)
                                            : offsets;

                            List<
                                    LabelAwareMaskingEngine.EntitySpan>
                                    argmaxWindowSpans =
                                    maskingEngine.decodeSpans(
                                            event.getMessage(),
                                            new float[][][]{
                                                    logits[localIndex]
                                            },
                                            truncatedOffsets
                                    );

                            argmaxSpansByOriginal
                                    .get(originalIndex)
                                    .addAll(argmaxWindowSpans);

                            if (decoderMode
                                    == MlDecoderMode
                                            .BIO_VITERBI_SAFETY_SUPPLEMENT) {
                                BioConstrainedSpanDecoder.DecodeResult
                                        constrainedResult =
                                        constrainedDecoder.decode(
                                                event.getMessage(),
                                                logits[localIndex],
                                                truncatedOffsets
                                        );

                                constrainedSpansByOriginal
                                        .get(originalIndex)
                                        .addAll(
                                                constrainedResult.spans()
                                        );
                            }
                        }
                    }
                }

                mlInferenceWindows += inferenceBatchSize;
                onnxInferenceCalls++;
                maxInferenceWindowsPerCallObserved = Math.max(
                        maxInferenceWindowsPerCallObserved,
                        inferenceBatchSize
                );
            }

            // 4) Merge duplicate/overlapping same-entity spans across all
            // micro-batches, resolve deterministic evidence once, and mask.
            for (Map.Entry<
                    Integer,
                    List<LabelAwareMaskingEngine.EntitySpan>>
                    entry : argmaxSpansByOriginal.entrySet()) {
                int originalIndex = entry.getKey();
                LogEvent event = batch.get(originalIndex);

                List<LabelAwareMaskingEngine.EntitySpan> mergedArgmax =
                        mergeWindowSpans(entry.getValue());

                List<LabelAwareMaskingEngine.EntitySpan> selectedSpans;
                if (decoderMode
                        == MlDecoderMode.BIO_VITERBI_SAFETY_SUPPLEMENT) {
                    List<LabelAwareMaskingEngine.EntitySpan>
                            mergedConstrained =
                            mergeWindowSpans(
                                    constrainedSpansByOriginal
                                            .getOrDefault(
                                                    originalIndex,
                                                    List.of()
                                            )
                            );

                    selectedSpans =
                            BioConstrainedSpanDecoder.safetySupplement(
                                    mergedConstrained,
                                    mergedArgmax
                            );
                } else {
                    selectedSpans = mergedArgmax;
                }

                String masked = hybridMaskingPipeline.maskWithMl(
                        event.getMessage(),
                        scans.get(originalIndex),
                        selectedSpans,
                        event.shouldShowLastFour()
                );

                orderedOutput[originalIndex] =
                        formatMaskedEvent(event, masked);
                mlInferenceItems++;
                if (windowedOriginalIndices.contains(originalIndex)) {
                    windowedMlItems++;
                }
            }

            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            updatePerformanceMetrics(inferenceTime, batch.size());

            if (totalBatches % 10 == 0) {
                long avgPerBatch =
                        totalBatches > 0 ? totalInferenceTime / totalBatches : 0;
                long avgPerItem =
                        totalItems > 0 ? totalInferenceTime / totalItems : 0;
                long routed = deterministicOnlyItems + mlInferenceItems;
                double mlRate = routed > 0
                        ? (100.0 * mlInferenceItems / routed)
                        : 0.0;

                System.out.println(
                        String.format(
                                "[PERF] Batch %d: %dms (%d items, avg: %dms/batch, %dms/item, ML-route=%.2f%%)",
                                totalBatches,
                                inferenceTime,
                                batch.size(),
                                avgPerBatch,
                                avgPerItem,
                                mlRate
                        )
                );
            }

            return Arrays.asList(orderedOutput);

        } catch (InterruptedException e) {
            System.err.println(
                    "[ERROR] Hybrid tokenization interrupted: " + e.getMessage()
            );
            Thread.currentThread().interrupt();
            return createFallbackResults(batch);
        } catch (ExecutionException e) {
            System.err.println(
                    "[ERROR] Hybrid tokenization failed: " + e.getMessage()
            );
            return createFallbackResults(batch);
        } catch (Exception e) {
            System.err.println(
                    "[ERROR] Hybrid inference failed for batch size: "
                            + batch.size()
            );
            e.printStackTrace();
            return createFallbackResults(batch);
        }
    }

    private static List<LabelAwareMaskingEngine.EntitySpan>
            mergeWindowSpans(
                    List<LabelAwareMaskingEngine.EntitySpan> spans
            ) {
        if (spans.isEmpty()) {
            return List.of();
        }

        List<LabelAwareMaskingEngine.EntitySpan> ordered =
                new ArrayList<>(spans);
        ordered.sort(
                Comparator.comparing(
                                LabelAwareMaskingEngine.EntitySpan::entityType
                        )
                        .thenComparingInt(
                                LabelAwareMaskingEngine.EntitySpan::start
                        )
                        .thenComparingInt(
                                LabelAwareMaskingEngine.EntitySpan::end
                        )
        );

        List<LabelAwareMaskingEngine.EntitySpan> merged =
                new ArrayList<>();
        LabelAwareMaskingEngine.EntitySpan current = null;

        for (LabelAwareMaskingEngine.EntitySpan span : ordered) {
            if (current == null) {
                current = span;
                continue;
            }

            boolean sameType =
                    current.entityType().equals(span.entityType());
            boolean overlaps =
                    Math.max(current.start(), span.start())
                            < Math.min(current.end(), span.end());

            if (sameType && overlaps) {
                current = new LabelAwareMaskingEngine.EntitySpan(
                        Math.min(current.start(), span.start()),
                        Math.max(current.end(), span.end()),
                        current.entityType()
                );
            } else {
                merged.add(current);
                current = span;
            }
        }

        if (current != null) {
            merged.add(current);
        }

        merged.sort(
                Comparator.comparingInt(
                                LabelAwareMaskingEngine.EntitySpan::start
                        )
                        .thenComparingInt(
                                LabelAwareMaskingEngine.EntitySpan::end
                        )
                        .thenComparing(
                                LabelAwareMaskingEngine.EntitySpan::entityType
                        )
        );
        return List.copyOf(merged);
    }

    private String formatMaskedEvent(LogEvent event, String masked) {
        String timestamp = java.time.LocalDateTime.now().toString();
        return String.format(
                "timestamp=%s level=%s traceId=%s seq=%d message=\"%s\"",
                timestamp,
                event.getLevel().name(),
                event.getTraceId(),
                event.getSequenceNumber(),
                masked
        );
    }

    private List<String> createFallbackResults(List<LogEvent> batch) {
        List<String> fallbackResults = new ArrayList<>();
        for (LogEvent event : batch) {
            fallbackResults.add(
                    formatMaskedEvent(
                            event,
                            "[SECURELOGX_REDACTED_PROCESSING_FAILURE]"
                    )
            );
        }
        return fallbackResults;
    }

    private void updatePerformanceMetrics(long inferenceTime, int batchSize) {
        totalInferenceTime += inferenceTime;
        totalBatches++;
        totalItems += batchSize;
    }

    private void warmupGPU() {
        try {
            System.out.println("[SecureLogX INIT] Starting GPU warmup with optimal batch size...");
            long startTime = System.currentTimeMillis();

            // Use optimal batch size for warmup
            long[][] dummyInput = new long[optimalBatchSize][64]; // Smaller sequence for warmup
            long[][] dummyMask = new long[optimalBatchSize][64];
            long[][] dummyType = new long[optimalBatchSize][64];

            // Fill with realistic data
            for (int i = 0; i < optimalBatchSize; i++) {
                for (int j = 0; j < 64; j++) {
                    dummyInput[i][j] = j + 1; // Avoid zeros
                    dummyMask[i][j] = 1;
                    dummyType[i][j] = 0;
                }
            }

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, dummyInput);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(env, dummyMask);
                 OnnxTensor typeTensor = OnnxTensor.createTensor(env, dummyType)) {

                Map<String, OnnxTensor> warmupInputs = Map.of(
                        "input_ids", inputTensor,
                        "attention_mask", maskTensor,
                        "token_type_ids", typeTensor
                );

                // Run multiple warmup iterations
                for (int i = 0; i < 3; i++) {
                    try (OrtSession.Result result = session.run(warmupInputs)) {
                        result.get(0).getValue(); // Consume result
                    }
                }
            }

            long warmupTime = System.currentTimeMillis() - startTime;
            System.out.println("[SecureLogX INIT] ✅ GPU warmup completed in " + warmupTime + "ms");
        } catch (Exception e) {
            System.err.println("[SecureLogX INIT] ❌ GPU warmup failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ... existing methods (addCudaToLibraryPath, checkCudaEnvironment) remain the same ...

    private void addCudaToLibraryPath() {
        try {
            String[] cudaPaths = {
                    "C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA\\v12.4\\bin",
                    "C:\\Program Files\\NVIDIA\\CUDNN\\v9.12\\bin\\12.9"
            };

            String currentLibPath = System.getProperty("java.library.path");
            StringBuilder newLibPath = new StringBuilder(currentLibPath);

            for (String cudaPath : cudaPaths) {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(cudaPath))) {
                    newLibPath.append(System.getProperty("path.separator")).append(cudaPath);
                    System.out.println("[SecureLogX INIT] Added to library path: " + cudaPath);
                }
            }

            System.setProperty("java.library.path", newLibPath.toString());

            java.lang.reflect.Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            fieldSysPath.setAccessible(true);
            fieldSysPath.set(null, null);

        } catch (Exception e) {
            System.err.println("[SecureLogX INIT] Failed to update library path: " + e.getMessage());
        }
    }

    private void checkCudaEnvironment() {
        System.out.println("[SecureLogX INIT] === CUDA Environment Check ===");

        String cudaPath = System.getenv("CUDA_PATH");
        System.out.println("[SecureLogX INIT] CUDA_PATH: " + (cudaPath != null ? cudaPath : "Not set"));

        String path = System.getenv("PATH");
        boolean hasCudaInPath = path != null && (path.contains("CUDA") || path.contains("cuda"));
        System.out.println("[SecureLogX INIT] CUDA in PATH: " + hasCudaInPath);

        try {
            ProcessBuilder pb = new ProcessBuilder("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits");
            Process process = pb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String gpuInfo = reader.readLine();
            if (gpuInfo != null && !gpuInfo.trim().isEmpty()) {
                System.out.println("[SecureLogX INIT] Detected GPU: " + gpuInfo.trim());
            }
            process.waitFor();
        } catch (Exception e) {
            System.out.println("[SecureLogX INIT] GPU detection failed: " + e.getMessage());
        }

        System.out.println("[SecureLogX INIT] === End CUDA Environment Check ===");
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        this.running = false;

        // Shutdown tokenizer executor
        if (tokenizerExecutor != null) {
            tokenizerExecutor.shutdown();
            try {
                if (!tokenizerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    tokenizerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                tokenizerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Clean up tensor pool
        synchronized (tensorLock) {
            tensorPool.values().forEach(queue ->
                    queue.forEach(tensor -> {
                        if (!tensor.isClosed()) tensor.close();
                    }));
            tensorPool.clear();
        }

        // Print final performance statistics
        if (totalBatches > 0) {
            System.out.println(String.format("[PERF] Final stats: %d batches, %d items, avg: %dms/batch, %dms/item",
                    totalBatches, totalItems, totalInferenceTime / totalBatches, totalInferenceTime / totalItems));
        }

        try {
            session.close();
        } catch (Exception e) {
            System.err.println(
                    "[SecureLogX SHUTDOWN] Failed to close ONNX session: "
                            + e.getMessage()
            );
        }
    }

    public HybridRuntimeStats getHybridRuntimeStats() {
        return new HybridRuntimeStats(
                deterministicOnlyItems,
                mlInferenceItems,
                windowedMlItems,
                mlInferenceWindows,
                onnxInferenceCalls,
                maxInferenceWindowsPerCallObserved,
                truncatedFailClosedItems
        );
    }

    public boolean isRunning() {
        return running;
    }
}