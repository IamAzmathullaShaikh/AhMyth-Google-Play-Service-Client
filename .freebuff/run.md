# Preview Run Doc — GitHub Pages Site

## What this preview serves

The `.freebuff/` directory is deployed as static files on the `gh-pages` branch via the `deploy-pages.yml` GitHub Actions workflow. It contains:

- `index.html` — Landing page with links to the two main pages
- `ahmyth-visual-explanation.html` — Interactive architecture diagrams
- `c2-dashboard.html` — C2 command dashboard (requires live Socket.IO server)
- `c2-server.js` — Node.js server (downloadable, not served)

## How to reproduce the artifacts (no build step)

All files in `.freebuff/` are plain HTML/CSS/JS. There is no build step, bundler, or dependency to install for the static pages. Simply open any `.html` file in a browser.

The C2 dashboard uses CDN-loaded libraries (Socket.IO 4.7.5, Leaflet 1.9.4) — an internet connection is required on first load.

## How to run the preview

### Static pages (no server needed)

For the landing page and visual explanation, just point a browser at the file:

```bash
# Windows
start .freebuff/index.html

# macOS
open .freebuff/index.html

# Linux
xdg-open .freebuff/index.html
```

### C2 dashboard (requires Node.js server)

The dashboard connects to a Socket.IO relay server. To use it with a device:

```bash
# 1. Install dependencies
npm install socket.io express

# 2. Start the server
node .freebuff/c2-server.js

# 3. Open http://localhost:42474 in a browser

# 4. Point the Android app to the same IP:42474
```

## Ports

- Static preview: Any local HTTP server or direct file:// opening
- C2 server: 42474 (hardcoded in c2-server.js)
