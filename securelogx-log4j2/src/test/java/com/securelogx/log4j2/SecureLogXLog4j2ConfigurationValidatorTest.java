package com.securelogx.log4j2;

import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureLogXLog4j2ConfigurationValidatorTest {

    @Test
    void rejectsConfigurationWhoseRootBypassesRewrite() {
        DefaultConfiguration configuration =
                new DefaultConfiguration();

        List<String> violations =
                SecureLogXLog4j2ConfigurationValidator.findViolations(
                        configuration,
                        "SecureLogXRewrite",
                        true
                );

        assertTrue(
                violations.stream().anyMatch(
                        item -> item.contains(
                                "Root logger does not reference"
                        )
                )
        );
    }
}
