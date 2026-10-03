# SecureLogX Log4j2 integration

SecureLogX uses Apache Log4j2's existing `RewriteAppender`. The SecureLogX
artifact supplies a `SecureLogXRewritePolicy`; it does not replace Log4j2's
destination appenders.

## Required production topology

For a pre-queue sanitization guarantee, the root logger must reference only the
SecureLogX rewrite. Any asynchronous destination delivery must occur after the
rewrite:

```text
synchronous Logger / LoggerConfig
        |
        v
SecureLogX RewriteAppender
        |
        v
AsyncAppender (optional)
        |
        +--> RollingFile
        +--> Console
        +--> KafkaAppender
```

Do not configure a sibling File, Console, Kafka, Socket, or other destination
directly on root or a named LoggerConfig. That creates a raw-data bypass path.

Do not use `AsyncRoot`, `AsyncLogger`, or the
`AsyncLoggerContextSelector` when
`requirePreQueueSanitization=true`. Those modes can enqueue the original
LogEvent before a RewriteAppender can sanitize it.

## Canonical XML example

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN"
               packages="com.securelogx.log4j2">
  <Appenders>
    <RollingFile name="ProtectedFile"
                 fileName="logs/application.log"
                 filePattern="logs/application-%d{yyyy-MM-dd}-%i.log.gz">
      <PatternLayout pattern="%d %-5level [%t] %X %msg%n%throwable"/>
      <Policies>
        <SizeBasedTriggeringPolicy size="100 MB"/>
      </Policies>
    </RollingFile>

    <Console name="ProtectedConsole" target="SYSTEM_OUT">
      <PatternLayout pattern="%d %-5level [%t] %X %msg%n%throwable"/>
    </Console>

    <Async name="ProtectedAsync" bufferSize="8192" blocking="true">
      <AppenderRef ref="ProtectedFile"/>
      <AppenderRef ref="ProtectedConsole"/>
    </Async>

    <Rewrite name="SecureLogXRewrite">
      <SecureLogXRewritePolicy
          environment="prod"
          expectedAppenderName="SecureLogXRewrite"
          strictConfiguration="true"
          requirePreQueueSanitization="true"/>
      <AppenderRef ref="ProtectedAsync"/>
    </Rewrite>
  </Appenders>

  <Loggers>
    <Root level="info">
      <AppenderRef ref="SecureLogXRewrite"/>
    </Root>
  </Loggers>
</Configuration>
```

The referenced destination appenders must be declared before the rewrite so
Log4j can stop the dependency chain cleanly.

## What is sanitized

The policy rebuilds an immutable LogEvent and sanitizes:

- formatted message text,
- message format text,
- message parameters,
- `MapMessage` values,
- ThreadContext/MDC values,
- ThreadContext stack values,
- throwable messages,
- throwable causes and suppressed throwable messages.

It preserves source severity, source timestamp/instant, logger identity,
thread metadata, marker, source location, end-of-batch state, and stack frames.

If any payload field cannot be safely processed, the entire event fails closed
to:

```text
[SECURELOGX_REDACTED_PROCESSING_FAILURE]
```

The failure path does not forward the original message, MDC values, context
stack, or throwable message.

## Configuration enforcement

With `strictConfiguration=true`, unsafe topology causes each event to fail
closed. The validator detects:

- missing expected RewriteAppender,
- root logger not routed through the rewrite,
- sibling/direct destination appenders that bypass the rewrite,
- `AsyncRoot` / `AsyncLogger`,
- async logger context selectors when pre-queue sanitization is required.

## Scope boundary

SecureLogX protects events that pass through this Log4j2 topology. It does not
intercept:

- direct `System.out` / `System.err`,
- direct `Throwable.printStackTrace()`,
- logging frameworks/backends not routed through this Log4j2 configuration,
- Kafka producers used directly by application code rather than a downstream
  Log4j2 appender.

Applications should still avoid intentionally logging secrets. SecureLogX is a
defense-in-depth redaction layer, not a substitute for secure application
logging practices.
