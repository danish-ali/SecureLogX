package com.securelogx.ner.impl;

import ai.onnxruntime.*;
import com.securelogx.model.LogEvent;
import com.securelogx.ner.TokenizerEngine;
import com.securelogx.ner.TokenizedInput;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.Arrays;


public class ONNXDynamicInferenceEngine {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final LabelAwareMaskingEngine maskingEngine = new LabelAwareMaskingEngine();
    private volatile boolean running = true;

    public ONNXDynamicInferenceEngine(String modelPath, com.securelogx.config.SecureLogXConfig config) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

        if (config.isGpuInferenceEnabled() && OrtEnvironment.getAvailableProviders().contains("CUDAExecutionProvider")) {
            System.out.println("[SecureLogX INIT] GPU Inference Mode Enabled (CUDA)");
            opts.addCUDA();
        } else {
            int threads = config.isCpuMultithreadingEnabled() ? Runtime.getRuntime().availableProcessors() : 1;
            System.out.println("[SecureLogX INIT] CPU Inference Mode Enabled");
            System.out.println("[SecureLogX INIT] CPU Threads Used: " + threads);
            opts.setIntraOpNumThreads(threads);
        }

        this.session = env.createSession(modelPath.replace("\\", "/"), opts);
    }

    public List<String> runBatch(TokenizerEngine tokenizer, List<LogEvent> batch) {
        System.out.println("[DEBUG] Running ONNX batch inference for batch size: " + batch.size());
        final int MAX_SEQ_LEN = 512;
        List<String> output = new ArrayList<>();

        try {
            // 1) Tokenize each message
            List<TokenizedInput> encoded = batch.stream()
                    .map(e -> tokenizer.tokenize(e.getMessage()))
                    .collect(Collectors.toList());

            // 2) Determine seqLen ≤ MAX_SEQ_LEN
            int rawMax = encoded.stream()
                    .mapToInt(t -> t.getInputIds().length)
                    .max().orElse(0);
            int seqLen = Math.min(rawMax, MAX_SEQ_LEN);
            int batchSize = encoded.size();

            // 3) Allocate batch tensors
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

            // 5) Build ONNX inputs
            Map<String, OnnxTensor> inputs = Map.of(
                    "input_ids",      OnnxTensor.createTensor(env, inputIds),
                    "attention_mask", OnnxTensor.createTensor(env, attentionMask),
                    "token_type_ids", OnnxTensor.createTensor(env, tokenTypeIds)
            );

            // 6) Run the session
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] logits = (float[][][]) result.get(0).getValue();

                // 7) Mask and format each message
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
            }

            System.out.println("[DEBUG] Masked outputs generated: " + output.size());
            if (!output.isEmpty()) {
                System.out.println("[DEBUG] First masked output: " + output.get(0));
            }

        } catch (Exception e) {
            System.err.println("[ERROR] Batch inference failed for batch size: " + batch.size());
            e.printStackTrace();
        }

        return output;
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
