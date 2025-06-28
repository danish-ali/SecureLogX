package com.securelogx.processor;

import com.sun.source.tree.*;
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
            // 1. Figure out exactly what form of call this is
            String selectExpr = node.getMethodSelect().toString();
            System.err.printf("[Processor] selectExpr = '%s'%n", selectExpr);

            // 2. Skip any secure(...) calls
            if (selectExpr.endsWith(".secure") || selectExpr.equals("secure")) {
                return super.visitMethodInvocation(node, p);
            }

            // 3. Only consider STRING_LITERALs, not null, numeric, etc.
            if (!node.getArguments().isEmpty()) {
                ExpressionTree first = node.getArguments().get(0);
                if (first.getKind() == Tree.Kind.STRING_LITERAL) {
                    LiteralTree litTree = (LiteralTree) first;
                    Object raw = litTree.getValue();
                    if (raw instanceof String) {
                        String lit = (String) raw;
                        // 4. Now safely check for SSN pattern
                        if (SSN.matcher(lit).find()) {
                            error(node,
                                    "SSN literal in '" + selectExpr +
                                            "'; only secure() may contain SSN literals.");
                        }
                    }
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
