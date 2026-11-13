@echo off
title Slot - mock llm-server (9000)
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
cd /d "%~dp0"
"%JAVA_HOME%\bin\java" tools\MockLlmServer.java 9000
pause
