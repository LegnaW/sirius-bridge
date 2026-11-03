@echo off
rem ============================================================
rem sirius-bridge deploy script
rem Builds the mod and copies the jar into a Minecraft mods directory.
rem Set SIRIUS_MODS_DIR to override the default HMCL test instance.
rem Idempotent: old sirius_bridge jars in mods are removed first.
rem
rem The build syncs this repository's schema directory into the jar.
rem Proxy flags match the local dev proxy; remove them if the
rem machine has direct internet access.
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "MODS_DIR=%SIRIUS_MODS_DIR%"
if not defined MODS_DIR set "MODS_DIR=..\Sirius-Minecraft\.minecraft\versions\1.21.1-Sirius\mods"

echo [deploy] Building sirius-bridge (NeoForge %~dp0) ...
call gradlew.bat build --console=plain -Dhttps.proxyHost=localhost -Dhttps.proxyPort=9674 -Dhttp.proxyHost=localhost -Dhttp.proxyPort=9674
if errorlevel 1 (
    echo [deploy] BUILD FAILED - deploy aborted.
    exit /b 1
)

if not exist "%MODS_DIR%" (
    echo [deploy] Mods directory not found, creating: %MODS_DIR%
    mkdir "%MODS_DIR%"
)

rem --- Remove old sirius_bridge jars so exactly one version remains ---
for %%F in ("%MODS_DIR%\sirius_bridge-*.jar") do (
    echo [deploy] Removing old jar: %%~nxF
    del /f /q "%%F"
)

rem --- Copy every non-sources/javadoc jar from build\libs ---
set "DEPLOYED="
for %%F in ("build\libs\sirius_bridge-*.jar") do (
    set "NAME=%%~nxF"
    if "!NAME:-sources=!"=="!NAME!" if "!NAME:-javadoc=!"=="!NAME!" (
        copy /y "%%F" "%MODS_DIR%\" >nul
        set "DEPLOYED=!NAME!"
    )
)

if not defined DEPLOYED (
    echo [deploy] ERROR: no deployable jar found in build\libs.
    exit /b 1
)

if not exist "libs\baritone-api-neoforge-1.11.2.jar" (
    echo [deploy] ERROR: the Baritone API jar was not downloaded.
    exit /b 1
)
copy /y "libs\baritone-api-neoforge-1.11.2.jar" "%MODS_DIR%\" >nul
for %%F in ("%MODS_DIR%\baritone-standalone-*.jar") do (
    echo [deploy] Removing incompatible standalone Baritone: %%~nxF
    del /f /q "%%F"
)

echo [deploy] Deployed: %MODS_DIR%\%DEPLOYED%
echo [deploy] Deployed: %MODS_DIR%\baritone-api-neoforge-1.11.2.jar
echo [deploy] Done.
endlocal
