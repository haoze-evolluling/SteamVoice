@echo off
setlocal

rem Build SteamVoice Desktop from this script's directory.
pushd "%~dp0"

where wails >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Wails CLI was not found on PATH.
    echo Install it with: go install github.com/wailsapp/wails/v2/cmd/wails@latest
    set "BUILD_EXIT=1"
    goto :done
)

where npm >nul 2>nul
if errorlevel 1 (
    echo [ERROR] npm was not found on PATH.
    set "BUILD_EXIT=1"
    goto :done
)

set "CGO_ENABLED=1"
where pkg-config >nul 2>nul
if errorlevel 1 (
    echo [ERROR] pkg-config was not found on PATH.
    set "BUILD_EXIT=1"
    goto :done
)
pkg-config --exists opus
if errorlevel 1 (
    echo [ERROR] libopus development files were not found by pkg-config.
    set "BUILD_EXIT=1"
    goto :done
)
echo Building SteamVoice Desktop...
wails build -tags steamvoice_opus
if errorlevel 1 (
    echo [ERROR] Desktop build failed.
    set "BUILD_EXIT=1"
    goto :done
)

echo [OK] Build completed. Output: build\bin\SteamVoice.exe
set "BUILD_EXIT=0"

:done
popd
exit /b %BUILD_EXIT%
