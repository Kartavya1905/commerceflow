# PowerShell entry point for scripts/check-infra.sh. Usage: .\scripts\check-infra.ps1
& "$PSScriptRoot\lib\run-bash.ps1" -Script 'check-infra.sh' -Arguments $args
exit $LASTEXITCODE
