/*
  project.js — clone the *host* generator's project out of the platform.

  When this editor is imported into some generator, that generator's source is
  not reachable through the DOM — but most of it is public:

    main.pjs   <- /api/getGeneratorsAndDependencies   (CORS-enabled)
    index.html <- /api/getGeneratorHtml               (needs a CORS-free fetch)
    src/**     <- served from the host origin; the paths are *discovered* by
                  scanning the pjs + html for `src/...` references
    scratch/** <- same discovery; served only if the host published it

  Everything is returned as a flat {path: text} map so it can be committed to
  the mini-git as one tree.
*/

const REF_RE = /(?:^|["'`(=\s,\[])((?:\.\/)?(?:src|scratch)\/[A-Za-z0-9._@\-/]+?)(?=["'`)\s,\];?]|$)/g;
const EXCLUDE = /(^|\/)(\.\.|node_modules)$/;

/* Documentation is mirrored too, even though nothing imports it. We probe a set
   of conventional names in every project directory, and fetch any doc that is
   *mentioned* anywhere in the sources (e.g. "see src/canvas-editor/BUILD.md"). */
const DOC_NAMES = ["README.md", "AGENTS.md", "SPEC.md", "TODO.md", "BUILD.md", "CHANGELOG.md", "NOTES.md", "ROADMAP.md", "PLAN.md", "DESIGN.md"];
const DOC_RE = /(?:^|["'`(=\s,:])((?:\.{0,2}\/)?[A-Za-z0-9._@\-/]+?\.(?:md|markdown|txt))(?=["'`)\s,\];?]|$)/g;

/* A permanent mirror of the agent-instructions file (AGENTS.md lives at the
   workspace root, which generators do NOT serve, so it cannot be fetched from
   the host origin — the plugin carries a hosted mirror instead). */
const AGENTS_MIRROR_URL = "https://user.uploads.dev/file/b081fd9ddb025c93efa313b7d9102bf1.txt";

/* Prose files are never ref-scanned: their examples and mentions (e.g. the
   screenshot paths in src/change.log, or install instructions naming another
   generator's src/api.js) are not real imports. A 404 on a *discovered* ref
   likewise means "mentioned but not published", so it is skipped silently —
   only genuine errors (non-404) land in `failed`. */
export function isProsePath(p) {
  return /\.(md|markdown|txt|log)$/i.test(p || "");
}

export function discoverDocs(text) {
  const out = new Set();
  if (!text) return out;
  const re = new RegExp(DOC_RE.source, "g");
  let m;
  while ((m = re.exec(text))) {
    let p = m[1].replace(/[.,;:'"`)\]]+$/, "");
    if (p.startsWith("./")) p = p.slice(2);
    if (!p || p.endsWith("/")) continue;
    out.add(p);
  }
  return out;
}

export function discoverRefs(text) {
  const found = new Set();
  if (!text) return found;
  let m;
  const re = new RegExp(REF_RE.source, "g");
  while ((m = re.exec(text))) {
    let p = m[1].replace(/[.,;:'"`)\]]+$/, "");
    if (p.startsWith("./")) p = p.slice(2);
    if (!p || p.endsWith("/")) continue;
    if (!/\.[A-Za-z0-9]{1,8}$/.test(p)) continue; /* must look like a file */
    if (EXCLUDE.test(p)) continue;
    found.add(p);
  }
  return found;
}

/* quoted relative specifiers: imports, fetch(), src=/href=, css url() */
const SPEC_RE = /(?:from\s*|\bimport\s*\(?\s*|\bfetch\s*\(\s*|\bsrc\s*=\s*|\bhref\s*=\s*|\burl\s*\(\s*|["'`(=\s,\[])(["'`])(\.\.?\/[A-Za-z0-9._@\-/]+?)\1/g;

export function relativeSpecs(text) {
  const out = new Set();
  if (!text) return out;
  const re = new RegExp(SPEC_RE.source, "g");
  let m;
  while ((m = re.exec(text))) {
    let p = m[2].replace(/[.,;:'"`)\]]+$/, "");
    if (p && p !== "./" && p !== "../" && /\.[A-Za-z0-9]{1,8}$/.test(p)) out.add(p);
  }
  return out;
}

function resolveSpec(baseDir, spec) {
  if (!spec || /^https?:/.test(spec)) return null;
  const stack = baseDir ? baseDir.split("/") : [];
  for (const part of spec.split("/")) {
    if (part === "." || part === "") continue;
    if (part === "..") stack.pop();
    else stack.push(part);
  }
  const p = stack.join("/");
  return p && /\.[A-Za-z0-9]{1,8}$/.test(p) ? p : null;
}

function defaultFetchText() {
  return async function (url) {
    const root = typeof window !== "undefined" && window.root;
    if (root && typeof root.superFetch === "function") {
      try { const r = await root.superFetch(url); return await r.text(); } catch (e) { /* fall through */ }
    }
    const plugin = typeof window !== "undefined" && window.__canceledCodebaseEditorPlugin;
    if (plugin && typeof plugin.fetchText === "function") {
      try { return await plugin.fetchText(url); } catch (e) { /* fall through */ }
    }
    const r = await fetch(url);
    if (!r.ok) throw new Error(url + " -> " + r.status);
    return await r.text();
  };
}

export async function fetchHostProject(opts) {
  opts = opts || {};
  const name = opts.generatorName || (typeof window !== "undefined" && window.generatorName) || "";
  const origin = opts.origin || (typeof location !== "undefined" ? location.origin : "");
  const fetchText = opts.fetchText || defaultFetchText();
  const files = {};
  const failed = [];
  const wanted = new Set(opts.extraFiles || []);

  /* --- main.pjs (+ imports + last edit) via the public dependency API --- */
  let imports = [];
  let lastEditTime = null;
  try {
    const r = await fetch("https://perchance.org/api/getGeneratorsAndDependencies?generatorNames=" + encodeURIComponent(name));
    const data = await r.json();
    const g = data && data.generators && data.generators[name];
    if (g) {
      if (typeof g.code === "string") files["main.pjs"] = g.code;
      imports = g.imports || [];
      lastEditTime = g.lastEditTime || null;
    }
  } catch (e) { failed.push({ path: "main.pjs", error: String(e && e.message || e) }); }

  /* --- index.html via the HTML-panel API --- */
  try {
    const html = await fetchText("https://perchance.org/api/getGeneratorHtml?generatorName=" + encodeURIComponent(name));
    if (typeof html === "string" && html.length) files["index.html"] = html;
  } catch (e) { failed.push({ path: "index.html", error: String(e && e.message || e) }); }

  /* --- discover + fetch src/ and scratch/ references --- */
  for (const p of discoverRefs(files["main.pjs"] || "")) wanted.add(p);
  for (const p of discoverRefs(files["index.html"] || "")) wanted.add(p);

  const fetched = await Promise.all([...wanted].map(async (path) => {
    const url = /^https?:/.test(path) ? path : (origin + "/" + path.replace(/^\.?\//, ""));
    try {
      const r = await fetch(url);
      if (!r.ok) {
        if (r.status === 404) return { path: path, missing: true };
        return { path: path, error: "HTTP " + r.status };
      }
      return { path: path, text: await r.text() };
    } catch (e) { return { path: path, error: String(e && e.message || e) }; }
  }));
  for (const f of fetched) {
    if (f.text != null) files[f.path] = f.text;
    else if (!f.missing) failed.push({ path: f.path, error: f.error });
  }

  /* nested references: css url(...), and relative imports inside a src module
     (e.g. index.js -> "./canvas-editor.js") resolved against the importer's dir */
  const tried = new Set([...wanted]);
  for (let pass = 0; pass < 6; pass++) {
    const extra = new Set();
    for (const path of Object.keys(files)) {
      if (path === "main.pjs" || path === "index.html") continue;
      if (isProsePath(path)) continue; /* prose: don't treat its examples as refs */
      const dir = path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
      for (const spec of relativeSpecs(files[path])) {
        const resolved = resolveSpec(dir, spec);
        if (resolved && !files[resolved] && !tried.has(resolved)) extra.add(resolved);
      }
      for (const p of discoverRefs(files[path])) if (!files[p] && !tried.has(p)) extra.add(p);
    }
    if (!extra.size) break;
    const more = await Promise.all([...extra].map(async (path) => {
      tried.add(path);
      try { const r = await fetch(origin + "/" + path.replace(/^\.?\//, "")); if (!r.ok) return r.status === 404 ? { path: path, missing: true } : { path: path, error: "HTTP " + r.status }; return { path: path, text: await r.text() }; }
      catch (e) { return { path: path, error: String(e && e.message || e) }; }
    }));
    let got = false;
    for (const f of more) { if (f.text != null && !files[f.path]) { files[f.path] = f.text; got = true; } else if (!f.missing) failed.push({ path: f.path, error: f.error }); }
    if (!got) break;
  }

  /* --- documentation mirror ---
     Every *.md/*.txt in the project is mirrored even though nothing imports it:
     conventional names are probed in each project directory, and any doc that is
     *mentioned* in the sources is fetched too. Caller-supplied `opts.docs` win,
     and the (unserved) root AGENTS.md falls back to a hosted mirror. */
  const dirs = new Set([""]);
  const addDirs = (p) => {
    const parts = String(p).split("/");
    let acc = "";
    for (let i = 0; i < parts.length - 1; i++) { acc = acc ? acc + "/" + parts[i] : parts[i]; dirs.add(acc); }
  };
  for (const p of Object.keys(files)) addDirs(p);
  for (const p of wanted) addDirs(p);

  const docWanted = new Set();
  for (const d of dirs) for (const n of DOC_NAMES) docWanted.add((d ? d + "/" : "") + n);
  for (let pass = 0; pass < 3; pass++) {
    let added = false;
    for (const text of Object.values(files)) for (const p of discoverDocs(text)) { if (!docWanted.has(p) && !files[p]) { docWanted.add(p); added = true; } }
    if (!added) break;
  }
  const docFetched = await Promise.all([...docWanted].map(async (path) => {
    if (files[path] || tried.has(path)) return null;
    tried.add(path);
    try {
      const r = await fetch(origin + "/" + path.replace(/^\.?\//, ""));
      return r.ok ? { path: path, text: await r.text() } : null;
    } catch (e) { return null; }
  }));
  for (const f of docFetched) if (f && !files[f.path]) files[f.path] = f.text;

  if (opts.docs && typeof opts.docs === "object") {
    for (const p of Object.keys(opts.docs)) if (typeof opts.docs[p] === "string") files[p] = opts.docs[p];
  }
  if (!files["AGENTS.md"] && AGENTS_MIRROR_URL) {
    try { const t = await fetchText(AGENTS_MIRROR_URL); if (typeof t === "string" && t.length) files["AGENTS.md"] = t; }
    catch (e) { /* optional */ }
  }

  const docs = Object.keys(files).filter((p) => /\.(md|markdown|txt)$/i.test(p)).sort();
  return { generatorName: name, origin: origin, imports: imports, lastEditTime: lastEditTime, files: files, failed: failed, docs: docs };
}

/* Build a nested tree from a flat path->text map (for a file-tree UI). */
export function buildTree(files) {
  const root = { name: "", path: "", dir: true, children: [] };
  const index = new Map([["", root]]);
  const ensure = (dirPath) => {
    if (index.has(dirPath)) return index.get(dirPath);
    const slash = dirPath.lastIndexOf("/");
    const parent = ensure(slash < 0 ? "" : dirPath.slice(0, slash));
    const node = { name: dirPath.slice(slash + 1), path: dirPath, dir: true, children: [] };
    parent.children.push(node); index.set(dirPath, node);
    return node;
  };
  for (const path of Object.keys(files || {}).sort()) {
    const slash = path.lastIndexOf("/");
    const parent = ensure(slash < 0 ? "" : path.slice(0, slash));
    parent.children.push({ name: path.slice(slash + 1), path: path, dir: false, children: [] });
  }
  const sortRec = (n) => { n.children.sort((a, b) => (a.dir === b.dir) ? a.name.localeCompare(b.name) : (a.dir ? -1 : 1)); n.children.forEach(sortRec); };
  sortRec(root);
  return root;
}

export default { fetchHostProject, discoverRefs, discoverDocs, isProsePath, buildTree };
