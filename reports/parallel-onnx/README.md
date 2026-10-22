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

## Recover the existing study after an aggregation error

The first steady-rate runner could finish real ONNX work and then fail
at `Measure-Object -Property fail_closed`: the trial rows were
ordered dictionaries, not directly consumable by that aggregation.
The corrected runner now uses typed PSObjects and projected numeric
group metrics.

If the local detailed run files already exist and the only failure
was *after* real ONNX measurement, do **not** blindly rerun the
benchmark. First retrieve the fix and recover the most recently
modified local steady-rate study folder:

```powershell
cd D:\workspace\SecureLogX
git pull origin chatgpt/hybrid-detection-pipeline

powershell -ExecutionPolicy Bypass `
  -File .\scripts\run-steady-onnx-study.ps1 `
  -RecoverLatest `
  -Rates "5,6,7" -Repetitions 2 -DurationSeconds 60 `
  -CallerMode platform -MaxOutstanding 32 `
  -MemorySampleMillis 5000 -MaskingDeadlineMillis 2000 `
  -CpuThreads 4 -WindowBatchLimit 4 -MaskingQueueCapacity 64 `
  -RandomSeed 20261017 -UseExampleOrganizationRules `
  -CommitResults -PushResults
```

`-RecoverLatest` **never runs Maven or ONNX**. It reconstructs the
seeded plan from the supplied settings, checks original local
`result.json` and `protected-application.log` pairs, rechecks
synthetic known-secret rejection, record counts, model execution,
sequence integrity, and verifies the newly produced ZIP against
original SHA-256 hashes before publishing a compact JSON.
If only some trials exist, it publishes an explicitly
`PARTIAL_VERIFIED` report, not a fictional completed study.
The original source commit SHA was not preserved inside the old
raw trial JSON, so recovery keeps it null rather than using the
new aggregation-script commit as a fabricated experiment SHA.

A new study run after pulling the fix will report
`queue_wait.max_millis_measured_only` distinctly from
`max_millis_including_warmup` and will include
`benign_version_preserved` as a **functional acceptance gate**.
A recovered older trial cannot retroactively gain measurements
or acceptance gates absent from its original evidence.

### Recovery folder discovery

If the original timestamp/name filter finds no folder, the current
script searches for completed `result.json` files underneath each local
study folder instead. It also accepts an explicit `-RecoverFromPath`
argument. Neither recovery option launches Maven or the ONNX model.

List the actual saved paths in PowerShell:

```powershell
cd D:\workspace\SecureLogX
Get-ChildItem .\reports\parallel-onnx -Filter result.json -File -Recurse -Force -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty FullName
```

If output includes a path such as
`...\.local-steady-runs\<study-id>\run-001-r5\result.json`,
pass the parent `<study-id>` folder to `-RecoverFromPath`
in place of `-RecoverLatest` while keeping all original
rates, repetitions, seed, caller mode and limits.

If the file search returns no results, there is no recoverable raw
`result.json` in that workspace. Search other local
SecureLogX clones or backups rather than treating a console-only
pass as a completed archived study. Git cannot restore ignored
local ZIPs or benchmark outputs that were never committed.

## Experimental pre-queue routing A/B gate (off by default)

A source-level implementation now supports the **explicit experimental**
`securelogx.experimental.preQueueDeterministicRoutingEnabled`
flag. It is **false by default** and is **not approved as a production
route** before full locked-corpus and real-ONNX A/B validation. The
same immutable hybrid scanner and organization rules are used on the
caller and native worker; only complete fields already deterministically
resolved skip native queue admission. Every unresolved field continues
through the original bounded single worker, and any failed field fails
the **entire** Log4j event closed.

First verify the model-free routing/merge regressions and usual
Log4j topology and deadline tests:

```powershell
cd D:\workspace\SecureLogX
git pull origin chatgpt/hybrid-detection-pipeline
mvn -pl securelogx-log4j2 -am clean test
```

For a controlled 5/6-events-per-second A/B pilot, the **same code
revision**, model/tokenizer SHA-256s, approved organization policy hash,
fixed workload seed, queue, worker, and deadline must be used.
**Do not use -CommitResults / -PushResults between the baseline and
experimental run** because the first result commit would change the
source revision of the second.

Run the following in **one PowerShell session** (all scripts use
the same current repository source HEAD):

~~~powershell
cd D:\workspace\SecureLogX
git pull origin chatgpt/hybrid-detection-pipeline
mvn -pl securelogx-log4j2 -am clean test

# Baseline without pre-queue routing:
powershell -ExecutionPolicy Bypass -File .\scripts\run-steady-onnx-study.ps1 -Rates "5,6" -Repetitions 2 -DurationSeconds 60 -CallerMode platform -MaxOutstanding 32 -MaskingDeadlineMillis 2000 -CpuThreads 4 -WindowBatchLimit 4 -MaskingQueueCapacity 64 -MemorySampleMillis 5000 -RandomSeed 20261018 -UseExampleOrganizationRules
$offStudy = @(Get-ChildItem .\reports\parallel-onnx\steady -Filter 'study-*.json' -File | Sort-Object LastWriteTimeUtc -Descending)[0]

# Experiment with ONLY the pre-queue flag different:
powershell -ExecutionPolicy Bypass -File .\scripts\run-steady-onnx-study.ps1 -Rates "5,6" -Repetitions 2 -DurationSeconds 60 -CallerMode platform -MaxOutstanding 32 -MaskingDeadlineMillis 2000 -CpuThreads 4 -WindowBatchLimit 4 -MaskingQueueCapacity 64 -MemorySampleMillis 5000 -RandomSeed 20261018 -UseExampleOrganizationRules -EnablePreQueueRouting
$onStudy = @(Get-ChildItem .\reports\parallel-onnx\steady -Filter 'study-*.json' -File | Sort-Object LastWriteTimeUtc -Descending)[0]

$off = Get-Content $offStudy.FullName -Raw | ConvertFrom-Json
$on = Get-Content $onStudy.FullName -Raw | ConvertFrom-Json
$offZip = ".\reports\parallel-onnx\.local-steady-runs\detail-$($off.study_id).zip"
$onZip = ".\reports\parallel-onnx\.local-steady-runs\detail-$($on.study_id).zip"
$comparison = ".\reports\parallel-onnx\steady\comparison-$($off.study_id)-vs-$($on.study_id).json"

powershell -ExecutionPolicy Bypass -File .\scripts\compare-steady-prequeue-ab.ps1 -BaselineStudy $offStudy.FullName -PreQueueStudy $onStudy.FullName -OutputPath $comparison
powershell -ExecutionPolicy Bypass -File .\scripts\verify-prequeue-output-parity.ps1 -BaselineStudy $offStudy.FullName -PreQueueStudy $onStudy.FullName -BaselineDetailZip $offZip -PreQueueDetailZip $onZip

# Only after reviewing results: publish the two compact studies and A/B JSON.
git add -- $offStudy.FullName $onStudy.FullName $comparison
git commit -m "Archive matched synthetic pre-queue OFF/ON real ONNX study"
git push origin chatgpt/hybrid-detection-pipeline
~~~

The offline metrics comparator rejects mismatched source SHA,
model/tokenizer fingerprint, synthetic organization rules SHA,
seed, or workload configuration. The ZIP parity verifier
rehashes the original local protected logs, compares normalized
synthetic fields by event ID, and **never prints log payloads**.
Failed functional trials are excluded from exact-output parity,
not misreported as equivalent. Even complete synthetic parity
does **not** prove parity on the locked 6,463-case high-risk
corpus; that release gate remains open.

Each entire study produces **one compact Git JSON**, with detailed
synthetic outputs retained only in local, Git-ignored ZIP archives.
The JSON now includes
`pre_queue_routing_measured_only`:
deterministically bypassed fields, escalated fields, and entire
events failed closed. Native ONNX calls/windows, normal masking
throughput, p99/max, measured-only queue waiting, benign
`version=1.2.3.4` retention, ingress reconstruction
and sampled RSS are comparison gates.

**Fail the A/B gate** on any newly unmasked known sensitive fixture,
new benign overmask, nonzero regression in sequence/output completeness,
or violation of whole-event fail-closed; compare all individual
trials rather than only median throughput. A less reliable functional
trial cannot be rescued by higher total protected-output throughput.
Finally run the locked 6,463-record high-risk corpus with the same
policy/model and compare recall and negative controls. The feature
**must stay off** until that independent gate passes.

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
