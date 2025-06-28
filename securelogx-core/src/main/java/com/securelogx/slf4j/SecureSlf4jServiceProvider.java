package com.securelogx.slf4j;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.spi.SLF4JServiceProvider;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

public class SecureSlf4jServiceProvider implements SLF4JServiceProvider {

    private final SecureSlf4jLoggerFactory loggerFactory = new SecureSlf4jLoggerFactory();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return null;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return new NOPMDCAdapter(); // or implement MDC if needed
    }

    @Override public String getRequestedApiVersion() { return "2.0.99"; }
    @Override public void initialize() {}
}
