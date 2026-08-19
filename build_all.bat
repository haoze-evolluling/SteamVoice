@echo off
setlocal EnableExtensions

rem Build both SteamVoice artifacts from the repository root.
pushd "%~dp0"

set "PAUSE_AT_END=1"
if /i "%~1"=="--no-pause" set "PAUSE_AT_END=0"

set "BUILD_EXIT=1"
set "ANDROID_APK=SteamVoice-Android\app\build\outputs\apk\debug\app-debug.apk"

echo [1/2] Building Android debug APK...
pushd "SteamVoice-Android"
call "gradlew.bat" assembleDebug
set "ANDROID_EXIT=%ERRORLEVEL%"
popd
if not "%ANDROID_EXIT%"=="0" (
    echo [ERROR] Android debug APK build failed.
    goto :done
)

if not exist "%ANDROID_APK%" (
    echo [ERROR] Android build completed but APK was not found:
    echo         %ANDROID_APK%
    goto :done
)
echo [OK] Android APK: %ANDROID_APK%

echo [2/2] Building Windows desktop artifact...
call "SteamVoice-Desktop\build.bat" --no-pause
if errorlevel 1 (
    echo [ERROR] Windows desktop build failed.
    goto :done
)

set "INSTALLER="
for /f "delims=" %%I in ('dir /b /a-d "SteamVoice-Desktop\build\bin\*installer*.exe" 2^>nul') do set "INSTALLER=SteamVoice-Desktop\build\bin\%%I"
if not defined INSTALLER (
    echo [ERROR] Desktop build completed but no installer was found in:
    echo         SteamVoice-Desktop\build\bin
    goto :done
)

echo [OK] Windows installer: %INSTALLER%
set "BUILD_EXIT=0"

:done
popd
if "%PAUSE_AT_END%"=="1" (
    echo.
    if "%BUILD_EXIT%"=="0" (
        echo Build finished successfully.
    ) else (
        echo Build stopped with errors. Review the message above.
    )
    pause
)
exit /b %BUILD_EXIT%
