# Canceled Codebase – Design Principles

Created: Sep 24, 2026. Updated: Sep 24, 2026. v1.1

> **AI Helper:** Read this document at the start of every session and at
> every handoff. Do not change any design described here without direct user
> approval. Mode: Full-git. Personal Data Mode: OFF.

## Version Control Policy

Edit-in-place then archive: edit this file, bump version + date, copy to
`src/Core-Design-Principles/core-design-principles-vXX.YY.ZZ.md` (zero-padded).
Never edit an archived copy. Patch = typos only; minor = new content;
major = structural reorganization.

### Current version
Latest is this file; archives in `src/Core-Design-Principles/`.

## Code Version History

Code (index.html) versions live in `src/version-history.md`. Append an entry
with every code change; update the pointer comment at the top of index.html.

## Key Terms

- **Host**: a generator that imports this plugin to get the editor.
- **Clone**: the host project copy fetched by `project.js` (main.pjs via
  dependency API, index.html via HTML API, src/ by reference crawling).
- **Scratch**: the live collab workspace (`state.files`), shown as `local/`.
- **Bridge**: the server-socket journal + heartbeat shared with the AI helper.

## Project Overview

A VS Code-style collaborative coding surface, shipped as a Perchance plugin.
The generator's own page is a live project preview with the editor floating on
top; `$output` is the API other generators import. Everything client-side;
files persist per-browser (kv/IndexedDB) and sync across tabs via
BroadcastChannel. See `src/README.md` for the full architecture and
`src/SPEC.md` for the must-have list.

## Architecture & Design
- Editor: custom OffscreenCanvas renderer, no Monaco. Worker
  (`src/canvas-editor/editor-worker.js`) owns doc model + paint;
  `canvas-editor.js` mounts canvas + hidden textarea; `floating-window.js`
  is generic window chrome; `index.js` is the public entry.
- Published plugin = single prebuilt ES module on the upload service
  (`bundleUrl` in main.pjs). Rebuild per `src/canvas-editor/BUILD.md` after
  changing editor sources, then re-upload and update `bundleUrl`.
- Workspace model (index.html): all edits flow through
  `applyText(file, text, {by})`; kv folder `collab`; 30 revs/file;
  sandboxed `srcdoc` run/preview.
- Host clone + mini-git: `project.js` / `git.js` / `store.js` /
  `project-panel.js`. Docs (`.md`/`.txt`) mirrored by probing + mentions.
- Thinking overlay: `[aiThinking({key:"build"})]` PiP, editor-only; revert
  hook restores collab file snapshots by `changeLabel`.
- Thought traces: every agent generation auto-saves a trace through the
  verified pipeline (pgrep-gated, worker-digested via Function constructor,
  kv `thought-traces`, digest line in journal); `thought`/`thoughts` shell
  commands, `bridge.thought/.thoughts` API.
- Blueprint protocol: pointer block at top of main.pjs + `blueprintProtocol`
  import; newest master text pulled live each session.

## UI/UX Details

Dark (`#1e1e1e`) / light (`#ffffff`) themes, accent `#4f9cf9`, AI `#b18cff`.
Stage = full-bleed preview iframe; Editor/Explorer/Panel/Project float above.
Phones get stacked layout, Explorer hidden behind toggle. Animated loading
indicators for all async (generateText/Image up to ~60s).

## Known Issues & Tensions

- Clone reflects the *saved* generator; unsaved src/ edits need the service
  worker, else "Load failed" in some in-app browsers.
- Unreferenced src/ files are invisible to the clone crawler.
- Multi-tab sync is last-write-wins full-file; multi-device CRDT (Yjs) is future.

## Test Cases / References

- After edits: `page_refresh`, exercise the path in `page_eval`, check console
  + `perchanceErrors`; visual work must be screenshotted and eyeballed.
- Phone (390x844) + desktop (1920x1080) via `set_viewport_size`.

## Future Features

- Yjs CRDT relay over server-plugin for multi-device collab.
- Offline plugin catalog adopt (`src/plugin-tools.js` + index.json).

## Advice for AI Helper

- Timestamps: fresh Eastern Time stamp on every message, never reused.
- Discuss before implementing; wait for go-ahead before editing files.
- Keep user informed without barraging; acknowledge notes, don't always act.
- Safety check anything major, irreversible, or destructive first.
- Never save/publish the generator; only the user saves.
- Verify saves via API `lastEditTime` first, never page fetch alone.
