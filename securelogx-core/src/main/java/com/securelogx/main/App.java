package com.securelogx.main;
import com.securelogx.engine.SecureLogX;
import com.securelogx.slf4j.SecureSlf4jLogger;
import com.securelogx.api.SecureLogger;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

public class App {
    public static void main(String[] args) throws IOException, InterruptedException {
        SecureSlf4jLogger logger = new SecureSlf4jLogger("App");
        System.out.println("Current mode: " + System.getProperty("securelogx.mode", "CPU_SINGLE"));

        int totalLogs = 10000;
        long start = System.nanoTime();
        int j = 0;
        for (int i = 0; i < totalLogs; i++) {
            String traceId = UUID.randomUUID().toString();
            MDC.put("traceId", traceId);
            MDC.put("userId", "user" + i);

            // ⚙️ Simulate computational overhead (SHA-256 hash of payload)
            String payload = "{" +
                    "\"email\":\"john.doe" + i + "@test.com\"," +
                    "\"ssn\":\"123-45-6789\"," +
                    "\"address\":\"123 Main St, NY\"}";
            // Standard log levels
         //   logger.info("User logged in.");
          //  logger.debug("Debugging authentication flow.");
          //  logger.warn("Disk space running low.");
            //   logger.error("System failure!");
            //  logger.info("User SSN: 123-45-6789");
            // Secure logs with different types of sensitive data
            logger.secure("User SSN: 123-45-6789", true);
            logger.secure("User email: john.doe@example.com", false);
            logger.secure("Credit Card: 4111 1111 1111 1111", true);
            logger.secure("Patient NPI: 1234567890", false);
            logger.secure("Customer address: 123 Main St, NY", false);
            logger.secure("Phone: (123) 456-7890", false);
            j++;

            // Secure log with no sensitive content (should be unchanged if NER is correct)
          //  logger.secure("User clicked the submit button.", false);
        }
        // 2) Read your full XML payload from file or stream

     /*   String xmlPayload = null;
        try (InputStream in =
                     Thread.currentThread()
                             .getContextClassLoader()
                             .getResourceAsStream("customer_data.xml")) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: customer_data.xml");
            }

            xmlPayload = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            logger.secure(xmlPayload, true);
        }

        InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("customer_data.json");
        String jsonPayload = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        logger.secure(jsonPayload, true);
*/
        // Wait for all queues to be processed before shutdown
        // ==== GRACEFUL FLUSH BEFORE SHUTDOWN ====
        SecureLogX engine = SecureLogger.getEngine();   // same as in your test
        if (engine != null) {
            // 1) Wait until internal queues are drained
            while (!engine.isQueueEmpty()) {
                Thread.sleep(100);
            }

            // 2) Give a little extra time for final writes under heavy load
            Thread.sleep(1000);
        }

        // 3) Stop any executors / consumers if you have this in SecureLogger
        SecureLogger.shutdownExecutor();  // must internally do shutdown() + awaitTermination()

        // 4) Now stop batch/writer threads and close files
        SecureLogger.shutdownAppender();

        long end = System.nanoTime();
        double seconds = (end - start) / 1_000_000_000.0;

        System.out.println("----- Performance Summary -----");
        System.out.println("Total log events: " + totalLogs + " (6 per log)" + j);
        System.out.println("Total time taken: " + seconds + " seconds");
        System.out.println("Logs per second: " + (j / seconds));

        // optional: you don't really need System.exit(0) here
    }
}
