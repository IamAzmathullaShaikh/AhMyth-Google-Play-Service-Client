# Changelog

All notable changes to the AhMyth client + control-panel tooling are tracked
here. Version numbers apply to the desktop control panel (`desktop/package.json`)
unless noted; the Android app is installed by `tools/auto_setup.sh` and its
wire protocol is exercised by `tools/feature_test.sh`.

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
