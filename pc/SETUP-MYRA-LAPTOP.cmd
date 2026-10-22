@echo off
title MYRA laptop setup
rem Once: connects this laptop to your Supabase project and shows the QR code to pair your phone.
where node >nul 2>nul || (echo Node.js isn't installed. Get it from https://nodejs.org & pause & exit /b 1)
node "%~dp0worker.mjs" setup
pause
