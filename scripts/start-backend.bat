@echo off
REM Start the Spring Boot backend detached on port 8080.
REM javaw has no console window, so closing the launcher (or the scheduled task
REM finishing) does not take the server down with it.
REM Keep this file ASCII-only: non-ASCII bytes break cmd.exe parsing under GBK.
cd /d D:\program\travel\backend
if not exist logs mkdir logs
start "travel-backend" /b javaw -jar target\travel-guide-mvp-0.1.0.jar --server.port=8080 >> logs\backend.out.log 2>&1
