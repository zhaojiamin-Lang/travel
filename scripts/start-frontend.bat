@echo off
REM Start the Vite dev server on port 5173, bound to IPv4 loopback so both
REM http://127.0.0.1:5173 and http://localhost:5173 work.
REM Keep this file ASCII-only: non-ASCII bytes break cmd.exe parsing under GBK.
cd /d D:\program\travel\frontend
if not exist logs mkdir logs
call npm.cmd run dev -- --host 127.0.0.1 >> logs\frontend.out.log 2>&1
