@echo off
setlocal EnableExtensions
set "ROOT=%~dp0.."
where py >nul 2>&1
if not errorlevel 1 (
  py -3 "%~dp0publish_v092_prerelease.py" %*
  exit /b %errorlevel%
)
where python >nul 2>&1
if errorlevel 1 (
  echo Python 3 not found on PATH.
  exit /b 2
)
python "%~dp0publish_v092_prerelease.py" %*
exit /b %errorlevel%
