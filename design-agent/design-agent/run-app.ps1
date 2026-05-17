# Design Agent Startup Script - PowerShell
# This script loads env vars from .env file and starts the JAR

Write-Host "[*] Navigating to project directory..." -ForegroundColor Cyan
cd "c:\Users\Yuvaraj.Arumugam\Workspace\20APR26\design-agent01\design-agent"

Write-Host "[*] Loading environment variables from .env file..." -ForegroundColor Cyan

# Extract and set ANTHROPIC_API_KEY
$anthropicKey = (Get-Content .env | Select-String 'ANTHROPIC_API_KEY' | ForEach-Object { $_.Line.Split('=')[1] })
$env:ANTHROPIC_API_KEY = $anthropicKey
Write-Host "[+] ANTHROPIC_API_KEY loaded ($($anthropicKey.Substring(0, 15))...)" -ForegroundColor Green

# Extract and set OPENAI_API_KEY
$openaiKey = (Get-Content .env | Select-String 'OPENAI_API_KEY' | ForEach-Object { $_.Line.Split('=')[1] })
$env:OPENAI_API_KEY = $openaiKey
Write-Host "[+] OPENAI_API_KEY loaded ($($openaiKey.Substring(0, 15))...)" -ForegroundColor Green

Write-Host ""
Write-Host "[*] Environment variables set. Starting Design Agent..." -ForegroundColor Cyan
Write-Host "[*] Port: 8091" -ForegroundColor Yellow
Write-Host "[*] Health Check: http://localhost:8091/actuator/health" -ForegroundColor Yellow
Write-Host ""

# Start the JAR with Java 21 and disable OTLP metrics
& "C:\Program Files\Zulu\zulu-21\bin\java.exe" `
    -Dmanagement.otlp.metrics.export.enabled=false `
    -jar target\design-agent-1.0.0-SNAPSHOT.jar `
    --spring.profiles.active=local

Write-Host "[*] Application stopped." -ForegroundColor Red
