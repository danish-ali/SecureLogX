package com.securelogx.main;
import com.securelogx.slf4j.SecureSlf4jLogger;
import com.securelogx.api.SecureLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class App {
    public static void main(String[] args) throws IOException {
        SecureSlf4jLogger logger = new SecureSlf4jLogger("App");

        // Standard log levels
        logger.info("User logged in.");
        logger.debug("Debugging authentication flow.");
        logger.warn("Disk space running low.");
     //   logger.error("System failure!");
      //  logger.info("User SSN: 123-45-6789");
        // Secure logs with different types of sensitive data
        logger.secure("User SSN: 123-45-6789", true);
        logger.secure("User email: john.doe@example.com", false);
        logger.secure("Credit Card: 4111 1111 1111 1111", true);
        logger.secure("Patient NPI: 1234567890", false);
        logger.secure("Customer address: 123 Main St, NY", false);
        logger.secure("Phone: (123) 456-7890", false);

        // Secure log with no sensitive content (should be unchanged if NER is correct)
        logger.secure("User clicked the submit button.", false);

        // 2) Read your full XML payload from file or stream
        String xmlPayload = null;
        try (InputStream in =
                     Thread.currentThread()
                             .getContextClassLoader()
                             .getResourceAsStream("customer_data.xml")) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: customer_data.xml");
            }

            xmlPayload = new String(in.readAllBytes(), StandardCharsets.UTF_8);
// 3) Mask everything in one go
            logger.secure(xmlPayload, true);
        }

        InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("customer_data.json");
        String jsonPayload = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        logger.secure(jsonPayload, true);
//to validate that inference threads stop cleanly before your application exits, good for testing only
      //  SecureLogger.getEngine().shutdownExecutor();
      //  SecureLogger.getEngine().shutdownAppender();
        // generic
       SecureLogger.shutdownExecutor();
       SecureLogger.shutdownAppender();

        System.exit(0);
    }
}
