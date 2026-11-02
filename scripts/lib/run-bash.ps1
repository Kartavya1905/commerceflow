# Runs one of the bash scripts in scripts/ from PowerShell using Git for Windows' bash.
# The PowerShell wrappers exist so Windows users can type .\scripts\dev-up.ps1; the logic lives
# in one place (the .sh files) so the two can never drift apart.
param(
    [Parameter(Mandatory = $true)][string]$Script,
    [string[]]$Arguments = @()
)
$ErrorActionPreference = 'Stop'

$candidates = @()
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    # git.exe usually lives in <Git>\cmd or <Git>\bin; bash.exe is in <Git>\bin.
    $gitRoot = Split-Path (Split-Path $git.Source -Parent) -Parent
    $candidates += (Join-Path $gitRoot 'bin\bash.exe')
}
$candidates += "$env:ProgramFiles\Git\bin\bash.exe"

$bash = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $bash) {
    Write-Error 'Git Bash not found. Install Git for Windows (https://git-scm.com/download/win) and retry.'
    exit 1
}

$scriptPath = Join-Path $PSScriptRoot "..\$Script"
# Windows PowerShell 5.1 turns any stderr line from a native program into an error record, which
# 'Stop' would make fatal. The bash script's exit code is the real result, so pass it through.
$ErrorActionPreference = 'Continue'
& $bash $scriptPath @Arguments
exit $LASTEXITCODE
