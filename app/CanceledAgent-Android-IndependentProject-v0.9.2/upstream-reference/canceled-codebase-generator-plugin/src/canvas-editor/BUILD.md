# Building the plugin bundle

`main.pjs` exports the plugin API from a **single prebuilt ES module** that is
hosted on the upload service:

```
https://user.uploads.dev/file/5981e24c229657dfb091277d3dae7567.js
```

`__ceApi()` in `main.pjs` lazily `import()`s that URL. The module is bundled from
the sources in this directory; the page itself (`index.html`) loads the exact
same sources directly via `import("./src/canvas-editor/index.js")`, so an
in-session edit under `src/canvas-editor/` shows up in this generator's own page
immediately — but the *published plugin* others import keeps serving the
**uploaded bundle** until you rebuild and re-upload it.

So: if you change `src/canvas-editor/**`, either edit this generator's own page
(fine for local use), or rebuild + re-upload and update `bundleUrl` in
`main.pjs` (needed for other generators to see the change).

## Sources

| File | Role |
| --- | --- |
| `editor-worker.js` | Self-contained **classic** Web Worker. Owns the doc model, tokenisers (js/html/css/json/md), cursor/selection/scroll, undo/search/keymap, and paints an **OffscreenCanvas**. No imports — its full text is inlined into the bundle as a string. |
| `canvas-editor.js` | `CanvasEditor` class (main thread): `<canvas>` (bitmaprenderer) + hidden `<textarea>` for native input/IME, spawns the worker (from the inlined source when bundled, else `./editor-worker.js`), forwards input, ResizeObserver, theme/options, `setDoc/getValue/getSelection/insert/command/...` and events. |
| `floating-window.js` | `createFloatingWindow()` — generic draggable / resizable / collapsible / closable panel; injects its own `<style data-floating-window>`. |
| `diff.js` | Dependency-free line diffing: `diffRows`, `diffStat`, `unifiedDiff` (LCS + hunks). |
| `git.js` | `createRepo({store,name})` — a content-addressed mini-git (blobs deduped by hash, commits with parent chain + the unified patch that produced them, `log`/`show`/`diff`/`files`/`read`/`head`/`changelog`, GC, 250-commit cap). Store is injected. |
| `store.js` | `createMemoryStore()`, `createIdbStore(name)`, `createDefaultStore(name)` — the async `{get,set,delete}` backends for the repo (IndexedDB by default, no kv-plugin needed). |
| `project.js` | `fetchHostProject(opts)` clones the **host** generator: `main.pjs` via the public dependency API, `index.html` via the HTML API (CORS-free fetch), `src/**`/`scratch/**` **discovered** by scanning those sources for references (then each referenced module's own relative imports), and **all `.md`/`.txt` docs** (probed by conventional name per directory, plus mentioned ones, plus an `AGENTS.md` mirror). `discoverRefs`, `discoverDocs`, `buildTree` helpers. |
| `project-panel.js` | `createProjectPanel(container, opts)` — the clone + mini-git UI: host project tree, local scratch tree, commit list, unified-diff / file viewer, Sync button. |
| `index.js` | Public entry: re-exports everything above plus `createEditorWindow(opts)`, `createProjectWindow(opts)`, `mount(container, opts)`, and `version`. |
| `worker-source.js` | Returns the worker source: the inlined `__CE_WORKER_SRC__` when bundled, else `fetch("./editor-worker.js")`. |

## Rebuild recipe (esbuild-wasm, runnable via the `execute_js` tool)

The build reads the workspace files through the mounted `fs`, inlines
`editor-worker.js` as a string via `define`, and bundles to one minified ESM
file. Run this in `execute_js`:

```js
const worker = await fs.readTextFile("src/canvas-editor/editor-worker.js");
const { default: esbuild } = await import("https://esm.sh/esbuild-wasm@0.21.5?bundle");
await esbuild.initialize({ wasmURL: "https://esm.sh/esbuild-wasm@0.21.5/esbuild.wasm" });

// All editor sources are flat in src/canvas-editor/, so relative imports
// ("./x.js") map straight onto workspace paths.
const wsPlugin = {
  name: "workspace",
  setup(build) {
    build.onResolve({ filter: /^\.{1,2}\// }, (a) => ({ path: "src/canvas-editor/" + a.path.replace(/^\.\//, ""), namespace: "ws" }));
    build.onLoad({ filter: /.*/, namespace: "ws" }, async (a) => ({ contents: await fs.readTextFile(a.path), loader: "js" }));
  },
};

const result = await esbuild.build({
  stdin: { contents: 'export * from "./index.js";', resolveDir: "src/canvas-editor", sourcefile: "entry.js", loader: "js" },
  bundle: true, format: "esm", platform: "browser", target: ["es2020"],
  minify: true, legalComments: "none", write: false,
  define: { __CE_WORKER_SRC__: JSON.stringify(worker) },
  plugins: [wsPlugin],
});
await fs.writeTextFile("scratch/canvas-editor-plugin.js", result.outputFiles[0].text);
return result.outputFiles[0].text.length; // ~78,860 bytes
```

Then upload it and put the returned URL in `bundleUrl` in `main.pjs`:

```js
await tools.upload_file({ path: "scratch/canvas-editor-plugin.js" });
```

(That esbuild plugin's `onLoad` path-mapping is a little fiddly; if the build
errors, print the requested `path`/`resolveDir` in `onLoad` and adjust the
prefix handling. The upload never needs to happen again unless the sources
change.)

## Plugin API (what `{import:canceled-codebase-generator-plugin}` gives you)

```js
// in an importing generator's main.pjs:
canvasEditor = {import:canceled-codebase-generator-plugin}

// then in that generator's page code:
const api = await root.canvasEditor;                       // the object below
const mod = await api.load();                              // the raw ES module
const { win, editor } = await api.createEditorWindow({     // floating window + editor
  title: "Editor", x: 20, y: 20, width: 640, height: 420,
  text: "console.log(1)", language: "javascript",
});
editor.on("change", ({ text }) => console.log(text));

// clone THIS generator's project (main.pjs + index.html + src/**) and keep a
// version history of it — no store to wire up (IndexedDB by default):
const { win: projWin, panel, repo } = await api.createProjectWindow({
  generatorName: window.generatorName,   // defaults to it anyway
  scratchFiles: () => [{ name: "notes.txt", text: "..." }], // optional local tree
  onChange: ({ kind, commit }) => console.log(kind, commit && commit.id),
});
const log = await repo.log();              // [{id, message, stats, changes, patch}]
const files = await panel.repo.read("src/foo.js");
```

`api` = `{ version, bundleUrl, fetchText, load, createEditorWindow, createProjectWindow,
createProjectPanel, fetchHostProject, createRepo, mount, createFloatingWindow }`.
All methods return promises (they lazily load the bundle first). `fetchText(url)` is
the plugin's own CORS-free fetcher (backed by its `super-fetch-plugin` import), so
importing generators get host-project cloning without importing super-fetch-plugin
themselves.

### How the host project is discovered

There is **no public API that lists a generator's `src/` tree**, so `fetchHostProject`
finds it by crawling references:

1. `main.pjs` — `/api/getGeneratorsAndDependencies?generatorNames=<name>` (CORS-enabled).
2. `index.html` — `/api/getGeneratorHtml?generatorName=<name>` (needs a CORS-free
   fetch; the panel uses `root.superFetch` when present, which the plugin imports).
3. `src/**` & `scratch/**` — every `src/…` / `./src/…` reference found in the two
   files above is fetched from the host origin; then each fetched module's own
   quoted relative specifiers (`from "./x.js"`, `fetch("./d.json")`, `src=`) are
   resolved and fetched too, to a fixed depth.
4. **Documentation** (`.md` / `.markdown` / `.txt`) is mirrored even though
   nothing imports it: conventional names (`README.md`, `AGENTS.md`, `SPEC.md`,
   `TODO.md`, `BUILD.md`, …) are probed in the root and in every project
   directory, and any doc *mentioned* in the sources is fetched as well. Callers
   can override/add docs with `opts.docs` (a `{path: text}` map). The root
   `AGENTS.md` is a special case: generators do **not** serve it (it 404s at the
   host origin), so the plugin falls back to a hosted mirror
   (`AGENTS_MIRROR_URL` in `project.js`). Because docs are ordinary files in the
   committed tree, edits to them show up in the history as normal unified diffs.

Because `src/` is only visible through references, a source file that nothing
mentions is not surfaced. `scratch/` is never published on the web, so a host's
scratch files can only be shown if the host passes `scratchFiles` in (the plugin's
own page does this from its local workspace); a bare import shows the host clone
only.

