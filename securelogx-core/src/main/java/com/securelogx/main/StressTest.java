// ✅ SecureLogX Stress Test Harness with XML + CPU Load + MDC
package com.securelogx.main;

import com.securelogx.api.SecureLogger;
import com.securelogx.model.LogLevel;
import org.slf4j.MDC;

import java.security.MessageDigest;
import java.util.UUID;

public class StressTest {
    public static void main(String[] args) throws Exception {
        int totalLogs = 300_000;
        int threads = 1; // 🔁 Change to 4 or 8 for multi-core testing

        boolean usePerThreadFiles = true; // 🔁 Toggle this for benchmarking

     //   SecureLogger.getEngine().initRequest("REQ-STRESS-BENCHMARK", usePerThreadFiles);
        SecureLogger.getEngine().initRequest(UUID.randomUUID().toString());

        long start = System.nanoTime();

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            workers[t] = new Thread(() -> {
                int logsPerThread = totalLogs / threads;
                for (int i = 0; i < logsPerThread; i++) {
                    String traceId = UUID.randomUUID().toString();
                    MDC.put("traceId", traceId);
                    MDC.put("userId", "user" + i);

                    // ⚙️ Simulate computational overhead (SHA-256 hash of payload)
                    String payload = "{" +
                            "\"email\":\"john.doe" + i + "@test.com\"," +
                      //      "\"ssn\":\"123-45-6789\"," +
                            "\"address\":\"123 Main St, NY\"}";

                    String heavyPayload = hash(payload) + payload.replaceAll("[aeiou]", "*");

                 //   SecureLogger.log(LogLevel.INFO, heavyPayload);
                    SecureLogger.log(LogLevel.WARN, "<warning index='" + i + "'>Low Disk</warning>");
                    SecureLogger.log(LogLevel.ERROR, "<error><msg>Failure " + i + "</msg><trace>...</trace></error>");

                    MDC.clear();
                }
            }, "LogProducer-" + threadIndex);
            workers[t].start();
        }

        for (Thread t : workers) {
            t.join();
        }

        while (!SecureLogger.getEngine().isQueueEmpty()) {
            Thread.sleep(50);
        }
        SecureLogger.getEngine().shutdownExecutor();
        SecureLogger.getEngine().shutdownAppender();

        long end = System.nanoTime();
        double seconds = (end - start) / 1_000_000_000.0;

        System.out.println("----- Performance Summary -----");
        System.out.println("Total log events: " + (totalLogs * 3));
        System.out.println("Total time taken: " + seconds + " seconds");
        System.out.println("Logs per second: " + ((totalLogs * 3) / seconds));
    }

    // 🔐 CPU stress function: SHA-256 hash
    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "[hash-failed]";
        }
    }
}
