@echo off
title Slot - finesse-api (8092)
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
cd /d "%~dp0"
call gradlew.bat :microservices:finesse-api:bootRun --console=plain
pause
