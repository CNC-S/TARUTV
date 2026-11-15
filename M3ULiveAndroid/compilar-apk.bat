@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "GRADLE_VERSION=8.13"
set "GRADLE_HOME=%USERPROFILE%\.m3ulive\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%TEMP%\gradle-%GRADLE_VERSION%-bin.zip"

if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if not exist "local.properties" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "'sdk.dir=' + $env:ANDROID_HOME.Replace('\','/')" > "local.properties"
)

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo Descargando Gradle %GRADLE_VERSION%...
    if not exist "%USERPROFILE%\.m3ulive" mkdir "%USERPROFILE%\.m3ulive"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%GRADLE_ZIP%'; Expand-Archive -LiteralPath '%GRADLE_ZIP%' -DestinationPath '%USERPROFILE%\.m3ulive' -Force; Remove-Item -LiteralPath '%GRADLE_ZIP%' -Force"
    if errorlevel 1 (
        echo ERROR: no se pudo descargar Gradle. Comprueba tu conexion a Internet.
        pause
        exit /b 1
    )
)

echo.
echo Compilando M3U Live TV...
call "%GRADLE_HOME%\bin\gradle.bat" --no-daemon assembleDebug
if errorlevel 1 (
    echo.
    echo ERROR: la compilacion ha fallado. Comprueba que Android SDK Platform 36 esta instalado y aceptaste sus licencias.
    pause
    exit /b 1
)

echo.
echo APK generado:
echo %CD%\app\build\outputs\apk\debug\app-debug.apk
pause
