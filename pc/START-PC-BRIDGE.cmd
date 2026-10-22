@echo off
title MYRA PC bridge
rem Lets MYRA on your phone use this PC over your home Wi-Fi. Keep this window open.
rem The first time, Windows may ask whether Node.js can use the network: allow PRIVATE networks only.
where node >nul 2>nul || (echo Node.js isn't installed. Get it from https://nodejs.org & pause & exit /b 1)
node "%~dp0bridge.mjs"
pause
