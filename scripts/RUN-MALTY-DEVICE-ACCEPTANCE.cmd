@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
where py >nul 2>&1
if not errorlevel 1 goto use_py
where python >nul 2>&1
if not errorlevel 1 goto use_python
echo ERROR: Python 3 is required.
exit /b 3
:use_py
py -3 scripts\device_acceptance_v092.py %*
exit /b %errorlevel%
:use_python
python scripts\device_acceptance_v092.py %*
exit /b %errorlevel%
