# Perchance AI Helper Blueprint — self-contained copy, packaged for import

This generator (`perchance.org/ai-helper-blueprint-plugin`) holds a user-owned
copy of the Perchance AI Helper Blueprint — the protocol an AI Helper session
follows — and is packaged so other projects can import it.

It is **not** the canonical master. The master is
`perchance.org/ai-helper-blueprint`, owned by someone else; the blueprint text
itself says so in its PROVENANCE section. This generator is an independent copy
of it, which means its owner (not the master's owner) controls updates here.

## Layout

| Path | Purpose |
| --- | --- |
| `main.pjs` | AI-facing entry point. Instructions to FOLLOW, not content to summarize. Carries the quick-path key (a fallback), the always-pull-the-newest resolver, the mode/sub-mode/overlay model, and the release + verification rules. |
| `index.html` | Human-facing landing page. Page 1 shows the plugin install — the `blueprintProtocol = {import:ai-helper-blueprint-plugin}` line (built at runtime; see constraint 3) plus the pointer-block note, with a fine-print credit line linking the master. Page 2 holds the **mode builder** (editor-only, like the overlays), the info bullets, and the master generator embedded **live** in an iframe. Page 2 scrolls when the viewport is too short for its content. |
| `src/perchance-ai-helper-blueprint-latest.txt` | The protocol text (master v2.7 + a LOCAL ADDITIONS list in its provenance section, 159890 bytes). Its "How to Use" section documents installing the blueprint as a plugin, the master it tracks, and the activation prompt; its SUB-MODES section defines three stackable capability layers; its OVERLAYS section defines the plugin's three default overlay layers (the `thinking` one including back-thinking). |
| `src/Blueprint-Archive/` | Previous protocol versions. |
| `src/skills/` | Custom skills, loaded when a matching request arrives. |
| `src/adopt-main-snippet.pjs` | Pointer block a consuming project pastes into its `main.pjs`. |
| `src/generator-url.txt` | Identity (URL + public id) of the upstream master. |

## The page: mode builder + activation prompt

The second page of the landing page is a **mode builder**. It has three rows of
chips — base mode (Full default / Core), the three sub-modes, and the three
overlays (all ON by default) — and it writes a paste-ready **activation
prompt** from whatever is ticked. That prompt is the intended way to start a
session: paste it as the first message and Startup is already answered (the
protocol's Startup section says so explicitly). Two buttons: `Copy activation
prompt` (the whole thing) and `Copy import lines` (just the `{import:}` lines).
Choosing settings by hand works identically — the builder only writes the
prompt. The selection persists in `localStorage["blueprint-mode-builder:v2"]`
(the key was bumped when `git` became a default overlay, so a stale v1 selection
can't leave it off).

**The builder is editor-only**, like the overlays: it is rendered by
`isEditorView()` (see below) and a published page renders no builder at all
(the markup ships with `style="display:none"`, so there is no flash either).
`window.__bpPip.previewPublished(true)` hides it, along with both overlays.

Below the builder is a live master status line ("master v2.7 · N chars · last
edit … · pulled live just now"), fed by the resolver below.

## Always pull the newest master (never trust a frozen key)

The quick-path key in `main.pjs` is a **fallback**, not the source of truth.
At the start of a session the plugin resolves the current text live:
generator API record → its `code` field → the key URL named in that text →
download. So a master update that moves the text to a new URL is picked up with
no plugin edit. Available at runtime from this page:

- `root.blueprintMaster()` — newest master `{ok, url, lastEditTime, keyUrl, bytes, version, text}`
- `root.blueprintSelf()` — this copy's published text (same shape)
- `root.blueprintSources()` — both, plus a `rebase` verdict
- helpers: `root.extractKeyUrl(text)`, `root.extractVersion(text)`, `root.extractForkPoint(text)`

`main.pjs` carries a `>>> PENDING KEY UPDATE` note whenever the `src/` text is
ahead of the frozen key (see "Changing the blueprint text").

## Overlays (plugin-only, default ON)

Overlays are the part of the Blueprint that is this **plugin's** own, not the
master's. They are on by default and change what the *session* does while it
builds, rather than what the project is.

| Overlay | What it adds | Import |
| --- | --- | --- |
| `git` | The default working discipline: the project is a versioned codebase, so the AI clones it, commits each meaningful change with a message, and reads history/diffs instead of asking what happened. No window — runtime behavior, nothing to hide. | `canvasEditor = {import:canceled-codebase-generator-plugin}` |
| `thinking` | The AI helper's reasoning as a **unified thought pipeline** in a PiP window: entries carry a seq, a kind (think/action/change) and a parent link, with ⤴ Trace / Trace view / ⤓ Export traces, and a feedback block with thinking IDs + TRACE-BACK. Includes **back-thinking** (see below). | `aiThinking = {import:canceled-ai-helper-thinking-plugin}` |
| `plugins` | The offline catalog of all 195 Perchance plugins (source, declared imports, exposed names) plus the headless registry, and the browsing Plugin Inspector as a PiP window. The AI looks plugins up instead of guessing; the per-plugin toggles copy ready-to-paste import lines. | none of its own (adopted engine) |

**The provider no longer shares the model's thinking**, so the thinking window
is the only place the user can see it — hence the overlay is on by default and
the protocol now says pushing thinking through `h.think` / `h.act` / `h.pipe`
is default behavior for every session, not an optional extra.

**Back-thinking (Sep 25, 2026).** The pipeline only starts when the overlay does,
so two kinds of thinking would never reach the log on their own: reasoning from
rounds that happened *before* it was installed, and reasoning the provider *does*
show the user. Both belong in the chain, and both go in **verbatim** — a
paraphrase is a different thought, and the user would be rating the one they
never saw. Two helpers live in this generator's own `main.pjs` (as the reference
implementation; the protocol's thinking overlay section carries the same code to
paste into a project):

- `root.aiThinkVerbatim(text, {key, turn, …})` — mirrors one visible block
  word-for-word (`source:"helper-visible"`, `pivot:true` so it stays steerable).
- `root.aiThinkBackfill([{turn, at, entries:[{kind, text}, …]}, …], {key})` —
  inserts whole earlier rounds *under* the live entries, oldest round first.
  It renumbers `seq` so back-filled entries take the first numbers and the live
  ones shift up, chains each retro entry to the one before it and re-parents the
  live chain's oldest root onto the newest back-filled entry (so Trace view
  reads the whole session as one line of thought, oldest → newest), marks them
  `source:"backfill"` (the card's top pill), and is idempotent for a repeated
  `turn` + text, so re-running a round never duplicates it. Entries without an
  `at` are stamped just before `now` (one second apart), so nothing back-filled
  can look newer than the live log; `lastIds[key]` is moved onto the newest
  entry so the next live log links on. It is for rounds that are already over —
  a round still in progress goes through `log`/`think`/`act`, or through one
  `aiThinkBackfill(round, {position:"over"})` call, which appends on top and
  continues the numbering instead of inserting underneath (the default,
  `"under"`, would make a newer round the chain's root and reverse the order).
- The rule is **never invent**: back-fill what is on the record and label a
  partial round as partial. The protocol records this in the thinking overlay's
  **Back-thinking** subsection, in the Startup section, in the activation prompt
  the builder writes, and as a pitfall (back-fill is one pass per round, not a
  running summary).

**Editor-only windows.** `thinking` and `plugins` render as PiP windows on the
editor view only (edit hash, unsaved preview, or `?showThinking=1` / `?pip=1`)
and render **nothing** on a published page — no thought text and no catalog
enter public HTML, and the catalog iframe is not even requested. The `thinking`
embed is `[aiThinking({key:"blueprint", title:"Blueprint thinking"})]`; the
inspector PiP is opened by this page's own script, and **starts collapsed to a
"🧩 plugins" pill** on first visit (click it to open; the open/closed state is
remembered in `localStorage["blueprint-pip-closed:plugins"]`) so it never buries
the landing page. Both are hidden/restored at runtime via
`window.__bpPip.previewPublished(true|false)` and `window.__aiThink.forceHide`.

History: `plugins` moved from sub-mode to overlay (Sep 24, 2026); `ROM editor`
was removed (Sep 24, 2026); `git` moved from sub-mode to overlay and is on by
default (Sep 25, 2026); the thinking overlay was updated for the plugin's
unified pipeline (Sep 25, 2026), and back-thinking — verbatim mirrors of visible
reasoning plus back-fill of earlier rounds — was added to it (Sep 25, 2026).
Also Sep 25, 2026: the `3D editor` sub-mode **absorbed the Human 3D editor
extension** — its 77 rigged humans, `window.CharacterAPI`, the pjs `character*`
mirror and the paint-on-the-mesh engine are now documented as part of that
sub-mode (see below) — and the `image editor` sub-mode **absorbed the canvas
extension** (`canceled-canvas-extension`): a second engine, the painting surface
(1100 brushes, palettes, papers, a bakeable relighting pass). Sub-modes are still
**three**.

## Sub-modes (stackable)

The protocol defines two **base modes** (Core, Full) plus three stackable
**sub-modes** – capability layers that combine with either base mode and with
each other. Names read base-mode-first, sub-modes in the canonical order
`image editor`, `3D editor`, `tilemap and sprite editor`: `Core`, `Full`,
`Core-image editor`, `Full-3D editor`, `Core-3D editor-tilemap and sprite
editor`, `Full-tilemap and sprite editor`, `Full-image editor-3D editor-tilemap
and sprite editor` (the sub-modes after the base mode are order-independent with
each other; any combination of the three is valid). Overlays are recorded
*beside* the name: `Full-3D editor, overlays: git, thinking, plugins`.

| Sub-mode | Engine | Imports it adds |
| --- | --- | --- |
| `image editor` | `perchance.org/image-editor-tool` (reference implementation, read its `src/README.md`) **plus the painting surface** merged from `perchance.org/canceled-canvas-extension` ("canceled canvas"): `src/studio.js` (engine), `src/agent.js` + `src/AGENT.md` (deck + docs), `src/sk-catalog.js` + `src/palettes.js` + the `src/sk/**`, `src/brushes/**` and `src/pocket/**` brush images (shared with the `3D editor` paint surface) | `generateImage`, `superFetch`, `kv`, `uploadPlugin` (+ `touchscreen` for the painting surface) |
| `3D editor` | `perchance.org/3d-lab` (adopted engine: `src/studio/*.js` + `src/studio/README.md` + `src/README.md`) **plus the human cast** merged from `perchance.org/canceled-human-3d-editor-extension` ("3D Paint Studio"): `src/cc/*.js` (paint engine + brush/palette catalogs), `src/models/*.glb` (+ `index.json`, `CREDITS.txt`) and `src/character-api-examples.json` | `superFetch`, `uploadPlugin`, `generateText`, `generateImage` (+ optional `touchscreen` for the cast's workbench UI) |
| `tilemap and sprite editor` | `perchance.org/canceled-sprite-lab` (adopted engine: `src/api.js` + `src/core/*.js` + optionally `src/ui/*.js` + `src/ui/styles.css` + the `toolkit`/`spritefusion`/`samples`/`palettes` lists from its `main.pjs`; API at `root.skt`) | `superFetch`, `uploadPlugin`, `sprite` |

This generator imports `canvasEditor = {import:canceled-codebase-generator-plugin}`
(the `git` overlay engine), `aiThinking = {import:canceled-ai-helper-thinking-plugin}`
(the `thinking` overlay), and `superFetch = {import:super-fetch-plugin}` in its own
`main.pjs` so the APIs stay verifiable here; nothing on this landing page uses the
`plugins` overlay or any sub-mode, so those are *not* imported here (their names
appear only in `main.pjs`'s comments — see constraint 5). Each reference
implementation's own AI-helper integration is documented verbatim in the
protocol's SUB-MODES and OVERLAYS sections:

- `git` (now an **overlay**, on by default) – `root.canvasEditor`:
  `fetchHostProject()`, `createRepo({name, store})` (`log`, `show`, `diff`,
  `files`, `read`, `head`, `changelog`, `reset`, `hashText`, `commit`),
  `createProjectWindow(...)`, `createEditorWindow(...)`, `fetchText(url)`.
  Commits are the unit of work; a commit is a working snapshot, never a
  publication.
- `image editor` – a handoff deck ("Copy project brief" / "Copy handoff +
  request"), a fixed handoff block format, and its request queue in
  `root.kv.agentDeck` / `"queue"`.
  Its **painting surface** (merged from `canceled-canvas-extension`) adds the
  brush engine and catalog: `window.__studio` (`layers`, `composite()`,
  `paintUV(u, v, pressure, "down"|"move"|"up")`, `fit()`, `resize(w, h, live)`,
  `state.paper`), 1100 brushes in ten named brushsets + a pocket set
  (`src/brushes/**`, `src/pocket/**`, `src/sk/**`, indexed by
  `src/sk-catalog.js` with `src/palettes.js`), the canvas/grid/guide, layer and
  relighting-bake controls, the PNG / Layers-zip / Brushes-zip exports, and the
  `__touchBridge` receiver that lets the touchscreen plugin (mode `agent`) drive
  the surface. ≈41 MB / ~1000 files if the whole library is copied, so ship only
  the brushes the project uses; the engine app is `src/studio.js` and the deck is
  `src/agent.js` + `src/AGENT.md` (read them before driving the rest).
- `3D editor` – `window.studio` / `window.animMode` / `window.assetHub` /
  `root.animLab`, driven by `page_eval`, with a pixel-grid + vision verify rule.
  Its **human cast** (merged from `canceled-human-3d-editor-extension`) adds
  `window.CharacterAPI` (alias `CharacterLab`; `list`/`search`/`resolve`/`get`/
  `current`/`load`/`next`/`prev`/`random`, `getSpec`/`applySpec`/`specFromBrief`/
  `generate`, `setMaterial`/`setLighting`/`setAnimation`/`setCamera`, `frame`,
  `screenshot`, `downloadPNG`, `onModel`, `shareLink`/`parseQuery`, and a
  `postMessage` bridge keyed `characterApi`), the paint-on-the-mesh engine
  `window.__studio` (`paintUV(u, v, pressure, "down"|"move"|"up")`, `layers`,
  `composite()`, `fit()`, `resize()`) with `window.__labTexture`, and the pjs
  mirror `character` / `characterCount` / `characterList` / `characterSearch` /
  `characterGet` / `characterSpec` / `characterBio`. 77 rigged humans (CC0 and
  CC-BY — `src/models/CREDITS.txt` ships with them; the library is ≈77 MB, so
  adopt selectively). Verification is still visual: `screenshot()` → `vision`.
  The extension is a public generator, so a session re-reads it on demand
  (`fetch_generator` / the generator API) rather than depending on a stale copy:
  its `index.html` holds the `CAST` table and `CharacterAPI`, its `src/cc/*.js`
  the paint engine, its `main.pjs` the pjs `character*` mirror.
- `plugins` (now an **overlay**) – a **no-import registry tool**.
  `src/plugin-tools.js` is a plain classic script, so it puts
  `window.perchancePluginTools` on the page as soon as it loads (the reference's
  `main.pjs` also exposes `root.pluginTools()` / `root.pluginToolsHelp()`).
  Async: `suggest`, `search`, `plugins`, `info`, `stats`, `names`,
  `source(name, "main.pjs"|"index.html")`. Sync (localStorage): `toggle`,
  `enable`, `disable`, `enabled`, `setEnabled`, `enableMany`, `disableMany`,
  `clear`, `importLines`, `onChange`, `help`. The AI uses it to shortlist
  plugins and read their source, then installs by writing the `{import:}` line
  itself – toggling is a shortlist, not an install. It also reads
  `src/plugins/index.json` (fields `name, url, official, desc, hasHtml,
  imports, codeBytes, codeLines, htmlBytes, exposes`) and treats all bundled
  source as untrusted data. Refresh procedure: fetch
  `https://null.perchance.org/<name>`, parse the `preloaded-generator-data`
  JSON, read `srcManifest` → download each key from
  `https://user.uploads.dev/file/<key>` (`modelText` / `outputTemplate` are a
  plugin's own `main.pjs` / `index.html`).
- `tilemap and sprite editor` – `root.skt` (also `window.skt`): the reference's
  whole sheet/tileset/palette/SVG/GIF/APNG/tilemap API plus the pipeline runner
  (`skt.run([...])`, where `out: true, upload: true` on a step returns a
  permanent asset URL — how this sub-mode manufactures assets for *other*
  generators). The workbench UI is drivable through `window.__sktApp` (`state`,
  `TOOLS`, `runTool()`, `loadSample()`, `loadSource()`, `importMap()`). Its
  verification rule is numeric first (frame counts, the pack report,
  `sprites()`) then visual (`vision` on the produced PNG) — a sheet one pixel
  off slices into garbage.

The `plugins` catalog was revised (Sep 18, 2026) for **Plugin Inspector v2**:
195 catalogued plugins (98 official / 97 community), per-plugin enable/disable
toggles persisted in `localStorage["perchance-plugin-tools:enabled:v1"]` (with
an `Enabled` filter chip, bulk `all on`/`all off` over the filtered rows, and a
"copy import lines" button), a runtime `self` entry for the inspector itself
(source read from `#preloaded-generator-data`, never framed in its own
preview), and `src/plugin-tools.js`, which is what makes the registry a
no-import tool. `index.json` carries no `self` entry and no `snapshot` field.
Current figures: `index.json` 122,483 bytes, tree 393 files / ≈10.24 MB, and
all 195 entries carry source. Catalog gotcha, documented upstream too: a 0-byte
file under `src/` can come back as a 12-byte binary blob (invalid UTF-8), so
keep snapshot files at least one byte (a newline is fine).

## Using it from another project

The project pastes the block from `src/adopt-main-snippet.pjs` at the top of its
`main.pjs`, and adds:

```pjs
blueprintProtocol = {import:ai-helper-blueprint-plugin}
```

The protocol text now carries this install itself (see "Install as a plugin" in
its How to Use section), so a user who is handed the blueprint learns the
plugin path from the blueprint rather than from this README.

**Why the pointer is needed.** Perchance mirrors an imported generator's
`main.pjs` into the importing project as `imports/ai-helper-blueprint-plugin/main.pjs`,
but nothing makes a session open that file. The pointer is what sends it there.
The plugin cannot do this on the project's behalf — an import cannot write into
the importer's own `main.pjs`. So import line + pointer block is the whole
installation, and it is the minimum possible ceremony.

The pointer also tells the session to STOP if the mirrored file is absent,
rather than proceed protocol-less. That matters because the import is a single
point of failure: renaming or deleting this generator breaks every importer at
once (what an importer sees is a load-time error, "Tried to (dynamically?) load
the module … but it doesn't exist").

## Verified constraints (do not violate these)

1. **Only `main.pjs` is mirrored by an import** — never `index.html`, never
   `src/`. That is why the protocol reaches consuming projects by URL rather
   than by mirroring the file itself.
2. **`//` comments are inert.** The pjs engine strips comments before its
   tokenizer runs, so `[1+1]` and `{alpha|beta}` inside a comment are neither
   evaluated nor rendered. This is what makes a prose protocol portable inside a
   `main.pjs` at all.
3. **A literal `{import:...}` anywhere in `index.html` is a booby trap.** The
   engine's scanner matches it even when `&#123;`-escaped, and the page then
   hangs forever on "🔃︎ Loading imported generators…" if that generator doesn't
   exist. Build such text at runtime (`String.fromCharCode(123/125)`) if you ever
   need to display it on a page.
4. **`srcManifest` in the public page's `preloaded-generator-data` is
   owner-only.** An anonymous fetch of a generator's public page returns only
   `{name, imports, isPrivate, collabEditModeIsEnabled}` — no manifest. So key
   discovery must go through the API's `code` field (public) or a key URL;
   sessions cannot use the manifest procedure. Inside the editor's own preview
   the manifest *is* present, which is how a fresh key is read right after the
   user saves.
5. **In `main.pjs`, a literal `{import:...}` inside a comment is still scanned
   by the *editor's* dependency mirror.** Measured: adding a comment containing
   `{import:animal}` made the editor fetch and mirror `imports/animal/main.pjs`
   ("fetched new imports: animal"), while `{ import:adjective }` — a space after
   the brace — was ignored. Those mirrors are read-only reference copies and do
   **not** reach the runtime: `root.generateImage`/`root.kv`/`root.sprite` are
   absent, and the saved generator's API `imports` field lists only the three
   real top-level assignments. So comment lines in `main.pjs` are safe as
   documentation, but they do cost a one-time editor fetch per name mentioned.

## Changing the blueprint text

The text in `src/perchance-ai-helper-blueprint-latest.txt` is the master text
(currently v2.7) plus a LOCAL ADDITIONS list in its provenance section. This
generator is **not** the master, so it never invents a version number:

1. Edit the file in place and record the change in its LOCAL ADDITIONS list
   (mark an entry `→ promoted to master vX.Y` and drop it once the master
   carries it). Keeping master v2.7 archived in `src/Blueprint-Archive/` is what
   lets the list rebuild the copy exactly, so no new numbered file is created.
2. The user saves. That uploads the file and produces a new key — read it from
   the preview's `srcManifest` (or the editor's file panel).
3. Update the quick-path URL in `main.pjs` to the new key and delete the
   `PENDING KEY UPDATE` note. This is mandatory: the old key keeps serving the
   old version, forever, to every importer. (The live resolver does not depend
   on it; the key is what keeps the fallback honest.)
4. Verify per the VERIFICATION RULE in `main.pjs` — never trust a page fetch.

## Syncing from upstream

Upstream (`ai-helper-blueprint`) is not owned by this account, so the only
public way to see its current file key is the API's `code` field, which is the
upstream `main.pjs` and contains its quick-path URL:

```
https://perchance.org/api/getGeneratorsAndDependencies?generatorNames=ai-helper-blueprint
```

Download the file at that key, read its version header, and install it here if
the user wants to adopt it — then bump the key in `main.pjs`. Details in
`src/skills/fetch-latest-blueprint.md`. The plugin's own page does the same
resolution live (`root.blueprintMaster()`), which is the fastest way to see
whether the copy is behind.
