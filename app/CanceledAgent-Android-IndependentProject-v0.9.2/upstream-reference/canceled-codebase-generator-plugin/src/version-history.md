# Canceled Codebase – Code Version History

Living file – edit in place, append new entries at the bottom. Update with
every version change to the code (index.html).

---

## v1.1.0 – Thinking overlay + Blueprint adoption (Sep 24, 2026)

- Embedded `[aiThinking({key:"build"})]` PiP (editor-only) with file-restore
  revert hook (`changeLabel` → collab file snapshot restore)
- Installed Blueprint protocol: adoption pointer block at top of main.pjs,
  `blueprintProtocol` import; master text pulled live (v2.7 line)
- Added `src/SPEC.md`, `src/TODO.md`, Full-mode docs

## v1.2.0 – Thought traces (Sep 24, 2026)

- Runner worker `trace` op: off-thread normalize + FNV digest via the
  Function constructor (constructor.constructor path)
- Every `agentAsk` generation auto-saves a trace (pgrep-gated, kv
  `thought-traces`, digest line in socket journal)
- Terminal `thought <text>` / `thoughts [n]`, API `bridge.thought/.thoughts`,
  `window.logAiThought` forwards to the bridge (overlay PiP + trace store)

## v1.3.0 – Trace visibility + offline catalog (Sep 24, 2026)

- Fixed scratch `main.js`: removed pjs import line pasted into JS (broke Preview)
- `logAiThought` now also logs to the visible `build` PiP key
- Adopted offline plugin catalog: `src/plugin-tools.js` + `src/plugins/index.json`
  (195 plugins), wired as `window.perchancePluginTools` via dynamic import
- `$meta.image` cover screenshot of the live app
- Unified thinking home: `saveThought` and AI `agentSay` messages log into the
  `build` PiP (single entry each, digest-tagged); kv traces backfilled once

## v1.4.0 – Clone root docs + bundle v1.2.0 (Sep 26, 2026)

- Project panel aliases `src/README.md`, `src/SPEC.md`, `src/TODO.md` to the
  clone root on every sync (conventional top-level docs)
- Plugin bundle rebuilt as v1.2.0, `bundleUrl` updated in main.pjs + BUILD.md

## v1.5.0 – Published landing + emulate demo (Sep 26, 2026)

- Full app chrome now editor-only (same gate as thinking overlay); published
  visitors get a plugin dialogue landing (install steps + copy button)
- Emulate button boots the real app as an in-page demo; `?emulate=1` forces it

## v1.5.1 – Emulation controls + plugin-page preview (Sep 26, 2026)

- End-emulation button beside the copy-file button; exits back to the landing
- Emulation auto-opens the AI thinking overlay; the preview stage shows the
  plugin page instead of the particle demo

## v1.5.2 – Starter project is the plugin page (Sep 26, 2026)

- Default `main.js` / `index.html` / `styles.css` no longer the particle demo;
  the sample project renders the plugin info page, so Run/Preview shows it
- Stored workspaces migrate untouched particle starters to the new files;
  user-edited files are left alone

## v1.6.0 – Plugins menu + visual page editing (Sep 26, 2026)

- Imported `i-am-canceled-presents-all-of-the-plugins`; editor-only floating
  Plugins menu (search + toggles + copy import lines, powered by the registry)
- Preview pages get a click-to-edit overlay (click selects, double-click edits,
  Enter commits); edits collect in the Agent panel and "Save edits → AI prompt"
  briefs the AI helper. Injection is generic, so any project's index.html gets
  the overlay automatically

## v1.6.1 – Full inspector window + thinking restyle (Sep 26, 2026)

- Plugins menu is now a real movable/resizable floating window hosting the
  entire Plugin Inspector page in an iframe (lazy-loaded on first open)
- AI thinking PiP reparented into the app and restyled to match the app chrome

## v1.6.2 – Inspector via output-subdomain embed (Sep 26, 2026)

- Plugins window now embeds the generator output URL (null subdomain → the
  plugin's own uid origin) instead of the heavy site-shell page, plus a
  toolbar with Reload and an "Open full page" escape hatch

## v1.7.0 – Forge toolbox bridge (Sep 26, 2026)

- Forge embedded as a hidden offscreen `?embed=1` iframe, driven via its
  postMessage bridge (`window.ForgeBridge.call`, same protocol as its snippet)
- Calls always hit the live generator, so Forge updates apply automatically;
  Check-updates compares the live tool count against the last seen
- Forge floating window (movable/resizable): status, quick-pick chips for the
  useful non-redundant tools, searchable grouped browser, arg JSON + output.
  Redundant groups (Editor file ops, replay, think/todo memory) are excluded —
  our collab API and trace pipeline already cover them

## v1.7.1 – Thinking PiP can never strand off-screen (Sep 26, 2026)

- Its dragged position persists in localStorage, so a shorter viewport left it
  below the fold with no way to grab it back; now clamped into view on every
  restyle + window resize (and the stranded position was reset)

## v1.8.0 – Touchscreen dual surface (Sep 26, 2026)

- Imported `canceled-touchscreen-api-plugin` with `touchOptions` (dual mode);
  no explicit target, so the iframe follows whichever generator hosts the
  plugin, with the plugin's own nesting guard
- Receiver wired into every preview doc (project, boot, emulate) plus our own
  page, so forwarded taps land as real clicks — including on the click-to-edit
  overlay, which is how visitors live-edit the page in user mode
- Touch floating window (movable/resizable, editor-only) renders the plugin's
  own dual surface; `window.TouchBridge` stays live for agent driving