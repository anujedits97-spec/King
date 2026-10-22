#!/bin/bash
# Tests heal_build from assets/run_project.sh against sample error logs, with a fake `pkg`. Usage: bash tests/runner_heal_test.sh
D="$(cd "$(dirname "$0")/.." && pwd)"; SCRIPT="$D/android/app/src/main/assets/run_project.sh"
W="$(mktemp -d)"; mkdir -p "$W/bin"; export PATH="$W/bin:$PATH"
printf '#!/bin/bash\necho "$*" >> %s/pkg.log\nexit 0\n' "$W" > "$W/bin/pkg"; chmod +x "$W/bin/pkg"
LOG="$W/log.txt"; AUTO=install
eval "$(sed -n '/^have() {/,/^LAST=0/p' "$SCRIPT" | sed '$d')"
eval "$(sed -n '/^# ── installing ──/,/^step_heal()/p' "$SCRIPT" | sed '$d')"
bad=0
t() { rm -f "$W/pkg.log"; printf '%s\n' "$3" > "$LOG"; PKG_UPDATED=1; heal_build >/dev/null; r=$?
  got="$(cat "$W/pkg.log" 2>/dev/null)"; if [ "$r" = "$2" ] && { [ -z "$4" ] || echo "$got" | grep -q "$4"; }; then echo "PASS $1"; else echo "FAIL $1 (return $r, pkg: $got)"; bad=1; fi; }
t "gcc missing" 0 "error: command 'gcc' failed" "clang"
t "wheel build" 0 "ERROR: Failed building wheel for x" "clang"
t "rust needed" 0 "error: can't find Rust compiler" "rust"
t "jpeg"        0 "could not be found for jpeg" "libjpeg-turbo"
t "xml"         0 "libxml2 and libxslt development packages" "libxslt"
t "curl header" 0 "fatal error: curl/curl.h: No such file" "libcurl"
t "plain error" 1 "SyntaxError: invalid syntax" ""
rm -rf "$W"; exit $bad
