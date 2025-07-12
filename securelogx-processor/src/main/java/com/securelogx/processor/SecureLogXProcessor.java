package com.securelogx.processor;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;

import com.sun.source.tree.*;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class SecureLogXProcessor extends AbstractProcessor {
    private Trees trees;
    private boolean nerEnabled;
    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    private static final float CONFIDENCE_THRESHOLD = 0.85f;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.trees = Trees.instance(
                jbUnwrap(ProcessingEnvironment.class, processingEnv));

        // Read processor option - enable or disable NER checks
        String prop = processingEnv.getOptions().get("enableNer");
        nerEnabled = prop == null || Boolean.parseBoolean(prop);

        if (false) {
            try {
                // Initialize ONNX Runtime
                this.env = OrtEnvironment.getEnvironment();
                File modelFile = new File(
                        getClass().getResource("/models/ner_model.onnx").toURI());
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                // opts.addCUDA(0); // Uncomment if GPU is available
                this.session = env.createSession(
                        modelFile.getAbsolutePath(), opts);

                // Initialize Hugging Face tokenizer
                File tokFile = new File(
                        getClass().getResource("/tokenizer/tokenizer.json").toURI());
                this.tokenizer = HuggingFaceTokenizer
                        .newInstance(tokFile.getAbsolutePath());
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to initialize ONNX or tokenizer", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T jbUnwrap(Class<? extends T> iface, T wrapper) {
        T unwrapped = null;
        try {
            var apiWrappers = wrapper.getClass()
                    .getClassLoader()
                    .loadClass("org.jetbrains.jps.javac.APIWrappers");
            var unwrap = apiWrappers.getDeclaredMethod(
                    "unwrap", Class.class, Object.class);
            unwrapped = iface.cast(unwrap.invoke(null, iface, wrapper));
        } catch (Throwable ignored) { }
        return unwrapped != null ? unwrapped : wrapper;
    }

    @Override
    public boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv
    ) {
        for (var root : roundEnv.getRootElements()) {
            new Scanner().scan(trees.getPath(root), null);
        }
        return false;
    }

    private class Scanner extends TreePathScanner<Void, Void> {
        @Override
        public Void visitMethodInvocation(
                MethodInvocationTree node,
                Void unused
        ) {
            String selectExpr = node.getMethodSelect().toString();

            // 1) Skip any secure() calls
            if (selectExpr.endsWith(".secure") ||
                    selectExpr.equals("secure")) {
                return super.visitMethodInvocation(node, unused);
            }

            // 2) Regex-based SSN check (always runs)
            if (!node.getArguments().isEmpty() &&
                    node.getArguments().get(0).getKind() ==
                            Tree.Kind.STRING_LITERAL) {

                LiteralTree lit = (LiteralTree) node.getArguments().get(0);
                Object val = lit.getValue();
                if (val instanceof String text &&
                        SSN_PATTERN.matcher(text).find()) {
                    error(node,
                            "SSN literal in '" + selectExpr +
                                    "'; only secure() may contain SSN literals.");
                }
            }

            // 3) Optional NER-based check
            if (nerEnabled &&
                    !node.getArguments().isEmpty() &&
                    node.getArguments().get(0).getKind() ==
                            Tree.Kind.STRING_LITERAL) {

                LiteralTree lit = (LiteralTree) node.getArguments().get(0);
                Object val = lit.getValue();
                if (val instanceof String text) {
                    // tokenize
                    Encoding enc = tokenizer.encode(text);
                    long[] ids = Arrays.stream(enc.getIds()).toArray();

                    // create tensor and run inference
                    try (
                            OnnxTensor tensor = OnnxTensor
                                    .createTensor(env, new long[][]{ids});
                            Result result = session.run(
                                    Collections.singletonMap("input_ids", tensor))
                    ) {
                        float[][][] logits =
                                (float[][][]) result.get(0).getValue();
                        boolean found = false;
                        outer:
                        for (float[] scores : logits[0]) {
                            for (float score : scores) {
                                if (score > CONFIDENCE_THRESHOLD) {
                                    found = true;
                                    break outer;
                                }
                            }
                        }
                        if (found) {
                            error(node,
                                    "Sensitive literal detected in '" +
                                            selectExpr +
                                            "'; only secure() may contain unmasked PII/NPI.");
                        }
                    } catch (OrtException ex) {
                        error(node,
                                "ONNX inference error: " + ex.getMessage());
                    }
                }
            }

            return super.visitMethodInvocation(node, unused);
        }

        private void error(
                MethodInvocationTree node,
                String msg
        ) {
            var cu = getCurrentPath().getCompilationUnit();
            trees.printMessage(Kind.ERROR, msg, node, cu);
        }
    }
}
