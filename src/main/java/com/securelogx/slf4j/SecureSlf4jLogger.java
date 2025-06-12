package com.securelogx.slf4j;

import com.securelogx.api.SecureLogger;
import com.securelogx.model.LogLevel;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

public class SecureSlf4jLogger implements Logger {
    private final String name;

    public SecureSlf4jLogger(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    public void secure(String msg, boolean showLastFour) {
        SecureLogger.log(LogLevel.SECURE, msg, showLastFour);
    }

    @Override public void info(String msg) { SecureLogger.log(LogLevel.INFO, msg); }
    @Override public void debug(String msg) {
        SecureLogger.log(LogLevel.DEBUG, msg); // ✅ Consistent with others
    }

    @Override public void error(String msg) { SecureLogger.log(LogLevel.ERROR, msg); }
    @Override public void warn(String msg) { SecureLogger.log(LogLevel.WARN, msg); }

    // Convenience overloads using SLF4J formatting
    @Override public void info(String format, Object arg) {
        info(MessageFormatter.format(format, arg).getMessage());
    }

    @Override public void error(String format, Object arg) {
        error(MessageFormatter.format(format, arg).getMessage());
    }

    @Override public void warn(String format, Object arg) {
        warn(MessageFormatter.format(format, arg).getMessage());
    }

    @Override public void debug(String format, Object arg) {
        debug(MessageFormatter.format(format, arg).getMessage());
    }

    // Other required but unused stubs — safe defaults
    @Override public boolean isDebugEnabled() { return true; }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public boolean isErrorEnabled() { return true; }
    @Override public boolean isTraceEnabled() { return false; }
    @Override public void trace(String msg) {}
    @Override public void trace(String format, Object arg) {}
    @Override public void trace(String format, Object arg1, Object arg2) {}
    @Override public void trace(String format, Object... arguments) {}
    @Override public void trace(String msg, Throwable t) {}
    @Override public void debug(String msg, Throwable t) {}
    @Override public void info(String msg, Throwable t) {}
    @Override public void warn(String msg, Throwable t) {}
    @Override public void error(String msg, Throwable t) {}
    @Override public void debug(String format, Object arg1, Object arg2) {}
    @Override public void debug(String format, Object... arguments) {}
    @Override public void info(String format, Object arg1, Object arg2) {}
    @Override public void info(String format, Object... arguments) {}
    @Override public void warn(String format, Object arg1, Object arg2) {}
    @Override public void warn(String format, Object... arguments) {}
    @Override public void error(String format, Object arg1, Object arg2) {}
    @Override public void error(String format, Object... arguments) {}

    // Marker overloads - no-op
    @Override public boolean isTraceEnabled(Marker marker) { return false; }
    @Override public boolean isDebugEnabled(Marker marker) { return false; }
    @Override public boolean isInfoEnabled(Marker marker) { return false; }
    @Override public boolean isWarnEnabled(Marker marker) { return false; }
    @Override public boolean isErrorEnabled(Marker marker) { return false; }
    @Override public void trace(Marker marker, String msg) {}
    @Override public void trace(Marker marker, String format, Object arg) {}
    @Override public void trace(Marker marker, String format, Object arg1, Object arg2) {}
    @Override public void trace(Marker marker, String format, Object... arguments) {}
    @Override public void trace(Marker marker, String msg, Throwable t) {}
    @Override public void debug(Marker marker, String msg) {}
    @Override public void debug(Marker marker, String format, Object arg) {}
    @Override public void debug(Marker marker, String format, Object arg1, Object arg2) {}
    @Override public void debug(Marker marker, String format, Object... arguments) {}
    @Override public void debug(Marker marker, String msg, Throwable t) {}
    @Override public void info(Marker marker, String msg) {}
    @Override public void info(Marker marker, String format, Object arg) {}
    @Override public void info(Marker marker, String format, Object arg1, Object arg2) {}
    @Override public void info(Marker marker, String format, Object... arguments) {}
    @Override public void info(Marker marker, String msg, Throwable t) {}
    @Override public void warn(Marker marker, String msg) {}
    @Override public void warn(Marker marker, String format, Object arg) {}
    @Override public void warn(Marker marker, String format, Object arg1, Object arg2) {}
    @Override public void warn(Marker marker, String format, Object... arguments) {}
    @Override public void warn(Marker marker, String msg, Throwable t) {}
    @Override public void error(Marker marker, String msg) {}
    @Override public void error(Marker marker, String format, Object arg) {}
    @Override public void error(Marker marker, String format, Object arg1, Object arg2) {}
    @Override public void error(Marker marker, String format, Object... arguments) {}
    @Override public void error(Marker marker, String msg, Throwable t) {}
}
