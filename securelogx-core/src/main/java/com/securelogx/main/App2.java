// ✅ SecureLogX Stress Test Harness for Journal Comparison
package com.securelogx.main;

import com.securelogx.api.SecureLogger;
import com.securelogx.model.LogLevel;
import com.securelogx.slf4j.SecureSlf4jLogger;

public class App2 {
    public static void main(String[] args) throws InterruptedException {
        int totalLogs = 200;
        int threads = 1; // 🔁 Change to 4 or 8 for multi-core testing

        SecureSlf4jLogger logger = new SecureSlf4jLogger("App");

        long start = System.nanoTime();

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            workers[t] = new Thread(() -> {
                int logsPerThread = totalLogs / threads;
                for (int i = 0; i < logsPerThread; i++) {
              //      String jsonLog = String.format("User data: {\"email\":\"john.doe%d@test.com\", \"ssn\":\"123-45-6789\", \"meta\":{\"ip\":\"192.168.1.%d\"}}", i, i % 255);
              //      logger.info(jsonLog, false);
                    logger.info("Disk warning at operation index: " + i);
               //     logger.info("SSN=123-45-6789");

                    logger.error( "StackTrace at index " + i + ":\n\tat com.fake.Main.method(Main.java:42)\n\tat java.base/java.lang.Thread.run(Thread.java:829)");
                }
            }, "LogProducer-" + threadIndex);
            workers[t].start();
        }

        for (Thread t : workers) {
            t.join();
        }

        // 🔻 Wait for flush
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
}
