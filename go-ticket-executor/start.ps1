Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$localConfig = Join-Path $root "config.local.json"
$exampleConfig = Join-Path $root "config.example.json"
$exePath = Join-Path $root "ticket-order-executor.exe"
$logsDir = Join-Path $root "logs"

if (!(Test-Path -LiteralPath $localConfig)) {
  if (Test-Path -LiteralPath $exampleConfig) {
    Copy-Item -LiteralPath $exampleConfig -Destination $localConfig
    Write-Host "Created config.local.json. Please verify the database settings before startup." -ForegroundColor Yellow
  }
}

if (!(Test-Path -LiteralPath $logsDir)) {
  New-Item -ItemType Directory -Path $logsDir | Out-Null
}

function Get-ExecutorConfig {
  $defaults = @{
    port = "8099"
  }

  if (!(Test-Path -LiteralPath $localConfig)) {
    return $defaults
  }

  try {
    $config = Get-Content -LiteralPath $localConfig -Raw | ConvertFrom-Json -AsHashtable
    if (-not $config.ContainsKey("port") -or [string]::IsNullOrWhiteSpace([string]$config["port"])) {
      $config["port"] = $defaults["port"]
    }
    return $config
  } catch {
    Write-Host "config.local.json is invalid JSON. Falling back to defaults." -ForegroundColor Yellow
    return $defaults
  }
}

function Get-ListeningProcessId([string]$port) {
  $lines = netstat -ano | Select-String ":$port"
  foreach ($line in $lines) {
    $text = $line.ToString()
    if ($text -match "LISTENING\s+(\d+)\s*$") {
      return [int]$Matches[1]
    }
  }
  return $null
}

function Get-ProcessCommandLine([int]$processId) {
  try {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
    return $process.CommandLine
  } catch {
    return $null
  }
}

function Stop-ExistingExecutor([string]$port) {
  $stoppedAny = $false

  if (Test-Path -LiteralPath $exePath) {
    $sameExeProcesses = Get-Process | Where-Object {
      $_.Path -eq $exePath
    }

    foreach ($proc in $sameExeProcesses) {
      Write-Host "Stopping existing executor PID $($proc.Id)..." -ForegroundColor Yellow
      Stop-Process -Id $proc.Id -Force
      $stoppedAny = $true
    }
  }

  Start-Sleep -Milliseconds 300

  $portPid = Get-ListeningProcessId $port
  if ($null -eq $portPid) {
    return $stoppedAny
  }

  $cmd = Get-ProcessCommandLine $portPid
  $portProcess = Get-Process -Id $portPid -ErrorAction SilentlyContinue
  $isExecutorProcess = $false

  if ($portProcess -and (Test-Path -LiteralPath $exePath) -and $portProcess.Path -eq $exePath) {
    $isExecutorProcess = $true
  } elseif ($cmd -and $cmd -like "*go-ticket-executor*") {
    $isExecutorProcess = $true
  }

  if ($isExecutorProcess) {
    Write-Host "Stopping port occupant PID $portPid on :$port..." -ForegroundColor Yellow
    Stop-Process -Id $portPid -Force
    Start-Sleep -Milliseconds 300
    return $true
  }

  $name = if ($portProcess) { $portProcess.ProcessName } else { "unknown" }
  throw "Port :$port is already in use by PID $portPid ($name). Refusing to stop a non-executor process."
}

function Start-Executor([string]$port) {
  $stdoutLog = Join-Path $logsDir "executor.stdout.log"
  $stderrLog = Join-Path $logsDir "executor.stderr.log"

  if (Test-Path -LiteralPath $stdoutLog) {
    Remove-Item -LiteralPath $stdoutLog -Force
  }
  if (Test-Path -LiteralPath $stderrLog) {
    Remove-Item -LiteralPath $stderrLog -Force
  }

  if (Test-Path -LiteralPath $exePath) {
    Write-Host "Starting compiled executor..." -ForegroundColor Green
    return Start-Process -FilePath $exePath `
      -WorkingDirectory $root `
      -RedirectStandardOutput $stdoutLog `
      -RedirectStandardError $stderrLog `
      -PassThru
  }

  $go = Get-Command go -ErrorAction SilentlyContinue
  if ($null -eq $go) {
    throw "go.exe was not found and ticket-order-executor.exe is not present."
  }

  if (-not $env:GOPROXY) {
    $env:GOPROXY = "https://goproxy.cn,direct"
  }

  Write-Host "Starting Go executor with go run..." -ForegroundColor Green
  return Start-Process -FilePath $go.Source `
    -ArgumentList "run", "." `
    -WorkingDirectory $root `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru
}

function Wait-ForHealth([string]$port, [int]$timeoutSeconds = 20) {
  $healthUrl = "http://127.0.0.1:$port/health"
  $deadline = (Get-Date).AddSeconds($timeoutSeconds)

  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 800
    try {
      $response = Invoke-RestMethod -UseBasicParsing -Uri $healthUrl -TimeoutSec 3
      return $response
    } catch {
    }
  }

  return $null
}

$config = Get-ExecutorConfig
$port = [string]$config["port"]

Stop-ExistingExecutor -port $port | Out-Null
$process = Start-Executor -port $port
$health = Wait-ForHealth -port $port

if ($null -ne $health) {
  Write-Host "Executor started successfully on :$port (PID $($process.Id))." -ForegroundColor Green
  Write-Host "Health: $($health | ConvertTo-Json -Compress)" -ForegroundColor DarkGreen
  Write-Host "Logs:" -ForegroundColor Cyan
  Write-Host "  $(Join-Path $logsDir 'executor.stdout.log')" -ForegroundColor Cyan
  Write-Host "  $(Join-Path $logsDir 'executor.stderr.log')" -ForegroundColor Cyan
  exit 0
}

Write-Host "Executor did not become healthy on :$port." -ForegroundColor Red
Write-Host "Please check the logs:" -ForegroundColor Yellow
Write-Host "  $(Join-Path $logsDir 'executor.stdout.log')" -ForegroundColor Yellow
Write-Host "  $(Join-Path $logsDir 'executor.stderr.log')" -ForegroundColor Yellow
exit 1
