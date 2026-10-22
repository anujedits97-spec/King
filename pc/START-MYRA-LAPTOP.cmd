@echo off
title MYRA laptop
rem Lets MYRA on your phone use this laptop from anywhere, through your Supabase project.
rem Keep this window open (it can be minimised). Close it to cut the phone off.
where node >nul 2>nul || (echo Node.js isn't installed. Get it from https://nodejs.org & pause & exit /b 1)
rem MYRA's voice (edge-tts). Microsoft changes its voice service now and then, and new
rem versions of edge-tts follow, so keep it up to date. Without Python the phone uses its own voice.
python -c "import sys" >nul 2>nul && (
  echo Checking MYRA's voice...
  python -m pip install --user --quiet --disable-pip-version-check --no-warn-script-location --timeout 8 --retries 0 --upgrade edge-tts >nul 2>nul
)
node "%~dp0worker.mjs"
pause
