package com.securelogx.ner.impl;

import ai.onnxruntime.*;
import com.securelogx.model.LogEvent;
import com.securelogx.ner.TokenizerEngine;
import com.securelogx.ner.TokenizedInput;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.Arrays;


public class ONNXDynamicInferenceEngine {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final LabelAwareMaskingEngine maskingEngine = new LabelAwareMaskingEngine();
    private volatile boolean running = true;

    // Add tensor cache for GPU memory reuse
    private final Map<String, OnnxTensor> tensorCache = new ConcurrentHashMap<>();
    private final Object tensorLock = new Object();




    public ONNXDynamicInferenceEngine(String modelPath, com.securelogx.config.SecureLogXConfig config) throws Exception {
        // Check CUDA environment first
        checkCudaEnvironment();

        // Add CUDA paths to library path before creating environment
        addCudaToLibraryPath();

        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

        // Print all available providers
        EnumSet<OrtProvider> availableProviders = OrtEnvironment.getAvailableProviders();
        System.out.println("[SecureLogX INIT] Available ONNX Providers: " + availableProviders);

        // Check GPU configuration - Fix: Use proper enum comparison
        boolean gpuRequested = config.isGpuInferenceEnabled();
        boolean cudaAvailable = availableProviders.contains(OrtProvider.CUDA);

        System.out.println("[SecureLogX INIT] GPU Inference Requested: " + gpuRequested);
        System.out.println("[SecureLogX INIT] CUDA Provider Available: " + cudaAvailable);

        if (gpuRequested && cudaAvailable) {
            try {
                System.out.println("[SecureLogX INIT] Attempting to enable CUDA...");
                opts.addCUDA();
                System.out.println("[SecureLogX INIT] ✅ GPU Inference Mode Enabled (CUDA)");
            } catch (Exception e) {
                System.err.println("[SecureLogX INIT] ❌ Failed to enable CUDA: " + e.getMessage());
                System.out.println("[SecureLogX INIT] Falling back to CPU mode");
                int threads = config.isCpuMultithreadingEnabled() ? Runtime.getRuntime().availableProcessors() : 1;
                System.out.println("[SecureLogX INIT] CPU Threads Used: " + threads);
                opts.setIntraOpNumThreads(threads);
            }
        } else {
            if (gpuRequested && !cudaAvailable) {
                System.out.println("[SecureLogX INIT] ⚠️  GPU requested but CUDA not available - using CPU");
                System.out.println("[SecureLogX INIT] Available providers: " + availableProviders);
                System.out.println("[SecureLogX INIT] Looking for: " + OrtProvider.CUDA);
            }
            int threads = config.isCpuMultithreadingEnabled() ? Runtime.getRuntime().availableProcessors() : 1;
            System.out.println("[SecureLogX INIT] CPU Inference Mode Enabled");
            System.out.println("[SecureLogX INIT] CPU Threads Used: " + threads);
            opts.setIntraOpNumThreads(threads);
        }

        this.session = env.createSession(modelPath.replace("\\", "/"), opts);

        // Print session provider information
        try {
            System.out.println("[SecureLogX INIT] Session created successfully");
            System.out.println("[SecureLogX INIT] Model loaded from: " + modelPath);
        } catch (Exception e) {
            System.err.println("[SecureLogX INIT] Error getting session info: " + e.getMessage());
        }

        // GPU warmup - run a dummy inference to initialize CUDA context
        if (gpuRequested && cudaAvailable) {
            warmupGPU();
        }
    }


    private void addCudaToLibraryPath() {
        try {
            // Add CUDA paths to system library path
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

            // Force reload of library path
            java.lang.reflect.Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            fieldSysPath.setAccessible(true);
            fieldSysPath.set(null, null);

        } catch (Exception e) {
            System.err.println("[SecureLogX INIT] Failed to update library path: " + e.getMessage());
        }
    }


    private void warmupGPU() {
        try {
            System.out.println("[SecureLogX INIT] Starting GPU warmup...");
            long startTime = System.currentTimeMillis();

            // Create dummy tensors for warmup
            long[][] dummyInput = new long[1][10];
            long[][] dummyMask = new long[1][10];
            long[][] dummyType = new long[1][10];

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, dummyInput);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(env, dummyMask);
                 OnnxTensor typeTensor = OnnxTensor.createTensor(env, dummyType)) {

                Map<String, OnnxTensor> warmupInputs = Map.of(
                        "input_ids", inputTensor,
                        "attention_mask", maskTensor,
                        "token_type_ids", typeTensor
                );

                try (OrtSession.Result result = session.run(warmupInputs)) {
                    // Consume result to ensure GPU initialization
                    result.get(0).getValue();
                }
            }

            long warmupTime = System.currentTimeMillis() - startTime;
            System.out.println("[SecureLogX INIT] ✅ GPU warmup completed in " + warmupTime + "ms");
        } catch (Exception e) {
            System.err.println("[SecureLogX INIT] ❌ GPU warmup failed: " + e.getMessage());
            e.printStackTrace();
        }
    }



    public List<String> runBatch(TokenizerEngine tokenizer, List<LogEvent> batch) {
        long batchStartTime = System.currentTimeMillis();
        System.out.println("[DEBUG] Running ONNX batch inference for batch size: " + batch.size());
        final int MAX_SEQ_LEN = 512;
        List<String> output = new ArrayList<>();

        try {
            // 1) Tokenize each message
            long tokenizeStart = System.currentTimeMillis();
            List<TokenizedInput> encoded = batch.stream()
                    .map(e -> tokenizer.tokenize(e.getMessage()))
                    .collect(Collectors.toList());
            long tokenizeTime = System.currentTimeMillis() - tokenizeStart;
         //   System.out.println("[DEBUG] Tokenization took: " + tokenizeTime + "ms");

            // 2) Determine seqLen ≤ MAX_SEQ_LEN
            int rawMax = encoded.stream()
                    .mapToInt(t -> t.getInputIds().length)
                    .max().orElse(0);
            int seqLen = Math.min(rawMax, MAX_SEQ_LEN);
            int batchSize = encoded.size();

            // 3) Allocate batch tensors
            long prepareStart = System.currentTimeMillis();
            long[][] inputIds      = new long[batchSize][seqLen];
            long[][] attentionMask = new long[batchSize][seqLen];
            long[][] tokenTypeIds  = new long[batchSize][seqLen];

            // 4) Copy with truncation
            for (int i = 0; i < batchSize; i++) {
                int[] ids  = encoded.get(i).getInputIds();
                int[] mask = encoded.get(i).getAttentionMask();

                for (int j = 0; j < Math.min(ids.length, seqLen); j++) {
                    inputIds[i][j]      = ids[j];
                    attentionMask[i][j] = mask[j];
                    tokenTypeIds[i][j]  = 0;
                }
            }
            long prepareTime = System.currentTimeMillis() - prepareStart;
        //    System.out.println("[DEBUG] Data preparation took: " + prepareTime + "ms");

            // 5) Build ONNX inputs and run inference
            long inferenceStart = System.currentTimeMillis();
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputIds);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(env, attentionMask);
                 OnnxTensor typeTensor = OnnxTensor.createTensor(env, tokenTypeIds)) {

                Map<String, OnnxTensor> inputs = Map.of(
                        "input_ids", inputTensor,
                        "attention_mask", maskTensor,
                        "token_type_ids", typeTensor
                );

                // 6) Run the session
                long sessionStart = System.currentTimeMillis();
                try (OrtSession.Result result = session.run(inputs)) {
                    long sessionTime = System.currentTimeMillis() - sessionStart;
                    System.out.println("[DEBUG] ONNX session.run() took: " + sessionTime + "ms");

                    float[][][] logits = (float[][][]) result.get(0).getValue();

                    // 7) Mask and format each message
                    long maskingStart = System.currentTimeMillis();
                    for (int i = 0; i < batchSize; i++) {
                        // Pull the original offsets list
                        List<int[]> rawOffsets = encoded.get(i).getOffsets();
                        // Truncate it locally to seqLen
                        List<int[]> truncatedOffsets = rawOffsets.size() > seqLen
                                ? rawOffsets.subList(0, seqLen)
                                : rawOffsets;

                        // Call your mask method
                        String masked = maskingEngine.mask(
                                batch.get(i).getMessage(),
                                encoded.get(i).getInputIds(),
                                new float[][][]{logits[i]},
                                truncatedOffsets,
                                batch.get(i).shouldShowLastFour()
                        );

                        String timestamp = java.time.LocalDateTime.now().toString();
                        String formatted = String.format(
                                "timestamp=%s level=%s traceId=%s seq=%d message=\"%s\"",
                                timestamp,
                                batch.get(i).getLevel().name(),
                                batch.get(i).getTraceId(),
                                batch.get(i).getSequenceNumber(),
                                masked
                        );
                        output.add(formatted);
                    }
                    long maskingTime = System.currentTimeMillis() - maskingStart;
                    System.out.println("[DEBUG] Post-processing/masking took: " + maskingTime + "ms");
                }
            }
            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            System.out.println("[DEBUG] Total inference + post-processing took: " + inferenceTime + "ms");

        } catch (Exception e) {
            System.err.println("[ERROR] Batch inference failed for batch size: " + batch.size());
            e.printStackTrace();
        }

        long totalBatchTime = System.currentTimeMillis() - batchStartTime;
        System.out.println("[DEBUG] ⏱️  TOTAL BATCH TIME: " + totalBatchTime + "ms for " + batch.size() + " items");
        System.out.println("[DEBUG] ⏱️  Average per item: " + (totalBatchTime / Math.max(1, batch.size())) + "ms");

        return output;
    }





    private void checkCudaEnvironment() {
        System.out.println("[SecureLogX INIT] === CUDA Environment Check ===");

        // Check CUDA_PATH environment variable
        String cudaPath = System.getenv("CUDA_PATH");
        System.out.println("[SecureLogX INIT] CUDA_PATH: " + (cudaPath != null ? cudaPath : "Not set"));

        // Check PATH for CUDA binaries
        String path = System.getenv("PATH");
        boolean hasCudaInPath = path != null && (path.contains("CUDA") || path.contains("cuda"));
        System.out.println("[SecureLogX INIT] CUDA in PATH: " + hasCudaInPath);

        // Check library path
        String libraryPath = System.getProperty("java.library.path");
        boolean hasCudaLibs = libraryPath != null && (libraryPath.contains("CUDA") || libraryPath.contains("cuda"));
        System.out.println("[SecureLogX INIT] CUDA in library path: " + hasCudaLibs);

        // Check what ONNX Runtime JARs are loaded
        try {
            String classPath = System.getProperty("java.class.path");
            boolean hasGpuJar = classPath.contains("onnxruntime_gpu") || classPath.contains("onnxruntime-gpu");
            boolean hasCpuJar = classPath.contains("onnxruntime") && !hasGpuJar;
            System.out.println("[SecureLogX INIT] ONNX Runtime GPU JAR loaded: " + hasGpuJar);
            System.out.println("[SecureLogX INIT] ONNX Runtime CPU JAR loaded: " + hasCpuJar);

            if (!hasGpuJar) {
                System.out.println("[SecureLogX INIT] ❌ onnxruntime_gpu dependency not found in classpath");
            }

            // Print actual JAR files loaded
            String[] cpEntries = classPath.split(System.getProperty("path.separator"));
            for (String entry : cpEntries) {
                if (entry.contains("onnxruntime")) {
                    System.out.println("[SecureLogX INIT] ONNX Runtime JAR: " + entry);
                }
            }

        } catch (Exception e) {
            System.out.println("[SecureLogX INIT] Could not check classpath: " + e.getMessage());
        }

        // Enhanced cuDNN checking
        System.out.println("[SecureLogX INIT] === Checking Native Library Loading ===");
        try {
            System.out.println("[SecureLogX INIT] Attempting to manually load CUDA provider...");

            // Check multiple cuDNN installation locations
            String[] cudnnSearchPaths = {
                    // Standard cuDNN standalone installation
                    "C:\\Program Files\\NVIDIA\\CUDNN\\v8.9\\bin",
                    "C:\\Program Files\\NVIDIA\\CUDNN\\v9.0\\bin",
                    "C:\\Program Files\\NVIDIA\\CUDNN\\v9.1\\bin",
                    "C:\\Program Files\\NVIDIA\\CUDNN\\v8.8\\bin",
                    "C:\\Program Files\\NVIDIA\\CUDNN\\v9.2\\bin",
                    // cuDNN inside CUDA toolkit
                    "C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA\\v12.4\\bin",
                    "C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA\\v12.3\\bin",
                    "C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA\\v12.2\\bin",
                    // Alternative paths
                    "C:\\Program Files\\NVIDIA Corporation\\NVSMI",
                    "C:\\Windows\\System32"
            };

            boolean cudnnFound = false;
            for (String cudnnPath : cudnnSearchPaths) {
                System.out.println("[SecureLogX INIT] Checking path: " + cudnnPath);
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(cudnnPath))) {
                    // Check for specific cuDNN DLLs
                    String[] cudnnDlls = {
                            "cudnn64_8.dll", "cudnn64_9.dll",
                            "cudnn_cnn_infer64_8.dll", "cudnn_cnn_infer64_9.dll",
                            "cudnn_ops_infer64_8.dll", "cudnn_ops_infer64_9.dll",
                            "cudnn_adv_infer64_8.dll", "cudnn_adv_infer64_9.dll"
                    };

                    for (String dll : cudnnDlls) {
                        java.nio.file.Path dllPath = java.nio.file.Paths.get(cudnnPath, dll);
                        if (java.nio.file.Files.exists(dllPath)) {
                            System.out.println("[SecureLogX INIT] ✅ Found cuDNN DLL: " + dllPath);
                            cudnnFound = true;
                        }
                    }
                }
            }

            // Also check PATH environment for cuDNN DLLs
            if (path != null) {
                String[] pathEntries = path.split(";");
                for (String pathEntry : pathEntries) {
                    if (pathEntry.toLowerCase().contains("cudnn") || pathEntry.toLowerCase().contains("cuda")) {
                        System.out.println("[SecureLogX INIT] CUDA/cuDNN in PATH: " + pathEntry);
                        // Check if this path contains cuDNN DLLs
                        try {
                            java.nio.file.Path pathDir = java.nio.file.Paths.get(pathEntry);
                            if (java.nio.file.Files.exists(pathDir) && java.nio.file.Files.isDirectory(pathDir)) {
                                try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                                             java.nio.file.Files.newDirectoryStream(pathDir, "cudnn*.dll")) {
                                    for (java.nio.file.Path entry : stream) {
                                        System.out.println("[SecureLogX INIT] ✅ Found cuDNN in PATH: " + entry);
                                        cudnnFound = true;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Skip invalid paths
                        }
                    }
                }
            }

            if (!cudnnFound) {
                System.out.println("[SecureLogX INIT] ❌ cuDNN not found in any standard location");
                System.out.println("[SecureLogX INIT] Make sure to:");
                System.out.println("[SecureLogX INIT] 1. Download cuDNN from: https://developer.nvidia.com/cudnn");
                System.out.println("[SecureLogX INIT] 2. Extract to CUDA installation: C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA\\v12.4\\");
                System.out.println("[SecureLogX INIT] 3. Add CUDA bin to PATH: C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA\\v12.4\\bin");
                System.out.println("[SecureLogX INIT] 4. Restart your IDE and command prompt");
            }

        } catch (Exception e) {
            System.out.println("[SecureLogX INIT] Error during native library check: " + e.getMessage());
        }


        // Try to detect GPU using nvidia-smi (if available)
        try {
            ProcessBuilder pb = new ProcessBuilder("nvidia-smi", "--query-gpu=name", "--format=csv,noheader");
            Process process = pb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String gpuName = reader.readLine();
            if (gpuName != null && !gpuName.trim().isEmpty()) {
                System.out.println("[SecureLogX INIT] Detected GPU: " + gpuName.trim());
            } else {
                System.out.println("[SecureLogX INIT] No GPU detected via nvidia-smi");
            }
            process.waitFor();

            // Fix: Also check CUDA version - use correct process for reading
            ProcessBuilder cudaVersion = new ProcessBuilder("nvcc", "--version");
            Process cudaProcess = cudaVersion.start();
            java.io.BufferedReader cudaReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(cudaProcess.getInputStream())); // Fix: use cudaProcess instead of process
            String line;
            while ((line = cudaReader.readLine()) != null) {
                if (line.contains("release")) {
                    System.out.println("[SecureLogX INIT] CUDA Version: " + line.trim());
                    break;
                }
            }
            cudaProcess.waitFor();
        } catch (Exception e) {
            System.out.println("[SecureLogX INIT] CUDA tools check failed: " + e.getMessage());
        }
        System.out.println("[SecureLogX INIT] === End CUDA Environment Check ===");
    }



    public void shutdown() {
        this.running = false;
        try {
            TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }
}
