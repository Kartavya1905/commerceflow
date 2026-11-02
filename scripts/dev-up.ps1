# PowerShell entry point for scripts/dev-up.sh. Usage: .\scripts\dev-up.ps1 [--obs]
& "$PSScriptRoot\lib\run-bash.ps1" -Script 'dev-up.sh' -Arguments $args
exit $LASTEXITCODE
