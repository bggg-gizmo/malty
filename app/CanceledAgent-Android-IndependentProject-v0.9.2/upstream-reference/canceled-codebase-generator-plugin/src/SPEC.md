# SPEC — Canceled Codebase

Human-readable spec. Updated whenever constraints change so a new session can resume from source.

## What this is
Perchance plugin + generator page. Page is a live preview with floating editor on top. `$output` is the plugin API other generators import.

## Must-haves
- OffscreenCanvas editor painted by a classic worker, no Monaco/CDN editor.
  Sources: `src/canvas-editor/editor-worker.js`, `canvas-editor.js`, `floating-window.js`, `index.js`, `worker-source.js`.
- Floating windows: Editor, Explorer (files/activity), Panel (console/terminal/agent), Project (clone + git).
- Workspace model in `index.html`: `state.files[]`, `applyText()` for all edits, kv `collab` persistence with localStorage fallback, 30 revs/file, BroadcastChannel multi-tab sync, sandboxed `srcdoc` run/preview.
- Host clone + mini-git: `project.js` fetches host `main.pjs` + `index.html`, crawls `src/` refs and nested relative imports, mirrors all `.md`/`.txt` docs (probed names + mentioned + `AGENTS.md` mirror), `git.js` content-addressed history, `project-panel.js` Files/History UI. Panel scratch tree = live workspace as `local/`.
- Terminal PTY with shell commands, Agent bridge journal over server socket.
- Published plugin bundle hosted on upload service, `bundleUrl` in `main.pjs`. Rebuild per `src/canvas-editor/BUILD.md` after changing editor sources.

## Storage rules
- `src/` is sacred: only shipped code/docs. Quota 2GB total, 100MB/file.
- `scratch/` is ephemeral, never ships. Chat images land in `src/assets/` (ship) and `local/` scratch (session).
- Root docs live in `src/`: `README.md`, `SPEC.md` (this), `TODO.md`.

## Images
- Static assets: generate once, upload, reference by URL or `src/assets/`.
- Per-run images: `text-to-image-plugin` at runtime, never `generate_image` tool.
