@echo off
REM Design Agent Startup Script with Environment Setup
REM This script properly sets up environment variables and starts the agent

setlocal enabledelayedexpansion

cd /d "%~dp0"

echo.
echo ============================================================
echo  Design Agent - Startup Script
echo ============================================================
echo.

REM Check if .env file exists
if not exist ".env" (
    echo ERROR: .env file not found!
    echo Please create .env with ANTHROPIC_API_KEY and OPENAI_API_KEY
    exit /b 1
)

REM Extract API keys from .env
for /f "tokens=2 delims==" %%a in ('findstr /B "ANTHROPIC_API_KEY" .env') do set "ANTHROPIC_API_KEY=%%a"
for /f "tokens=2 delims==" %%a in ('findstr /B "OPENAI_API_KEY" .env') do set "OPENAI_API_KEY=%%a"

if "!ANTHROPIC_API_KEY!"=="" (
    echo ERROR: ANTHROPIC_API_KEY not found in .env
    exit /b 1
)

if "!OPENAI_API_KEY!"=="" (
    echo ERROR: OPENAI_API_KEY not found in .env
    exit /b 1
)

echo [✓] API keys loaded from .env
echo [✓] ANTHROPIC_API_KEY: !ANTHROPIC_API_KEY:~0,15!...
echo [✓] OPENAI_API_KEY: !OPENAI_API_KEY:~0,15!...
echo.

REM Check if Java 21 is available
set "JAVA_HOME=C:\Program Files\Zulu\zulu-21"
if not exist "!JAVA_HOME!\bin\java.exe" (
    echo ERROR: Java 21 not found at !JAVA_HOME!
    exit /b 1
)

echo [✓] Java 21 found at !JAVA_HOME!
echo [✓] Starting Design Agent on port 8091...
echo.

REM Start the app
"!JAVA_HOME!\bin\java.exe" ^
    -Dmanagement.otlp.metrics.export.enabled=false ^
    -jar target/design-agent-1.0.0-SNAPSHOT.jar ^
    --spring.profiles.active=local

pause
