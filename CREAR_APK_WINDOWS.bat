@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Chicha Sonic - Generar APK Android
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not exist "%ANDROID_HOME%\platforms\android-35\android.jar" (
 echo.
 echo FALTA Android SDK 35. Instala Android Studio y Android SDK Platform 35.
 echo https://developer.android.com/studio
 pause
 exit /b 1
)
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
 set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
) else (
 if "%JAVA_HOME%"=="" (
   echo FALTA Java. Instala Android Studio primero.
   pause
   exit /b 1
 )
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "DEST=%USERPROFILE%\.gradle\chicha-sonic-distribution"
set "GRADLE=%DEST%\gradle-8.11.1\bin\gradle.bat"
if not exist "%GRADLE%" (
 echo Descargando Gradle 8.11.1 desde el sitio oficial... esto requiere Internet.
 if not exist "%DEST%" mkdir "%DEST%"
 powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $u='https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'; $f=Join-Path $env:TEMP 'chichasonic-gradle-8.11.1.zip'; Invoke-WebRequest -Uri $u -OutFile $f; Expand-Archive -LiteralPath $f -DestinationPath $env:DEST -Force; Remove-Item $f" 
 if errorlevel 1 (
   echo ERROR descargando Gradle. Verifica Internet.
   pause
   exit /b 1
 )
)
echo Compilando el APK... 
call "%GRADLE%" assembleDebug --no-daemon --stacktrace
if errorlevel 1 (
 echo ERROR al compilar. Comparte una captura del error de esta ventana.
 pause
 exit /b 1
)
echo.
echo APK CREADO: %CD%\app\build\outputs\apk\debug\app-debug.apk
explorer "%CD%\app\build\outputs\apk\debug"
pause
