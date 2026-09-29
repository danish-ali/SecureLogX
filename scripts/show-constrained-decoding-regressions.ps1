param(
    [string]$Result = ".\reports\constrained-decoding\result.json"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Result)) {
    throw "Constrained decoding result not found: $Result"
}

$data = Get-Content -Raw $Result | ConvertFrom-Json
$diag = $data.difference_diagnostics

Write-Host "M0 -> C1 regression review"
Write-Host ""
Write-Host ("Records with changed predictions: {0}" -f $diag.records_with_changed_predictions)
Write-Host ("M0 full / C1 not full: {0}" -f $diag.m0_full_c1_not_full)
Write-Host ("High-risk regressions: {0}" -f $diag.high_risk_regressions)
Write-Host ("C1 full / M0 not full: {0}" -f $diag.c1_full_m0_not_full)
Write-Host ("Exact-boundary losses / gains: {0} / {1}" -f $diag.exact_boundary_losses, $diag.exact_boundary_gains)

Write-Host ""
Write-Host "Regression labels:"
$labels = $diag.regressions_by_gold_label
if ($null -eq $labels -or $labels.PSObject.Properties.Count -eq 0) {
    Write-Host "  none"
} else {
    $labels.PSObject.Properties |
        Sort-Object { [long]$_.Value } -Descending |
        ForEach-Object {
            Write-Host ("  {0}: {1}" -f $_.Name, $_.Value)
        }
}

Write-Host ""
Write-Host "Gain labels:"
$gains = $diag.gains_by_gold_label
if ($null -eq $gains -or $gains.PSObject.Properties.Count -eq 0) {
    Write-Host "  none"
} else {
    $gains.PSObject.Properties |
        Sort-Object { [long]$_.Value } -Descending |
        ForEach-Object {
            Write-Host ("  {0}: {1}" -f $_.Name, $_.Value)
        }
}

Write-Host ""
Write-Host "Diagnostic samples:"
if ($null -eq $diag.samples -or $diag.samples.Count -eq 0) {
    Write-Host "  none"
} else {
    foreach ($sample in $diag.samples) {
        Write-Host ("  {0}" -f $sample)
    }
}
