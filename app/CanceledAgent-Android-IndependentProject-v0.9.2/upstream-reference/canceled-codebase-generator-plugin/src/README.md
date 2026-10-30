# Canceled Codebase

A VS Code–style **collaborative coding surface** the user and the AI pair-program
in together. It ships as a **Perchance plugin**: the generator's own page is a
live project **preview** with the editor floating on top, and its `$output` is an
API other generators can import to get the same editor.

Everything is client-side. Files persist per-browser (kv-plugin / IndexedDB) and
sync live across tabs of the same page via `BroadcastChannel`.

## The generated page (index.html)

- The **main surface is the preview** — a full-bleed sandboxed `<iframe>` that
  renders the project (`index.html` + `styles.css` + `main.js` composed into one
  document). A hint overlay sits on top until the first preview is built.
- The **editor** lives in a floating, draggable, resizable, collapsible window
  (plus an **Explorer** window for files/activity and a **Panel** window for
  console/terminal/agent). Windows are re-laid-out when the viewport crosses the
  narrow/wide threshold, so phones get a stacked layout with the Explorer hidden
  behind its toolbar toggle.

## The editor is an OffscreenCanvas, painted by a worker

There is **no Monaco** (and no CDN editor dependency). The editor is a custom
renderer:

- `src/canvas-editor/editor-worker.js` runs in a **classic Web Worker**, owns the
  document model, tokenisers, cursor/selection/scroll and keymap, and paints to
  an **`OffscreenCanvas`**. The main thread blits the transferred `ImageBitmap`
  with an `ImageBitmapRenderingContext`.
- `src/canvas-editor/canvas-editor.js` (`CanvasEditor`) mounts the visible
  `<canvas>` plus a hidden `<textarea>` (native input, IME, clipboard), forwards
  events to the worker, and exposes `setDoc/getValue/getSelection/insert/command/
  setSearch/findNext/undo/redo/goToLine/setTheme/setOptions/resize/destroy/...`
  and events `ready/change/cursor/focus/blur/find/copy/cut`.
- `src/canvas-editor/floating-window.js` (`createFloatingWindow`) is the generic
  window chrome.
- `src/canvas-editor/index.js` exports `CanvasEditor`, `createFloatingWindow`,
  `createEditorWindow`, `mount`, `version`.
- Diffs are a small **HTML unified-diff** (`lcsRows`/`renderDiffHTML`) rendered in
  the editor pane, replacing Monaco's diff editor.

On this generator's page these are imported directly as
`./src/canvas-editor/index.js`. The **published plugin** serves a bundled copy
(worker source inlined) from the upload service; **rebuild notes and the exact
esbuild recipe are in `src/canvas-editor/BUILD.md`.**

## Project clone + mini-git (what the plugin reveals of its host)

When this plugin is imported into another generator, it can **clone that host's
project and version it**:

- `project.js` fetches the host's `main.pjs` (public dependency API) and
  `index.html` (HTML API, via `superFetch`), then discovers the `src/**` tree by
  **crawling references** out of those two files and out of each module's own
  relative imports (a source file nothing references is not surfaced, since
  there's no API that lists `src/`). Referenced files are fetched from the host
  origin.
- **Documentation is mirrored too**: every `.md`/`.markdown`/`.txt` in the
  project is included even though nothing imports it — conventional names
  (`README.md`, `AGENTS.md`, `SPEC.md`, `TODO.md`, `BUILD.md`, …) are probed in
  the root and each project directory, and mentioned docs are fetched as well.
  The root `AGENTS.md` is not served by generators, so it falls back to a hosted
  mirror (`AGENTS_MIRROR_URL`). Callers can pass `docs: {path: text}` to
  override/add docs. Doc edits are versioned like any other file, so their
  diffs appear in the history.
- `git.js` is a dependency-free, content-addressed mini-git: blobs deduped by
  hash, commits form a parent chain, and each commit stores the **unified diff**
  that produced it, so `log`/`show`/`changelog` are instant.
- `store.js` provides the async `{get,set,delete}` backend (IndexedDB by
  default, memory fallback) so no host wiring is required; the plugin's own page
  passes its kv folder instead.
- `project-panel.js` is the UI: a **Files** tab with the host project tree plus a
  `local/`-prefixed **scratch** tree, and a **History** tab listing commits with
  `+/−` stats; clicking a file or commit opens the file / unified patch. The
  **Sync** button re-fetches the host and commits any change.
- On this generator's own page the panel is the **Project** window (toolbar
  `projectBtn`, `setupProject()` in `index.html`), wired to the local workspace as
  its scratch tree. Its own clone reflects the **saved** generator, so a change
  only appears in the clone after saving.

## Files

| File | Purpose |
| --- | --- |
| `main.pjs` | `$meta`, the plugin `$output` (`__ceApi()`), the `kv`/`superFetch`/`aiText`/`createServerSocket` imports, `collabConfig`, `bridgeConfig`. |
| `index.html` | The app: stage/preview layout, floating windows, the workspace model, activity feed, history/diff, run/preview sandbox, the PTY terminal shell, the agent bridge, and the `collab` API. |
| `src/canvas-editor/*` | The editor + floating-window + project-clone sources (see above). |
| `src/canvas-editor/project.js`, `project-panel.js`, `git.js`, `diff.js`, `store.js` | The **host-project clone + mini-git** feature (see the dedicated section below). |
| `src/canvas-editor/BUILD.md` | How to rebuild/upload the plugin bundle and the plugin API surface. |
| `src/Core-Design-Principles/core-design-principles-v01.0X.md` | Archived design doc versions (v01.00, v01.01, …). |
| `src/Conversation-History/chat-record-00_2026-09-24.md` | Setup session narrative; raw transcript in `Archive/`. |
| `src/README.md` | This file. |
| `src/CHAT_LOG.md`, `src/ISSUES_FIXES.md`, `src/CODE_REFERENCE.md`, `src/change.log`, `src/treeshake-worklist.md`, `src/MEMORY_CUBE.md` | Working-guidelines layer: session log, fixes log, system map, change log, audit worklist, recall index. |
| `src/tests/` | Runnable suites (`run.js` aggregator; run via `page_eval` with `path`). Slot 8 = scratch file, slot 9 = kv backup key. |

## Workspace model (index.html)

- **State** — `state.files[]` = `{id, name, language, text, createdAt, updatedAt, updatedBy}`.
- **Edits** — every change flows through `applyText(file, text, {by, ...})`, which
  syncs the canvas editor model, records a revision, persists, broadcasts, and
  logs activity. `by: "user" | "ai" | "peer"` drives the activity feed and AI flash.
- **Persistence** — kv folder `collab` (configurable via `collabConfig.workspace`):
  keys `meta`, `text:<id>`, `rev:<id>`, `activity`, `theme`. Falls back to
  `localStorage` if kv is unavailable.
- **Revisions** — capped at 30/file, skipped for >400 KB files, throttled to one
  snapshot / 4 s. History modal lists them; **Diff** shows the HTML unified diff;
  **Restore** reverts.
- **Sync** — `BroadcastChannel("collab-editor:<generatorName>")` relays edits,
  create/rename/delete and activity between tabs; a heartbeat tracks peer count.
- **Run / Preview** — a sandboxed `<iframe srcdoc>` relays `console.*` to the
  Console panel. `Run` on a `.js` file evaluates it (top-level `await` supported);
  `Preview` composes the project by inlining every `styles.css`/`main.js`
  referenced by the HTML file into one document. `sandbox="allow-scripts …"`
  (**no** `allow-same-origin`), so apps cannot touch the outer page.

## Terminal (PTY / TUI) + sandbox

The Panel window has a **Terminal** tab (also `Ctrl+`` ` ``): an **xterm.js 5.5.0**
pty with a JS line-discipline (`promptText`/`onTermData`/`handleEscape`/`history`/
tab-completion) and a small shell. The AI types into the same terminal the user
sees.

Commands: `help ls cat tree grep pgrep repl eval/js curl/wget wasmdom sandbox/xss
preview run open write rm echo theme clear history whoami pwd date bridge about
thought/thoughts`.
Pipes (`|`) and redirection (`>` / `>>`) work, e.g.
`cat main.js | grep -n console > hits.txt`.

- `pgrep`/`ps` — real task table (editor host, terminal, collab-api, open models,
  preview iframe, in-flight `curl` fetches registered in `ptyState.tasks`).
- `curl` — fetches through the platform **superFetch** proxy (no CORS); `-i`
  headers, `-o FILE` to save.
- `repl`/`eval` — a JS REPL evaluated with the **Function constructor**
  (`[].constructor.constructor('return 6*7')()`). Bindings: `collab`, `files()`,
  `cat`, `write`, `print`, `sleep`, `fetch` (superFetch), `sandbox`, `wasmdom`,
  `$`/`$$`, `exec`.
- `wasmdom` — parses HTML with **web-tree-sitter 0.22.6 + tree-sitter-html.wasm**
  (real WebAssembly) into a normalized DOM clone, two-pane preview. Loaded via the
  UMD runtime from jsDelivr (esm.sh's build lacks `Parser.Language`).
- `sandbox`/`xss` — renders untrusted HTML in the sandboxed preview iframe
  (`allow-scripts`, no `allow-same-origin`, no `allow-modals`); `alert`/`confirm`/
  `prompt` are stubbed by `RELAY_SCRIPT` and relayed as captured events.

xterm + fit addon are **dynamic `import()`** from esm.sh.

## Agent bridge (server socket + web workers + OffscreenCanvas)

The **Agent** tab is a chat surface rendered entirely inside an **OffscreenCanvas
owned by a web worker** (the worker draws, `transferToImageBitmap()`s, the main
thread blits), wired to an authoritative **server-socket journal** and a heartbeat.

- **Server script** (first `<script type="text/x-server-plugin">` in `index.html`)
  keeps a bounded, versioned **fixed-slot ring** journal in durable `state` bytes,
  a probe-source region, and heartbeat/digest counters. Appending is **O(1)** (one
  320-byte slot + 32-byte header, never re-serialised). Layout v2: header
  `version/beats(u32)/seq(u32)/digest(16B)/count(u16)/cursor(u16)/probeLen(u16)`;
  ring of `MAX_JOURNAL=400` × `SLOT=320` at offset 32; probe source (≤2 KiB) at
  `PROBE_OFF`. Manual UTF-8 codec (no `TextEncoder` on the server runtime).
  Clients publish; the server sequences (`seq`), bounds, persists, and
  `pubsub.publish`es on topic `bridge`. RPCs: `snapshot`, `journal`, `probe`,
  `reset`. All of it is public source and it trusts no client.
- **Worker bus** — `RUNNER_WORKER_SRC` (a Blob worker) runs the off-thread half:
  `curl`, `repl` (`new Function`), and `wasmdom`.
- **Persistent probe** — a function compiled with the **Function constructor** from
  a source string; `bridge.probe(src)` installs a new source (persisted in kv as
  `bridge:probe`, published as `probe:set`). Each beat runs it and publishes an
  FNV-1a digest of its JSON output.
- **Heartbeat** — every `bridgeConfig.heartbeatMs` (15 s) the loop runs `pgrep` +
  `curl` + `repl` + `wasmdom`, publishes a `beat`, and repaints the canvas.
  `curlProbeUrl` targets a CORS-enabled, generously rate-limited endpoint.
- **Thought traces** — the provider hides its thinking, so every `agentAsk`
  generation auto-saves a trace: `pgrep` proves the runner worker is live,
  the worker normalizes + digests the entry off-thread via the Function
  constructor (`trace` op, main-thread fallback), the full entry persists in
  kv `thought-traces` (cap 200), and a digest line goes to the journal.
  Terminal `thought <text>` / `thoughts [n]`, API `bridge.thought/.thoughts`;
  `window.logAiThought` forwards there too.

## The `collab` API (also on `window.collab`)

```js
await root.collab.ready
root.collab.list() / .read("main.js") / .write("main.js", code, {note})
root.collab.append / .replace(ref, find, repl, {all})
root.collab.create(name, text, language) / .remove(ref) / .rename(ref, newName) / .open(ref)
root.collab.run(ref) / .preview() / .clearConsole()
root.collab.history(ref) / .revisionText(ref, i) / .diff(ref, i) / .restore(ref, i|revision)
root.collab.say(text, {by}) / .messages(limit) / .activity(limit) / .notify(text) / .getActive() / .state()
root.collab.on("change", ({file, by, text}) => {})
root.collab.exec("cat main.js | grep -n console")   // shell line, echoes in terminal
root.collab.term.write(s) / .line(s) / .clear() / .run(line) / .open() / .focus() / .history()
root.collab.sandbox(html) / .xss(html)              // → captured [alert|log|error] events
root.collab.wasmdom(html)                           // → {nodes, depth, tags, sexp, html}
root.collab.processes() / .commands()
root.collab.bridge.state() / .journal(n) / .remoteJournal(n) / .remoteSnapshot()
root.collab.bridge.chat(n) / .clearChat() / .tasks(n) / .task(text)
root.collab.bridge.say(text, {by}) / .ask(text) / .probe(src) / .beat("manual")
root.collab.bridge.run("curl", {url}) / .start() / .stop() / .open()
root.collab.bridge.thought("why this approach", {kind}) / .thoughts(n)
```

`ref` accepts a file name, an id, or the file object.

The editor is also reachable from the page scope as the module-level `editor`
(`CanvasEditor` instance) — use `editor.getValue()`, `editor.insert(text)`,
`editor.command(name)`, etc.

## Rebuild / extend notes

- Editor internals live under `src/canvas-editor/`. The plugin bundle that other
  generators import is built & uploaded per `src/canvas-editor/BUILD.md`; update
  `bundleUrl` in `main.pjs` after rebuilding.
- Default starter files live in `DEFAULT_FILES` in `index.html`; used only when no
  persisted `meta` exists (first run or after clearing storage).
- For multi-*device* (not just multi-tab) collaboration the natural next step is a
  CRDT (Yjs) relayed over `server-plugin`; today's model is last-write-wins
  full-file sync per edit.
