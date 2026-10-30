# CODE_REFERENCE

Every system, its hooks, and the generator's objective. Update this file when systems gain or lose hooks.

Objective: a VS Code-style collaborative coding surface shipped as a Perchance plugin — the page is a live preview with a floating OffscreenCanvas editor on top, and `$output` is the plugin API other generators import.

| System | Hooks | Notes |
| --- | --- | --- |
| OffscreenCanvas editor | `src/canvas-editor/editor-worker.js`, `canvas-editor.js` (`CanvasEditor`), `floating-window.js` (`createFloatingWindow`), `index.js`, `worker-source.js`, `diff.js` (`lcsRows`/`renderDiffHTML`) | No Monaco; worker paints, main thread blits `ImageBitmap`. Rebuild bundle per `src/canvas-editor/BUILD.md`, update `bundleUrl` in `main.pjs`. |
| Floating windows | `createFloatingWindow`, `createEditorWindow`, Editor/Explorer/Panel/Project windows in `index.html` | Narrow/wide responsive re-layout. |
| Workspace model | `state.files[]`, `applyText()`, `createFile`, `deleteFile`, `renameFile`, `openFile`, `findFile`, `pubMeta` in `index.html` | All edits flow through `applyText()` (`by: user\|ai\|peer`). |
| `collab` API | `window.collab` / `root.collab`: `ready/list/read/write/append/replace/create/remove/rename/open/run/preview/exec/say/activity/history/diff/restore/bridge/term/sandbox/wasmdom/processes` in `index.html` | Test suites may only touch the slot-8 scratch file; slot-9 key holds the live backup. |
| Persistence | kv folder `collab` (via `collabConfig.workspace`), keys `meta`, `text:<id>`, `rev:<id>`, `activity`, `theme`, `bridge:probe`, `thought-traces`; `sGet`/`sSet` with localStorage fallback | Revisions capped 30/file, >400 KB files skipped, 4 s throttle. |
| Multi-tab sync | `BroadcastChannel("collab-editor:<generatorName>")`, heartbeat peer count | Last-write-wins full-file sync per edit. |
| Run / preview sandbox | `runActive`, `previewProject`, sandboxed `<iframe srcdoc>` (`allow-scripts`, no `allow-same-origin`), `RELAY_SCRIPT` | Console relayed to Console panel. |
| Terminal PTY | `runShellLine`, `COMMANDS`, `ptyState`, xterm dynamic import from esm.sh | Commands: help/ls/cat/tree/grep/pgrep/repl/eval/curl/wget/wasmdom/sandbox/preview/run/open/write/rm/echo/theme/clear/history/whoami/pwd/date/bridge/about/thought/thoughts. Pipes + `>`/`>>` supported. |
| Agent bridge | `<script type="text/x-server-plugin">` journal ring (400×320-byte slots), `bridge.*`, `agentAsk`/`agentSay`, `saveThought`, `runBeat`, probe via Function constructor | Server source is public; trusts no client. |
| Host clone + mini-git | `src/canvas-editor/project.js`, `git.js`, `store.js`, `project-panel.js`, `setupProject()` + `projectBtn` in `index.html` | Clone reflects the *saved* generator. Root docs aliased on sync. |
| Plugin `$output` | `__ceApi()` in `main.pjs`, `bundleUrl`, `fetchText` via own `superFetch` import | Importing generators pay nothing until first call. |
| Landing / emulate gate | Landing dialogue + Emulate demo + **Mode builder** (`mbMode` radios, `mbSub*`/`mbOv*` checkboxes → `buildActivation()` → `#activationBox`, `copyActivation`) in `index.html`, `?emulate=1`, End-emulation button (first in toolbar via `order: -1`) | Full app chrome is editor-only. Builder has no persistence; import lines concatenated to avoid the dependency scanner. `buildEmulateDoc` is a STATIC mirror of the landing card (`EMULATE_BUILDER_*`) — update both together. |
| Touch surface | `touchOptions` (dual, framed) in `main.pjs`, `touchscreen` import, Touch window + receivers | Receiver injected into preview docs and own page. |
| Forge bridge | `window.ForgeBridge`, hidden `?embed=1` iframe | Tool groups + quick chips; see landing Plugins/Forge windows. |
| Thinking overlay | `aiThinking` import (`build` key PiP), `window.logAiThought` → bridge trace store | Traces in kv `thought-traces` (cap 200). |
| Working-guidelines layer | `src/CHAT_LOG.md`, `src/ISSUES_FIXES.md`, `src/change.log`, `src/treeshake-worklist.md`, `src/MEMORY_CUBE.md`, `src/RULE_X_FORCE_THE_WIRE.md`, `src/RULE_F_FACTORY_BUILD.md`, `src/RULE_C_CAMPAIGN.md`, `src/REVERSE_TREESHAKE_SEQ.md`, `src/tests/`, `main.pjs` startup + WORKING GUIDELINES block (15 rules) | Process docs; startup block points sessions at them; suites use slot-8 file + slot-9 kv key only. |
