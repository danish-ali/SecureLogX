# Phase 1 — Real-ONNX bottleneck assessment (26 October 2026)

**Evidence:** [COMPLETE_VERIFIED compact steady study](study-20261026T231647Z-steady-77daf0044636.json), study ID `20261026T231647Z-steady-77daf0044636`, source SHA `a53d0fa012bfd49a4c557017e11f61ad74cfe955`.

**Stage data provenance:** Existing Java per-stage counters extracted from the original locally ignored ZIP using `scripts/extract-phase-one-stage-timings.ps1`, with original JSON and protected-log SHA-256 verification against the compact Git manifest. The operator provided the resulting payload-free, numeric extractor output. This text does **not** reproduce raw logs or claim independent execution of the model/ZIP by the GitHub reviewer.

## Controlled run configuration

Two **individual** 60-second synthetic application-log trials, platform callers, pre-queue routing **OFF**, single bounded native worker/session, 2,000-ms deadline, queue capacity 64, 4 CPU threads, 4-window cap for *expanded segments*, max outstanding 32, seed 20261026, example organization rules. The unexpanded segment uses its own `optimalBatchSize`, so total windows / native calls can exceed 4; do not interpret the expanded-segment cap as a global limit on all ONNX runs.

| Metric | 3 events/s | 5 events/s |
|---|---:|---:|
| Normally masked events | 180/180 | 300/300 |
| Fail-closed / native failures or cancellations | 0 / 0 | 0 / 0 |
| Caller p99 (ms) | 823.458 | 849.1372 |
| Average queue wait for started jobs (ms) | 49.830215 | 121.673582 |
| Worker scan count | 936 | 1560 |
| Worker scan total (ms) | 60.2718 | 71.8147 |
| Worker scan mean per event (ms) | 0.334843 | 0.239382 |
| Native ONNX calls completed / attempted | 198 / 198 | 330 / 330 |
| Native inference windows attempted | 792 | 1380 |
| Native ONNX time (cumulative ms) | 20,911.3321 | 34,896.7942 |
| Non-native processing stages (cumulative ms) | 172.1232 | 196.5830 |
| Native share of measured processing stages | 99.1836% | 99.4398% |
| Native mean time per event (ms) | 116.1741 | 116.3226 |
| Native mean time per call (ms) | 105.6128 | 105.7479 |
| Window-expanded native time, subset (ms) | 12,642.9870 | 20,479.6648 |
| Window-expanded share of native duration | 60.4600% | 58.6864% |
| Fields using multiple inference windows | 18/756 | 30/1320 |
| Events bypassing native worker | 0 (routing OFF) | 0 (routing OFF) |

**Measured processing-stage totals exclude queue waiting.** Native window-expanded duration is already included in total native duration and must not be added a second time. Worker-scan time is measured independently and may overlap deterministic stage totals; do not add it to the stage total. Cumulative durations are not event-level latency percentiles. Queue-wait averages apply only to requests that started on the worker; they exclude requests abandoned before start (none reported in these trials).

## Attribution and decision

1. **Dominant worker service-time expense: ONNX inference.** Native execution was above 99% of measured processing-stage total under both tested rates. Native time per event stayed approximately 116 ms, while mean queue waiting grew from 49.8 to 121.7 ms as the offered rate increased.
2. **Expanded-window interference is a strong hypothesis for Phase 3.** At 5/s, only 30 ML-routed fields were multi-window fields, yet native runs for expanded segments accounted for ~58.7% of native execution time. These figures do **not** by themselves prove per-record head-of-line interference or a short-record p99 improvement from reordering. A controlled short/long mixed workload and per-class latency measurement are required.
3. **Phase 2 is not a demonstrated latency bottleneck in this baseline.** With pre-queue routing OFF, there are no caller-side deterministic scans to duplicate. Worker scans consumed only 0.239 ms per event on average at 5/s. Phase 2 may still remove work when experimental pre-queue routing is ON, but must remain opt-in, immutable/request-scoped, policy-identity checked, and exact-parity gated; never treat this OFF result as evidence of actual duplicate scans.
4. **Functional and synthetic safety checks passed** at both rates, but each had just one 60-second trial. These data cannot establish long-term capacity, production privacy or memory stability, or reliability at higher offered rates.

## Four-phase progression

- **Phase 1:** Observation-only counters and diagnostic evidence complete for the 3/s and 5/s trials. Keep the review change focused on numeric reporting; no runtime changes.
- **Phase 2:** If proceeding in planned order, run a narrowly scoped **opt-in** duplicate-scan reuse implementation, with unchanged model and complete output, ordering, purpose/policy identity, benign negative control and high-risk privacy parity. Measure actual duplicated scan counts in pre-queue-ON trials; never assume a 99% runtime reduction from a sub-millisecond scan saving.
- **Phase 3:** Build short-record-only / long-record-only / fixed-mix workload comparisons and observe per-class p99, deadline, queue wait, work cost and starvation before a conservative single-worker cost-aware admission experiment. Do not add another native session, change the 2-second deadline, or forward raw logs.
- **Phase 4:** Matched baseline/opt-in capacity curves, repeats, longer steady soak, memory/GC stability, locked high-risk corpus and privacy regression.

**Decision:** Keep Phase 2 a safety-first parity micro-optimization, but prioritize Phase 3's hypothesis when choosing where material latency improvement is likely. Do not deploy either change without independent safety and performance gates.
