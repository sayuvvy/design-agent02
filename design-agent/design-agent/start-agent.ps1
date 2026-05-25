# Design Agent — local startup script (PowerShell)
# Reads .env, validates prerequisites, starts the jar on port 8091.
#
# Usage (from design-agent/design-agent/):
#   .\start-agent.ps1
#
# Prerequisite: mvn clean package -DskipTests

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Design Agent - Local Startup" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# ── .env ──────────────────────────────────────────────────────────────────────
if (-not (Test-Path ".env")) {
    Write-Host "ERROR: .env file not found in $(Get-Location)" -ForegroundColor Red
    Write-Host "Create it with ANTHROPIC_API_KEY and OPENAI_API_KEY."
    exit 1
}

# Parse .env: skip blank lines and comments; split on first '=' only
Get-Content ".env" | Where-Object { $_ -match '^\s*[^#\s]' } | ForEach-Object {
    $idx = $_.IndexOf('=')
    if ($idx -gt 0) {
        $k = $_.Substring(0, $idx).Trim()
        $v = $_.Substring($idx + 1).Trim()
        [System.Environment]::SetEnvironmentVariable($k, $v, 'Process')
    }
}

if ([string]::IsNullOrEmpty($env:ANTHROPIC_API_KEY) -or
    $env:ANTHROPIC_API_KEY -like '*YOUR-ANTHROPIC-KEY*') {
    Write-Host "ERROR: ANTHROPIC_API_KEY is missing or still a placeholder in .env" -ForegroundColor Red
    exit 1
}

Write-Host "[OK] ANTHROPIC_API_KEY loaded ($($env:ANTHROPIC_API_KEY.Substring(0, [Math]::Min(20, $env:ANTHROPIC_API_KEY.Length)))...)" -ForegroundColor Green

if (-not [string]::IsNullOrEmpty($env:OPENAI_API_KEY) -and
    $env:OPENAI_API_KEY -notlike '*YOUR-OPENAI-KEY*' -and
    $env:OPENAI_API_KEY -ne 'sk-no-key-set') {
    Write-Host "[OK] OPENAI_API_KEY loaded (semantic memory enabled)" -ForegroundColor Green
} else {
    Write-Host "[--] OPENAI_API_KEY not set — semantic memory disabled" -ForegroundColor Yellow
    $env:OPENAI_API_KEY = "sk-no-key-set"
}

Write-Host ""

# ── Java ───────────────────────────────────────────────────────────────────────
$JAVA = "C:\Program Files\Java\jdk-21.0.10\bin\java.exe"
if (-not (Test-Path $JAVA)) {
    # Fallback: use java on PATH
    $JAVA = (Get-Command java -ErrorAction SilentlyContinue)?.Source
}
if (-not $JAVA) {
    Write-Host "ERROR: Java 21 not found. Set JAVA_HOME or install JDK 21." -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Java: $JAVA" -ForegroundColor Green

# ── JAR ────────────────────────────────────────────────────────────────────────
$JAR = "target\design-agent-1.0.0-SNAPSHOT.jar"
if (-not (Test-Path $JAR)) {
    Write-Host "ERROR: $JAR not found." -ForegroundColor Red
    Write-Host "Run:  mvn clean package -DskipTests" -ForegroundColor Yellow
    exit 1
}
Write-Host "[OK] JAR: $JAR" -ForegroundColor Green
Write-Host ""
Write-Host "Starting on http://localhost:8091 ..." -ForegroundColor Cyan
Write-Host "Health: http://localhost:8091/actuator/health" -ForegroundColor Yellow
Write-Host "Press Ctrl+C to stop." -ForegroundColor Yellow
Write-Host ""

# ── Launch ─────────────────────────────────────────────────────────────────────
& $JAVA `
    "-Dmanagement.otlp.metrics.export.enabled=false" `
    "-Dspring.profiles.active=local" `
    -jar $JAR
