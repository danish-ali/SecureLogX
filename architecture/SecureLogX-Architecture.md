# SecureLogX Architecture and Production Design Reference

> **Canonical design reference**
>
> This file is the single source of truth for SecureLogX architecture, design decisions, evaluation strategy, production-readiness criteria, and major architectural changes.
>
> **Rule:** Future architectural changes must be recorded in this file with the date, the change, and the reason. Do not create competing architecture documents unless this file explicitly delegates a topic.

---

## 1. Product Goal

SecureLogX is a production-grade Java secure logging framework designed to detect and redact sensitive information before logs leave the application boundary.

The product goal is not to maximize either regex usage or ML usage in isolation. The objective is to achieve the best combination of:

- high sensitive-data recall,
- very low over-masking,
- strong negative-context preservation,
- low and predictable runtime overhead,
- local/private inference,
- fail-safe behavior,
- practical Java/Spring/Log4j adoption,
- auditable security decisions,
- vulnerability-conscious dependencies and supply chain.

The guiding principle is:

> **Use deterministic logic where evidence is structurally strong, and use contextual ML where semantics or ambiguity require it.**

### Current release posture

**Public positioning:** SecureLogX is currently a **research runtime + Log4j2
pilot**, not a universally adoptable 1.0 product.

Do not change that positioning until all five OPEN production gates in the
scorecard below are closed and validated under load.

### Production-readiness scorecard

```text
H1_C1S model/quality             VALIDATED
Hybrid resolver                  VALIDATED
Long-input handling              VALIDATED
CPU memory                       VALIDATED
Log4j Rewrite architecture       IMPLEMENTED
Structured LogEvent masking      IMPLEMENTED
Config bypass detection          IMPLEMENTED
Startup fail-fast API            IMPLEMENTED
Legacy payload leak              FIXED
Unsupported Kafka ingress        REMOVED

Timeout/backpressure             OPEN
Concurrency saturation policy    OPEN
Hot redeploy/native lifecycle    OPEN
Real application log benchmark   OPEN
Multi-hour soak                  OPEN
```

The five OPEN items are release gates, not documentation TODOs.

For timeout/backpressure specifically, do **not** wrap
`tokenizationFuture.get()` with a caller timeout and call that bounded. The
current inference path includes asynchronous tokenization followed by
synchronous `OrtSession.run()`. A caller deadline could return while abandoned
tokenization or ONNX work continues consuming CPU/native memory.

The required design is:

```text
bounded admission
  -> bounded executor/queue
  -> deadline covering tokenize + ONNX
  -> success
  or fail-closed synthetic event + overload metric
```

Queue-full, rejection, timeout, and overload must never forward raw payload.
The default target behavior is a synthetic fail-closed event plus a metric.
Drop-without-forward may exist only as an explicit, documented, metered policy
and must be load-tested before 1.0.

---

## 2. Repository Boundaries

### SecureLogX-NER

Purpose:

- Python training and evaluation,
- dataset preparation,
- frozen ML-v1.3 model,
- ONNX export,
- model-selection evidence,
- parity fixtures.

SecureLogX-NER must remain a **model/data repository**.

Hybrid production architecture must not be implemented there.

### SecureLogX

Purpose:

- Java runtime,
- deterministic detection,
- contextual ML inference,
- evidence resolution,
- masking policy,
- integrations,
- performance,
- production hardening,
- packaging,
- security and supply-chain controls.

Hybrid production logic belongs here.

---

## 3. Current Production Architecture

```text
Application log event
        |
        v
Structure-aware inspection
        |
        v
Deterministic evidence collection
        |
        +------------------------------+
        |                              |
        | strong structural evidence   | ambiguous / contextual
        v                              v
MASK / ALLOW evidence              ML-v1.3 BERT
                                       |
                                       v
                              contextual span evidence
        |                              |
        +--------------+---------------+
                       |
                       v
              Hybrid Context Resolver
                       |
                       v
                 Masking Policy
                       |
                       v
                 Protected Log
                       |
        +--------------+------------------+
        |              |                  |
      Log4j2         Kafka              File/SIEM
```

### Appender ingress and event-envelope contract

**Status: CORE + LOG4J2 REWRITE INTEGRATION IMPLEMENTED; LOCAL MODULE VALIDATION PENDING**

Moving SecureLogX toward an appender integration changes the meaning of log
severity and ordering metadata.

#### Severity is not the protection gate

Application severity must be preserved:

- TRACE remains TRACE,
- DEBUG remains DEBUG,
- INFO remains INFO,
- WARN remains WARN,
- ERROR remains ERROR.

The legacy `SECURE` level remains available for backward compatibility with
the original `logger.secure(...)` API, but SecureLogX protection is no longer
conditioned on `level == SECURE`.

All application severities entering a SecureLogX protection path are inspected
by H1 when masking is enabled. The deterministic gate then decides whether the
record can be resolved without ML or requires contextual inference.

A future appender marker such as `SECURELOGX` / `FORCE_SECURE` may be used
as an explicit policy hint, but it must not imply that unmarked INFO/WARN/ERROR
records bypass protection.

#### Sequence contract

SecureLogX owns a process-wide monotonic `long` ingress sequence.

The sequence is assigned:

1. when the event enters SecureLogX,
2. before inference queues,
3. before batching,
4. before ONNX execution,
5. before writer queues.

Therefore `seq` represents **SecureLogX ingestion order**, not completion or
physical file-write order.

Asynchronous inference and multiple writer threads may complete events out of
order; `seq` is the stable field used to reconstruct ingress ordering.

The old per-request/thread-local sequence has been removed. Request context now
owns correlation (`traceId`) only.

#### Timestamp and identity contract

The event envelope separates:

- original source event timestamp,
- local SecureLogX ingest timestamp,
- source severity,
- trace/correlation ID,
- process/JVM `instanceId`,
- process-wide `seq`.

The formatted protected log preserves the **original event timestamp** instead
of substituting the later inference/write time.

Event identity is:

```text
instanceId + "-" + seq
```

`instanceId` is generated once for the SecureLogX JVM/classloader and is
preserved when a formatted event crosses a Kafka hop. `traceId` remains a
correlation identifier and is intentionally separate from sequence/order.

The public API now includes an appender/framework ingress overload that accepts
the source severity, trace ID, and original event timestamp while SecureLogX
assigns the sequence internally.

#### Log4j2 production integration

SecureLogX uses Apache Log4j2's existing `RewriteAppender`. SecureLogX does
not implement a competing destination appender.

The `securelogx-log4j2` module provides:

- `SecureLogXRewritePolicy`,
- strict topology validation,
- structured-field sanitization,
- process/classloader-stable rewrite sequence and instance identity,
- fail-closed event replacement,
- generated Log4j plugin metadata,
- model-free CI tests.

Required pre-queue topology:

```text
Synchronous Logger / LoggerConfig
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

When `requirePreQueueSanitization=true`, the validator rejects:

- missing expected RewriteAppender,
- a root logger that does not reference the rewrite,
- direct/sibling destination appenders that bypass the rewrite,
- `AsyncRoot`,
- `AsyncLogger`,
- AsyncLogger context selectors.

The rewrite sanitizes:

- formatted message,
- message format,
- message parameters,
- MapMessage values,
- MDC/ThreadContext values,
- ThreadContext stack,
- throwable messages,
- cause/suppressed throwable messages.

If any payload-bearing field reports a processing failure, the **entire**
LogEvent is replaced with
`[SECURELOGX_REDACTED_PROCESSING_FAILURE]`. The original message, MDC values,
context stack, and throwable message are not forwarded on that path.

Rewrite sequence and `instanceId` live outside individual policy instances so
Log4j configuration reload does not reset event identity.

A previous JVM shutdown hook in the Log4j2 registry was removed because a
library-owned shutdown-hook thread can retain an application classloader during
redeploy. Explicit hot-redeploy/native-session lifecycle closure is still a
production-hardening item and must be validated before claiming application-
server redeploy support.

Scope boundary:

- direct `System.out` / `System.err` are outside the integration,
- direct `Throwable.printStackTrace()` is outside the integration,
- direct Kafka clients are outside the integration,
- upstream Log4j AsyncLogger modes do not satisfy a pre-queue raw-data
  exclusion guarantee.

Canonical configuration and constraints are documented in
`securelogx-log4j2/README.md`.

---

The deterministic layer is not intended to replace ML.

Its roles are:

1. identify structurally provable sensitive values,
2. identify structurally provable negative cases,
3. provide high-confidence evidence to the resolver,
4. catch catastrophic ML misses for selected high-risk classes,
5. avoid unnecessary ML invocation where safety can be proven.

The ML layer remains responsible for semantic and contextual cases such as:

- person names,
- addresses,
- contextual business/customer identifiers,
- customer-vs-technical account distinctions,
- ambiguous identifiers,
- context-dependent IP treatment,
- other sensitive spans that cannot be proven from structure alone.

---

## 4. Frozen ML Baseline

Current production model:

- Base model: `bert-base-cased`
- Selected checkpoint: epoch 3
- Max sequence length: 384
- ONNX opset: 17
- Java tokenizer parity: passed
- ONNX argmax parity: passed
- decoded-span parity: passed
- sealed validation: completed once and must not be rerun for model selection or tuning

Important policy:

> **Do not retrain or replace ML-v1.3 merely because new hybrid architecture experiments are introduced.**

Any future model replacement must beat the frozen baseline on locked evaluation criteria and must not use sealed data for tuning.

---

## 5. Deterministic Evidence Policy

Deterministic logic is evidence-based rather than regex-first.

### Appropriate deterministic MASK examples

- explicit bearer/JWT token,
- explicit API-key field,
- validated IBAN where policy requires masking,
- card/PAN field plus Luhn validation,
- explicit network-IP field when configured sensitive,
- other future values with strong structural validation and authoritative field ownership.

### Appropriate deterministic ALLOW examples

ALLOW must be rare and highly defensible.

Example:

- explicit software/version field containing an IPv4-shaped version reference.

ALLOW must never be inferred from loose nearby text.

### ESCALATE examples

The deterministic layer should escalate to contextual ML when structure is insufficient, including:

- SSN-shaped values without enough context,
- routing numbers,
- Luhn-valid numbers without authoritative card ownership,
- ambiguous IPs,
- unknown identifiers,
- contextual business/customer IDs,
- uncertain field semantics.

---

## 6. Conflict Resolution Principles

The resolver combines deterministic and ML evidence.

Current principles:

1. high-confidence deterministic MASK evidence can protect a value even if ML misses it,
2. deterministic MASK is a **protection floor, not a span ceiling**; overlapping ML MASK evidence may extend the protected span,
3. deterministic ALLOW may suppress ML only when it is authoritative, matches the same entity type, and fully contains the ML span,
4. partial-overlap ALLOW must not erase a larger ML protection span,
5. ESCALATE evidence does not decide the final outcome,
6. non-overlapping contextual ML spans are normally masked,
7. unsafe ambiguity should favor protection or escalation rather than raw exposure.

Future hardening requirement:

- deterministic MASK/ALLOW conflicts must use explicit precedence rather than accidental ordering,
- conflicting decisive evidence should normally resolve to MASK or explicit escalation according to policy,
- resolver decisions must be auditable.

---

## 7. Current Hybrid Validation Baseline

Milestone commit:

`078f63d - Record hybrid safety and runtime validation milestone`

Branch:

`chatgpt/hybrid-detection-pipeline`

### Deterministic detection check

- 19 cases passed

### Dataset-wide gate safety audit

Dataset:

- 6,463 records
- 9,238 gold sensitive spans

Result:

- safety passed,
- unsafe bypass: 0,
- deterministic overmask: 0,
- incorrect ALLOW/gold conflicts: 0,
- deterministic bypass on this NER-heavy corpus: 0%.

Interpretation:

> This corpus is primarily a security/NER evaluation corpus and is not a valid standalone estimate of production ML invocation rate.

It should continue to be used as a **safety corpus**.

### End-to-end runtime check

- 10 cases,
- 6 deterministic-only,
- 4 ML,
- 40% ML invocation,
- negative technical IP preserved,
- 0 processing failures,
- fail-closed behavior enabled.

Interpretation:

> Controlled bypass is demonstrated, but production invocation rate has not yet been established.

---

## 8. Production Routing Evaluation Strategy

Do not optimize against the 6,463-record security corpus simply to increase bypass.

Use two distinct evaluations.

### A. Security Corpus

Purpose:

> Determine whether hybrid routing can expose or incorrectly alter protected data.

Primary requirements:

- unsafe bypass = 0,
- incorrect ALLOW = 0,
- deterministic overmask = 0 or extremely low with documented rationale,
- high-risk recall approximately 99-100%,
- no raw leakage on processing failure.

### B. Production Routing Corpus

Purpose:

> Measure how frequently real application traffic actually requires ML.

The production-routing corpus must contain realistic mixtures of:

- ordinary operational logs with no sensitive data,
- structured logs with obvious sensitive values,
- contextual PII,
- ambiguous identifiers,
- negative technical examples,
- malformed/adversarial inputs,
- Spring Boot logs,
- Log4j logs,
- JSON logs,
- nested JSON,
- key=value logs,
- access logs,
- Kafka logs,
- database errors,
- stack-trace-adjacent logs,
- syslog,
- Kubernetes/container logs,
- free text.

Recommended workload mixes:

```text
Scenario A:
90% ordinary / 10% sensitive or ambiguous

Scenario B:
75% ordinary / 25% sensitive or ambiguous

Scenario C:
50% ordinary / 50% sensitive or ambiguous

Scenario D:
high-risk stress workload
```

Measure:

- ML invocation rate,
- deterministic/structural resolution rate,
- security misses,
- partial masking,
- overmasking,
- p50/p95/p99 latency,
- throughput,
- CPU,
- memory,
- model startup time.

No fixed ML invocation target should override safety.

A practical product goal is to reduce ML invocation materially, potentially toward 10-20% on normal production traffic, **only if security quality remains unchanged or improves**.

### Production-routing benchmark implementation

**Status: EXPERIMENTAL**

A dedicated Java routing benchmark now lives in:

`securelogx-core/src/main/java/com/securelogx/validation/ProductionRoutingBenchmark.java`

Runner:

`scripts/run-production-routing-benchmark.ps1`

Output:

`reports/production-routing-benchmark/result.json`

The benchmark is intentionally separate from the NER security corpus and currently uses four deterministic 1,000-record synthetic traffic mixes:

- Scenario A: 90% ordinary/negative operational traffic and 10% sensitive/ambiguous traffic,
- Scenario B: 75% ordinary/negative and 25% sensitive/ambiguous,
- Scenario C: 50% ordinary/negative and 50% sensitive/ambiguous,
- Scenario D: high-risk stress workload.

Record classes are:

- ordinary safe,
- deterministic sensitive,
- contextual sensitive,
- negative technical,
- adversarial.

The benchmark measures:

- ML invocation rate,
- fast-path rate,
- gate reasons,
- unsafe contextual/adversarial bypass,
- fast-path uncovered sensitive values,
- negative technical overmask.

The benchmark hard-fails only on safety violations. Scenario A separately reports whether the provisional `<=20%` ML-invocation target is met.

### First routing baseline

First local execution produced:

| Scenario | ML invocation | Fast path | Safety |
|---|---:|---:|---|
| A normal operations 90/10 | 75.80% | 24.20% | Passed |
| B mixed operations 75/25 | 71.20% | 28.80% | Passed |
| C balanced 50/50 | 68.30% | 31.70% | Passed |
| D high-risk stress | 68.30% | 31.70% | Passed |

Overall:

- 4,000 records,
- 70.90% ML invocation,
- 29.10% fast path,
- 0 unsafe bypass,
- 0 fast-path uncovered sensitive values,
- 0 negative technical overmask.

Interpretation:

> Safety is preserved, but the first production-routing baseline does not yet meet the normal-operations efficiency target.

The first optimization is therefore a **strict metadata-only fast path**, not broader regex detection.

A non-JSON record may bypass ML without sensitive evidence only when:

- every parsed key belongs to a narrow approved metadata set,
- every value has a simple metadata token shape,
- any leading text matches a recognized operational log prefix,
- no detector has already emitted ESCALATE evidence,
- no capitalized person-name pattern is present outside evidence.

Unknown keys, free prose, contextual fields, ambiguous identifiers, and adversarial values continue to ML.

### Validated post-optimization routing result

**Status: VALIDATED**

After adding the strict metadata-only fast path, the locked hybrid safety validation still passed and the production-routing benchmark produced:

| Scenario | ML invocation | Fast path | Safety |
|---|---:|---:|---|
| A normal operations 90/10 | **5.00%** | **95.00%** | Passed; <=20% target met |
| B mixed operations 75/25 | 15.00% | 85.00% | Passed |
| C balanced 50/50 | 35.00% | 65.00% | Passed |
| D high-risk stress | 60.00% | 40.00% | Passed |

Overall:

- 4,000 records,
- 28.75% ML invocation,
- 71.25% fast path,
- unsafe bypass: 0,
- fast-path uncovered sensitive values: 0,
- negative technical overmask: 0.

Routing segmentation was clean across every scenario:

- ordinary-safe: 0% ML,
- deterministic-sensitive: 0% ML,
- negative-technical: 0% ML,
- contextual-sensitive: 100% ML,
- adversarial: 100% ML.

Interpretation:

> Phase 1 production-routing objective is met on the reproducible engineering workload. Further reduction of ML invocation is not currently an optimization goal. Contextual and adversarial records should continue to pay the ML cost unless future evidence shows a safe alternative.

The next product/research step is architecture-quality comparison (D0/M0/H1 first, with H2 remaining experimental until a genuine contextual reviewer exists).

This benchmark is a reproducible engineering workload, not a claim that the synthetic distribution exactly represents any production estate. Real anonymized operational distributions should eventually be used to calibrate scenario weights.

---

## 9. Research-Driven Evaluation Changes

Recent research does not justify replacing the architecture yet, but it changes what SecureLogX should benchmark and report.

### 9.1 Four-way architecture comparison

**Current implementation status: IN PROGRESS**

A non-sealed Java comparison harness now exists at:

`securelogx-core/src/main/java/com/securelogx/validation/ArchitectureComparisonBenchmark.java`

Runner:

`scripts/run-architecture-comparison.ps1`

Output:

`reports/architecture-comparison/result.json`

The first executable comparison covers D0, M0, and H1 on the same non-sealed datasets. ML-v1.3 is inferred once for every **scorable non-truncated benchmark record** and those same frozen predictions are reused for M0 and H1 analysis. Therefore H1 reports a **logical ML routing rate**; benchmark wall-clock compute is not yet an H1 performance measurement.

Records beyond the frozen 384-token window are excluded from all three quality denominators and reported separately as a truncation cohort. This keeps D0/M0/H1 denominators identical while preserving the production rule that over-window ML-routed records fail closed.

H2 is intentionally reported as `EXPERIMENTAL_NOT_IMPLEMENTED` until a genuine contextual Redact/Keep reviewer exists. SecureLogX must not emulate H2 with heuristics and present that as a valid research baseline.

### First Phase 2 comparison result

**Status: VALIDATED ON FULL 6,463-RECORD CORPUS**

First local D0/M0/H1 execution on the non-sealed 6,463-record corpus produced:

| Metric | D0 | M0 | H1 |
|---|---:|---:|---:|
| Sensitive-character recall | 29.5337% | 94.8855% | 94.7886% |
| Non-sensitive-character redaction | 0.0026% | 0.2126% | 0.2126% |
| Full-span recall | 17.4388% | 92.5633% | 91.6107% |
| High-risk full-span recall | 13.4263% | **98.4462%** | **93.7450%** |
| Whole-record perfect redaction | 15.4263% | 92.3410% | 90.5462% |
| Logical ML invocation | 0% | 100% | 100% |

Interpretation:

> The resolver quality defect is closed for the current scorable cohort. H1 now exactly preserves M0 masking quality wherever the frozen model has complete visibility.

The final Phase 2 architecture comparison was run across **all 6,463 records**, including 581 records processed through validated overlapping-window inference.

| Metric | D0 | M0 | H1 |
|---|---:|---:|---:|
| Sensitive-character recall | 29.5337% | 97.4260% | **97.4497%** |
| Non-sensitive-character redaction | 0.0026% | 0.2335% | **0.2335%** |
| Full-span recall | 17.4388% | 95.4969% | **95.5510%** |
| High-risk full-span recall | 13.4263% | 98.9243% | **98.9641%** |
| Whole-record perfect redaction | 15.4263% | 93.3622% | **93.4086%** |
| Logical ML invocation on this NER-heavy corpus | 0% | 100% | 100% |

M0 -> H1 regression diagnostics:

- M0 full / H1 not full: **0**,
- high-risk regressions: **0**,
- H1 full / M0 not full: **5**,
- regressions by gold label: none,
- overlapping decisive evidence reasons: none.

Windowed cohort:

- input records: 6,463,
- scored records: 6,463,
- excluded records: 0,
- windowed records: 581,
- original_test_regression windowed records: 293,
- standard_dev windowed records: 288,
- ML inference windows: 7,088.

Interpretation:

> **H1 is validated as non-regressive versus frozen M0 on the complete non-sealed corpus. It preserves the same non-sensitive-character redaction rate while slightly improving sensitive-character recall, full-span recall, high-risk full-span recall, and whole-record perfect redaction.**

The five H1-only full-span recoveries demonstrate that deterministic evidence can add protection without reducing M0 coverage under the corrected resolver semantics.

The corrected resolver semantics are therefore validated:

- deterministic MASK remains a protection floor,
- overlapping ML MASK may extend protection,
- deterministic ALLOW can suppress ML only when it matches the same entity type and fully contains the ML span,
- partial ALLOW overlap cannot erase a larger ML span,
- ESCALATE remains non-decisive.

The 384-token blind-tail limitation is also closed for the current Java runtime through overlapping-window inference. Long-record performance and boundary robustness remain engineering/performance topics, but long records are no longer excluded from architecture-quality evaluation.

The journal benchmark should include:

| ID | Architecture | Purpose |
|---|---|---|
| D0 | Deterministic-only | Establish rule/validator baseline |
| M0 | Frozen ML-only | Establish BERT v1.3 baseline |
| H1 | Current SecureLogX hybrid | Current production candidate |
| H2 | Recall-first proposal + contextual Redact/Keep review | Research comparison |

H2 is an **experimental baseline**, not a production replacement unless measurements justify it.

### 9.2 Candidate recall ceiling

For any cascade architecture:

```text
Candidate proposal
      |
      v
Candidate recall
      |
      v
Contextual reviewer
      |
      v
Final masking
```

If stage 1 fails to propose a sensitive span, a later reviewer cannot recover it.

Therefore report:

- overall candidate recall,
- high-risk candidate recall,
- candidate false-positive rate,
- reviewer precision/recall,
- final masking safety.

High-risk candidate recall should be approximately 99%+ before a cascade is considered production-safe.

---

## 10. Evaluation Metrics

Do not rely only on token/entity micro-F1 and macro-F1.

Primary production and journal metrics should include:

### Detection Quality

The implemented D0/M0/H1 benchmark currently calculates these metrics over character offsets from the labeled non-sealed corpora:

- sensitive-character recall,
- non-sensitive-character redaction rate,
- full-span recall,
- exact-boundary recall,
- partial-span and missed-span counts,
- high-risk full-span recall,
- sensitive-record full-coverage rate,
- whole-record perfect-redaction rate.

Character metrics currently use **all characters in the record** as the denominator; this definition must remain stable when comparing architecture runs.

Additional target metrics remain:

- sensitive-character recall,
- non-sensitive-character redaction rate,
- exact full-span recall,
- partial-redaction rate,
- high-risk full-mask recall,
- critical miss rate,
- policy-safe wrong-class rate,
- negative-record preservation rate,
- whole-record safe-redaction rate.

### Routing / Architecture

- ML invocation rate,
- deterministic-only rate,
- candidate proposal recall,
- reviewer acceptance/rejection distribution,
- conflict-resolution frequency.

### Performance

A reproducible first-pass M0-vs-H1 engineering benchmark now exists at:

`securelogx-core/src/main/java/com/securelogx/validation/PerformanceComparisonBenchmark.java`

Runner:

`scripts/run-performance-comparison.ps1`

Output:

`reports/performance-comparison/result.json`

It reuses the exact Scenario A-D workloads from `ProductionRoutingBenchmark` and compares:

- M0: frozen ML-v1.3 on every record,
- H1: current deterministic gate + windowed ML + resolver/policy.

Default measurement design:

- 160 deterministically sampled records per scenario,
- 32-record benchmark batches,
- warm-up excluded from measurements,
- 6 measured rounds by default,
- alternating M0->H1 / H1->M0 execution order,
- an even round count so each runner executes first equally often,
- same frozen model/tokenizer,
- same 64-token overlapping-window policy,
- initialization measured separately,
- steady-state throughput in records/second,
- batch p50/p95/p99,
- H1 logical ML invocation,
- H1 windowed record count,
- H1 inference-window count,
- H1 fail-closed count,
- M0/H1 steady-state speedup ratio.

This is an end-to-end engineering benchmark rather than JMH. The first run is now validated as an engineering performance milestone.

Measured results:

| Scenario | M0 throughput | H1 throughput | H1 speedup | H1 ML invocation | Fail-closed |
|---|---:|---:|---:|---:|---:|
| A normal 90/10 | 20.286 rec/s | 130.770 rec/s | 6.446x | 5.00% | 0 |
| B 75/25 | 21.181 rec/s | 73.737 rec/s | 3.481x | 15.00% | 0 |
| C 50/50 | 26.161 rec/s | 50.175 rec/s | 1.918x | 35.00% | 0 |
| D high-risk | 28.597 rec/s | 28.506 rec/s | 0.997x | 60.00% | 0 |

Observed H1 batch p50:

- Scenario A: ~2.7 ms,
- Scenario B: ~2.7 ms,
- Scenario C: ~248 ms,
- Scenario D: ~1,305 ms.

Initialization:

- M0: 1,458 ms,
- H1: 1,100 ms.

Interpretation:

> H1 converts ML avoidance into substantial steady-state throughput gains on normal and mixed operational traffic. The advantage narrows as ML invocation rises and is effectively neutral around the current 60% high-risk stress mix.

This benchmark therefore establishes an **operating envelope**, not a universal speed claim. For workloads dominated by contextual/adversarial records, H1 should be treated primarily as a safety/architecture layer rather than a throughput optimization.

The strong A/B result supports the production-routing thesis: deterministic evidence is most valuable when ordinary operational records dominate and contextual ML is reserved for the minority of ambiguous records.

Allocation rate, peak RSS/native memory, CPU profiling, GPU-specific behavior, and detailed JFR/JMH work remain later performance-hardening tasks.

Target performance metrics remain:

- cold-start latency,
- steady-state p50,
- p95,
- p99,
- logs/second,
- batch throughput,
- CPU utilization,
- thread scaling,
- peak memory,
- model-load time,
- GPU/CPU comparison where applicable.

### Memory ownership and native ONNX budget

**Status: VALIDATED ON CPU AT 4-WINDOW CAP; GPU VALIDATION PENDING**

JVM heap is **not** the SecureLogX memory boundary.

ONNX Runtime owns native allocations outside normal Java heap accounting. Production memory analysis must therefore separate:

- JVM heap used / committed / max,
- JVM non-heap memory,
- direct and mapped buffer pools,
- whole-process working set / RSS,
- whole-process private bytes where the OS exposes them,
- process committed virtual memory,
- GPU process memory when CUDA is active.

The release gate must use whole-process memory in addition to JVM metrics. A healthy heap graph does not prove that native ONNX memory is bounded.

Native lifecycle requirements:

1. every `OnnxTensor` must be closed deterministically,
2. every `OrtSession.Result` must be closed deterministically,
3. `OrtSession.SessionOptions` must be closed immediately after session construction,
4. `OrtSession` must be closed during engine shutdown,
5. shutdown must be idempotent,
6. retained native-memory growth after warm-up must be measured during repeated inference,
7. GPU memory must be measured separately from host-process memory.

The production engine now explicitly closes `OrtSession.SessionOptions` after session creation and closes `OrtSession` during idempotent shutdown.

A native-aware memory benchmark now exists:

`securelogx-core/src/main/java/com/securelogx/validation/RuntimeMemoryBenchmark.java`

with snapshot support in:

`securelogx-core/src/main/java/com/securelogx/validation/RuntimeMemorySnapshot.java`

Runner:

`scripts/run-runtime-memory-benchmark.ps1`

Repeated session/native-retention soak:

`securelogx-core/src/main/java/com/securelogx/validation/RuntimeMemoryRestartSoakBenchmark.java`

Runner:

`scripts/run-runtime-memory-restart-soak.ps1`

Default soak:

- 4 ONNX create/warm-up/infer/shutdown cycles,
- 80 sampled records from each Scenario A-D per cycle,
- 8 explicit long-window records per cycle,
- post-GC settled checkpoints before create, after warm-up, after A-D workload, after long-window workload, and after shutdown,
- post-shutdown private/working-set deltas versus tokenizer baseline and previous cycle.

The benchmark records:

- process start,
- tokenizer initialization,
- ONNX session initialization,
- post-warm-up,
- post-GC settled checkpoints before/after each Scenario A-D workload,
- post-session-shutdown,
- post-shutdown settled memory.

It reports JVM heap/non-heap/direct buffers separately from process working set/private/virtual memory and optionally reports GPU process memory through `nvidia-smi`.

Initial release policy:

> Treat repeated post-warm-up/post-GC growth in whole-process memory as a leak signal even when JVM heap is stable.

First CPU baseline result:

| Checkpoint | Heap used | Process working set | Process private |
|---|---:|---:|---:|
| Process start | 8.94 MiB | 119.44 MiB | 460.31 MiB |
| After tokenizer | 11.92 MiB | 103.73 MiB | 221.03 MiB |
| After engine init | 11.92 MiB | 564.32 MiB | 668.71 MiB |
| After warm-up settled | 12.99 MiB | 549.16 MiB | 638.00 MiB |
| After shutdown settled | 13.10 MiB | 142.76 MiB | 218.79 MiB |

Observed retained growth per production scenario:

| Scenario | Heap used | Working set | Process private |
|---|---:|---:|---:|
| A | +0.06 MiB | +48.08 MiB | +64.34 MiB |
| B | +0.01 MiB | +63.93 MiB | +67.81 MiB |
| C | +0.01 MiB | +2.52 MiB | +3.14 MiB |
| D | +0.01 MiB | +6.75 MiB | -6.16 MiB |

Interpretation:

- JVM heap remained effectively flat,
- the ONNX model/session footprint is predominantly native,
- engine initialization accounts for the dominant process-memory increase,
- the A/B growth followed by small/negative C/D growth is consistent with allocator/session stabilization rather than a clearly monotonic leak,
- after explicit engine shutdown, process-private memory returned essentially to the tokenizer-only baseline,
- working set remained somewhat above the tokenizer checkpoint, which may reflect resident JVM/native pages and must be checked across repeated restart cycles,
- GPU process memory was unavailable in this CPU run.

A four-cycle restart soak now distinguishes lifecycle retention from active-session memory pressure.

Restart-soak observations:

| Cycle | Warm-up private | Post-window private | Shutdown private | Shutdown WS vs tokenizer |
|---|---:|---:|---:|---:|
| 1 | 640.23 MiB | 2,820.62 MiB | 217.89 MiB | +35.37 MiB |
| 2 | 639.20 MiB | 2,824.66 MiB | 220.98 MiB | +37.29 MiB |
| 3 | 640.21 MiB | 2,826.75 MiB | 222.57 MiB | +38.61 MiB |
| 4 | 642.38 MiB | 2,879.04 MiB | 221.23 MiB | +39.02 MiB |

Interpretation:

- session warm-up private memory is stable near 640 MiB,
- post-shutdown private memory repeatedly returns near the ~221 MiB tokenizer baseline,
- there is no material monotonic private-memory leak across session restart cycles,
- the small working-set increase across shutdown cycles is not accompanied by private-memory accumulation and remains a trend to monitor,
- the critical unresolved issue is the ~2.8 GiB **active-session native high-water footprint after window-heavy inference**,
- because the post-window checkpoint is taken after GC/settling while the session remains alive, 2.8 GiB represents retained active-session native memory, not merely a transient Java allocation peak.

Root cause:

> record-level batching was bounded, but overlapping-window expansion could produce many more model windows and all expanded windows were submitted to one `session.run(...)` call. Native ONNX activation/arena memory therefore scaled with the expanded window count.

Mitigation implemented, pending rerun:

- property: `securelogx.model.maxInferenceWindowsPerBatch`,
- validated baseline: **8 windows per ONNX call**,
- current CPU optimization target: **4 windows per ONNX call**,
- expanded windows are processed as bounded micro-batches,
- spans remain accumulated in original coordinates and are merged/resolved once per original log record,
- runtime stats now expose `onnxInferenceCalls` and `maxInferenceWindowsPerCallObserved`,
- the locked runtime check fails if the observed window count per ONNX call exceeds the configured cap.

The bounded-window rerun is now validated.

Locked safety/runtime result:

- compile: success,
- detection/resolver checks: 25/25 passed,
- 6,463-record gate audit: 0 unsafe bypass, 0 deterministic overmask, 0 ALLOW/gold conflicts,
- runtime: 11 records,
- deterministic-only: 6,
- ML-routed: 5,
- windowed ML records: 1,
- ML inference windows: 11,
- ONNX inference calls: 2,
- maximum windows in one ONNX call: 8,
- truncated fail-closed: 0,
- processing failures: 0,
- negative technical IP preserved.

Four-cycle bounded-window restart soak:

| Cycle | Warm-up private | Post-window private | Shutdown private | Shutdown WS vs tokenizer |
|---|---:|---:|---:|---:|
| 1 | 638.47 MiB | 1,167.17 MiB | 218.16 MiB | +26.34 MiB |
| 2 | 641.41 MiB | 1,170.34 MiB | 222.72 MiB | +31.44 MiB |
| 3 | 642.44 MiB | 1,170.18 MiB | 222.22 MiB | +30.49 MiB |
| 4 | 641.91 MiB | 1,177.24 MiB | 220.53 MiB | +30.21 MiB |

Interpretation:

- the configured 8-window micro-batch cap was enforced,
- active-session private high-water memory fell from approximately 2.8 GiB to approximately 1.17 GiB,
- this is roughly a 58% reduction in retained active-session private memory under the same long-window soak shape,
- warm-up/session footprint remains stable near 640 MiB,
- post-shutdown private memory repeatedly returns near 220 MiB,
- shutdown working-set delta stabilizes rather than climbing monotonically,
- there is no current evidence of a CPU native-memory lifecycle leak across restart cycles.

The CPU/native lifecycle and shutdown behavior are validated for the current engineering workload.

The 4-window CPU cap is now validated.

Locked safety/runtime result:

- compile: success,
- 25/25 hybrid detection checks passed,
- 6,463-record gate audit passed,
- unsafe bypass: 0,
- deterministic overmask: 0,
- ALLOW/gold conflicts: 0,
- runtime deterministic-only: 6/11,
- runtime ML-routed: 5/11,
- windowed ML records: 1,
- ML inference windows: 11,
- ONNX inference calls: 3,
- maximum windows per ONNX call: 4,
- truncated fail-closed: 0,
- processing failures: 0,
- negative technical IP preserved.

Four-cycle restart soak at cap=4:

| Cycle | Warm-up private | Post-window private | Shutdown private | Shutdown WS vs tokenizer |
|---|---:|---:|---:|---:|
| 1 | 640.19 MiB | 902.23 MiB | 221.07 MiB | +27.63 MiB |
| 2 | 642.15 MiB | 907.06 MiB | 223.29 MiB | +28.40 MiB |
| 3 | 643.97 MiB | 904.93 MiB | 221.91 MiB | +29.39 MiB |
| 4 | 640.42 MiB | 907.77 MiB | 221.68 MiB | +28.48 MiB |

Interpretation:

- peak retained private memory under the long-window workload is approximately **908 MiB**,
- this is below the 1,024 MiB hard engineering target and below the preferred 950 MiB target,
- compared with the unbounded design (~2.8 GiB), retained active-session private memory is reduced by roughly 68%,
- compared with the 8-window cap (~1.17 GiB), the 4-window cap reduces the long-window high-water mark by roughly 22%,
- warm-up/session private memory remains stable near 640 MiB,
- post-shutdown private memory repeatedly returns near ~222 MiB,
- shutdown working-set residual stabilizes around +28 to +29 MiB,
- there is no current evidence of cycle-to-cycle native-memory accumulation.

**Validated expanded-window CPU cap: 4 inference windows per ONNX call.**

A first global-cap=4 performance run showed:

| Scenario | M0 throughput | H1 throughput | Speedup | H1 ML invocation |
|---|---:|---:|---:|---:|
| A | 26.389 rec/s | 154.622 rec/s | 5.859x | 5% |
| B | 24.442 rec/s | 83.090 rec/s | 3.400x | 15% |
| C | 30.085 rec/s | 43.621 rec/s | 1.450x | 35% |
| D | 32.680 rec/s | 26.360 rec/s | 0.807x | 60% |

That run applied the 4-window cap to **all** ML inference, including ordinary single-window records. This was unnecessarily conservative and explains much of the C/D throughput loss.

The runtime has therefore been refined:

- ordinary single-window ML records use the normal runtime batch size,
- only records that expand into multiple overlapping windows are subject to the 4-window native-memory cap,
- windowed-call metrics are tracked separately from ordinary ONNX calls,
- the safety assertion applies specifically to expanded-window calls.

The adaptive batching memory and performance reruns are complete.

Adaptive-batching performance result:

| Scenario | M0 throughput | H1 throughput | Speedup | H1 ML invocation |
|---|---:|---:|---:|---:|
| A | 15.954 rec/s | **171.724 rec/s** | **10.764x** | 5% |
| B | 16.290 rec/s | **113.412 rec/s** | **6.962x** | 15% |
| C | 28.091 rec/s | **109.414 rec/s** | **3.895x** | 35% |
| D | 34.920 rec/s | **71.489 rec/s** | **2.047x** | 60% |

H1 batch p50 was approximately 5 ms in A/B, 148 ms in C, and 453 ms in D. Fail-closed count was 0 in every scenario.

Interpretation:

> adaptive batching removes the unnecessary global 4-window throttle from ordinary ML traffic while preserving the 4-window cap for expanded long-record inference. In this engineering run, H1 was faster than M0 in every scenario, including the 60%-ML stress mix.

Because absolute throughput and speedup varied materially across earlier engineering runs, these exact ratios are not yet release/publication claims. A repeatability benchmark with interleaved M0/H1 ordering is required before reporting stable performance numbers externally.

The repeatability harness now defaults to **6 measured rounds** and alternates order:

```text
round 1: M0 -> H1
round 2: H1 -> M0
round 3: M0 -> H1
...
```

Odd round counts are rejected so each architecture executes first equally often. The PowerShell runner has been aligned to the same 6-round default.

The benchmark also now equalizes final event-envelope work: both M0 and H1 construct normal `INFO` `LogEvent` instances and serialize the protected result through the same canonical event-envelope formatter. This prevents H1 from being charged for timestamp/sequence/instance formatting that M0 did not previously perform.

GPU/CUDA memory remains a separate future validation requirement.

Hard production MiB budgets remain deployment-profile dependent. After CPU/GPU deployment baselines, define:

- steady-state working-set/private-memory budget,
- maximum acceptable retained growth across soak iterations,
- long-record/windowing memory budget,
- GPU memory ceiling,
- shutdown/restart recovery expectations.

### Robustness

- identifier-format perturbation,
- whitespace/punctuation perturbation,
- malformed structured logs,
- nested data,
- unknown fields,
- adversarial negative examples,
- dependency/runtime failures.

Where possible, report denominators and confidence intervals.

---

## 11. Constrained Decoding Experiment

**Status: H1+C1-S PRODUCTION-CANDIDATE QUALITY VALIDATED**

The current production ML pipeline uses token-wise argmax followed by BIO span normalization. Production behavior is unchanged.

An experimental strict-BIO Viterbi decoder now compares:

```text
M0: current token argmax + existing span normalization
        vs
C1: strict BIO-constrained Viterbi decoding
```

Implementation:

- `BioConstrainedDecoder.java`
- `ConstrainedDecodingBenchmark.java`
- `scripts/run-constrained-decoding-benchmark.ps1`

C1 constraints:

- a sequence cannot begin with `I-X`,
- `I-X` may follow only `B-X` or `I-X`,
- `O` and `B-X` may follow any valid previous state.

The decoder uses the frozen ML-v1.3 logits without retraining. It performs an optimized Viterbi pass in O(tokens x labels), rather than evaluating a full 51x51 transition matrix at every token.

The completed benchmark used:

- the full 6,463-record non-sealed corpus,
- 581 windowed records,
- the same 64-token overlapping-window policy,
- the then-validated maximum of 8 windows per ONNX call,
- one ONNX inference result reused by M0 and C1,
- zero sealed-challenge access.

Metrics:

- sensitive-character recall,
- non-sensitive-character redaction,
- full-span recall,
- exact-boundary recall,
- high-risk full-span recall,
- whole-record perfect redaction,
- partial/missed gold spans,
- raw argmax illegal BIO-transition count,
- token labels changed by C1,
- records whose predicted spans change,
- M0-full/C1-not-full regressions,
- high-risk regressions,
- C1-full/M0-not-full gains,
- exact-boundary gains/losses.

Full-corpus result:

| Metric | M0 argmax | C1 BIO-Viterbi | Delta |
|---|---:|---:|---:|
| Sensitive-character recall | 97.4260% | 97.4243% | -0.0017 pp |
| Non-sensitive-character redaction | 0.2335% | 0.2325% | -0.0010 pp |
| Full-span recall | 95.4969% | 95.8216% | +0.3247 pp |
| Exact-boundary recall | 94.4144% | 94.9773% | +0.5629 pp |
| High-risk full-span recall | 98.9243% | 99.0837% | +0.1594 pp |
| Whole-record perfect redaction | 93.3622% | 93.6407% | +0.2785 pp |
| Partial gold spans | 152 | 107 | -45 |
| Missed gold spans | 264 | 279 | +15 |

Difference diagnostics:

- records with changed predictions: 141,
- M0 full / C1 not full: **3**,
- high-risk regressions: **0**,
- C1 full / M0 not full: **33**,
- exact-boundary losses / gains: **8 / 60**,
- illegal argmax BIO transitions: **253**,
- C1 changed 382 token labels, or **0.3999% of argmax entity tokens**.

Interpretation:

> C1 is materially better on span coherence and boundary quality while preserving high-risk safety. It converts 33 previously non-full spans to full coverage while causing 3 full-span regressions, for a net +30 full spans. It also produces 60 exact-boundary gains versus 8 losses.

The 3 full-span regressions have now been reviewed:

- `PERSON_NAME`: 2 regressions,
- `STREET_ADDRESS`: 1 regression,
- high-risk regressions: 0.

The two printed name regressions are short names (`Smith`, `Jeff`) that C1 drops entirely. The gain set is much broader: STREET_ADDRESS 12, PERSON_NAME 9, DOB 3, BUSINESS_ID/API_KEY/PHONE 2 each, and SSN/EMAIL/IBAN 1 each. Most observed gains are cases where C1 joins split argmax fragments into one coherent span.

This makes C1 a strong candidate, but dropping even low-frequency PII spans is undesirable for a privacy-protection library.

The next experiment, **C1-S**, is now implemented in the comparison benchmark.

C1-S safety-supplement rule:

1. use C1 BIO-Viterbi spans as the primary prediction,
2. inspect every M0 argmax span,
3. if C1 does **not fully cover** that M0 span, retain the M0 span as a safety supplement,
4. preserve all C1 spans,
5. compare M0, C1, and C1-S from the same frozen logits and same ONNX calls.

This is deliberately not a blind decoder replacement or second model pass. It is a conservative coverage floor around the constrained decoder.

Full-corpus C1-S result:

| Metric | M0 argmax | C1-S | Delta |
|---|---:|---:|---:|
| Sensitive-character recall | 97.4260% | **97.5172%** | +0.0912 pp |
| Non-sensitive-character redaction | 0.2335% | **0.2422%** | +0.0087 pp |
| Full-span recall | 95.4969% | **95.8541%** | +0.3572 pp |
| Exact-boundary recall | 94.4144% | **94.9989%** | +0.5845 pp |
| High-risk full-span recall | 98.9243% | **99.0837%** | +0.1594 pp |
| Whole-record perfect redaction | 93.3622% | **93.5169%** | +0.1547 pp |
| Partial gold spans | 152 | **119** | -33 |
| Missed gold spans | 264 | **264** | 0 |

M0 -> C1-S diagnostics:

- records with changed predictions: 105,
- M0 full / C1-S not full: **0**,
- high-risk regressions: **0**,
- C1-S full / M0 not full: **33**,
- exact-boundary losses / gains: **6 / 60**.

Interpretation:

> C1-S preserves M0's full-span coverage floor while retaining C1's span-coherence improvements. It adds 33 full-span recoveries with zero full-span regressions and zero high-risk regressions.

The non-sensitive-character redaction increase is +0.0087 percentage points absolute. This is small but must remain visible as a precision tradeoff rather than being hidden.

C1-S passes the ML-only quality promotion gate. The shared production decoder has also passed the locked hybrid/runtime validation and the full H1+C1-S architecture comparison.

The benchmark and production runtime now share the same `BioConstrainedSpanDecoder` implementation. The development validation profile selects `BIO_VITERBI_SAFETY_SUPPLEMENT`, while the code-level fallback remains `ARGMAX_LEGACY`.

Goal:

> preserve C1's span-coherence/boundary gains while preventing C1 from losing protection that M0 already supplied.

Production-path H1+C1-S result on all 6,463 non-sealed records:

| Metric | H1 legacy | H1+C1-S |
|---|---:|---:|
| Sensitive-character recall | 97.4497% | **97.5374%** |
| Non-sensitive-character redaction | 0.2335% | 0.2422% |
| Full-span recall | 95.5510% | **95.8866%** |
| High-risk full-span recall | 98.9641% | **99.0837%** |
| Whole-record perfect redaction | 93.4086% | **93.5479%** |

M0 -> H1+C1-S diagnostics:

- M0-full / H1+C1-S-not-full: **0**,
- high-risk regressions: **0**,
- H1+C1-S full / M0 not full: **36**.

The production runtime validation also passed with decoder mode `BIO_VITERBI_SAFETY_SUPPLEMENT`, 0 fail-closed records, 0 processing failures, and the expanded-window cap enforced.

Promotion gate status:

1. C1 high-risk full-span recall not lower than M0: **PASS**.
2. Sensitive-character recall no material regression: **PROVISIONAL PASS**, pending 3-case inspection.
3. Non-sensitive-character redaction not increased: **PASS**.
4. Illegal BIO paths eliminated by construction: **PASS**.
5. Every M0-full/C1-not-full regression inspected: **PASS / REVIEW COMPLETE**.
6. Production decoder unchanged until review completes: **ENFORCED**.

A report-only helper exists at:

`scripts/show-constrained-decoding-regressions.ps1`

It reads the existing result JSON and prints regression labels, gain labels, and diagnostic samples without rerunning ONNX.

Production `LabelAwareMaskingEngine` remains unchanged.

---

## 12. ONNX Runtime Upgrade Policy

Current Java dependency:

`com.microsoft.onnxruntime:onnxruntime_gpu:1.23.2`

A newer ONNX Runtime version must not be adopted solely because it is newer.

Upgrade procedure:

1. create evaluation branch,
2. update ONNX Runtime,
3. rerun tokenizer parity,
4. rerun ONNX argmax parity,
5. rerun decoded-span parity,
6. rerun hybrid detection checks,
7. rerun security corpus,
8. rerun runtime smoke,
9. benchmark CPU,
10. benchmark GPU,
11. benchmark cold start,
12. benchmark memory,
13. scan dependencies/CVEs,
14. compare results with current release.

Only upgrade if correctness is preserved and security/performance results are acceptable.

---

## 13. Fail-Safe Runtime Requirements

Raw sensitive input must never be returned as an error fallback.

Current fail-closed marker:

`[SECURELOGX_REDACTED_PROCESSING_FAILURE]`

Production runtime must additionally define:

- batch failure behavior,
- inference timeout behavior,
- tokenizer failure behavior,
- model load failure behavior,
- backpressure,
- bounded queues,
- dropped-log policy,
- retry policy,
- metrics,
- audit events,
- graceful shutdown,
- interruption behavior,
- unexpected outer-exception behavior.

No failure path may emit the original unmasked message.

### Long-input / tokenizer-window policy

**Current status: VALIDATED FOR RUNTIME SAFETY AND FULL-CORPUS QUALITY**

ML-v1.3 currently has a validated maximum sequence length of 384 tokens.

The tokenizer now reports whether input was truncated and the last covered character offset. An ML-routed message that exceeds the validated tokenizer window must **fail closed for the entire message** and emit only:

`[SECURELOGX_REDACTED_PROCESSING_FAILURE]`

It must not emit the partially analyzed prefix or the unseen tail.

Runtime metrics retain `truncatedFailClosedItems` as the fallback counter for tokenizer implementations that cannot produce complete windows.

A first overlapping-window implementation now exists:

- tokenize the complete message into WordPieces with original character offsets,
- split content into windows of at most the frozen model sequence limit,
- use 64 content-token overlap between adjacent windows,
- run ONNX on every window,
- decode spans directly in original-text coordinates,
- merge overlapping spans of the same entity type across windows,
- run deterministic/ML conflict resolution once per original log record,
- track windowed records and total ML inference windows separately.

New runtime metrics:

- `windowedMlItems`,
- `mlInferenceWindows`,
- `averageWindowsPerMlItem`,
- `truncatedFailClosedItems` remains a fallback safety counter.

The overlapping-window runtime path is now validated for the current safety suite.

Validated runtime result:

- compile: success,
- hybrid detection checks: 25/25 passed,
- security audit: 6,463 records,
- unsafe bypass: 0,
- deterministic overmask: 0,
- ALLOW/gold conflicts: 0,
- runtime: 11 records,
- deterministic-only: 6,
- ML-routed records: 5,
- windowed ML records: 1,
- ML inference windows: 11,
- truncated fail-closed records: 0,
- processing failures: 0,
- negative technical IP preserved,
- sensitive tail value beyond the first model window was masked.

The architecture-quality benchmark now scores the previous 581-record over-window cohort through the same 64-token overlapping-window policy. The full 6,463-record comparison completed with zero excluded records and zero M0-to-H1 regressions.

Silent truncation remains prohibited.

Architecture-quality benchmarks must not score incomplete inference.

The current D0/M0/H1 comparison therefore uses the validated overlapping-window path for long records:

- all 6,463 non-sealed records are scored,
- 581 records use multiple inference windows,
- zero records are excluded,
- M0 and H1 reuse the same merged windowed ML predictions,
- windowed-record and inference-window counts remain explicit in the report.

Fail-closed behavior remains the safety fallback if a tokenizer implementation cannot produce complete windows.

---

## 14. Production Module Architecture

Target modular design:

```text
securelogx-core
    structure parsing
    deterministic evidence
    resolver
    policy
    masking
    common API

securelogx-ml
    tokenizer
    ONNX inference
    model lifecycle
    contextual detection

securelogx-log4j2
    Log4j2 integration

securelogx-spring-boot-starter
    auto-configuration
    configuration properties
    health/metrics integration

optional future modules
    Kafka
    SIEM/export adapters
```

The ML component should eventually support lazy initialization where practical.

A Java application that can operate entirely with approved deterministic policy should not be forced to initialize a heavyweight model unless its configured protection requirements demand it.

---

## 15. Dependency and Supply-Chain Security

Dependency security is a release-blocking requirement.

### Scope

Scan:

- direct Maven dependencies,
- transitive Maven dependencies,
- ONNX Runtime,
- native libraries,
- tokenizer dependencies,
- Spring integrations,
- Log4j integrations,
- Kafka dependencies,
- JSON libraries,
- build plugins,
- GitHub Actions dependencies,
- container images if distributed.

### Required controls

- SBOM generation using CycloneDX or equivalent,
- OWASP Dependency-Check or equivalent vulnerability scan,
- GitHub dependency/Dependabot monitoring,
- CodeQL/static analysis where applicable,
- dependency tree capture,
- license report,
- signed/reproducible artifacts where practical.

### Release policy

```text
Critical vulnerability:
    release blocked

High vulnerability:
    release blocked unless formally documented as false-positive
    or non-exploitable in the shipped configuration

Medium vulnerability:
    explicit review required

Low vulnerability:
    documented and scheduled if remediation is appropriate

Snapshot dependency:
    prohibited for 1.0 release

Unknown/unmaintained dependency:
    explicit review required
```

Public release wording should be:

> **No known Critical or High severity vulnerabilities were identified in the shipped dependency set at the time of release.**

Do not claim permanent or absolute "zero vulnerabilities."

---

## 16. Production-Grade Exit Criteria

### Security

- critical raw-data leakage = 0,
- unsafe deterministic bypass = 0,
- incorrect ALLOW = 0,
- high-risk recall approximately 99%+,
- partial high-risk masking extremely low,
- failure-path raw leakage = 0.

### Performance

- measured ML invocation on realistic traffic,
- acceptable p50/p95/p99,
- sustained throughput,
- bounded memory,
- predictable overload degradation,
- measured CPU and GPU behavior.

### Engineering

- Java 21 baseline,
- reproducible build,
- automated CI,
- Java/ONNX parity,
- integration tests,
- concurrency tests,
- load tests,
- soak tests,
- failure injection,
- documented configuration.

### Product

- Maven-publishable artifacts,
- Spring Boot starter,
- Log4j2 integration,
- optional/lazy ML where practical,
- metrics and observability,
- deployment guide,
- API documentation,
- compatibility policy.

### Supply Chain

- SBOM,
- CVE scan,
- license scan,
- transitive dependency review,
- release-blocking security gate.

---

## 17. Delivery Roadmap

```text
CURRENT
Hybrid safety baseline
Java/ONNX parity
Fail-closed behavior
        |
        v
Phase 1
Production-routing diagnostics and realistic routing corpus
        |
        v
Phase 2
D0 / M0 / H1 / H2 architecture benchmark
+ modern safety metrics
        |
        v
Phase 3
Overlapping-window inference for >384-token records
+ offset rebasing / span merge / boundary tests
VALIDATED
        |
        v
Phase 4
M0 vs H1 latency / throughput / routing-cost benchmark
VALIDATED (engineering benchmark)
        |
        v
Phase 5
Native-aware memory baseline + lifecycle / retained-growth gate
CAP=4 MEMORY VALIDATED; ADAPTIVE BATCH PERFORMANCE RERUN PENDING
        |
        v
Phase 6
Constrained BIO decoding experiment
C1-S QUALITY GATE PASSED; PRODUCTION-PATH VALIDATION IN PROGRESS
        |
        v
Phase 7
Runtime/concurrency/backpressure hardening
        |
        v
Phase 8
ONNX Runtime upgrade evaluation
        |
        v
Phase 9
Dependency / SBOM / CVE / license gate
        |
        v
Phase 10
Spring Boot + Log4j2 production packaging
        |
        v
Phase 11
Load, soak, failure and adversarial testing
        |
        v
Phase 12
Release candidate
        |
        v
SecureLogX 1.0
```

Recommended milestone terminology:

- Phase 2 complete: **journal/demo ready**
- Phase 7 complete: **runtime beta**
- Phase 10 complete: **product beta**
- Phase 12 complete: **production-grade release candidate**

---

## 18. Design Principles That Must Not Be Violated

1. **Never tune on sealed validation data.**
2. **Never weaken safety checks merely to improve bypass percentage.**
3. **Never let a loose regex override stronger contextual evidence.**
4. **Never expose raw log contents on processing failure.**
5. **Never treat aggregate F1 as sufficient evidence of production safety.**
6. **Never claim performance improvement without measured results.**
7. **Never claim dependency security without a current scan.**
8. **Never change the production model without parity and locked-benchmark evidence.**
9. **Never allow architecture documentation to drift across multiple competing files.**
10. **Record every material architectural change in the change log below.**

---

## 19. Architectural Change Log

### 2026-09-27 — Canonical architecture reference created

**Change**

Created this file as the single SecureLogX architecture and production-design reference.

**Reason**

The project had accumulated design decisions across model work, Java parity, hybrid routing, runtime validation, research discussions, and production planning. A single maintained reference is needed to prevent architectural drift.

### 2026-09-27 — Hybrid design retained; evaluation expanded

**Change**

Retained the current deterministic-evidence + contextual-ML + resolver architecture as the production candidate. Added a recall-first proposal + contextual Redact/Keep cascade as an experimental benchmark rather than replacing the current design.

**Reason**

Recent research supports cascade architectures and context-aware review, but does not yet establish that a heavier multi-stage cascade is preferable for short, high-volume Java application logs.

### 2026-09-27 — Evaluation expanded beyond entity F1

**Change**

Added sensitive-character recall, non-sensitive-character redaction rate, whole-record safety, candidate recall, perturbation robustness, ML invocation rate, latency, throughput, CPU, and memory as first-class metrics.

**Reason**

Production safety and operational usefulness cannot be demonstrated by micro/macro F1 alone.

### 2026-09-27 — Security corpus separated from production-routing corpus

**Change**

The existing 6,463-record NER-heavy audit corpus is designated as a security corpus, not as the primary ML-invocation benchmark. A separate realistic production-routing corpus is required.

**Reason**

The security corpus intentionally contains many contextual sensitive spans and therefore over-represents workloads that should invoke ML.

### 2026-09-27 — Dependency security made release-blocking

**Change**

Added SBOM, CVE scanning, license analysis, transitive dependency review, and release-blocking rules for Critical/High vulnerabilities.

**Reason**

SecureLogX is intended for security-sensitive production environments; dependency and native-runtime vulnerabilities must be treated as part of the product security boundary.

### 2026-09-27 — ONNX Runtime upgrades require benchmarked migration

**Change**

Future ONNX Runtime upgrades must pass parity, hybrid safety, performance, memory, and vulnerability checks before adoption.

**Reason**

A newer runtime can alter inference behavior, native dependencies, memory use, CPU/GPU behavior, or security posture. Version upgrades must therefore be evidence-driven.

---

### 2026-09-27 — Production-routing benchmark implemented

**Change**

Added a dedicated Java-side production-routing benchmark with four reproducible traffic scenarios and a standalone runner.

**Reason**

The 6,463-record NER-heavy corpus is appropriate for safety evaluation but is not representative enough to estimate normal production ML invocation. A separate workload is required to measure the fast path without weakening the locked safety corpus.

**Evidence / benchmark**

Pending first local execution of `scripts/run-production-routing-benchmark.ps1`.

**Affected modules**

- `securelogx-core`
- `scripts`
- `reports/production-routing-benchmark`

**Backward-compatibility impact**

None. This change adds evaluation tooling only; production routing behavior is unchanged.

---

### 2026-09-27 — Strict metadata-only fast path added

**Change**

Added a narrow routing fast path for structured operational records containing only approved metadata fields, including a recognized timestamp/level/service prefix form.

**Reason**

The first production-routing benchmark passed all safety checks but routed 75.80% of normal-operation Scenario A records to ML. Inspection of the gate showed that records with no deterministic sensitive evidence were always routed to ML, including ordinary metadata-only logs. Running contextual inference on those records provides little security value.

**Evidence / benchmark**

Pre-change production-routing baseline:

- Scenario A ML invocation: 75.80%
- Overall ML invocation: 70.90%
- unsafe bypass: 0
- uncovered sensitive values: 0
- negative technical overmask: 0

Post-change evidence is pending rerun of both the locked hybrid safety suite and the production-routing benchmark.

**Affected modules**

- `securelogx-core`
- production routing benchmark
- hybrid detection regression suite

**Backward-compatibility impact**

Routing behavior changes for a narrow set of approved metadata-only log records. No public API change.

---

### 2026-09-27 — Phase 1 routing target validated

**Change**

Marked the strict metadata-only fast path and production-routing benchmark as validated for the current engineering workload. Stopped further optimization toward lower ML invocation and advanced the roadmap to architecture-quality comparison.

**Reason**

The post-change benchmark reduced Scenario A ML invocation from 75.80% to 5.00% while preserving all current safety assertions. Ordinary-safe, deterministic-sensitive, and negative-technical records remained entirely on the fast path, while contextual-sensitive and adversarial records remained entirely on ML.

**Evidence / benchmark**

- Scenario A: 5.00% ML / 95.00% fast path
- Scenario B: 15.00% ML / 85.00% fast path
- Scenario C: 35.00% ML / 65.00% fast path
- Scenario D: 60.00% ML / 40.00% fast path
- unsafe bypass: 0
- uncovered sensitive values: 0
- negative technical overmask: 0
- locked hybrid safety validation: passed

**Affected modules**

- routing policy status only; no additional production code change in this entry

**Backward-compatibility impact**

None.

---

### 2026-09-27 — Phase 2 D0/M0/H1 comparison harness added

**Change**

Added a non-sealed Java architecture-quality benchmark for deterministic-only (D0), frozen ML-only (M0), and current hybrid (H1). H2 remains explicitly unimplemented.

**Reason**

Phase 1 routing efficiency is now validated. The next decision is whether H1 materially improves protection/overmask tradeoffs relative to deterministic-only and ML-only baselines. The comparison must use modern safety metrics rather than aggregate F1 alone.

**Evidence / benchmark**

Pending first local execution of `scripts/run-architecture-comparison.ps1`.

The harness is designed to report:

- sensitive-character recall,
- non-sensitive-character redaction rate,
- full-span recall,
- exact-boundary recall,
- partial and missed spans,
- high-risk full-span recall,
- sensitive-record full coverage,
- whole-record perfect redaction,
- logical ML invocation rate.

M0 predictions are generated once on every record and reused by H1 where the production gate logically invokes ML, ensuring identical ML predictions across those two architectures.

**Affected modules**

- `securelogx-core` validation tooling
- `scripts`
- `reports/architecture-comparison`

**Backward-compatibility impact**

None. Production routing/runtime behavior is unchanged.

---

### 2026-09-27 — H1 quality regression identified; resolver diagnosis added

**Change**

Recorded the first D0/M0/H1 comparison and added focused M0-to-H1 regression diagnostics to the Phase 2 benchmark. Production resolver behavior remains unchanged pending evidence.

**Reason**

H1 showed lower full-span and high-risk full-span recall than M0 despite using the same frozen ML predictions and invoking ML on 100% of the NER-heavy comparison corpus. This isolates the likely problem to deterministic/resolver interaction rather than routing.

**Evidence / benchmark**

Initial results:

- D0 sensitive-character recall: 29.5337%
- M0 sensitive-character recall: 94.8855%
- H1 sensitive-character recall: 94.7886%
- M0 full-span recall: 92.5633%
- H1 full-span recall: 91.6107%
- M0 high-risk full-span recall: 98.4462%
- H1 high-risk full-span recall: 93.7450%
- M0 whole-record perfect redaction: 92.3410%
- H1 whole-record perfect redaction: 90.5462%
- M0 and H1 non-sensitive-character redaction: 0.2126%
- H1 logical ML invocation on this corpus: 100%

**Affected modules**

- `securelogx-core` validation tooling only
- canonical architecture reference

**Backward-compatibility impact**

None. Production resolver behavior has not yet changed.

---

### 2026-09-27 — Resolver changed so ML may extend deterministic MASK spans

**Status**

PENDING VALIDATION

**Change**

Changed resolver precedence so deterministic MASK evidence no longer suppresses an overlapping ML MASK span. Deterministic MASK remains protective, while the ML span may extend the masked interval. Deterministic ALLOW remains authoritative for overlapping ML suppression.

**Reason**

Regression diagnostics found:

- 120 spans fully protected by M0 but not H1,
- all 120 were high-risk `AUTH_TOKEN`,
- all were associated with `MASK:AUTH_TOKEN:explicit-bearer-token`,
- 32 spans were fully protected by H1 but not M0.

This indicates the previous resolver rule converted larger ML token spans into shorter deterministic spans.

**Evidence / benchmark**

Pre-change:

- M0 high-risk full-span recall: 98.4462%
- H1 high-risk full-span recall: 93.7450%
- M0 full-span recall: 92.5633%
- H1 full-span recall: 91.6107%
- M0/H1 non-sensitive-character redaction: 0.2126%

Post-change validation is pending.

**Affected modules**

- `HybridContextResolver`
- `HybridDetectionCheck`
- architecture-quality evaluation

**Backward-compatibility impact**

Masking may expand when both deterministic MASK and overlapping ML MASK evidence are present. Public APIs are unchanged.

---

### 2026-09-27 — Partial ALLOW veto and silent tokenizer truncation closed

**Status**

VALIDATED FOR SAFETY

**Change**

Two safety holes were closed:

1. deterministic ALLOW no longer suppresses a merely overlapping ML span; it must fully contain the ML span and match the same entity type,
2. the tokenizer now exposes truncation metadata, and ML-routed messages beyond the 384-token validated window fail closed for the entire message.

The architecture comparison harness also refuses to score truncated records.

**Reason**

The resolver used the same overlap-based veto mechanism for ALLOW that previously caused deterministic MASK to shrink larger ML protection spans. Although the current labeled corpus showed zero ALLOW/gold conflicts, the mechanism was unsafe in principle.

Separately, `PureJavaTokenizer` silently stopped after the configured sequence limit, meaning tail characters could remain unseen by ML without triggering a fail-closed response.

**Evidence / benchmark**

Pre-change diagnostic:

- 120 M0-to-H1 regressions,
- all high-risk `AUTH_TOKEN`,
- all associated with `MASK:AUTH_TOKEN:explicit-bearer-token`,
- zero observed ALLOW/gold conflicts on the current corpus,
- silent truncation path existed for messages beyond the validated 384-token model window.

Post-change locked safety validation:

- compile: passed,
- deterministic/resolver checks: 25/25 passed,
- security corpus: 6,463 records,
- unsafe bypass: 0,
- deterministic overmask: 0,
- ALLOW/gold conflicts: 0,
- runtime: 11 cases,
- deterministic-only routes: 6,
- ML routes: 4,
- truncated fail-closed routes: 1,
- processing failures: 0,
- negative technical IP preserved.

The resolver/truncation changes are therefore validated for safety. Phase 2 architecture-quality rerun remains required before declaring the resolver quality issue closed.

**Affected modules**

- `HybridContextResolver`
- `TokenizedInput`
- `PureJavaTokenizer`
- `ONNXDynamicInferenceEngine`
- `HybridRuntimeStats`
- `HybridDetectionCheck`
- `HybridRuntimeCheck`
- `ArchitectureComparisonBenchmark`

**Backward-compatibility impact**

Long ML-routed messages that previously could be partially analyzed now fail closed until windowed inference is implemented. Partial-overlap deterministic ALLOW may no longer suppress a larger ML masking span.

---

### 2026-09-27 — Resolver and truncation safety changes validated

**Status**

VALIDATED FOR SAFETY

**Change**

Promoted the stricter resolver overlap policy and tokenizer-truncation fail-closed behavior from pending validation to validated-for-safety status.

**Reason**

The complete hybrid validation suite passed after the changes, including the new partial-ALLOW and long-input regression checks.

**Evidence / benchmark**

- compile: success
- hybrid detection checks: 25/25 passed
- security audit: 6,463 records
- unsafe bypass: 0
- deterministic overmask: 0
- ALLOW/gold conflicts: 0
- runtime cases: 11
- deterministic-only: 6
- ML: 4
- truncated fail-closed: 1
- processing failures: 0
- negative technical IP preserved

**Affected modules**

No additional implementation change in this entry; documentation status only.

**Backward-compatibility impact**

None beyond the previously documented resolver and long-input behavior changes.

---

### 2026-09-27 — Architecture comparison separates over-window truncation cohort

**Status**

IMPLEMENTED; QUALITY RERUN PENDING

**Change**

Changed the Phase 2 D0/M0/H1 benchmark so over-window records no longer abort the entire comparison. Records exceeding the frozen 384-token model window are excluded from all three quality denominators and reported separately.

**Reason**

The first post-safety architecture rerun correctly aborted on a real dataset record with:

- covered characters: 933
- total characters: 1,451

Scoring M0 or H1 on that record would use incomplete model visibility. Aborting the whole experiment, however, prevents valid comparison of the remaining records.

The fair interim policy is therefore:

- same non-truncated subset for D0/M0/H1,
- explicit truncation counts globally and by source,
- no silent exclusion,
- runtime remains fail-closed on over-window ML-routed records.

**Evidence / benchmark**

The failed comparison compiled successfully and stopped before producing D0/M0/H1 quality metrics. No NER data was modified.

**Affected modules**

- `ArchitectureComparisonBenchmark`
- canonical architecture reference

**Backward-compatibility impact**

None. Production runtime behavior is unchanged.

---

### 2026-09-27 — Phase 2 resolver quality validated; long-window support promoted

**Status**

VALIDATED ON SCORABLE COHORT

**Change**

Promoted the corrected H1 resolver behavior to validated architecture-quality status for records that fit within the frozen 384-token model window. Promoted overlapping-window inference to the next implementation priority.

**Reason**

On 5,882 scorable records, H1 exactly matched M0 on every reported quality metric and produced zero M0-to-H1 span regressions.

At the same time, 581 of 6,463 records exceeded the frozen tokenizer window. Runtime fail-closed handling is safe, but excluding that cohort from quality scoring means complete long-record support remains unvalidated.

**Evidence / benchmark**

Scorable cohort:

- records: 5,882
- M0/H1 sensitive-character recall: 99.0918%
- M0/H1 non-sensitive-character redaction: 0.2325%
- M0/H1 full-span recall: 98.1830%
- M0/H1 high-risk full-span recall: 99.5039%
- M0/H1 whole-record perfect redaction: 96.1238%
- M0-to-H1 full-span regressions: 0
- high-risk regressions: 0
- H1-only full-span gains: 0

Truncation cohort:

- excluded records: 581
- original_test_regression: 293
- standard_dev: 288

**Affected modules**

No new production code in this entry. Roadmap and architecture status updated.

**Backward-compatibility impact**

None.

---

### 2026-09-27 — Overlapping-window ML inference implemented

**Status**

PENDING VALIDATION

**Change**

Implemented overlapping-window tokenization and runtime ONNX inference for ML-routed records that exceed the frozen 384-token model window.

Current design:

- absolute original-text WordPiece offsets,
- 64-token content overlap,
- multiple ONNX windows per long record,
- same-entity overlapping span merge,
- one final resolver/policy pass per original record,
- explicit window-level runtime metrics.

**Reason**

The Phase 2 comparison identified 581 of 6,463 records that could not be validly scored through a single 384-token window. Fail-closed behavior was safe but not an acceptable final production behavior for long logs.

**Validation requirement**

The hybrid runtime suite now contains a long ML-routed message with a sensitive SSN beyond the first model window. Validation must show:

- tail value masked,
- no processing failure,
- one windowed ML record,
- ML inference windows greater than ML record count,
- zero truncation fail-closed records.

Only after that passes should the architecture comparison be moved from truncation exclusion to full-cohort windowed scoring.

**Affected modules**

- `TokenizerEngine`
- `ParallelTokenizer`
- `PureJavaTokenizer`
- `ONNXDynamicInferenceEngine`
- `HybridRuntimeStats`
- `HybridRuntimeCheck`

**Backward-compatibility impact**

Long ML-routed messages may now be processed through multiple model windows instead of failing closed solely because of sequence length. Public API signatures remain compatible through the default tokenizer-window method.

---

### 2026-09-27 — Windowed runtime validation harness wiring corrected

**Status**

RERUN REQUIRED

**Change**

Changed `HybridRuntimeCheck` to pass the concrete `ParallelTokenizer` object into `ONNXDynamicInferenceEngine.runBatch(...)` instead of passing the method reference `tokenizer::tokenize`.

**Reason**

The method reference implemented only the single-record `tokenize(...)` contract and therefore used the interface default `tokenizeWindows(...)`, which returns a single tokenized input. For an over-window record that single input was correctly marked truncated, causing the engine to fail closed.

The production `SecureLogX` and `MaskingConsumer` paths already pass the tokenizer object directly and therefore retain the overridden overlapping-window implementation.

**Evidence / benchmark**

Failed validation showed:

- compilation success,
- runtime warning that the tokenizer returned an incomplete window,
- fail-closed fallback,
- `HybridRuntimeCheck` aborted before validating windowed masking.

This run does not invalidate the overlapping-window design because the windowing override was not invoked by the validation harness.

**Affected modules**

- `HybridRuntimeCheck`
- canonical architecture reference

**Backward-compatibility impact**

None. Production runtime behavior is unchanged.

---

### 2026-09-27 — Windowed runtime validated; full-corpus Phase 2 scoring enabled

**Status**

RUNTIME VALIDATED; FULL-CORPUS QUALITY RERUN PENDING

**Change**

Promoted overlapping-window inference to validated-for-runtime-safety status and changed the Phase 2 architecture benchmark to score all records, including the 581 records that previously exceeded one model window.

The comparison now uses the same 64-content-token overlap policy as the runtime, merges same-entity spans in original character coordinates, and uses identical merged ML predictions for M0 and H1.

**Reason**

The runtime validation successfully processed an ML-required long record with sensitive content beyond the first model window, with zero truncation fail-closed events and zero processing failures.

**Evidence / benchmark**

Validated runtime:

- deterministic-only: 6/11
- ML records: 5/11
- ML invocation: 45.45%
- windowed ML records: 1
- ML inference windows: 11
- truncated fail-closed: 0
- processing failures: 0
- negative technical IP preserved

Full-corpus architecture-quality results are pending.

**Affected modules**

- `ArchitectureComparisonBenchmark`
- canonical architecture reference

**Backward-compatibility impact**

None. This entry changes evaluation methodology only; production windowed runtime behavior was already implemented and validated.

---

### 2026-09-28 — Phase 2 full-corpus H1 architecture quality validated

**Status**

VALIDATED

**Change**

Completed D0/M0/H1 architecture-quality evaluation across the full 6,463-record non-sealed corpus, including all 581 records requiring overlapping-window inference.

**Reason**

The previous scorable-subset result showed H1 matching M0, but complete architecture validation required proving the same property on long records rather than excluding them.

**Evidence / benchmark**

Full corpus:

- input records: 6,463
- scored records: 6,463
- excluded records: 0
- windowed records: 581
- ML inference windows: 7,088

M0:

- sensitive-character recall: 97.4260%
- non-sensitive-character redaction: 0.2335%
- full-span recall: 95.4969%
- high-risk full-span recall: 98.9243%
- whole-record perfect redaction: 93.3622%

H1:

- sensitive-character recall: 97.4497%
- non-sensitive-character redaction: 0.2335%
- full-span recall: 95.5510%
- high-risk full-span recall: 98.9641%
- whole-record perfect redaction: 93.4086%

Regression diagnostics:

- M0 full / H1 not full: 0
- high-risk regressions: 0
- H1 full / M0 not full: 5

**Conclusion**

H1 is non-regressive versus M0 on the complete non-sealed corpus and provides a small measurable protection improvement without increasing character-level over-redaction.

**Affected modules**

No production code change in this entry; architecture status and evidence updated.

**Backward-compatibility impact**

None.

---

### 2026-09-28 — M0 versus H1 performance benchmark added

**Status**

IMPLEMENTED; FIRST RUN PENDING

**Change**

Added a reproducible end-to-end performance comparison between M0 and H1 using the same Scenario A-D workloads already used to validate production routing.

**Reason**

Architecture quality is now validated across the complete 6,463-record corpus. The next product question is whether H1 converts lower ML invocation on realistic traffic into measurable throughput and latency gains without safety regressions.

**Benchmark design**

- 160 sampled records per scenario by default,
- 32-record batches,
- one warm-up phase excluded from measurements,
- 3 measured iterations,
- M0 uses ML on every record,
- H1 uses current production routing and overlapping-window inference,
- p50/p95/p99 batch latency,
- records/second throughput,
- initialization time,
- H1 ML invocation/window metrics,
- speedup ratio.

**Evidence / benchmark**

First local run pending.

**Affected modules**

- `ProductionRoutingBenchmark` exposes its canonical workloads to validation tooling,
- new `PerformanceComparisonBenchmark`,
- new `run-performance-comparison.ps1`.

**Backward-compatibility impact**

None. Production masking/routing behavior is unchanged.

---

### 2026-09-28 — M0 versus H1 performance operating envelope validated

**Status**

VALIDATED ENGINEERING BENCHMARK

**Change**

Recorded the first steady-state M0-versus-H1 performance comparison on the canonical production-routing Scenario A-D workloads.

**Evidence / benchmark**

| Scenario | M0 throughput | H1 throughput | Speedup | H1 ML invocation |
|---|---:|---:|---:|---:|
| A | 20.286 rec/s | 130.770 rec/s | 6.446x | 5.00% |
| B | 21.181 rec/s | 73.737 rec/s | 3.481x | 15.00% |
| C | 26.161 rec/s | 50.175 rec/s | 1.918x | 35.00% |
| D | 28.597 rec/s | 28.506 rec/s | 0.997x | 60.00% |

Additional observations:

- H1 batch p50 was about 2.7 ms in A/B, ~248 ms in C, and ~1,305 ms in D.
- M0 initialization: 1,458 ms.
- H1 initialization: 1,100 ms.
- fail-closed records: 0 in every scenario.

**Interpretation**

H1 provides large throughput gains when the deterministic fast path keeps ML invocation low. The gain narrows as contextual/adversarial traffic increases and is effectively neutral at 60% ML invocation in the high-risk stress scenario.

This establishes an operating envelope rather than a universal performance advantage.

**Affected modules**

No production code change in this entry; benchmark evidence and architecture status updated.

**Backward-compatibility impact**

None.

---

### 2026-09-28 — Native ONNX memory lifecycle and measurement made explicit

**Status**

INSTRUMENTED; BASELINE RUN PENDING

**Change**

Added an explicit native-memory contract, fixed ONNX lifecycle cleanup, and added a runtime memory benchmark that separates JVM-managed memory from whole-process/native memory.

Implementation changes:

- `OrtSession.SessionOptions` is closed after session construction,
- `OrtSession` is closed during idempotent engine shutdown,
- cached-clock interruption now terminates the updater thread cleanly,
- M0 benchmark session options are also closed,
- runtime memory snapshots include heap, non-heap, direct/mapped buffers, process committed virtual memory, OS working set/private/virtual memory where available, and GPU process memory when attributable.

**Reason**

ONNX Runtime allocations occur outside Java heap accounting. Heap-only monitoring can therefore miss native retention, JNI/native allocator growth, or GPU memory pressure.

**Benchmark**

Added:

- `RuntimeMemorySnapshot`
- `RuntimeMemoryBenchmark`
- `scripts/run-runtime-memory-benchmark.ps1`

The benchmark establishes post-GC settled checkpoints across Scenario A-D workloads and before/after engine shutdown. Hard memory budgets will be set after repeated measurements on representative CPU/GPU hardware.

**Affected modules**

- `ONNXDynamicInferenceEngine`
- `CachedClock`
- performance validation tooling
- architecture memory/release policy

**Backward-compatibility impact**

No public API change. Engine shutdown now deterministically releases its ONNX session.

---

### 2026-09-28 — Native memory baseline validates session cleanup

**Status**

BASELINE VALIDATED; SOAK/RESTART STABILITY PENDING

**Change**

Recorded the first native-aware CPU memory baseline after explicit ONNX lifecycle cleanup.

**Evidence / benchmark**

Key checkpoints:

- heap remained approximately 12-13 MiB after model initialization and inference,
- process working set rose from 103.73 MiB after tokenizer initialization to 564.32 MiB after ONNX engine initialization,
- process private memory rose from 221.03 MiB to 668.71 MiB after engine initialization,
- after warm-up settled: 549.16 MiB working set / 638.00 MiB private,
- after engine shutdown settled: 142.76 MiB working set / 218.79 MiB private.

Per-scenario heap growth was negligible. Native process growth was front-loaded in Scenarios A/B and then largely flattened in C/D.

**Interpretation**

The current evidence supports two conclusions:

1. the model/session footprint is primarily native and must not be managed using JVM heap metrics alone,
2. explicit `OrtSession.close()` is releasing the dominant private-memory footprint on shutdown.

A single run does not establish leak-free steady-state behavior. Repeated session restart and soak measurements remain required before fixed release thresholds are declared.

**Affected modules**

No additional production behavior change in this entry; architecture evidence updated.

**Backward-compatibility impact**

None.

---

### 2026-09-28 — Repeated ONNX restart/native-memory soak added

**Status**

IMPLEMENTED; FIRST RUN PENDING

**Change**

Added a repeated memory stability benchmark that exercises fresh ONNX session creation, warm-up, Scenario A-D traffic, long-window inference, explicit session shutdown, and post-GC settled checkpoints across multiple cycles.

**Reason**

The first memory baseline showed strong shutdown recovery but also front-loaded native growth during early workloads. Multiple restart cycles are required to distinguish allocator stabilization from retained native growth.

**Default workload**

- 4 create/warm-up/infer/shutdown cycles,
- 80 sampled records per Scenario A-D per cycle,
- 8 long-window records per cycle,
- no fail-closed output allowed,
- compare each post-shutdown checkpoint against tokenizer baseline and the previous cycle.

**Interpretation target**

Desired shape:

- post-warm-up/process-private memory reaches a repeatable plateau,
- long-window workload does not cause cycle-over-cycle retained growth,
- post-shutdown private memory repeatedly returns near the tokenizer baseline,
- no monotonic restart-to-restart increase.

**Affected modules**

- new `RuntimeMemoryRestartSoakBenchmark`,
- new `run-runtime-memory-restart-soak.ps1`,
- architecture memory gate.

**Backward-compatibility impact**

None. Evaluation tooling only.

---

### 2026-09-28 — Window-expanded ONNX native memory bounded with micro-batches

**Status**

IMPLEMENTED; VALIDATION PENDING

**Change**

Added a configurable hard cap on the number of expanded tokenizer windows submitted to any single ONNX inference call.

New configuration:

`securelogx.model.maxInferenceWindowsPerBatch=8`

The runtime now processes expanded long-record windows in bounded micro-batches, accumulates decoded spans in original character coordinates, and runs resolver/policy once per original record after all windows complete.

New runtime metrics:

- `onnxInferenceCalls`,
- `maxInferenceWindowsPerCallObserved`,
- existing `mlInferenceWindows`.

**Reason**

A four-cycle restart soak showed healthy session cleanup but an unacceptable active-session native-memory high-water mark after long-window inference:

- stable warm-up private memory: ~639-642 MiB,
- post-window private memory: ~2.82-2.88 GiB,
- post-shutdown private memory: ~218-223 MiB.

This is not evidence of a restart leak; it is evidence that unbounded window expansion can cause ONNX native arena growth while the session remains alive.

**Validation requirement**

Rerun:

1. locked hybrid validation,
2. repeated restart/native-memory soak.

Required invariants:

- `maxInferenceWindowsPerCallObserved <= 8`,
- long-window sensitive tail remains masked,
- fail-closed count remains 0,
- resolver/gate safety remains unchanged,
- post-shutdown private memory continues to recover,
- post-window active-session private memory is materially lower than the previous ~2.8 GiB baseline.

**Affected modules**

- `SecureLogXConfig`,
- `ONNXDynamicInferenceEngine`,
- `HybridRuntimeStats`,
- `HybridRuntimeCheck`,
- memory benchmark reporting,
- development configuration.

**Backward-compatibility impact**

No public API change. Long-window inference may use more ONNX calls with smaller window batches in exchange for bounded native-memory pressure.

---

### 2026-09-28 — CPU native-memory window cap validated

**Status**

VALIDATED ON CPU

**Change**

Validated the 8-window ONNX micro-batch cap against the locked hybrid safety suite and the four-cycle restart/native-memory soak.

**Evidence / benchmark**

Safety/runtime:

- 25/25 detection checks passed
- 6,463-record gate audit passed
- 0 unsafe bypass
- 0 deterministic overmask
- 0 ALLOW/gold conflicts
- 11 runtime records
- 11 ML windows
- 2 ONNX calls
- max windows per ONNX call: 8
- 0 fail-closed records
- 0 processing failures

Memory:

- prior post-window private memory: ~2.82-2.88 GiB
- bounded-window post-window private memory: ~1.17 GiB
- reduction: ~58%
- warm-up private memory: ~638-642 MiB
- post-shutdown private memory: ~218-223 MiB
- shutdown working-set delta stabilizes around +26 to +31 MiB

**Conclusion**

The CPU/native lifecycle is stable for the current engineering workload, and window micro-batching materially bounds ONNX native-memory pressure without changing masking safety behavior.

**Affected modules**

No new implementation change in this entry; architecture status/evidence updated.

**Backward-compatibility impact**

None.

---

### 2026-09-28 — Experimental strict-BIO constrained decoder added

**Status**

IMPLEMENTED; FULL-CORPUS RUN PENDING

**Change**

Added a strict-BIO Viterbi decoder and a full-corpus comparison against the current ML-v1.3 token-argmax decoder.

**Design**

C1 disallows invalid BIO paths while using the same frozen logits:

- no initial `I-X`,
- `I-X` only after `B-X` or `I-X`,
- `O` / `B-X` unrestricted from valid prior states.

The implementation is optimized to O(tokens x labels) and runs as an experimental validation path only.

**Promotion requirements**

C1 must preserve high-risk recall, avoid material sensitive-character regression, avoid increased non-sensitive redaction, and show favorable span/boundary diagnostics before any production decoder change.

**Affected modules**

- new `BioConstrainedDecoder`,
- new `ConstrainedDecodingBenchmark`,
- new `run-constrained-decoding-benchmark.ps1`,
- architecture evaluation plan.

**Backward-compatibility impact**

None. Production decoding is unchanged.

---

### 2026-09-28 — C1 full-corpus result promising; CPU memory target tightened

**Status**

C1 PROMISING / REVIEW REQUIRED; MEMORY CAP=4 VALIDATION PENDING

**Change**

Recorded the first full-corpus strict-BIO Viterbi result and tightened the CPU inference-window micro-batch default from 8 to 4 to pursue a sub-1-GiB long-window stress target.

**C1 evidence**

- 6,463 records scored
- 581 windowed records
- sensitive-character recall: 97.4243% vs 97.4260% M0
- non-sensitive-character redaction: 0.2325% vs 0.2335%
- full-span recall: 95.8216% vs 95.4969%
- exact-boundary recall: 94.9773% vs 94.4144%
- high-risk full-span recall: 99.0837% vs 98.9243%
- whole-record perfect redaction: 93.6407% vs 93.3622%
- M0-full/C1-not-full: 3
- high-risk regressions: 0
- C1-full/M0-not-full: 33
- exact-boundary losses/gains: 8/60
- illegal argmax BIO transitions: 253
- token labels changed: 382 (0.3999% of argmax entity tokens)

**Memory decision**

The validated 8-window cap reduced post-window private memory from ~2.8 GiB to ~1.17 GiB, but the desired CPU engineering target is now <1,024 MiB, preferably <950 MiB. The CPU default is therefore 4 windows per ONNX call pending safety/performance/memory rerun.

**Affected modules**

- development/config default for `maxInferenceWindowsPerBatch`,
- constrained-decoding evaluation documentation,
- report-only constrained regression inspection helper.

**Backward-compatibility impact**

Production decoder is unchanged. Window-heavy CPU inference may use more ONNX calls because the default window micro-batch cap is lower.

---

### 2026-09-28 — CPU cap=4 meets sub-1-GiB target; C1 regressions reviewed

**Status**

CPU MEMORY VALIDATED; C1-S SAFETY-SUPPLEMENT EXPERIMENT NEXT

**Memory evidence**

At 4 windows per ONNX call:

- peak long-window private memory: ~908 MiB,
- hard target <1,024 MiB: PASS,
- preferred target <950 MiB: PASS,
- warm-up private memory: ~640-644 MiB,
- post-shutdown private memory: ~221-223 MiB,
- no cycle-to-cycle private-memory accumulation,
- locked safety/runtime suite unchanged.

**C1 regression review**

The three M0-full/C1-not-full cases are:

- PERSON_NAME: 2,
- STREET_ADDRESS: 1,
- high-risk: 0.

Observed name examples include short spans `Smith` and `Jeff`. C1 gains remain substantially larger and include addresses, names, DOB, API keys, phones, SSN, email, IBAN, and business identifiers.

**Decision**

Do not promote plain C1 yet. Evaluate a safety-supplemented C1-S decoder that restores only M0 spans for which C1 provides no overlapping protection.

**Backward-compatibility impact**

None. Production decoder remains unchanged. CPU inference-window default remains 4.

---

### 2026-09-28 — C1-S safety-supplement comparison implemented

**Status**

IMPLEMENTED; FULL-CORPUS RERUN PENDING

**Change**

Extended the constrained-decoding benchmark with C1-S, a safety-supplemented BIO-Viterbi output.

C1-S uses C1 as the primary decoder but restores an M0 argmax span whenever C1 does not fully cover that span. It does not run the model again and does not modify production decoding.

**Reason**

Plain C1 produced materially better span/boundary quality but had three M0-full/C1-not-full cases: two PERSON_NAME and one STREET_ADDRESS. A privacy-oriented production decoder should not accept avoidable loss of already-detected sensitive coverage when the same logits can preserve it.

**Promotion target**

C1-S should demonstrate:

- M0-full/C1-S-not-full = 0,
- high-risk regressions = 0,
- sensitive-character recall >= M0,
- full-span and exact-boundary gains close to C1,
- non-sensitive-character redaction not materially worse than M0,
- no extra ONNX calls versus C1.

**Backward-compatibility impact**

None. Benchmark-only experiment; production decoder remains unchanged.

---

### 2026-09-29 — C1-S passes full-corpus quality gate; adaptive batching integrated

**Status**

C1-S PRODUCTION CANDIDATE; PRODUCTION-PATH VALIDATION PENDING

**C1-S full-corpus evidence**

- 6,463 records scored,
- 581 windowed records,
- max 4 windows per benchmark ONNX call,
- sensitive-character recall: 97.5172%,
- non-sensitive-character redaction: 0.2422%,
- full-span recall: 95.8541%,
- exact-boundary recall: 94.9989%,
- high-risk full-span recall: 99.0837%,
- whole-record perfect redaction: 93.5169%,
- partial/missed: 119 / 264,
- M0-full/C1-S-not-full: 0,
- high-risk regressions: 0,
- C1-S-full/M0-not-full: 33,
- exact-boundary losses/gains: 6/60.

**Production integration**

- added shared `BioConstrainedSpanDecoder`,
- added configurable `MlDecoderMode`,
- dev validation profile selects `BIO_VITERBI_SAFETY_SUPPLEMENT`,
- code fallback remains `ARGMAX_LEGACY`,
- production ONNX runtime now uses the same shared decoder implementation as the benchmark,
- full architecture benchmark now includes `H1_C1S`.

**Adaptive memory/performance refinement**

The 4-window memory cap is now applied only to expanded long-record window segments. Ordinary single-window ML records retain the normal runtime batch size. This is intended to preserve the validated <950 MiB long-window memory target without imposing the same micro-batch limit on ordinary ML traffic.

**Next validation**

1. locked hybrid validation with production decoder mode reported as `BIO_VITERBI_SAFETY_SUPPLEMENT`,
2. full architecture comparison including `H1_C1S`,
3. restart memory soak to confirm expanded-window cap remains <=4 and private high-water remains <950 MiB,
4. performance comparison to confirm adaptive batching recovers C/D throughput.

**Backward-compatibility impact**

Decoder behavior is configurable and legacy argmax remains available for rollback. The development validation profile now opts into the production candidate.

---

### 2026-10-01 — H1+C1-S inference architecture reaches production-candidate freeze

**Status**

BEHAVIORAL ARCHITECTURE VALIDATED; PERFORMANCE CLAIMS REQUIRE REPEATABILITY PASS

**Evidence**

- locked hybrid detection/resolver checks passed,
- 6,463-record gate audit: 0 unsafe bypass, 0 deterministic overmask, 0 ALLOW/gold conflicts,
- production runtime decoder: `BIO_VITERBI_SAFETY_SUPPLEMENT`,
- full H1+C1-S architecture comparison: 0 M0 full-span losses, 0 high-risk regressions, 36 full-span gains,
- high-risk full-span recall: 99.0837%,
- long-window private memory: ~905-909 MiB,
- post-shutdown private memory: ~218-224 MiB,
- adaptive performance run: H1 faster than M0 in A-D, fail-closed 0.

**Decision**

Freeze the inference behavior as the production candidate:

- H1 deterministic/contextual routing,
- C1-S BIO-constrained decoding with M0 coverage floor,
- 64-token overlapping windows,
- 4-window cap only for expanded long-record inference,
- fail-closed processing behavior.

Do not freeze or publish exact speedup ratios until an interleaved repeatability benchmark confirms stable measurements.

---

### 2026-10-01 — Appender event contract and sequence ownership defined

**Status**

IMPLEMENTED IN CORE; VALIDATION PENDING

**Change**

Decoupled protection from the legacy `SECURE` severity and defined the
appender-oriented event metadata contract.

**Decisions**

- preserve original TRACE/DEBUG/INFO/WARN/ERROR severity,
- retain `SECURE` only for backward compatibility,
- inspect all severities through H1 when masking is enabled,
- assign a process-wide monotonic `long` sequence at SecureLogX ingress,
- keep `traceId` for correlation rather than ordering,
- preserve the original event timestamp through deterministic/ML/Kafka output,
- add local ingest timestamp separately,
- add process/JVM `instanceId`,
- use `instanceId + seq` as event identity,
- prevent callers from injecting production sequence values through a public
  `process(LogEvent)` path,
- preserve origin `instanceId` when parsing formatted Kafka records.

**Validation change**

The hybrid runtime smoke now uses normal INFO/WARN/ERROR/DEBUG events and checks
that protection still occurs while source severity, event timestamp, sequence,
and instance ID survive the masking path. It also parses a formatted protected
line back through `LogEvent.fromRaw(...)` and verifies that severity, timestamp,
sequence, and originating `instanceId` survive the round trip.

**Performance runner fix**

The PowerShell performance runner now defaults to 6 rounds and rejects odd
round counts, matching the interleaved benchmark contract. This fixes the
previous `iterations must be even` failure caused by the runner still passing
3 iterations.

---

### 2026-10-01 — Performance runner repaired and envelope work equalized

**Status**

IMPLEMENTED; LOCAL COMPILE/RUNTIME VALIDATION NEXT

**Changes**

- PowerShell performance runner default changed from 3 to 6 rounds.
- Odd performance-round counts are rejected explicitly.
- M0/H1 measured execution order alternates to reduce order/JIT/thermal bias.
- M0 and H1 now both construct normal INFO-level events and perform the same
  canonical event-envelope formatting before checksum/output accounting.
- Hybrid runtime validation now exercises standard application severities and
  verifies formatted metadata round-trip preservation.

**Reason**

The interleaved Java benchmark required an even round count, but the existing
PowerShell runner still supplied 3. The previous M0 path also omitted final
event-envelope formatting, making end-to-end performance comparison slightly
asymmetric.

---

### 2026-10-03 — Existing Log4j2 RewriteAppender integration hardened

**Status**

IMPLEMENTED; LOCAL COMPILE/TEST PENDING

**Decision**

Use Apache Log4j2's existing `RewriteAppender` as the integration point.
SecureLogX owns only the rewrite policy and security/configuration contract.

**Hardening changes**

- root logger must explicitly reference the SecureLogX rewrite,
- direct sibling appenders are treated as bypass violations,
- AsyncLogger/AsyncRoot/upstream async selectors are rejected when pre-queue
  sanitization is required,
- event sequence and instance identity survive RewritePolicy recreation,
- any field-level masking failure now fails the entire LogEvent closed,
- library-owned JVM shutdown hook removed to avoid a classloader retention
  root,
- model-free JUnit tests added for structured masking, fail-closed behavior,
  identity continuity, and unsafe root configuration,
- canonical safe XML topology documented,
- one-command integration check added:
  `scripts/run-log4j2-integration-check.ps1`.

**Open hardening item**

Automatic ONNX/native-session closure during application-server hot redeploy is
not yet validated. Do not claim hot-redeploy lifecycle support until an
explicit lifecycle mechanism and redeploy test are complete.

---

### 2026-10-03 — Legacy leak paths removed; startup fail-fast added

**Status**

IMMEDIATE DEFECTS CLOSED; TIMEOUT/BACKPRESSURE + REDEPLOY LIFECYCLE OPEN

**Fixes**

- deprecated `SecureLogger` no longer prints application payload when the
  engine is unavailable,
- initialization failure logging no longer prints a stack trace or exception
  message that could expose unnecessary runtime details,
- unsupported `SecureLogXKafkaListener` direct ingress removed,
- Log4j2 startup API added:
  `SecureLogXLog4j2ConfigurationValidator.validateCurrentContextOrThrow(...)`,
- per-event topology validation remains as a fail-closed secondary safety net.

**Open production gates**

1. **Bounded execution / timeout / overload**
   - tokenizer wait is currently unbounded,
   - ONNX `session.run()` is synchronous,
   - no safe cancellation contract exists yet,
   - a superficial caller timeout must not be used because abandoned native
     inference could continue running.

2. **Hot redeploy lifecycle**
   - registry shutdown-hook classloader retention was removed,
   - explicit native-session close on application-server undeploy/redeploy is
     not yet implemented/validated.

3. **Claims**
   - H1+C1-S measured high-risk full-span recall remains ~99.08%,
   - whole-record perfect redaction remains ~93.55%,
   - residual misses must be disclosed,
   - routing percentages from synthetic A-D workloads are not deployment ML
     invocation SLAs,
   - no claim that NPI/sensitive data can never appear in logs is permitted.

**Release implication**

Do not call the Log4j2 integration fully production-ready until bounded
execution/overload behavior and redeploy lifecycle are validated.

---

### 2026-10-03 — Freeze public posture and five-gate 1.0 scorecard

**Status**

RESEARCH RUNTIME + LOG4J2 PILOT

**Public line**

SecureLogX is not yet a universally adoptable 1.0 product.

**Five OPEN release gates**

1. timeout/backpressure,
2. concurrency saturation policy,
3. hot redeploy/native lifecycle,
4. representative real-application log benchmark,
5. multi-hour soak.

**Hard timeout rule**

A caller-side timeout around the current unbounded tokenization wait or
synchronous ONNX call is not accepted as a production bound. Admission,
execution capacity, end-to-end deadline, overload behavior, and metrics must be
bounded together.

Raw payload must never be forwarded on queue-full, rejection, timeout, or
overload. The default design target is synthetic fail-closed output plus a
metric.

---

## 20. How to Update This Document

For every material architecture change, update the relevant section **and** append a new entry to the change log containing:

```text
Date:
Change:
Reason:
Evidence / benchmark:
Affected modules:
Backward-compatibility impact:
```

If an architectural proposal has not yet been validated, label it clearly as:

- **PROPOSED**
- **EXPERIMENTAL**
- **VALIDATED**
- **PRODUCTION**

Do not silently convert an experimental idea into production architecture.
