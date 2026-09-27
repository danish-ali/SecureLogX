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
2. high-confidence deterministic ALLOW evidence can suppress an overlapping ML false positive only when the negative context is authoritative,
3. ESCALATE evidence does not decide the final outcome,
4. non-overlapping contextual ML spans are normally masked,
5. unsafe ambiguity should favor protection or escalation rather than raw exposure.

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

This benchmark is a reproducible engineering workload, not a claim that the synthetic distribution exactly represents any production estate. Real anonymized operational distributions should eventually be used to calibrate scenario weights.

---

## 9. Research-Driven Evaluation Changes

Recent research does not justify replacing the architecture yet, but it changes what SecureLogX should benchmark and report.

### 9.1 Four-way architecture comparison

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
ML invocation / latency / throughput optimization
        |
        v
Phase 4
Constrained decoding experiment
        |
        v
Phase 5
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
