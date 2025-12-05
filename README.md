# Araxxor Tracker Plugin

## Build & Run (PowerShell)

```powershell
cd C:\Users\gshal\Desktop\Projects\runescape\plugin-hub\araxxortracker
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
.\gradlew.bat shadowJar
& "$env:JAVA_HOME\bin\java.exe" -ea -jar "build\libs\araxxortracker-1.0-SNAPSHOT-all.jar"
```

## Quick Re-launch (after already built)

```powershell
& "$env:JAVA_HOME\bin\java.exe" -ea -jar "build\libs\araxxortracker-1.0-SNAPSHOT-all.jar"
```

## What each command does

1. `cd ...` - Navigate to plugin directory
2. `$env:JAVA_HOME = ...` - Set Java 17 path
3. `.\gradlew.bat shadowJar` - Build fat JAR with all dependencies
4. `& "$env:JAVA_HOME\bin\java.exe" ...` - Launch RuneLite with plugin loaded
