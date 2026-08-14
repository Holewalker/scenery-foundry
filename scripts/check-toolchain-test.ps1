$ErrorActionPreference = "Stop"
. "$PSScriptRoot/check-toolchain.ps1"

function Assert-Equal([object]$Actual, [object]$Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message. Expected '$Expected', got '$Actual'." }
}

$progressBeforeVersion = @(
    "Building scenery-foundry-geometry-worker @ file:///workspace/geometry-worker",
    "Python 3.14.2"
)
Assert-Equal -Actual (Get-PythonVersionLine $progressBeforeVersion) -Expected "Python 3.14.2" -Message "Must select the Python version after uv progress output"
Assert-Equal -Actual (Get-PythonVersionLine @("Building worker", "warning: cache miss")) -Expected $null -Message "Must reject output without a Python 3.14 line"

Write-Output "Toolchain Python-version extraction checks passed."
