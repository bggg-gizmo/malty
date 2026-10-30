@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
where gh >nul 2>&1
if errorlevel 1 (
  echo GitHub CLI is required. Install gh and run gh auth login first.
  exit /b 2
)
where py >nul 2>&1
if not errorlevel 1 (
  py -3 scripts\tag_and_protect_public.py %*
  exit /b %errorlevel%
)
where python >nul 2>&1
if not errorlevel 1 (
  python scripts\tag_and_protect_public.py %*
  exit /b %errorlevel%
)
echo Python 3 is required.
exit /b 2
