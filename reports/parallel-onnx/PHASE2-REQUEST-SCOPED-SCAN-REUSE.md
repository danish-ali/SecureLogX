# Phase 2 — Request-scoped deterministic scan reuse (opt-in)

**Status:** implementation and model-free regressions proposed for review; **NOT production approved**. The default runtime path and pre-queue experiment are unchanged.

## Safety and implementation contract

Two independent flags must both be enabled:

- `securelogx.experimental.preQueueDeterministicRoutingEnabled=true`
- `securelogx.experimental.preQueueScanReuseEnabled=true`

Both default to false. Setting reuse alone has no effect. The feature does not add an inference worker/session, external cache, background queue, request sharing, new ML bypass, or changes to ONNX/model data or the 2-second deadline.

For each event, the caller-side router continues using the **same** pipeline as the native worker. The router preserves its original behavior: it resolves only complete deterministic fields, while all remaining fields are submitted to the **existing** bounded worker. A request-local immutable handoff now contains ordered `Optional<DeterministicScanResult>` entries for unresolved fields. Entries for short fields already scanned and requiring ML contain their **exact** original immutable scan. Long, multiline, and null fields contain `Optional.empty()` and are scanned by the worker normally.

A reuse is accepted only if the worker's `HybridMaskingPipeline` is the **same object instance** as the router's, the request has the expected count/order, and each worker `LogEvent` is the **same object instance** as at routing. This prevents accidental cross-request, cross-policy or out-of-order scan substitution. The worker validates the entire request before its first chunk; any violation fails the worker request and the route's full-event merge closed. Existing byte-for-byte protections, matched model output, no partial event release, negative controls, absolute deadline and cancellation behavior remain authoritative.

Worker native diagnostics now include `worker_deterministic_scans_reused`; the compact study records it as `phase_two_worker_scans_reused`. Actual worker scanner call counts must decrease **exactly** by the reuse count when compared against a matched pre-queue-ON baseline. A completed ONNX call reduction is not sufficient evidence of correct reuse.

## Model-free verification

On `chatgpt/phase2-request-scoped-scan-reuse`, run:

```powershell
cd D:\workspace\SecureLogX
git fetch origin
git switch chatgpt/phase2-request-scoped-scan-reuse
git pull --ff-only origin chatgpt/phase2-request-scoped-scan-reuse
mvn -pl securelogx-log4j2 -am clean test
```

The core request-snapshot tests check exact ML gate/evidence parity, negative controls, immutable snapshots, long/multiline fallback, policy mismatch, request substitution and ordering. The existing whole-event fail-closed and Log4j sequence tests remain mandatory.

## Matched real-ONNX A/B run

Both runs **must be on the exact same source commit**, model/tokenizer SHA, example rules hash, seed, caller settings, window cap and deadline. Only the Phase 2 reuse flag changes; pre-queue routing remains ON in **both**. Do not commit or push results between runs. Do not include raw log payloads in Git.

```powershell
cd D:\workspace\SecureLogX
git switch chatgpt/phase2-request-scoped-scan-reuse
git pull --ff-only origin chatgpt/phase2-request-scoped-scan-reuse
mvn -pl securelogx-log4j2 -am clean test

# A: original worker rescans, pre-queue routing enabled
powershell -ExecutionPolicy Bypass -File .\scripts\run-steady-onnx-study.ps1 -Rates "3,5" -Repetitions 2 -DurationSeconds 60 -CallerMode platform -MaxOutstanding 32 -MaskingDeadlineMillis 2000 -CpuThreads 4 -WindowBatchLimit 4 -MaskingQueueCapacity 64 -MemorySampleMillis 5000 -RandomSeed 20261026 -UseExampleOrganizationRules -EnablePreQueueRouting
$baseline = @(Get-ChildItem .\reports\parallel-onnx\steady -Filter 'study-*.json' -File | Sort-Object LastWriteTimeUtc -Descending)[0]

# B: identical, but request-scoped reuse enabled
powershell -ExecutionPolicy Bypass -File .\scripts\run-steady-onnx-study.ps1 -Rates "3,5" -Repetitions 2 -DurationSeconds 60 -CallerMode platform -MaxOutstanding 32 -MaskingDeadlineMillis 2000 -CpuThreads 4 -WindowBatchLimit 4 -MaskingQueueCapacity 64 -MemorySampleMillis 5000 -RandomSeed 20261026 -UseExampleOrganizationRules -EnablePreQueueRouting -EnablePreQueueScanReuse
$optimized = @(Get-ChildItem .\reports\parallel-onnx\steady -Filter 'study-*.json' -File | Sort-Object LastWriteTimeUtc -Descending)[0]

$a = Get-Content -LiteralPath $baseline.FullName -Raw | ConvertFrom-Json
$b = Get-Content -LiteralPath $optimized.FullName -Raw | ConvertFrom-Json
$az = ".\reports\parallel-onnx\.local-steady-runs\detail-$($a.study_id).zip"
$bz = ".\reports\parallel-onnx\.local-steady-runs\detail-$($b.study_id).zip"
powershell -ExecutionPolicy Bypass -File .\scripts\verify-scan-reuse-output-parity.ps1 -BaselineStudy $baseline.FullName -PreQueueStudy $optimized.FullName -BaselineDetailZip $az -PreQueueDetailZip $bz
```

The parity verifier cryptographically checks the original local protected-log entries, compares *every synthetic event and warmup result* after removing only run-specific execution IDs/telemetry, **never prints masked or raw payloads**, requires normal-functional trials, and verifies that the reduction in worker deterministic scans equals the scan-reuse counter. Any failed or ineligible pair keeps the gate open.

## Remaining release gates

- Zero new raw sensitive fixture forwarding, no overmask of benign `version=1.2.3.4`, identical masked output and sequence reconstruction, no new fail-closed events or starvation at matched loads.
- Locked **6,463-record** high-risk corpus and negative controls rechecked on both configurations under identical policy/model, not inferred from synthetic parity.
- Native attempted/completed/failure, windows, queue/admission and memory metrics reviewed for each run, not just averages.
- If the safety gate passes but latency improvement is negligible, keep the feature OFF and move to Phase 3 short/long workload interference testing.


## Recommended staged validation entry point

After pulling the Phase 2 branch, the safer wrapper is
scripts/run-phase2-validation.ps1. It refuses to run on the wrong
branch or a dirty tracked working tree. It never uploads the ignored
local protected-output ZIPs or merges a pull request.

~~~powershell
cd D:\workspace\SecureLogX
git fetch origin
git switch chatgpt/phase2-request-scoped-scan-reuse
git pull --ff-only origin chatgpt/phase2-request-scoped-scan-reuse

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-phase2-validation.ps1 -Stage Tests
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-phase2-validation.ps1 -Stage Onnx
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-phase2-validation.ps1 -Stage Corpus -NerRoot "..\SecureLogX-NER"
~~~

Use -Stage All to perform all three sequentially on the same source
revision. Reuse is enabled **only inside the matched synthetic
benchmark's optimized study**; runtime defaults remain OFF.

The corpus stage writes **two local ignored numeric reports**:

- HybridGateDatasetAudit: existing gold-span / deterministic bypass /
  overmask / ALLOW safety checks, expected exactly 6,463 labeled cases.
- Phase2LockedScanReuseAudit: new request-scoped handoff check over
  those same 6,463 records, using the synthetic approved organization
  policy. Verifies exact deterministic scan/result parity, event/policy
  identity, deterministic-only output parity and required worker
  fallback for caller-ineligible fields; includes dataset SHA-256s.

**Limitations:** the Phase 2 corpus audit does not invoke the ONNX model,
calculate ML recall, or compare complete masked outputs on the full
corpus. Dataset hashes are emitted for review but are not compared
against a separately pinned independent manifest. These two checks
must not be mislabeled as a full 6,463-record real ONNX privacy
regression. The real-ONNX A/B stage compares synthetic application-log
fixtures only. An additional corpus-wide real-model redaction parity
and quality gate remains OPEN before release.
