# JDK 25 Migration

SecureLogX is compiled and tested with OpenJDK 25. The root POM owns the
compiler release and the Maven Compiler Plugin and Surefire versions; the
active modules inherit those settings. The standalone annotation-processor
module uses the same Java release.

## Consumer compatibility

Java 25 class files require a Java 25-or-newer runtime. Consumers running
Java 21 cannot load this build and may receive `UnsupportedClassVersionError`.
This is a deliberate compatibility change for the JDK 25 migration. If Java
21 consumers must remain supported, publish a separate Java 21-compatible
artifact or maintain a Java 21 release line; changing the runtime JDK alone
does not preserve Java 21 bytecode compatibility.

## ONNX native libraries

ONNX Runtime loads native libraries during session creation. CUDA and cuDNN
dependencies must be available on the process native-library search path
before the JVM starts. SecureLogX does not mutate `java.library.path` at
runtime or use reflective JDK internals to refresh a class-loader cache.
