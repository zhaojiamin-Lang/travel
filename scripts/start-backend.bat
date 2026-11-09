@echo off
REM Start the Spring Boot backend on port 8080.
REM Keep this file ASCII-only: non-ASCII bytes break cmd.exe parsing under GBK.
cd /d D:\program\travel\backend
if not exist logs mkdir logs
java -jar target\travel-guide-mvp-0.1.0.jar --server.port=8080 >> logs\backend.out.log 2>&1
