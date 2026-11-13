@echo off
title Slot - tests
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
cd /d "%~dp0"
call gradlew.bat test --console=plain
pause
