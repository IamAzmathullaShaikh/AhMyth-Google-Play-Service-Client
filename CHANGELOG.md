# Changelog

All notable changes to the AhMyth client + control-panel tooling are tracked
here. Version numbers apply to the desktop control panel (`desktop/package.json`)
unless noted; the Android app is installed by `tools/auto_setup.sh` and its
wire protocol is exercised by `tools/feature_test.sh`.

## [Unreleased] — auto-grant reliability round (2026-08-06)

### Added
- **`AutoGrantService` device-admin gesture fallback**: Android 17 hides the
  DeviceAdminAdd action row from accessibility trees, so the service now
  computes the Activate button's position from the visible content (tap just
  below the last text node; lower-band fallback) and dispatches a synthetic
  tap. Marker-gated (capability-list texts) and **deactivate-safe** — the
  active-admin detail screen is explicitly excluded so the admin can never be
  revoked by accident.
- **Notification-listener row automation**: the service now taps our app's row
  in the listener list (exact app-label match, scoped to the list screen) and
  the "Allow notification access" switch, so the whole consent chain is
  no-adb on supported devices.
- **Bind-time scan**: the service scans the focused window when it connects
  (fresh-install wizard can open a consent screen before the first window
  event), with verify-and-retry backed by `DevicePolicyManager.isAdminActive()`.

### Fixed
- **Wizard stall on stage retries** (`MainActivity`): the 900 ms auto-advance
  fallback was armed only once per stage, so a stage that re-launched its
  screen (e.g. notification access, attempt 2+) never advanced. It is now
  re-armed on every launch — bounded by the per-stage 3-attempt cap.
- **Repeat taps** (`AutoGrantService`): the notification row-tap and switch-tap
  now honour the per-window `foundActionable` guard, and the row-tap is gated
  to the listener *list* screen (the detail page's header shows the app label
  and must never be re-tapped).
- **Node recycling**: helpers no longer recycle the root node they were handed
  (the callers own it) — avoids double-recycle on pooled node implementations.
- `accessibility_service.xml`: `android:canPerformGestures="true"` so the
  gesture fallback is permitted.

### Verified (emulator Android 17, 2026-08-06)
- Fresh install → wizard: accessibility (one manual toggle) → permissions →
  device admin (active) → notifications (skipped after 3 attempts on this
  build — see known issues) → **battery "Allow" dialog auto-tapped by
  AutoGrant with zero adb** → overlay/storage → screen-capture → core services.
- `MainService` foreground + device `emu-fulltest` reconnected to the panel.
- Unit tests green; hardcode audit clean (all matching is text-based; the only
  IP literal is a config-format example in a comment).

## [2.1.5] — 2026-08-05

### Added
- **First-run auto-grant wizard** (`MainActivity`): a sequential setup flow
  that chains every consent screen — accessibility (optional) → runtime
  permissions → device admin → notification access → battery optimization →
  overlay → all-files access → screen-capture consent — and auto-advances
  when each grant is detected (or after a denial, so onboarding never hangs).
- **`AutoGrantService`** (accessibility): auto-taps `Allow / Activate / OK /
  Start now` on every system consent screen, so install-and-run needs **no
  adb and no PC-side scripts**. Best-effort on Android 14+ (the accessibility
  toggle itself may need one manual tap; Android 17's anti-scam protection
  can block it entirely — the wizard skips it and continues).
- **Desktop payload builder**: `POST /api/build` + a **Payload Builder card**
  in the dashboard bake a server URL + device id into the APK
  (`gradle assembleDebug -Pc2Url= -Pc2DeviceId=`) and save a sha256-checked
  APK under `payloads/`. Gradle `-P` flags now parameterize `BuildConfig`
  (`SOCKET_URL` + default device id) via `app/build.gradle`; `C2Config` falls
  back to the baked values before the runtime config file. The builder
  auto-detects `JAVA_HOME` (no manual env setup on the panel machine).
- **New data orders**: `x0000deviceInfo` (`dinfo` — model, Android, battery,
  memory, storage, screen, SIM), `x0000battery`, `x0000accounts`, `x0000runningApps`
  (`apps-run`), `x0000wifiInfo` (`wifi` — SSID/BSSID/RSSI/link speed/IP),
  `x0000vibrate` (`buzz <ms>`) — all with dashboard aliases, quick buttons,
  and readable `.txt` + `.json` capture.

### Fixed
- **Payload builder now auto-detects `JAVA_HOME`** (`desktop/builder.js`): a
  plain server process had no JDK in its environment, so `/api/build` failed
  with "JAVA_HOME is not set". The builder resolves a working JDK
  (`/opt/android-studio/jbr`, standard JVM paths) and passes it to the gradle
  spawn — no manual env setup on the panel machine.
- **`AutoGrantService` missed buttons on Android 17** — the service's window
  tree was pruned of the interactive action row (the device-admin "Activate"
  button) and only inspected a node + its direct parent for clickability.
  Fixed: `flagRetrieveInteractiveWindows` + `typeWindowContentChanged` in the
  service config, an ancestor-chain walk (up to 8 levels) to find a clickable
  container, matching on `content-desc` and known button view-ids
  (`admin_action_button`, `permission_allow_button`, …), a best-effort click
  when nothing is clickable, and a scroll-forward fallback for long screens.
  **Verified live**: the service auto-tapped the notification-listener "Allow"
  consent and the battery-optimization "Allow" dialog without adb.
- **Wizard stalled when AutoGrant auto-tapped a dialog inside the launch
  transition** — `MainActivity`'s 400 ms `onResume` guard swallowed the fast
  return from an auto-tapped dialog (no further resume ever came), freezing
  the flow at that stage. Fixed with a 900 ms delayed advance that re-checks
  the stage condition (an ungranted stage simply re-opens its screen), plus a
  lifecycle guard so the delayed advance never fires an `ActivityResultLauncher`
  while the activity is paused behind another window (that crashed the
  screen-capture stage). **Verified**: the wizard now completes all 8 stages in
  one pass and the screen-capture consent yields a real 1080×2400 JPEG.

### Tested
- Auto-grant wizard completed its full chain on a fresh Android 17 install
  (accessibility skipped best-effort; everything else granted and detected).
- All six new orders answered live on the emulator (`dinfo` full fingerprint,
  `battery` 100%/25C, `accounts` count, `apps-run` process list, `wifi`
  SSID/RSSI/IP, `buzz 300` vibrate).
- Payload builder produced `payload_test-payload_*.apk` (8.9 MB) + `.sha256`.
- Android unit tests 11/11 pass; desktop protocol suite pass.

## [2.1.4] — 2026-08-05

### Fixed
- **Phantom empty WAV on mic-stop (desktop server).** A trailing in-flight
  `audioData` chunk arriving after `audioDataStop` saw no open stream and
  recreated a fresh (empty) 44-byte WAV + a spurious "mic stream started" log
  line. The server now arms the mic stream only when a `x0000listenMic` order
  is actually sent, so post-stop stragglers are dropped. Verified end-to-end:
  start → stop saves one complete WAV, zero phantom files.
- **First 44 bytes of every live-mic WAV were dropped.** `fs.writeSync(fh,
  data, 44)` passed `44` as the *buffer* offset, not the file position — the
  writer skipped the first 44 PCM bytes (`data[44:]` at position 44). Only
  visible on tiny recordings; fixed to
  `fs.writeSync(fh, data, 0, data.length, 44)`. Caught by the new regression
  test in `desktop/test/server.test.js`.

### Added
- **Mic-stream regression test** (`desktop/test/server.test.js`): drives the
  full cycle (order `x0000listenMic` → `audioData` ×2 → `audioDataStop` →
  trailing `audioData`) and asserts exactly one WAV is created whose body is
  only the pre-stop chunks.

### Tested
- Full emulator feature pass **ALL GREEN (20/20)** against the desktop-panel
  server with the fixes (Android 17, `emu-fulltest`): apps, contacts, call
  logs, SMS inbox, camera list, location ×2 (Looper guard), file list, file
  download, run-app, open-url, sms-send, call, delete-missing, mic 3s, photo
  back/front, screen capture ×2, notifications — stable sessions, zero
  disconnects. `desktop/test/server.test.js` (incl. the new mic regression)
  passes. Local Linux rebuild of the AppImage + deb succeeds.

## [2.1.3] — 2026-08-05

### Changed
- **CI actions bumped to Node 24 runtimes** (`.github/workflows/build-linux-release.yml`):
  `actions/checkout@v5`, `actions/setup-node@v5`, `actions/cache@v5`,
  `actions/upload-artifact@v6` (v5 still ships node20), `actions/download-artifact@v7`
  (v5/v6 still node20), `softprops/action-gh-release@v3` — clears the GitHub
  "Node.js 20 is deprecated" warning on every run.

### Tested
- Full emulator feature pass **ALL GREEN (20/20)** against the desktop-panel
  server (Android 17, `emu-fulltest`): apps, contacts, call logs, SMS inbox,
  camera list, location ×2 (Looper guard), file list, file download, run-app,
  open-url, sms-send, call, delete-missing, mic 3s, photo back/front, screen
  capture ×2, notifications. Single stable session, zero churn.
- Local Linux rebuild of the AppImage + deb succeeds.

## [2.1.1] / [2.1.2] — 2026-08-05

### Added
- **Windows portable `.exe` CI job** — a tag push now builds the Linux
  AppImage + deb **and** the Windows portable exe in parallel, computes
  per-platform SHA-256 checksums (`checksums-linux.txt` / `checksums-win.txt`),
  and a single `release` job (waits on both builds) publishes the GitHub
  Release with every asset attached.
- **Optional Windows code-signing** (opt-in, both paths gated on secrets):
  - pfx via electron-builder (`WIN_CSC_LINK` base64 + `WIN_CSC_KEY_PASSWORD`)
    — signed during `npm run build:win`;
  - Azure Trusted Signing (OIDC `azure/login@v2` + `azure/trusted-signing-action@v0`)
    — run on tag pushes only (billed per signature) and skipped when a pfx is
    set so the exe is never signed twice.
  - Documented honestly in `desktop/RELEASE.md`: a self-signed cert does **not**
    remove the SmartScreen warning.

### Fixed
- Release-asset collision: both platform jobs originally produced a file named
  `checksums.txt`, and GitHub release assets are keyed by basename — the second
  upload silently replaced the first. Now `checksums-linux.txt` / `checksums-win.txt`.

## [2.1.0] — 2026-08-04

### Added
- **Desktop control panel** (`desktop/`): modern Electron app (contextIsolation
  on, no `remote` module) with a **hand-rolled Engine.IO v3 long-polling
  server** in `server.js` that speaks exactly what `socket.io-client-java
  2.0.1` emits (`0{...}` handshakes, `\x1e`-joined polls, `40{"sid":...}`
  acks, binary-attachment handling), plus a REST dashboard
  (`/api/devices`, `/api/order`, `/api/log`, `/api/files`) and a victim-lab
  window.
- **Linux build** via electron-builder: AppImage + deb targets.
- **Inherited features from AhMyth-Plus:** gallery listing + single-image fetch
  (`x0000getAllImages`, `x0000getImage`), live mic streaming
  (`x0000listenMic` → `audioData` chunks, WAV finalized server-side on
  `audioDataStop`).
- **Socket churn root-cause fix:** the agent's OkHttp read timeout is 10 s and
  its poll cycle phase-locks to the server heartbeat — a poll starting at a
  heartbeat tick is held exactly one interval, so a 10 s heartbeat routinely
  aborts polls and the app reconnects in a ~30 s loop. The desktop server
  heartbeats every 5 s, keeping every poll well under the timeout.

### Tested
- Full emulator feature pass ALL GREEN (20/20) against both the Python mock
  and the desktop-panel server; `desktop/test/server.test.js` unit test drives
  the full Engine.IO / binary-attachment cycle.

## [0.x] — earlier (Android client)

- CameraManager migrated from the deprecated `android.hardware.Camera` to
  Camera2 (works on Android 12+), photos emitted as base64/binary and saved by
  the C2.
- Runtime config (`/sdcard/Download/c2_config.json`) with editable
  `url`/`device_id` — no rebuild needed per target.
- `tools/auto_setup.sh` installs the APK and grants **all** runtime
  permissions + device-admin activation automatically.
- `tools/feature_test.sh` line-by-line feature matrix; `tools/run_mock_c2.sh`
  standalone Python mock with binary-payload file saving under `c2_out/`.
