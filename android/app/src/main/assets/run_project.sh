# MYRA project runner. It runs inside Termux:  bash -c "<this text>" runner <project dir> <install|no> <seconds>
# It copies the project, finds the language, INSTALLS WHAT IS MISSING (language, compiler, build tools, libraries,
# packages) when the second argument is "install" (the default), installs dependencies, runs tests if there are any,
# then runs the program with a time limit. It only prints RUNNER: lines and the tail of each step's output.
SRC="$1"; AUTO="${2:-install}"; T="${3:-40}"
OUT_MAX=2500
[ -d "$SRC" ] || { echo "RUNNER:NO_SOURCE"; exit 0; }
BASE="${HOME}/myra-run"; NAME="$(basename "$SRC")"; WORK="$BASE/$NAME"; LOG="$BASE/.last-output"
mkdir -p "$BASE" 2>/dev/null; rm -rf "$WORK"
cp -r "$SRC" "$WORK" 2>/dev/null || { echo "RUNNER:COPY_FAILED"; exit 0; }
cd "$WORK" || { echo "RUNNER:COPY_FAILED"; exit 0; }
rm -rf .history .trash .git node_modules __pycache__ 2>/dev/null
export DEBIAN_FRONTEND=noninteractive
have() { command -v "$1" >/dev/null 2>&1; }
anyf() { find . -maxdepth 2 -name "$1" -not -path './node_modules/*' 2>/dev/null | grep -q .; }
first_file() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return; }; done; }
LAST=0
step() { n="$1"; t="$2"; shift 2; echo "RUNNER:STEP $n"; timeout "$t" "$@" >"$LOG" 2>&1 </dev/null; LAST=$?; tail -c $OUT_MAX "$LOG"; echo; echo "RUNNER:EXIT $n $LAST"; }

# ── installing ──
PKG_UPDATED=0
pkg_install() { # package names...
  have pkg || return 1
  if [ "$PKG_UPDATED" = "0" ]; then PKG_UPDATED=1; timeout 300 pkg update -y >"$LOG" 2>&1 </dev/null; fi
  timeout 1500 pkg install -y "$@" >"$LOG" 2>&1 </dev/null; c=$?
  if [ $c -ne 0 ] && grep -qi "unable to locate\|no packages found\|404" "$LOG"; then timeout 300 pkg update -y >"$LOG" 2>&1 </dev/null; timeout 1500 pkg install -y "$@" >"$LOG" 2>&1 </dev/null; c=$?; fi
  if [ $c -eq 0 ]; then for p in "$@"; do echo "RUNNER:INSTALLED $p"; done; else echo "RUNNER:INSTALLFAIL $*"; fi
  return $c
}
need() { # need <command> <termux package>: install the tool when it is missing
  have "$1" && return 0
  if [ "$AUTO" = "install" ]; then pkg_install "$2"; have "$1" && return 0; echo "RUNNER:MISSING $1 $2 tried"
  else echo "RUNNER:MISSING $1 $2 notried"; fi
  echo "RUNNER:DONE"; exit 0
}
heal_build() { # after a failed install or build: install the build tools or libraries the error talks about. 0 = something was installed
  [ "$AUTO" = "install" ] && have pkg || return 1
  pk=""
  grep -qiE "gcc|g\+\+|cc: not found|clang|command 'cc'|make: not found|gyp ERR|Failed building wheel|cmake|pkg-config|cannot find -l" "$LOG" && pk="clang make cmake pkg-config binutils libffi openssl"
  grep -qiE "rust|cargo" "$LOG" && pk="$pk rust"
  grep -qiE "jpeg|png|freetype|zlib" "$LOG" && pk="$pk libjpeg-turbo libpng freetype zlib"
  grep -qiE "xml2|xslt|libxml" "$LOG" && pk="$pk libxml2 libxslt"
  h="$(grep -oE "fatal error: [A-Za-z0-9_/.+-]+\.h" "$LOG" | head -1 | sed 's/fatal error: //')"
  case "$h" in curl/*) pk="$pk libcurl";; openssl/*) pk="$pk openssl";; zlib.h|zconf.h) pk="$pk zlib";; sqlite3.h) pk="$pk libsqlite";; png.h) pk="$pk libpng";; jpeglib.h) pk="$pk libjpeg-turbo";; ncurses.h|curses.h) pk="$pk ncurses";; SDL2/*) pk="$pk sdl2";; ffi.h) pk="$pk libffi";; esac
  [ -n "$pk" ] || return 1
  pkg_install $pk
}
step_heal() { # a step that, when it fails, installs what the error shows is missing and tries once more
  step "$@"
  if [ "$LAST" != "0" ] && heal_build; then step "$@"; fi
}
flag_pkg() { # a package had to be installed at run time: tell MYRA to add it to the project's own list
  f="$1"; p="$2"; shift 2
  if [ -f "$f" ] && grep -qiE "(^|[^a-z0-9_-])$p([^a-z0-9_-]|\$)" "$f"; then return; fi
  echo "RUNNER:INSTALLED $p-not-listed-in-$f"
}

L=""
if [ -f package.json ]; then L=node
elif [ -f go.mod ]; then L=go
elif [ -f Cargo.toml ]; then L=rust
elif [ -f requirements.txt ] || anyf '*.py'; then L=python
elif anyf '*.java'; then L=java
elif anyf '*.cpp' || anyf '*.cc'; then L=cpp
elif anyf '*.c'; then L=c
elif anyf '*.sh'; then L=bash
elif [ -f index.html ]; then echo "RUNNER:LANG static"; echo "RUNNER:DONE"; exit 0
fi
[ -n "$L" ] || { echo "RUNNER:LANG unknown"; echo "RUNNER:DONE"; exit 0; }
echo "RUNNER:LANG $L"

run_py() {
  step run "$T" "$PY" "$E"
  if [ "$LAST" != "0" ] && [ "$LAST" != "124" ] && [ "$AUTO" = "install" ]; then
    M="$(grep -oE "No module named '[^'.]+" "$LOG" | head -1 | sed "s/.*'//")"
    if [ -n "$M" ]; then
      case "$M" in cv2) P=opencv-python;; PIL) P=Pillow;; yaml) P=pyyaml;; bs4) P=beautifulsoup4;; sklearn) P=scikit-learn;; dotenv) P=python-dotenv;; telegram) P=python-telegram-bot;; *) P="$M";; esac
      step_heal install 600 "$PY" -m pip install -q "$P"
      if [ "$LAST" = "0" ]; then flag_pkg requirements.txt "$P"; step run "$T" "$PY" "$E"; fi
    fi
  fi
}
run_node() { # command...
  step run "$T" "$@"
  if [ "$LAST" != "0" ] && [ "$LAST" != "124" ] && [ "$AUTO" = "install" ]; then
    M="$(grep -oE "Cannot find module '[^'./][^']*'" "$LOG" | head -1 | sed "s/.*module '//; s/'$//")"
    if [ -n "$M" ]; then
      step_heal install 600 npm install --no-audit --no-fund --loglevel=error "$M"
      if [ "$LAST" = "0" ]; then flag_pkg package.json "$M"; step run "$T" "$@"; fi
    fi
  fi
}

case "$L" in
python)
  PY="$(command -v python3 || command -v python)"
  if [ -z "$PY" ]; then need python python; PY="$(command -v python3 || command -v python)"; fi
  [ -f requirements.txt ] && step_heal install 600 "$PY" -m pip install -q -r requirements.txt
  if [ -d tests ] || anyf 'test_*.py'; then
    if "$PY" -c "import pytest" 2>/dev/null; then step tests 120 "$PY" -m pytest -q -x
    elif [ "$AUTO" = "install" ] && "$PY" -m pip install -q pytest >/dev/null 2>&1; then step tests 120 "$PY" -m pytest -q -x
    else step tests 120 "$PY" -m unittest discover -s . ; fi
  fi
  E="$(first_file main.py app.py run.py bot.py server.py __main__.py)"; [ -n "$E" ] || E="$(ls ./*.py 2>/dev/null | head -1)"
  if [ -n "$E" ]; then run_py; else echo "RUNNER:NO_ENTRY"; fi ;;
node)
  need node nodejs
  step_heal install 600 npm install --no-audit --no-fund --loglevel=error
  if grep -q '"test"' package.json && ! grep -q 'no test specified' package.json; then step tests 120 npm test --silent; fi
  if grep -q '"start"' package.json; then run_node npm start --silent
  else E="$(first_file index.js main.js app.js server.js bot.js)"; if [ -n "$E" ]; then run_node node "$E"; else echo "RUNNER:NO_ENTRY"; fi; fi ;;
go)
  need go golang
  anyf '*_test.go' && step tests 180 go test ./...
  step run "$T" go run . ;;
rust)
  need cargo rust
  step tests 600 cargo test -q
  step run "$T" cargo run -q ;;
java)
  need javac openjdk-17
  step build 180 javac ./*.java
  if [ "$LAST" = "0" ]; then
    M="$(grep -l 'static void main' ./*.java 2>/dev/null | head -1 | sed 's#^\./##; s#\.java$##')"
    if [ -n "$M" ]; then step run "$T" java -cp . "$M"; else echo "RUNNER:NO_ENTRY"; fi
  fi ;;
c)
  if ! have gcc && ! have clang; then need clang clang; fi
  CC="$(command -v gcc || command -v clang)"
  step_heal build 180 "$CC" ./*.c -o app -lm
  [ "$LAST" = "0" ] && step run "$T" ./app ;;
cpp)
  if ! have g++ && ! have clang++; then need clang clang; fi
  CXX="$(command -v g++ || command -v clang++)"
  step_heal build 180 "$CXX" ./*.cpp -o app
  [ "$LAST" = "0" ] && step run "$T" ./app ;;
bash)
  E="$(first_file main.sh run.sh start.sh)"; [ -n "$E" ] || E="$(ls ./*.sh 2>/dev/null | head -1)"
  step run "$T" bash "$E" ;;
esac
echo "RUNNER:DONE"
