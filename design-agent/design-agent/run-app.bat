@echo off
REM Design Agent Startup Script - Command Prompt
REM This script loads env vars from .env file and starts the JAR

cd /d "c:\Users\Yuvaraj.Arumugam\Workspace\20APR26\design-agent01\design-agent"

echo [*] Loading environment variables from .env file...

REM Read .env file and set environment variables
for /f "tokens=1,2 delims==" %%a in (.env) do (
    if "%%a"=="ANTHROPIC_API_KEY" (
        set "ANTHROPIC_API_KEY=%%b"
        echo [+] ANTHROPIC_API_KEY loaded (%ANTHROPIC_API_KEY:~0,15%...)
    )
    if "%%a"=="OPENAI_API_KEY" (
        set "OPENAI_API_KEY=%%b"
        echo [+] OPENAI_API_KEY loaded (%OPENAI_API_KEY:~0,15%...)
    )
)

echo.
echo [*] Environment variables set. Starting Design Agent...
echo [*] Port: 8091
echo [*] Health Check: http://localhost:8091/actuator/health
echo.

REM Start the JAR with Java 21 and disable OTLP metrics
"C:\Program Files\Zulu\zulu-21\bin\java.exe" ^
    -Dmanagement.otlp.metrics.export.enabled=false ^
    -jar target\design-agent-1.0.0-SNAPSHOT.jar ^
    --spring.profiles.active=local

pause
