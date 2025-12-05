@echo off
REM ========================================
REM Build and Test Araxxor Tracker Plugin
REM For Plugin Hub submission
REM ========================================

setlocal
title Araxxor Plugin Build

REM Set Java 11+ (required for RuneLite plugins)
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

echo.
echo ========================================
echo  Building Araxxor Tracker Plugin
echo ========================================
echo.

REM Build with Gradle
call gradlew.bat clean build

if errorlevel 1 (
    echo.
    echo ========================================
    echo  BUILD FAILED!
    echo ========================================
    echo.
    pause
    exit /b 1
)

echo.
echo ========================================
echo  BUILD SUCCESS!
echo ========================================
echo.
echo JAR location: build\libs\araxxortracker-1.0-SNAPSHOT.jar
echo.
echo To test locally:
echo   1. Copy JAR to RuneLite external plugins folder
echo   2. Or use RuneLite developer mode
echo.
echo To submit to Plugin Hub:
echo   1. Commit and push to your GitHub repo
echo   2. Create PR to runelite/plugin-hub
echo.
pause





