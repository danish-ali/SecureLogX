# SecureLogX Log4j2 integration

> **Current maturity:** research runtime + Log4j2 pilot. This integration is
> not yet presented as a universally adoptable 1.0 release.

The basic bounded timeout/backpressure, concurrency-saturation and
hot-redeploy/native lifecycle mechanisms have been validated for the
current pilot. A user-reported 48-event real application benchmark also
passed once at the 2-second deadline (p95 638.3143 ms, no fail-closed
events). Parallel end-to-end ONNX saturation/ordering, broader security
regression, repeatability and multi-hour soak remain production gates.

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
<Configuration status="WARN">
  <Appenders>
    <RollingFile name="ProtectedFile"
                 fileName="logs/application.log"
                 filePattern="logs/application-%d{yyyy-MM-dd}-%i.log.gz">
      <PatternLayout pattern="%d %-5level [%t] instanceId=%X{securelogx.instanceId} seq=%X{securelogx.seq} ingressId=%X{securelogx.ingressId} %X %msg%n%throwable"/>
      <Policies>
        <SizeBasedTriggeringPolicy size="100 MB"/>
      </Policies>
    </RollingFile>

    <Console name="ProtectedConsole" target="SYSTEM_OUT">
      <PatternLayout pattern="%d %-5level [%t] instanceId=%X{securelogx.instanceId} seq=%X{securelogx.seq} ingressId=%X{securelogx.ingressId} %X %msg%n%throwable"/>
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
Log4j can stop the dependency chain cleanly. The SecureLogX artifact includes
generated Log4j plugin metadata, so package scanning is not required.

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

## Startup fail-fast validation

Per-event validation remains a fail-closed safety net, but production
applications should also reject unsafe topology during bootstrap, after Log4j2
has loaded its complete configuration and before the application reports
readiness:

```java
SecureLogXLog4j2ConfigurationValidator.validateCurrentContextOrThrow(
        "SecureLogXRewrite",
        true
);
```

Do not run this from the RewritePolicy plugin factory; at that point Log4j may
still be constructing the configuration graph.

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

## Bounded masking runtime

The pilot runtime now uses one bounded masking worker and a bounded request
queue. A single absolute deadline covers queue wait, Java tokenization, and
ONNX inference.

On queue rejection or deadline expiry, SecureLogX emits only:

```text
[SECURELOGX_REDACTED_PROCESSING_FAILURE]
```

and records `OVERLOAD_REJECTED` or `DEADLINE_EXCEEDED` in SecureLogX event
metadata/metrics. Raw payload is never used as an overload fallback.

ONNX calls use request-specific `OrtSession.RunOptions`; deadline cancellation
signals `setTerminate(true)` on an active native run. Java tokenization is
cooperatively interruptible.

Current pilot defaults are a 64-request queue, 2000 ms end-to-end deadline, and
one active masking worker. These are not release SLAs and must be load-tested
before 1.0.

## Representative application-log benchmark and deadlines

The representative application-log benchmark uses real application `Logger`
calls through `RewriteAppender`, the production masking policy, ML-v1.3
ONNX, and a file appender. It contains 48 measured events by default,
including five long/windowed message cases (ordinals 5, 15, 25, 35, 45).

Run under the **2,000 ms pilot masking deadline** (the default):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1
```

If this fails with `DEADLINE_EXCEEDED`, the original event payload is
appropriately failed closed. The benchmark still fails because it requires
all application events to be successfully masked and emitted, not replaced
by fail-closed placeholders. On validation failure, the diagnostic
output now lists the 12 slowest synthetic event IDs, workload categories,
and caller durations without showing payloads.

To **characterize** the same workload with a longer bounded deadline,
without changing the production property file, run explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1 -MaskingDeadlineMillis 15000
```

The override applies only to the benchmark Maven execution and is restored
afterward. The output JSON reports `masking_deadline_millis`,
`caller_events_over_2000ms`, and
`long_windowed_caller_latency_millis`, in addition to existing safety and
throughput metrics.

**A characterization pass at 15,000 ms is not a pass of the 2,000 ms pilot
operational target.** Caller duration includes Log4j overhead in addition to
the masking request. Production sizing, deadline tuning, and long-windowed
ONNX optimization still require profiling and load tests. Do not simply
increase the production deadline to make the pilot test green.


## Opt-in adaptive organization masking (experimental)

This version implements **reviewed rules and ML-based proposals**, not live
self-modifying regex. An approved rule can bypass ML only when it matches
the *entire* structured field=value input. Mixed records and unknown text
still go through the existing conservative hybrid gate.

No Java switch/case on an organization name is used. **Both capabilities are
disabled by default** and configured per deployment:

| Setting | Default | Effect |
|---|---|---|
| `securelogx.adaptive.enabled` | `false` | Master switch for **approved deterministic organization rules** |
| `securelogx.organization.id` | `default` | Trusted deployment identity; explicit non-default identity required for enabled rule enforcement |
| `securelogx.organization.approvedRulesFile` | empty | Mandatory reviewed-policy file when adaptive rules are enabled |
| `securelogx.adaptive.mlRoutingTelemetryEnabled` | `false` | Separate opt-in for in-memory ML routing observations and rule *proposals*; can run with rule enforcement disabled |

**Option A — collect candidate patterns without enabling any rule:**

~~~properties
securelogx.adaptive.enabled=false
securelogx.organization.id=ORG42
securelogx.adaptive.mlRoutingTelemetryEnabled=true
~~~

**Option B — enable reviewed rules for the specified organization:**

~~~properties
securelogx.adaptive.enabled=true
securelogx.organization.id=ORG42
securelogx.organization.approvedRulesFile=examples/organization-rules.example.json
securelogx.adaptive.mlRoutingTelemetryEnabled=true
~~~

Configuration is read when the masker is constructed, so changes to these
settings require a controlled restart/recreation of the masking service.
A filename by itself never activates the optional rule engine. If the
master switch is `true` but the policy file is missing, invalid, or for
another organization, masker initialization fails closed.

The organization ID is trusted per-deployment configuration, **never read
from a log message**. Multi-organization deployments require isolated
masker/context instances. Untrusted per-request organization switching is
not supported by this version.

An approved, versioned JSON policy can contain:

~~~json
{
  "organizationId": "ORG42",
  "version": 1,
  "rules": [{
    "id": "customer-reference",
    "status": "APPROVED",
    "field": "customerReference",
    "prefix": "CUST-",
    "digits": 6,
    "entityType": "BUSINESS_ID"
  }]
}
~~~

Only exact field names, a fixed ASCII numeric length (4–20 digits), and
optional literal prefix (2–12 letters plus - or _) are permitted. Arbitrary
administrator regex is **not** supported. The implementation uses anchored,
bounded compiled patterns. For example, customerReference=CUST-834729
becomes customerReference=****-****** without invoking ML for that field.
A nonmatching value or additional text uses the original hybrid gate.

An **enabled** policy is restricted to 128 rules and 64 KiB. An invalid,
unapproved, missing, or wrong-organization enabled policy causes masker
initialization failure (fail-closed logging). Rules never self-promote or
silently alter the detection policy. With rule enforcement disabled, the
existing hybrid pipeline is unchanged even if a policy filename is present.

### Review and approval workflow

1. Optionally enable ML routing telemetry. On successful ML recognition of
   a complete structured value, SecureLogX aggregates the field name,
   **abstract shape**, ML entity type, and observation count. Raw identifier
   values and their literal prefixes are not retained.
2. A reviewer examines aggregated proposals, independently validates the
   actual field schema and tests positive and negative cases. Telemetry
   observations are **not** ground-truth labels or automatic approvals.
3. An authorized reviewer creates/updates a versioned, APPROVED rule file,
   protects file access, and restarts the masker to load the revision.
   The APPROVED field is a workflow convention, not a cryptographic signature.
4. Validate detection quality and performance on representative workloads
   before using the rules in production. Other unresolved fields still use ML.

For a running Log4j2 LoggerContext, inspect in-memory aggregates without
creating an additional ONNX session:

~~~java
LoggerContext context =
        (LoggerContext) LogManager.getContext(false);
MlRoutingTelemetry.Snapshot snapshot =
        SecureLogXAdaptiveDiagnostics.snapshot(context, "prod");
// snapshot.routingReasons(), snapshot.proposedCandidates()
~~~

The core SecureMasker exposes getMlRoutingTelemetrySnapshot() as well.

To collect a safe routing breakdown from the same 48-event benchmark, without
editing any production properties, run:

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1 -MaskingDeadlineMillis 15000 -CollectMlRuleProposals
~~~

The result JSON includes ml_routing_reason_counts_including_warmup,
ml_rule_candidates_including_warmup, and
approved_rule_matches_including_warmup. This run still uses the longer
diagnostic deadline and does not satisfy the 2-second performance target.
Snapshots never contain log values, but field names can reveal application
schema, so restrict access to operational diagnostics. Counters are per
masker instance, and repeated fields in the same event can be counted
separately. Nothing is persisted automatically.

### Targeted routing/performance improvements (October 12, 2026)

**Trusted field origin instrumentation.** The Log4j2 adapter labels captured
fields as MESSAGE, FORMAT, PARAMETER, MAP, MDC, STACK or THROWABLE.
The masking core records only a bounded, in-memory aggregate of ML routing
reason, source category, field name where the input is one structured
field=value, abstract value shape, and count. The adapter never selects a
masking rule based on the source category; this is *diagnostic metadata*.
No raw value, message, credential, literal prefix, or free text is stored in
the aggregate. Field names reveal schema and require controlled access.

**Event-local exact duplicate reuse.** SecureMasker detects identical
string inputs within one maskAll call (normally one Log4j event), calls the
masking executor for unique strings only, and reconstructs the original
result order and individual sequence numbers. The same sanitized result and
fail-closed state are propagated to repeated values; the entire rewritten
event still fails closed if any field fails. There is **no** cross-event
cache, no long-lived raw-value map, and no cross-organization reuse.
Diagnostics count duplicate field evaluations avoided. A saved field
evaluation is not necessarily a saved ONNX call.

**Strict safe-metadata fast path.** When rule enforcement is explicitly
enabled, the same organization JSON policy may include a \`safeMetadata\`
array in addition to sensitive \`rules\`. Only the exact *field names*
\`eventId\` and \`traceId\` are supported initially. Each requires an
APPROVED rule with an ID, fixed literal alphabetic prefix ending in "-" or
"_", and 1-12 digits. The whole field=value string must match; free text,
unknown fields, the sensitive \`customer\` field, multiple fields and longer
or mismatching values return to normal hybrid routing.

Example optional policy addition:

~~~json
"safeMetadata": [{
  "id": "application-event-id",
  "status": "APPROVED",
  "field": "eventId",
  "prefix": "bench-",
  "digits": 2
}]
~~~

A matching \`eventId=bench-12\` is passed through intact without ML **for
that field only**. This is a *reviewed narrow ALLOW*, not an automatic
decision that identifiers are harmless. Application owners must prove the
field is trusted, schema-constrained and not sourced from arbitrary user
input. Safe-metadata rules are disabled unless adaptive enforcement is on.

**Comparative benchmark commands** (with the same 15-second diagnostic
deadline; each invocation overwrites the top-level result.json but now also
preserves a uniquely named archive after successful completion):

~~~powershell
# Phase 1: new code, no organization rules; ML telemetry enabled
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1 -MaskingDeadlineMillis 15000 -CollectMlRuleProposals
Copy-Item .\reports\application-log-benchmark\result.json .\reports\application-log-benchmark\baseline-with-dedup.json

# Phase 2: explicitly load synthetic ORG42 sample approved rules
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1 -MaskingDeadlineMillis 15000 -CollectMlRuleProposals -UseExampleOrganizationRules
~~~

Compare \`ml_invoked_events\`, \`caller_latency_millis\`,
\`ml_routing_reason_counts_including_warmup\`,
\`ml_routing_details_including_warmup\`,
\`duplicate_field_evaluations_avoided_including_warmup\`,
\`safe_metadata_matches_including_warmup\`,
\`approved_rule_matches_including_warmup\`, \`fail_closed_events\`,
and \`raw_payload_forwarded\`.

**Limitations:** No runtime Maven/ONNX validation has yet been supplied for
these latest changes. A successful 15-second run is still not a pass for
the 2-second pilot target. The pilot workload continues to have ML-routed
contextual fields and long/windowed records; a lower whole-event ML count
is not guaranteed. The safe-metadata field allowlist is intentionally narrow
rather than treating arbitrary organization IDs or correlation fields as safe.

## Measured ONNX execution and long-record CPU tuning

The representative Log4j2 benchmark now reports **actual ONNX
`session.run` calls and token windows**, not just field-level ML
routing reasons or whole-event `mlInvoked` flags.

Read `result.json` -> `inference_metrics_measured_only`:

- `onnx_inference_calls`, `ml_inference_windows`:
  native calls and processed windows, **excluding four warmup events**.
- `windowed_onnx_inference_calls`, `windowed_ml_items`:
  long/window-expanded ML work, excluding warmup.
- `stage_total_millis`: distinct totals for deterministic processing,
  tokenization, window assembly, tensor preparation, native ONNX calls,
  span decoding and final reconciliation/masking.
- `stage_total_millis.onnx_windowed_native_run_subset` is
  **included** within `onnx_native_run`; do not add it again.
- `max_configured_cpu_threads` and
  `max_inference_windows_per_batch` document the tuning controls.

The stage durations are accumulated masked-worker execution times, not
full caller latencies: queue wait, Log4j ingress/rewrite, file output,
report generation and runtime scheduling contribute separately.
No raw messages, tokens, logits or decoded values are stored in the
stage metrics.

Previously, the CPU session chose
`Runtime.availableProcessors()` whenever ONNX multithreading was on,
ignoring the existing `securelogx.maxCpuThreads` setting. It now honors
the configured bound (dev default 4), with CPU intra-op threads =
`min(availableProcessors, maxCpuThreads)`; disabled CPU multithreading
still uses one thread. This is an *experimental performance change*,
not proof of improved throughput.

### Repeatable pilot comparison (PowerShell)

Run these only from the repository root. **Always run the full test
suite first**, then compare the same 48 synthetic events and
15-second *characterization* deadline. Example organization rules are
explicitly enabled solely for these measurements.

~~~powershell
mvn clean test

# Configured CPU cap=4, existing native window cap=4.
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1 -MaskingDeadlineMillis 15000 -CollectMlRuleProposals -UseExampleOrganizationRules -CpuThreads 4 -WindowBatchLimit 4
Copy-Item .\reports\application-log-benchmark\result.json .\reports\application-log-benchmark\cpu4-window4.json

# Alternative thread cap; same model, same windows and protections.
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1 -MaskingDeadlineMillis 15000 -CollectMlRuleProposals -UseExampleOrganizationRules -CpuThreads 2 -WindowBatchLimit 4
Copy-Item .\reports\application-log-benchmark\result.json .\reports\application-log-benchmark\cpu2-window4.json

# Repeat runs with reversed order to reduce warmup/CPU scheduling bias.
~~~

If native execution dominates, test several CPU thread caps before
considering other changes. If Java tokenization or decoding dominates,
target those stages instead. Only consider a different
`-WindowBatchLimit` after checking process/native-memory headroom;
raising it above the validated default of 4 requires renewed
native-memory and security regression checks. The runner warns
about larger micro-batches and preserves/restores JAVA_TOOL_OPTIONS
on exit.

**Acceptance:** zero fail-closed events, no raw sensitive test fixtures,
benign control preserved, correct event count, unchanged model/decoder/
tokenizer/window overlap, no native-memory regression. A single faster
p95 is not sufficient: compare repeated runs and report ONNX call/window
counts and stage costs alongside p50/p95/p99/throughput. The
**2-second pilot latency gate is still OPEN** until validated.

## Preserve and commit synthetic benchmark results

**Every successful invocation** of
`scripts/run-application-log-benchmark.ps1` now automatically archives
the current synthetic outputs under a **new, unique run directory**:

```text
reports/application-log-benchmark/history/
  20261017T221500Z-c4-w4-d2000-org-on-1a2b3c4d5e6f/
    result-20261017T221500Z-c4-w4-d2000-org-on-1a2b3c4d5e6f.json
    protected-application-20261017T221500Z-c4-w4-d2000-org-on-1a2b3c4d5e6f.log
    reconstructed-application-20261017T221500Z-c4-w4-d2000-org-on-1a2b3c4d5e6f.log  # only if matching
    manifest-20261017T221500Z-c4-w4-d2000-org-on-1a2b3c4d5e6f.json
```

Actual IDs are UTC timestamp + configuration label + random suffix.
The manifest records source filenames, content SHA-256 hashes, file sizes,
event count, masking deadline, CPU threads and window cap. Historical
comparison JSON files may be imported **as separate historical references**,
not misrepresented as outputs from the current run.

**Save your existing local results before starting another benchmark:**

```powershell
git pull origin chatgpt/hybrid-detection-pipeline
powershell -ExecutionPolicy Bypass -File .\scripts\archive-application-log-results.ps1 -RunLabel initial-2s-sequence -IncludeExistingReports -CommitResults -PushResults
```

This imports any existing locally saved comparison `*.json` reports in
`reports/application-log-benchmark` (e.g. CPU-2 versus CPU-4 runs)
alongside the current `result.json`, `protected-application.log`
and, if the lines match, `reconstructed-application.log`.
Any historical JSON without the expected **passed, no-leak, no-fail-closed
synthetic** flags is skipped with a warning. Reports that were overwritten
before archiving cannot be recovered.

**Automatically archive, Git commit and push new successful results:**

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-application-log-benchmark.ps1 -MaskingDeadlineMillis 2000 -CollectMlRuleProposals -UseExampleOrganizationRules -CpuThreads 4 -WindowBatchLimit 4 -CommitResults -PushResults
```

`-CommitResults` commits only the new archive directory on
`chatgpt/hybrid-detection-pipeline`; unrelated staged changes are
left alone. `-PushResults` also pushes the result commit to GitHub and
requires `-CommitResults`. Omitting both still archives automatically
on disk, without publishing. `-SkipArchive` opts out;
`-RunLabel` provides a custom semantic name, and
`-IncludeExistingReports` imports old root-level JSON snapshots.

**Security boundary:** These scripts are **only for the known synthetic
benchmark**, not production application logs. An archive/commit is refused
unless the current report confirms `passed=true`,
`raw_payload_forwarded=false`, zero fail-closed, and known synthetic
sensitive fixtures are absent from protected output. The archiver also
checks the reconstructed log against protected output to reject stale
copies. These tests do **not** prove that arbitrary real customer data
is safe to upload to a public GitHub repository. Review files before
pushing. The scripts never auto-commit or push without explicit flags.

### Run and archive parallel-sequencing JUnit evidence

The checked-in PowerShell wrapper quotes Surefire properties correctly
and now **archives passing test-suite summaries with unique file names**.
Raw Maven Surefire XML can include environment/JVM properties and captured
output and is **not** uploaded. Instead, the archive contains test
counts, timing, Suite IDs, result status, and the local source-XML SHA-256:

```powershell
# Re-run tests; archive a sanitized JSON outcome automatically.
powershell -ExecutionPolicy Bypass -File .\scripts\run-parallel-sequence-tests.ps1

# To commit and push the sanitized JSON after a successful re-run:
powershell -ExecutionPolicy Bypass -File .\scripts\run-parallel-sequence-tests.ps1 -CommitResults -PushResults

# If tests have already passed, archive existing JUnit files without re-running:
powershell -ExecutionPolicy Bypass -File .\scripts\archive-parallel-sequence-tests.ps1 -RunLabel oct17-parallel-pass -CommitResults -PushResults
```

The archiver requires passing, non-skipped results from
`SecureLogXParallelSequenceIntegrationTest` and
`SecureLogXSequenceAuditTest`; by default it rejects JUnit XML over
24 hours old. Set `-AllowOlderReports` only when you intentionally
need to archive older evidence, and retain source-file provenance.
All archived JUnit summaries go in
`reports/parallel-sequence-tests/history/<UTC timestamp>-<run label>-<unique suffix>/`.
The separately recorded user-reported pass is not a substitute for
locally parsed JUnit evidence.

For the entire relevant Maven reactor test suite, specify `-FullSuite`.
These model-free concurrency tests do not establish real ONNX
parallel-load latency, bounded queue saturation, native-resource
stability, or a production SLO.

---

## Parallel log sequencing and reconstruction (October 17, 2026)

**Ordering contract:** Every SecureLogX-protected Log4j2 event gets a
process/classloader-scoped increasing `securelogx.seq` number through
`AtomicLong.incrementAndGet()` **before** the field masking, ML queue
and downstream destination appender run. All concurrent callers,
including JDK 25 virtual-thread callers, share this counter within
the same loaded SecureLogX runtime class.

The protected LogEvent receives three reserved MDC values **after**
sanitization and before forwarding to any destination; any incoming
MDC values attempting to spoof these keys are overwritten:

| Field | Use |
|---|---|
| `securelogx.instanceId` | Producer/classloader UUID, stable through Log4j configuration reload |
| `securelogx.seq` | Positive 64-bit **numeric** sequence, ordered at rewrite ingress |
| `securelogx.ingressId` | Composite `instanceId-seq`; stable deduplication identifier, including fail-closed results |

**Do not assume FileAppender, Kafka, or SIEM physical arrival order equals
ingress order.** Concurrent requests can finish masking in a different
order. A protected event can have `seq=102` written before `seq=101`
if event 101 takes longer. Neither virtual threads nor multiple physical
writers should force a global lock around ONNX to prevent this.

To reconstruct ordering, group by `securelogx.instanceId`, then
sort `securelogx.seq` as a **64-bit integer**, ascending. Use
`securelogx.ingressId` for duplicate detection; a composite string
should never be the numeric sort key. For a SIEM, explicitly index
sequence as a numeric field and both IDs as exact-match keywords.

Distinct JVMs/redeploy classloaders have distinct UUIDs and unrelated
sequence counters. No total order across them is possible without other
time/correlation evidence. Do not automatically interpret non-consecutive
sequence values within one file as lost logs: other destinations,
logger configurations, or fail-closed paths may consume sequences.

**Example protected benchmark record:**

```text
eventId=bench-17|level=WARN|instanceId=11111111-1111-4111-8111-111111111111|seq=102|ingressId=11111111-1111-4111-8111-111111111111-102|ml=true|failClosed=false|reason=ML_RESOLVED|customer=[MASKED]|stack=|msg=[MASKED]|throwable=
```

The representative application benchmark now validates all sequence
fields in the final **protected** log, checks consistency/uniqueness,
and reports `protected_sequence_audit` including record count,
instance count and physical-order inversions.

To reconstruct the *synthetic single-line benchmark format* on Windows:

```powershell
powershell -ExecutionPolicy Bypass `
  -File .\scripts\reconstruct-protected-application-log.ps1 `
  -InputPath .\reports\application-log-benchmark\protected-application.log `
  -OutputPath .\reports\application-log-benchmark\reconstructed-application.log
```

The script refuses to overwrite the original file, rejects malformed,
duplicate or mismatched identities, and writes a copy grouped by
instance and **numerically** sequenced. For more than one instance,
the group order is arbitrary, *not* cross-process chronology.
The tool is intentionally **not** an arbitrary multiline-log parser.
Production logs should be processed using structured/typed SIEM fields.

Model-free tests cover: 64 simultaneous rewrite calls, 64 concurrent
Log4j RewriteAppender → FileAppender writes, forced reverse-completion
with numeric sequence recovery, forged incoming metadata, and
fail-closed/reload sequence continuity. These tests are **committed but
must be run locally**; a separate real-ONNX parallel saturation test is
still required before claiming production concurrency validation.

---

## Real ONNX parallel logging benchmark (October 17, 2026)

The new `SecureLogXParallelOnnxBenchmark` exercises **actual validated
ML-v1.3 BERT/ONNX inference** through the same synchronous Log4j2
`Logger -> RewriteAppender -> SecureLogXRewritePolicy -> FileAppender`
path as the sequential application benchmark. Only caller concurrency
varies; the bounded masking executor retains **one native inference
worker**, one ONNX session, and its existing deadline/cancellation rules.

It runs concurrency profiles of **10, 50 and 100 simultaneous callers**
in bounded waves (200 measured events per profile by default), after
four model warmup events. It supports platform callers or JDK 21+/25
virtual-thread callers while leaving the native inference worker on a
dedicated platform thread.

Before using the runner, execute the full Maven test suite:

```powershell
cd D:\workspace\SecureLogX
git pull origin chatgpt/hybrid-detection-pipeline
mvn clean test
```

Then run the real-ONNX parallel profiles. This will create unique
per-run output folders and **never overwrite prior benchmark history**:

```powershell
powershell -ExecutionPolicy Bypass `
  -File .\scripts\run-parallel-onnx-benchmark.ps1 `
  -Callers "10,50,100" `
  -Events 200 `
  -CallerMode platform `
  -MaskingDeadlineMillis 2000 `
  -CpuThreads 4 `
  -WindowBatchLimit 4 `
  -MaskingQueueCapacity 64 `
  -UseExampleOrganizationRules `
  -CommitResults `
  -PushResults
```

For an optional JDK 25 virtual-thread **caller-side** comparison, repeat
with `-CallerMode virtual` and the same settings. This does not move
ONNX inference onto virtual threads. Repeat runs in reverse order to
reduce warmup, thermal and OS scheduling bias. `-CommitResults` and
`-PushResults` are explicit publication options; omitting them still
archives every successful security-validated run locally.

Every run gets a unique UTC timestamp + concurrency + caller mode +
CPU/window/deadline + random-suffix directory under
`reports/parallel-onnx/history/` with:

- uniquely named `result-<run-id>.json`,
- `protected-application-<run-id>.log`,
- `reconstructed-application-<run-id>.log`,
- `manifest-<run-id>.json` recording source commit, settings,
  file sizes and SHA-256 hashes.

The result separates **security** from **functional** acceptance:

- `security_passed=true` requires every emitted synthetic event to
  exist in the protected output, no known raw fixture leakage, unique
  `securelogx.ingressId`, valid per-instance numeric sequences and
  successful reconstruction. The archive is refused on any failure.
- `functional_passed=true` additionally requires zero protected
  fail-closed events and zero caller invocation latencies beyond the
  configured masking deadline; this is intentionally conservative and
  is not yet a real-world SLO.
- An overload-rejected or deadline-exceeded event **still counts as
  delivered and secure**, but NOT successfully masked. Thus a run can
  be `security_passed=true`, `functional_passed=false`; its output is
  **valuable degradation evidence** and is archived, never mislabeled
  as a normal functional pass.
- Output includes measured-only ONNX calls/windows and stage timing,
  bounded worker admission/completion/overload/deadline counters,
  active/queued peaks, caller p50/p95/p99 and 2-second exceedances,
  numeric ordering inversions, event completeness, native/JVM/process
  memory snapshots and post-shutdown context/worker ownership.
  Memory snapshots are *point-in-time*, not a proven in-flight RSS peak.

At the default 2,000 ms deadline and a single bounded ONNX worker,
50/100 concurrent callers may exhaust the queue or time out. Those
fail-closed outcomes are **expected under stress** and must not be
treated as permission to forward raw log payloads. Never increase
worker count, window batch or queue size merely to claim better
throughput without validating native memory and data safety.

**Validation status:** The real-ONNX 10/50/100-caller platform
and virtual runs were executed and archived; all six passed synthetic
security/sequence checks and failed the strict functional deadline
gate. See the paired low-concurrency follow-up below. These runs do
not claim multi-hour soak or production acceptance.

---

## Paired low-concurrency real ONNX study (October 17, 2026)

The earlier **10/50/100-caller platform/virtual** experiments were
completed and archived in GitHub; all six passed synthetic protected
delivery/sequence validation, but **none met the strict 2-second
functional gate**. Their comparison is recorded in
`reports/parallel-onnx/comparisons/2026-10-17-real-onnx-parallel-platform-vs-virtual.json`.
At 100 callers, each mode recorded 70 overload rejections and the
overall logging rate mostly reflected fail-closed output, not true
masking throughput.

To identify a finite-run candidate for sustainable operation, the
next stage runs **1, 2, 4, 8** simultaneous logging callers through
the same actual Log4j2 + ONNX pipeline, with **two repetitions per
configuration** (16 independent runs), a reproducibly shuffled caller
count order and **AB/BA** platform/virtual pairing:

```powershell
cd D:\workspace\SecureLogX
git pull origin chatgpt/hybrid-detection-pipeline

powershell -ExecutionPolicy Bypass `
  -File .\scripts\run-low-concurrency-onnx-study.ps1 `
  -Callers "1,2,4,8" `
  -Repetitions 2 `
  -Events 200 `
  -MaskingDeadlineMillis 2000 `
  -CpuThreads 4 `
  -WindowBatchLimit 4 `
  -MaskingQueueCapacity 64 `
  -RandomSeed 20261017 `
  -UseExampleOrganizationRules `
  -CommitResults `
  -PushResults
```

- The study runner executes the module **Maven test suite once**
  before load testing, builds once, and reuses the compiled
  real-ONNX benchmark for every trial. This does not substitute
  mock masking, change native worker count or widen the queue.
- Each run has a distinct directory under
  `reports/parallel-onnx/history/`, with original result JSON,
  protected synthetic log, reconstructed sequence and SHA-256
  manifest. A separate machine-readable receipt proves which
  validated archive belongs to which scheduled trial.
- The study folder
  `reports/parallel-onnx/studies/<unique UTC run ID>/`
  contains the seeded run plan, the 16 verified receipt references
  and consolidated study summary. The exact file hashes are checked
  before any Git staging. `-CommitResults -PushResults` commits
  **only** those explicit synthetic evidence paths as one commit.
  If publishing fails, the local study commit is retained; retry
  `git push` rather than rerunning and duplicating experiments.
- The summary reports a separate
  `normal_masked_events_per_second` metric, which excludes
  fail-closed outputs; success fraction, p50/p95/p99,
  overload/deadline counts, ONNX calls/windows, sequence
  inversions and endpoint working-set samples remain traceable
  to each original run.
- For each mode and concurrency level, the conservative
  `strict_deadline_candidate_all_repetitions` flag is true
  only when **all** repeated trials had **zero fail-closed events**
  and **zero caller latencies beyond the 2-second deadline**.
  This is a candidate for further load/soak assessment, **not**
  an established production concurrency limit or SLA.
- Functional degradation with intact synthetic protection is
  preserved as valid diagnostic evidence, not mislabeled success.
  A failed security, completeness, ownership or hash check stops
  the study; no trial is pushed as a verified pass.

Use `-Repetitions 4` for a stronger follow-up with the same
balanced mode-order protocol. Two repetitions establish an
exploratory comparison only. Thread type remains a
**caller-side variable**—the ONNX worker stays a single
platform thread. Stable causal performance conclusions require
controlled load, repeated measurements and eventual soak testing.

**Measured outcome (October 17–18, 2026):** All 16/16
paired 1/2/4/8-caller real-ONNX trials passed both the
2,000 ms functional gate and protected-output sequencing/
synthetic-fixture security checks. There were 3,200 normally
masked measured events, zero fail-closed events and 538
physical write-order inversions across all trials.

The 4-caller platform configuration had the highest median
normally masked throughput (10.074/s, p95 784 ms).
Eight callers remained functional, but useful median
throughput declined to roughly 8.2 events/s and p95 rose
to about 1.15–1.17 seconds. Unlike the prior 10-caller
saturation runs, these short 8-caller trials incurred
no deadline failures. Full results:

- `reports/parallel-onnx/studies/20261017T235150Z-low-caller-seed20261017-54d99978b774/`
- `reports/parallel-onnx/comparisons/2026-10-17-low-concurrency-1-2-4-8-findings.json`

**Follow-up completed:** The earlier 8/9/10/12-caller trial
and the four-repetition 8/10/11/12-caller study are complete.
The latter produced **32/32 synthetic confidentiality/sequence
passes but only 15/32 strict functional passes**, including
failures at eight callers. Results varied by run order and
execution conditions. Use the compact comparison in
`reports/parallel-onnx/comparisons/2026-10-18-32-run-repeated-8-10-11-12-findings.json`
instead of rerunning synchronized bursts for a supposed
absolute concurrency limit.

These are short synthetic burst tests only. A larger multi-hour
soak with steady arrivals, in-flight native-memory monitoring,
and realistic workload variation remains required before any
claim of production concurrency/SLO readiness.

---

## Current: fixed-rate real-ONNX logging and compact evidence

The **new production-capacity characterization** is controlled
open-loop synthetic logging at fixed offered rates of 5, 6 and
7 requests/second. It measures real queued ONNX inference under
steady arrivals, not synchronized waves. It preserves the existing
single native inference worker and unchanged masking safety rules.
It also records payload-free *actual native queue waiting time*
histograms, producer dispatch jitter, caller p95/p99/max, measured
normal masking rate, fail-closed reasons, GC and periodically
sampled process working set.

```powershell
cd D:\workspace\SecureLogX
git pull origin chatgpt/hybrid-detection-pipeline
powershell -ExecutionPolicy Bypass -File .\scripts\run-steady-onnx-study.ps1 `
  -Rates "5,6,7" -Repetitions 2 -DurationSeconds 60 `
  -CallerMode platform -MaxOutstanding 32 `
  -MemorySampleMillis 5000 -MaskingDeadlineMillis 2000 `
  -CpuThreads 4 -WindowBatchLimit 4 -MaskingQueueCapacity 64 `
  -RandomSeed 20261017 -UseExampleOrganizationRules `
  -CommitResults -PushResults
```

**No more four-file-per-run Git commits.** This runner publishes
**one compact JSON per study** under
`reports/parallel-onnx/steady/`, with grouped metrics,
source revision, pass/fail status and SHA-256 provenance.
All individual detailed JSON and synthetic protected logs are
retained **locally as one Git-ignored ZIP per study** at
`reports/parallel-onnx/.local-steady-runs/`. Raw logs are
not pushed. The ZIP is validated entry-by-entry before the
temporary extracted directories are deleted.

To simplify existing history, 344 redundant previous
per-run files/receipts were removed from the **current Git
tree**. Their exact source contents remain retrievable
from reachable historical commits. The catalog
`reports/parallel-onnx/README.md` links each consolidated
finding to its original audit commit. Do not rewrite Git
history to erase these commits.

**State:** Java implementation, queue-wait instrumentation,
unit tests and the Windows study runner are committed.
The new steady-rate ONNX pilot still requires **local
Maven and actual model execution** before metrics or
functional acceptance can be claimed. Observed RSS
maxima are sampled peaks, not guaranteed instantaneous
native-memory peaks, and a 60-second run is not a soak
or a production service-level validation.

---

## Current assurance boundary

SecureLogX does **not** claim that sensitive/NPI data can never appear in an
application log. Current frozen H1+C1-S evaluation measured approximately
99.08% high-risk full-span recall and 93.55% whole-record perfect redaction on
the locked 6,463-record corpus. Residual misses remain.

Production-routing ML invocation rates must be measured on representative
application logs. The 5%/15%/35%/60% rates used in engineering benchmarks are
synthetic workload mixes, not deployment SLAs.

Timeout/backpressure and hot application-server redeploy lifecycle are still
open production-hardening items. Until they are validated, do not describe the
Log4j2 integration as fully production-ready for those operating modes.
