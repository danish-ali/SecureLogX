# Real-ONNX concurrency evidence

## Review the findings, not hundreds of artifacts

This directory uses **one compact review layer**. The current branch
retains consolidated JSON comparisons and original multi-run study
plans/summaries, while the large per-run logs and reconstructed outputs
from earlier experiments have been **removed from the current tree**.

The original records are **not rewritten or destroyed**: Git keeps them
in the following *reachable ancestor commits*. Each original commit
can be opened directly or checked out to audit the full synthetic
fixture records and SHA-256 manifests.

| Study | What to review | Full historical artifacts |
|---|---|---|
| 10/50/100 platform vs virtual: 6 runs | [Comparison](comparisons/2026-10-17-real-onnx-parallel-platform-vs-virtual.json) | [Commit 0d8e3b2](https://github.com/danish-ali/SecureLogX/tree/0d8e3b218bb5af7e3ba80367da61714c52ae9e11/reports/parallel-onnx/history) |
| 1/2/4/8: 16 paired runs | [Findings](comparisons/2026-10-17-low-concurrency-1-2-4-8-findings.json) | [Commit 6a905d6](https://github.com/danish-ali/SecureLogX/tree/6a905d6a452969aca6d71d5bdeaa81b1d0f8a073/reports/parallel-onnx/history) |
| 8/9/10/12: 16 paired runs | [Findings](comparisons/2026-10-18-boundary-8-9-10-12-findings.json) | [Commit d7d3ad2](https://github.com/danish-ali/SecureLogX/tree/d7d3ad2d2c1356bec80aba74b053a45ba960e2e9/reports/parallel-onnx/history) |
| 8/10/11/12: 32 paired runs | [Findings](comparisons/2026-10-18-32-run-repeated-8-10-11-12-findings.json) | [Commit 42e2533](https://github.com/danish-ali/SecureLogX/tree/42e25331892b7633c9e49c051c341488c3f8be44/reports/parallel-onnx/history) |

Earlier study plans and summary JSON remain under
[studies/](studies/). Redundant per-run
`history/` logs, raw reports, and individual receipt files are
omitted from the current branch view, but accessible through the
pinned historical commits above.

To recover one individual synthetic report without changing branches:

```powershell
git show 42e2533:reports/parallel-onnx/history/<run-id>/result-<run-id>.json
```

Do not routinely export past raw logs from Git into a production
logging context. These are synthetic benchmark fixtures only.

## New steady-arrival evidence policy

The new fixed-rate study
`scripts/run-steady-onnx-study.ps1` produces:

- **One committed summary JSON per entire study** in [steady/](steady/).
  It contains config, seeded execution plan, per-run aggregate metrics,
  observed synthetic fail-closed reasons, p95/p99/max, real ONNX calls
  and windows, measured queue-wait histogram, memory sample maxima,
  and SHA-256 hashes of the original detailed local evidence.
- **One ignored ZIP per entire study**, locally in
  `reports/parallel-onnx/.local-steady-runs/detail-<study-id>.zip`.
  It includes the original JSON (with periodic RSS samples) and
  synthetic protected log for each run. Every ZIP entry is rehashed
  before the detailed extracted folders are removed.
- **No routine per-trial commits**, no raw synthetic log histories
  pushed for every rate, and no model binaries in Git.

The locally ignored ZIP is **not** accessible through GitHub by
default. If the local ZIP is deleted, the SHA-256 pointers remain as
provenance but do not restore the original files. Archive ZIPs
elsewhere if long-term forensic retention is required.

## Run the 5/6/7-per-second pilot

Run from a JDK 21+ development environment with the validated
`onnx-model/ml-v1.3` artifacts present:

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

The study requires Maven tests to pass before running the actual
model. A qualifying pilot must have **zero generator drops** and every
synthetic arrival delivered to the protected output. Fail-closed
outputs are confidentially protected but counted as functional losses.
Use normal-masked events/s, not total log output rate, as a useful
inference-capacity metric.

This is a fixed-rate 60-second pilot, **not** a long soak or a
production SLO. Even repeated passes are insufficient to prove
performance on real logs or absence of native-memory leaks.
