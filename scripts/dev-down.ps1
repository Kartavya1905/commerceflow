# PowerShell entry point for scripts/dev-down.sh. Usage: .\scripts\dev-down.ps1 [--volumes] [--yes]
& "$PSScriptRoot\lib\run-bash.ps1" -Script 'dev-down.sh' -Arguments $args
exit $LASTEXITCODE
