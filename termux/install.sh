#!/data/data/com.termux/files/usr/bin/bash
# MYRA for your phone - Termux installer.
#
#   curl -fsSL https://raw.githubusercontent.com/Dynamox-DEV677/myra-phone/main/termux/install.sh | bash
#
# Safe to run again: it updates what's there and keeps your settings and token.
set -euo pipefail

REPO="Dynamox-DEV677/myra-phone"
DIR="$HOME/myra-phone"
ORANGE=$'\033[38;5;208m'; DIM=$'\033[2m'; OFF=$'\033[0m'
say()  { printf '\n%s▸ %s%s\n' "$ORANGE" "$*" "$OFF"; }
note() { printf '%s  %s%s\n' "$DIM" "$*" "$OFF"; }

if [ ! -d /data/data/com.termux ] || [ -z "${PREFIX:-}" ]; then
  echo "This installer is for Termux on Android. Open Termux on your phone and run it there."
  exit 1
fi

say "Installing packages: Node.js, git, curl and the Termux:API tools"
yes | pkg update >/dev/null 2>&1 || true
pkg install -y nodejs-lts git curl termux-api >/dev/null

say "Getting MYRA from github.com/$REPO"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" pull --ff-only --quiet
else
  git clone --quiet --depth 1 "https://github.com/$REPO.git" "$DIR"
fi
chmod +x "$DIR/termux/myra"
ln -sf "$DIR/termux/myra" "$PREFIX/bin/myra"

export PATH="$HOME/.local/bin:$PATH"
if ! command -v agy >/dev/null 2>&1; then
  say "Installing the Antigravity CLI (agy) - Google's official installer, Android build"
  curl -fsSL https://antigravity.google/cli/install.sh | bash
  export PATH="$HOME/.local/bin:$PATH"
fi
grep -q '.local/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"
# Lets agy open its Google sign-in page in the phone's browser (needs the Termux:API app).
grep -q 'BROWSER=termux-open-url' "$HOME/.bashrc" 2>/dev/null || echo 'export BROWSER=termux-open-url' >> "$HOME/.bashrc"
export BROWSER=termux-open-url
# The installer runs in its own shell, so the PATH line above only helps NEW Termux windows.
# A link in Termux's own bin folder makes `agy` work straight away, in this window too.
if [ -x "$HOME/.local/bin/agy" ]; then
  ln -sf "$HOME/.local/bin/agy" "$PREFIX/bin/agy"
else
  echo "The Antigravity CLI didn't install. Check your internet, then run the installer again."
  exit 1
fi

say "Setting up MYRA"
# Lets the MYRA app start the brain for you (only apps you grant the permission to can use this).
mkdir -p "$HOME/.termux"
touch "$HOME/.termux/termux.properties"
if grep -q '^allow-external-apps' "$HOME/.termux/termux.properties"; then
  sed -i 's/^allow-external-apps.*/allow-external-apps=true/' "$HOME/.termux/termux.properties"
else
  echo 'allow-external-apps=true' >> "$HOME/.termux/termux.properties"
fi
termux-reload-settings 2>/dev/null || true

# Your name goes only into the copy installed on this phone - never into the public repo.
mkdir -p "$HOME/.myra"
if [ ! -f "$HOME/.myra/owner" ]; then
  printf '\n  What should MYRA call you? (your first name): '
  read -r OWNER < /dev/tty || OWNER=""
  printf '%s\n' "${OWNER:-the user}" > "$HOME/.myra/owner"
fi

myra setup

if [ -d "$HOME/.termux/boot" ]; then
  ln -sf "$DIR/termux/boot.sh" "$HOME/.termux/boot/myra.sh"
  note "Termux:Boot found - the brain will start by itself after a restart."
fi

cat <<EOF

${ORANGE}MYRA is installed.${OFF} Four steps left:

  1. Sign in to agy with your Google account (the one with Google AI Pro):
       ${ORANGE}agy${OFF}
     The sign-in page opens in your browser. If it doesn't: long-press the
     screen, tap More > Select URL, copy the link and open it in Chrome.
     Sign in, then type /exit.

  2. Start the brain:
       ${ORANGE}myra start${OFF}

  3. Install the MYRA app on this phone:
       ${ORANGE}myra app${OFF}
     Open it, give it the permissions it asks for, and paste the token from:
       ${ORANGE}myra token${OFF}

  4. (Optional) Let MYRA use your PC: start the PC bridge on the PC
     (see the README), then run the pair command it shows, here in Termux.

Say "Myra" or tap the orange orb. Run ${ORANGE}myra help${OFF} any time.
EOF
