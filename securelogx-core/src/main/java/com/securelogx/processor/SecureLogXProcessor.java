package com.securelogx.processor;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import java.util.Set;
import java.util.regex.Pattern;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class SecureLogXProcessor extends AbstractProcessor {
    private Trees trees;
    private static final Pattern SSN = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");

    public SecureLogXProcessor() {
        super();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.trees = Trees.instance(jbUnwrap(ProcessingEnvironment.class, env));
        System.err.println("[Processor] init()");
    }


    @SuppressWarnings("unchecked")
    private static <T> T jbUnwrap(Class<? extends T> iface, T wrapper) {
        T unwrapped = null;
        try {
            final Class<?> apiWrappers = wrapper.getClass().getClassLoader().loadClass("org.jetbrains.jps.javac.APIWrappers");
            final java.lang.reflect.Method unwrapMethod = apiWrappers.getDeclaredMethod("unwrap", Class.class, Object.class);
            unwrapped = iface.cast(unwrapMethod.invoke(null, iface, wrapper));
        } catch (Throwable ignored) {
            // fallback to original
        }
        return unwrapped != null ? unwrapped : wrapper;
    }


    @Override
    public boolean process(Set<? extends TypeElement> annos, RoundEnvironment roundEnv) {
        for (var root : roundEnv.getRootElements()) {
            new Scanner().scan(trees.getPath(root), null);
        }
        return false;
    }

    private class Scanner extends TreePathScanner<Void, Void> {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
            // Print every invocation that has a string‐literal first arg
            ExpressionTree first = node.getArguments().isEmpty() ? null : node.getArguments().get(0);
            boolean hasSSN = first instanceof LiteralTree
                    && SSN.matcher(((LiteralTree) first).getValue().toString()).find();
            if (hasSSN) {
                // Extract method name
                String methodName = "<unknown>";
                if (node.getMethodSelect() instanceof MemberSelectTree m) {
                    methodName = m.getIdentifier().toString();
                }
                System.err.printf("[Processor] Found SSN literal in call to '%s'%n", methodName);

                // Only allow if methodName == "secure"
                if (!"secure".equals(methodName)) {
                    error(node, "SSN literal in '" + methodName +
                            "'; only logger.secure(...) may contain SSN literals.");
                }
            }
            return super.visitMethodInvocation(node, p);
        }

        private void error(MethodInvocationTree node, String msg) {
            CompilationUnitTree cu = getCurrentPath().getCompilationUnit();
            trees.printMessage(Kind.ERROR, msg, node, cu);
        }
    }
}
