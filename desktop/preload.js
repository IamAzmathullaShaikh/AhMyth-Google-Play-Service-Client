"use strict";
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("api", {
  listen: (port) => ipcRenderer.send("listen", port),
  order: (victim, payload) => ipcRenderer.send("order", { victim, payload }),
  broadcast: (payload) => ipcRenderer.send("order", { payload }),
  openLab: (victimId) => ipcRenderer.send("open-lab", victimId),

  onListenState: (cb) => ipcRenderer.on("listen-state", (_e, s) => cb(s)),
  onVictims: (cb) => ipcRenderer.on("victims", (_e, l) => cb(l)),
  onLog: (cb) => ipcRenderer.on("log", (_e, l) => cb(l)),
  onEvent: (cb) => ipcRenderer.on("event", (_e, p) => cb(p)),
  onAudio: (cb) => ipcRenderer.on("audio", (_e, p) => cb(p)),
  onAudioStop: (cb) => ipcRenderer.on("audio-stop", (_e, p) => cb(p)),
});
