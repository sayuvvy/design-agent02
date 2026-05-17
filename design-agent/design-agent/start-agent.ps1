# Design Agent Startup Script (PowerShell)
# This script properly sets up environment variables and starts the agent

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Design Agent - Startup Script" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Check if .env file exists
if (-not (Test-Path ".env")) {
    Write-Host "ERROR: .env file not found!" -ForegroundColor Red
    Write-Host "Please create .env with ANTHROPIC_API_KEY and OPENAI_API_KEY"
    exit 1
}

# Extract API keys from .env
$env:ANTHROPIC_API_KEY = (Get-Content .env | Select-String 'ANTHROPIC_API_KEY' | ForEach-Object { $_.Line.Split('=')[1] })
$env:OPENAI_API_KEY = (Get-Content .env | Select-String 'OPENAI_API_KEY' | ForEach-Object { $_.Line.Split('=')[1] })

if ([string]::IsNullOrEmpty($env:ANTHROPIC_API_KEY)) {
    Write-Host "ERROR: ANTHROPIC_API_KEY not found in .env" -ForegroundColor Red
    exit 1
}

if ([string]::IsNullOrEmpty($env:OPENAI_API_KEY)) {
    Write-Host "ERROR: OPENAI_API_KEY not found in .env" -ForegroundColor Red
    exit 1
}

Write-Host "[✓] API keys loaded from .env" -ForegroundColor Green
Write-Host "[✓] ANTHROPIC_API_KEY: $($env:ANTHROPIC_API_KEY.Substring(0,15))..." -ForegroundColor Green
Write-Host "[✓] OPENAI_API_KEY: $($env:OPENAI_API_KEY.Substring(0,15))..." -ForegroundColor Green
Write-Host ""

# Check if Java 21 is available
$javaHome = "C:\Program Files\Zulu\zulu-21"
if (-not (Test-Path "$javaHome\bin\java.exe")) {
    Write-Host "ERROR: Java 21 not found at $javaHome" -ForegroundColor Red
    exit 1
}

Write-Host "[✓] Java 21 found at $javaHome" -ForegroundColor Green

# Check if JAR exists
if (-not (Test-Path "target/design-agent-1.0.0-SNAPSHOT.jar")) {
    Write-Host "ERROR: JAR not found. Run 'mvn clean package' first." -ForegroundColor Red
    exit 1
}

Write-Host "[✓] JAR file found" -ForegroundColor Green
Write-Host "[✓] Starting Design Agent on port 8091..." -ForegroundColor Green
Write-Host ""

# Start the app
& "$javaHome\bin\java.exe" `
    -Dmanagement.otlp.metrics.export.enabled=false `
    -jar target/design-agent-1.0.0-SNAPSHOT.jar `
    --spring.profiles.active=local
