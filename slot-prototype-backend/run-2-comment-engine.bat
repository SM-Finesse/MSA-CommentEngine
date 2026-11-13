@echo off
title Slot - comment-engine (8091)
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
cd /d "%~dp0"
call gradlew.bat :microservices:comment-engine:bootRun --console=plain
pause
