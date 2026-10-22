#!/data/data/com.termux/files/usr/bin/bash
# Started by Termux:Boot after the phone restarts: brings MYRA's brain back up.
termux-wake-lock
exec "$PREFIX/bin/myra" start
