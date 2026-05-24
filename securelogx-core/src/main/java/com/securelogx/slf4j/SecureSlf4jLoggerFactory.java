package com.securelogx.slf4j;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SecureSlf4jLoggerFactory implements ILoggerFactory {
    private final ConcurrentMap<String, Logger> loggerMap = new ConcurrentHashMap<>();

   // @Override
   // public Logger getLogger(String name) {
   //     return loggerMap.computeIfAbsent(name, SecureSlf4jLogger::new);
   // }

    @Override
    public Logger getLogger(String name) {
        // Only intercept logs from our own packages
        if (!name.startsWith("com.securelogx")) {
            // Return a no-op logger for all external libraries
            return NOPLogger.NOP_LOGGER;
        }
        // For our packages, return or create SecureSlf4jLogger
        return loggerMap.computeIfAbsent(name, SecureSlf4jLogger::new);
    }
}
