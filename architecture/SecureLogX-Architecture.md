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

**Status: VALIDATED ON SCORABLE <=384-TOKEN COHORT**

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

The post-fix architecture comparison was run on the 5,882 records that fit within the validated tokenizer window.

| Metric | D0 | M0 | H1 |
|---|---:|---:|---:|
| Sensitive-character recall | 33.4230% | 99.0918% | 99.0918% |
| Non-sensitive-character redaction | 0.0000% | 0.2325% | 0.2325% |
| Full-span recall | 19.2971% | 98.1830% | 98.1830% |
| High-risk full-span recall | 13.5593% | 99.5039% | 99.5039% |
| Whole-record perfect redaction | 14.0258% | 96.1238% | 96.1238% |
| Logical ML invocation | 0% | 100% | 100% |

M0 -> H1 regression diagnostics:

- M0 full / H1 not full: 0,
- high-risk regressions: 0,
- H1 full / M0 not full: 0,
- regressions by gold label: none,
- overlapping decisive evidence reasons: none.

This validates the corrected resolver semantics on the scorable cohort:

- deterministic MASK remains a protection floor,
- overlapping ML MASK may extend protection,
- deterministic ALLOW can suppress ML only when it matches the same entity type and fully contains the ML span,
- partial ALLOW overlap cannot erase a larger ML span,
- ESCALATE remains non-decisive.

The earlier apparent H1 quality penalty is therefore resolved.

However, 581 of the 6,463 input records exceed the frozen 384-token window and are excluded from D0/M0/H1 quality scoring:

- original_test_regression: 293,
- standard_dev: 288.

This over-window cohort is now the dominant unresolved architecture-quality limitation. Runtime handling is safe because ML-routed over-window messages fail closed, but this is not an acceptable final production UX for long records. Validated overlapping-window inference should therefore be the next implementation priority before production readiness or final journal claims about complete-record coverage.

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

The current ML pipeline uses token classification followed by span decoding.

A future experiment should compare:

```text
Current BIO argmax decoding
        vs
BIO-constrained / sequence-constrained decoding
```

Purpose:

- reduce illegal BIO transitions,
- reduce partial spans,
- improve span coherence,
- preserve high-risk recall.

This is an evaluation item first, not an automatic production change.

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

**Current status: OVERLAPPING-WINDOW INFERENCE IMPLEMENTED; VALIDATION PENDING**

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

The implementation is not yet production-validated. The first required validation is an end-to-end record containing sensitive content beyond the first model window. It must be successfully masked with zero truncation fail-closed events.

After runtime validation, the architecture-quality benchmark should be upgraded to score the previous 581-record truncation cohort through the same windowed path and compare full-cohort D0/M0/H1 quality.

Silent truncation remains prohibited.

Architecture-quality benchmarks must not score incomplete inference.

For D0/M0/H1 comparison, records that exceed the frozen 384-token model window are now handled as a **separate truncation cohort**:

- they are excluded from D0/M0/H1 quality denominators,
- exclusion counts are reported globally and by dataset source,
- sample diagnostics record covered character count, total character count, gold-span count, and gold spans beyond the covered region,
- the same scorable subset is used for D0, M0, and H1 so the comparison remains fair,
- runtime policy remains fail-closed for ML-routed over-window records.

This exclusion is a temporary evaluation policy until validated overlapping-window inference exists. It must not be interpreted as evidence that long records are supported by the current model path.

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
        |
        v
Phase 4
ML invocation / latency / throughput optimization
        |
        v
Phase 5
Constrained decoding experiment
        |
        v
Phase 6
Runtime/concurrency/backpressure hardening
        |
        v
Phase 6
ONNX Runtime upgrade evaluation
        |
        v
Phase 7
Dependency / SBOM / CVE / license gate
        |
        v
Phase 8
Spring Boot + Log4j2 production packaging
        |
        v
Phase 9
Load, soak, failure and adversarial testing
        |
        v
Phase 10
Release candidate
        |
        v
SecureLogX 1.0
```

Recommended milestone terminology:

- Phase 2 complete: **journal/demo ready**
- Phase 5 complete: **runtime beta**
- Phase 8 complete: **product beta**
- Phase 10 complete: **production-grade release candidate**

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
