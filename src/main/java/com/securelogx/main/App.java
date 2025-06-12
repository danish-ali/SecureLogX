import com.securelogx.slf4j.SecureSlf4jLogger;
import com.securelogx.api.SecureLogger;

public class App {
    public static void main(String[] args) {
        SecureSlf4jLogger logger = new SecureSlf4jLogger("App");

        // Standard log levels
        logger.info("User logged in.");
        logger.debug("Debugging authentication flow.");
        logger.warn("Disk space running low.");
        logger.error("System failure!");

        // Secure logs with different types of sensitive data
        logger.secure("User SSN: 123-45-6789", true);
        logger.secure("User email: john.doe@example.com", false);
        logger.secure("Credit Card: 4111 1111 1111 1111", true);
        logger.secure("Patient NPI: 1234567890", false);
        logger.secure("Customer address: 123 Main St, NY", false);
        logger.secure("Phone: (123) 456-7890", false);

        // Secure log with no sensitive content (should be unchanged if NER is correct)
        logger.secure("User clicked the submit button.", false);

        SecureLogger.getEngine().shutdownExecutor();
        SecureLogger.getEngine().shutdownAppender();
        System.exit(0);
    }
}
