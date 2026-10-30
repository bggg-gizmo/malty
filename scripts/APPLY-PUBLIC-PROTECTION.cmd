@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
where py >nul 2>&1
if not errorlevel 1 (
    py -3 scripts\apply_public_rulesets.py %*
    exit /b %errorlevel%
)
where python >nul 2>&1
if not errorlevel 1 (
    python scripts\apply_public_rulesets.py %*
    exit /b %errorlevel%
)
echo ERROR: Python 3 is required to activate the Malty GitHub rulesets.
exit /b 2
