@echo off
:: setup.bat — Bootstrap script for Etendo (Windows)
::
:: Flow:
::   1. Require JAVA_HOME to be set
::   2. If githubToken is missing -> run GitHub Device Flow auth (gradle\setup.java)
::      Abort immediately if auth fails
::   3. Always launch gradlew.bat setup.web (or the task passed as argument)
::
:: Usage:
::   setup.bat               runs setup.web (default)
::   setup.bat <task>        runs any gradle task

setlocal enabledelayedexpansion

set "TASK=%~1"
if "%TASK%"=="" set "TASK=setup.web"

set "PROPS_FILE=gradle.properties"

:: ── 1. Require JAVA_HOME ─────────────────────────────────────────────────────
if not defined JAVA_HOME (
    echo.
    echo ERROR: JAVA_HOME is not set.
    echo   Please set JAVA_HOME to a Java 17+ installation and re-run.
    echo   Example: set JAVA_HOME=C:\Program Files\Java\jdk-17
    echo.
    exit /b 1
)

set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_CMD%" (
    echo.
    echo ERROR: Java binary not found at: %JAVA_CMD%
    echo   Check that JAVA_HOME points to a valid Java installation.
    echo.
    exit /b 1
)

:: ── 2. GitHub auth if token not set ──────────────────────────────────────────
set "EXISTING="
for /f "tokens=1* delims==" %%A in ('findstr /r /c:"^githubToken=." "%PROPS_FILE%" 2^>nul') do (
    set "EXISTING=%%B"
)

if "!EXISTING!"=="" (
    echo Starting GitHub authentication UI...
    "%JAVA_CMD%" gradle\setup.java
    if !ERRORLEVEL! neq 0 (
        echo.
        echo ERROR: GitHub authentication failed. Setup aborted.
        echo.
        exit /b 1
    )
)

:: ── 3. Always launch Gradle ───────────────────────────────────────────────────
call gradlew.bat %TASK%
exit /b %ERRORLEVEL%
