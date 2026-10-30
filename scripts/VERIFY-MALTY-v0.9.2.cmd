@echo off
setlocal
set "ROOT=%~dp0.."
set "APK=%ROOT%\downloads\v0.9.2\Malty-signed-v0.9.2.apk"
set "CHECKSUMS=%ROOT%\downloads\v0.9.2\SHA256SUMS.txt"
where py >nul 2>nul
if not errorlevel 1 (
  py -3 "%~dp0verify_v092_artifacts.py" --apk "%APK%" --checksums "%CHECKSUMS%" %*
  exit /b %errorlevel%
)
python "%~dp0verify_v092_artifacts.py" --apk "%APK%" --checksums "%CHECKSUMS%" %*
exit /b %errorlevel%
