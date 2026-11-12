@echo off
rem Build helper. Uses relative paths only so the file stays pure ASCII
rem (the project lives under a Chinese-named directory).
cd /d "%~dp0"
set "BUILDROOT=%~dp0..\.android-build"
set "JAVA_HOME=%BUILDROOT%\jdk\jdk-17.0.21+9"
set "ANDROID_HOME=%BUILDROOT%\sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
call "%BUILDROOT%\gradle\gradle-8.7\bin\gradle.bat" %*
