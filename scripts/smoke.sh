#!/usr/bin/env bash
# SeniorAlert minimal emulator smoke test: install, add alarm for next minute,
# wait for ring, tap "Turn off", assert RingService stopped.
# Usage: scripts/smoke.sh [serial]
set -u
PKG=app.phueber.senioralert
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/home/philipp/.local/opt/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
APK=$(ls "$ROOT"/app/build/outputs/apk/debug/SeniorAlert-*-debug.apk 2>/dev/null | head -1)
SERIAL="${1:-$(adb devices | awk '/emulator-[0-9]+\tdevice/{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "FAIL no device"; exit 2; }
SH() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }
XML=$(mktemp)
cleanup() { SH pm clear "$PKG" >/dev/null 2>&1; rm -f "$XML"; }
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; cleanup; exit 1; }

dump_ui() { # retry: uiautomator dump flakes with "could not get idle state"
  for _ in 1 2 3 4 5 6; do
    SH uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 \
      && adb -s "$SERIAL" pull /sdcard/ui.xml "$XML" >/dev/null 2>&1 \
      && grep -q '<hierarchy' "$XML" && return 0
    sleep 1
  done
  return 1
}
find_node() { # find_node text|resource-id value -> "cx cy"
  python3 -I - "$XML" "$1" "$2" <<'PY'
import sys, re, xml.etree.ElementTree as ET
f, key, val = sys.argv[1:4]
for n in ET.parse(f).getroot().iter('node'):
    v = n.get(key, '')
    lines = [l.strip().lower() for l in v.split('\n')]
    if v == val or (key == 'text' and val.lower() in lines):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
        if m:
            a, b, c, d = map(int, m.groups()); 
            # multi-line node (e.g. "Alarm is ringing\nTurn off"): tap the matching line's band
            i = lines.index(val.lower()) if key == 'text' and val.lower() in lines else 0
            print((a + c) // 2, b + (d - b) * (2 * i + 1) // (2 * len(lines))); sys.exit(0)
sys.exit(1)
PY
}
tap_key() { local xy; for _ in 1 2 3 4; do
    dump_ui && xy=$(find_node "$1" "$2") && { echo "  tap $2 at $xy"; SH input tap $xy >/dev/null; return 0; }; sleep 1; done
  echo "  (not found: $1=$2; visible: $(grep -o ' text="[^"]*"' "$XML" | tr '\n' ' '); top: $(SH dumpsys activity activities | grep -E 'topResumedActivity' | head -1))"; return 1; }
tap_text() { tap_key text "$1"; }
service_running() { SH dumpsys activity services "$PKG" | grep -q 'ServiceRecord.*RingService'; }
wait_service() { # up|down timeout
  local end=$((SECONDS+$2))
  while [ $SECONDS -lt $end ]; do
    if service_running; then [ "$1" = up ] && return 0; else [ "$1" = down ] && return 0; fi
    sleep 1
  done; return 1; }

echo "== $SERIAL (API $(SH getprop ro.build.version.sdk)) =="
API=$(SH getprop ro.build.version.sdk)
(cd "$ROOT" && ./gradlew assembleDebug -q) && pass "assembleDebug" || fail "assembleDebug"
APK=$(ls "$ROOT"/app/build/outputs/apk/debug/SeniorAlert-*-debug.apk | head -1)
adb -s "$SERIAL" install -r "$APK" 2>&1 | grep -q Success && pass "install" || fail "install"
SH pm clear "$PKG" >/dev/null

[ "$API" -ge 33 ] && SH pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
[ "$API" -ge 34 ] && SH appops set "$PKG" USE_FULL_SCREEN_INTENT allow >/dev/null 2>&1
SH dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
SH settings put system time_12_24 24
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  SH settings put global $s 0 >/dev/null; done
SH input keyevent KEYCODE_WAKEUP >/dev/null; SH wm dismiss-keyguard >/dev/null 2>&1

SH am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 2
SH dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | grep -q "$PKG/.*MainActivity" \
  && pass "MainActivity resumed" || fail "MainActivity not resumed"

# alarm for next minute (skip to +2 if <20s left in this one)
sec=$((10#$(SH date +%S))); add=60; [ "$sec" -ge 40 ] && add=120
target=$(SH date -d "@$(( $(SH date +%s) + add ))" +%H:%M)
h=$((10#${target%%:*})); m=$((10#${target##*:}))
echo "  alarm target $target (device now $(SH date +%H:%M:%S))"
tap_text "Add alarm" || fail "Add alarm not found"
sleep 1
dump_ui; find_node resource-id android:id/input_hour >/dev/null || { tap_key resource-id android:id/toggle_mode; sleep 1; }
for f in "input_hour $h" "input_minute $m"; do
  set -- $f; dump_ui
  xy=$(find_node resource-id android:id/$1) || fail "no $1 field"
  SH input tap $xy >/dev/null; sleep 0.4
  SH input keyevent KEYCODE_MOVE_END >/dev/null
  for _ in 1 2 3; do SH input keyevent KEYCODE_DEL >/dev/null; done
  SH input text "$2" >/dev/null
done
tap_key resource-id android:id/button1 || fail "time picker OK not found"
sleep 1
SH dumpsys alarm | grep -A3 "$PKG" | grep -q . && pass "alarm registered" || fail "alarm not in dumpsys alarm"

echo "  waiting for ring (<= 130s)..."
wait_service up 130 && pass "RingService running" || fail "alarm never rang"
# One tap must be enough: the ring screen covers everything while ringing.
sleep 3
tap_text "Turn off" || fail "Turn off not found"
wait_service down 5 || { SH dumpsys activity services "$PKG" | head -n 4; fail "RingService still running after one Turn off tap"; }
pass "RingService stopped after one Turn off tap"
# One-shot: the alarm's switch must now have its OFF half selected.
sleep 2; dump_ui
grep -q 'content-desc="OFF"[^>]*selected="true"' "$XML" && pass "alarm switched itself OFF" || fail "alarm not OFF after ringing"
cleanup
echo "ALL PASS"
