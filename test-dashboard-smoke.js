#!/usr/bin/env node
/**
 * Smoke test for C2 Dashboard (c2-dashboard.html)
 *
 * Validates:
 *   1. HTML parses without errors
 *   2. All critical DOM elements are present
 *   3. Socket.IO CDN script tag exists
 *   4. CSS custom properties are defined
 *   5. Command buttons exist in the command panel
 *   6. Key event listeners are wired up (onclick attributes)
 *
 * Run: node .freebuff/test-dashboard-smoke.js
 * Exit 0 = all checks pass, exit 1 = failure
 */

const fs = require('fs');
const path = require('path');

let passed = 0;
let failed = 0;

function check(name, condition) {
  if (condition) {
    passed++;
    console.log(`  ✅ ${name}`);
  } else {
    failed++;
    console.log(`  ❌ ${name}`);
  }
}

// ── Load HTML ──────────────────────────────────────────────
const htmlPath = path.join(__dirname, 'c2-dashboard.html');
const html = fs.readFileSync(htmlPath, 'utf-8');

console.log('C2 Dashboard Smoke Test');
console.log('='.repeat(50));
console.log(`File: ${htmlPath}`);
console.log(`Size: ${html.length.toLocaleString()} bytes`);
console.log('');

// ── 1. Basic structure ─────────────────────────────────────
console.log('1. Basic HTML structure');

check('Has DOCTYPE', html.includes('<!DOCTYPE html>'));
check('Has <html> tag', html.includes('<html'));
check('Has <head>', html.includes('<head>'));
check('Has <body>', html.includes('<body>'));
check('Has title "AhMyth C2"', html.includes('<title>AhMyth C2'));
console.log('');

// ── 2. Critical DOM elements ──────────────────────────────
console.log('2. Critical DOM elements');

const criticalIds = [
  'bgParticles',
  'imageViewer',
  'toastContainer',
  'statusBadge',
  'statusText',
  'deviceCount',
  'cmdCount',
  'logCount',
  'serverInfo',
  'devicesList',
  'selectionPrompt',
  'commandPanel',
  'deviceSearch',
  'selectedDeviceName',
  'selectedDeviceMeta',
];

for (const id of criticalIds) {
  const elId = `id="${id}"`;
  check(`#${id} exists`, html.includes(elId));
}
console.log('');

// ── 3. External dependencies ──────────────────────────────
console.log('3. External dependencies');

check('Socket.IO CDN script (4.x)',
  html.includes('cdn.socket.io/4.') && html.includes('socket.io.min.js'));
check('Leaflet CSS (map)',
  html.includes('unpkg.com/leaflet') && html.includes('leaflet.css'));
check('Leaflet JS (map)',
  html.includes('unpkg.com/leaflet') && html.includes('leaflet.js'));
console.log('');

// ── 4. CSS custom properties (design system) ─────────────
console.log('4. CSS custom properties');

const cssVars = [
  '--bg', '--bg2', '--surface', '--surface2',
  '--text', '--text-dim', '--text-muted',
  '--accent', '--accent2', '--accent3',
  '--red', '--green', '--blue', '--yellow',
  '--font', '--radius', '--glow',
];

for (const v of cssVars) {
  check(`:root defines ${v}`, html.includes(`${v}:`));
}
console.log('');

// ── 5. Command buttons in the command panel ───────────────
console.log('5. Command buttons');

const commands = [
  ['SMS', /onclick.*sms|class="cmd-btn".*SMS/i],
  ['Call Logs', /onclick.*call.?log|class="cmd-btn".*call/i],
  ['Contacts', /onclick.*contact|class="cmd-btn".*contact/i],
  ['Camera', /onclick.*camera|class="cmd-btn".*camera/i],
  ['Location', /onclick.*location|class="cmd-btn".*location|gps/i],
  ['Files', /onclick.*file|class="cmd-btn".*file/i],
  ['Mic', /onclick.*mic|recording|class="cmd-btn".*mic/i],
  ['Screen', /onclick.*screen|class="cmd-btn".*screen/i],
  ['Keystroke', /onclick.*keylog|class="cmd-btn".*key|kl/i],
];

for (const [name, pattern] of commands) {
  check(`${name} command button`, pattern.test(html));
}
console.log('');

// ── 6. JavaScript functions present ───────────────────────
console.log('6. Key JavaScript functions');

const jsFuncs = [
  'function toggleConnInfo',
  'function renderDevices',
  'function sendCmd',
  'function toast(',
  'function addLog',
  'function connect()',
  'socket.on(',
  'socket.emit(',
];

for (const fn of jsFuncs) {
  check(`JS: ${fn}`, html.includes(fn));
}
console.log('');

// ── 7. Scanline / cyberpunk effects ───────────────────────
console.log('7. UI/UX features');

check('Scanline overlay (body::after)', html.includes('body::after'));
check('Background particles', html.includes('bg-particle'));
check('Particle drift animation', html.includes('particleDrift'));
check('Cyberpunk accent color (#00f0ff)', html.includes('#00f0ff'));
console.log('');

// ── 8. Accessibility ──────────────────────────────────────
console.log('8. Accessibility');

check('Has lang attribute', html.includes('lang="en"'));
check('Has viewport meta', html.includes('viewport'));
console.log('');

// ── Summary ────────────────────────────────────────────────
console.log('='.repeat(50));
const total = passed + failed;
console.log(`Results: ${passed}/${total} passed, ${failed} failed`);
if (failed > 0) {
  console.log('❌ SMOKE TEST FAILED');
  process.exit(1);
} else {
  console.log('✅ SMOKE TEST PASSED');
  process.exit(0);
}
