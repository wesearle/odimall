#Requires -Version 5.1
<#
.SYNOPSIS
  Build and install the OdiMall Windows edge .NET service on an EC2 Windows host.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\install-windows.ps1
#>
param(
    [string]$InstallDir = "C:\opt\odimall-windows-edge",
    [string]$ServiceName = "OdiMallWindowsEdge",
    [string]$BindUrl = "http://0.0.0.0:9201",
    [int]$FirewallPort = 9201
)

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptRoot

Write-Host "=== OdiMall Windows edge service install ===" -ForegroundColor Cyan

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw "dotnet SDK/runtime not found. Install .NET 8 SDK from https://dotnet.microsoft.com/download"
}

Write-Host "Publishing release build to $InstallDir ..."
dotnet publish WindowsEdgeService.csproj -c Release -r win-x64 --self-contained false -o $InstallDir

$exe = Join-Path $InstallDir "WindowsEdgeService.exe"
if (-not (Test-Path $exe)) {
    throw "Publish failed: $exe not found"
}

Write-Host "Opening Windows Firewall port $FirewallPort ..."
$ruleName = "OdiMall Windows Edge ($FirewallPort)"
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    Remove-NetFirewallRule -DisplayName $ruleName
}
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $FirewallPort | Out-Null

Write-Host "Registering Windows service '$ServiceName' ..."
$binPath = "`"$exe`""
$existingSvc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existingSvc) {
    if ($existingSvc.Status -eq "Running") {
        Stop-Service -Name $ServiceName -Force
    }
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 2
}

sc.exe create $ServiceName binPath= $binPath start= auto | Out-Null
sc.exe description $ServiceName "OdiMall demo .NET edge service for Odigos storefront integration" | Out-Null
sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/5000/restart/5000 | Out-Null

[System.Environment]::SetEnvironmentVariable("WINDOWS_EDGE_BIND_URL", $BindUrl, "Machine")
sc.exe config $ServiceName start= auto | Out-Null

Start-Service -Name $ServiceName
Start-Sleep -Seconds 2

Write-Host ""
Write-Host "Service status:" -ForegroundColor Green
Get-Service -Name $ServiceName | Format-Table -AutoSize

Write-Host "Local smoke test:"
try {
    $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$FirewallPort/run" -Method GET -TimeoutSec 10
    Write-Host ($resp | ConvertTo-Json -Compress)
} catch {
    Write-Warning "Smoke test failed: $_"
}

Write-Host ""
Write-Host "Done. Configure the OdiMall cluster with:" -ForegroundColor Cyan
Write-Host "  WINDOWS_PIPELINE_BASE_URL=http://<THIS_HOST_PUBLIC_IP>:$FirewallPort"
Write-Host "Then click the Windows button in the storefront navbar."
