/*
  project-panel.js — the UI half of the "clone + git" feature.

  A self-contained panel (usable inside any container) that:
    1. fetches the host generator's project (main.pjs, index.html, src/**),
    2. shows it as a file tree,
    3. exposes the local scratch workspace alongside it,
    4. keeps a mini-git history of every sync (and of scratch commits),
       with per-commit unified diffs and a changelog.

  createProjectPanel(container, opts) -> controller
    opts.store          {get,set,delete}   required (kv-plugin folder works)
    opts.generatorName  defaults to window.generatorName
    opts.scratchFiles   () => [{name, text}]   local workspace, optional
    opts.scratchPrefix  default "local/"
    opts.fetchText      optional CORS-free text fetcher (superFetch)
    opts.autoSync       default true — sync on mount
    opts.onChange       ({kind}) => {}     called after a commit
*/

import { createRepo } from "./git.js";
import { fetchHostProject, buildTree } from "./project.js";
import { createDefaultStore } from "./store.js";

const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const bytes = (n) => n < 1024 ? n + " B" : n < 1024 * 1024 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB";
const rel = (t) => {
  const d = Math.max(0, Date.now() - t);
  if (d < 60e3) return "just now";
  if (d < 3600e3) return Math.floor(d / 60e3) + "m ago";
  if (d < 86400e3) return Math.floor(d / 3600e3) + "h ago";
  return new Date(t).toLocaleDateString();
};

const CSS = `
.pp{display:flex;flex-direction:column;height:100%;min-height:0;text-align:left;
  color:var(--fw-fg,#d4d4d4);font:12.5px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
.pp *{box-sizing:border-box}
.pp-bar{display:flex;align-items:center;gap:6px;padding:6px 8px;flex:0 0 auto;
  border-bottom:1px solid var(--fw-border,#3c3c3c);background:var(--fw-bar,#2d2d30)}
.pp-name{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pp-dim{color:var(--fw-muted,#9a9a9a);font-size:11px;white-space:nowrap}
.pp-sp{flex:1 1 auto}
.pp button{border:1px solid transparent;background:transparent;color:inherit;cursor:pointer;border-radius:5px;
  padding:3px 8px;font:inherit;white-space:nowrap}
.pp button:hover{background:rgba(128,128,128,.18)}
.pp button.primary{background:color-mix(in srgb,var(--fw-accent,#4f9cf9) 26%,transparent);
  border-color:color-mix(in srgb,var(--fw-accent,#4f9cf9) 55%,transparent)}
.pp input.bp-msg{flex:1 1 auto;min-width:60px;height:24px;padding:2px 7px;border-radius:5px;
  border:1px solid var(--fw-border,#3c3c3c);background:rgba(0,0,0,.22);color:inherit;font:inherit;outline:none}
.pp-tabs{display:flex;gap:2px;padding:4px 6px 0;flex:0 0 auto;border-bottom:1px solid var(--fw-border,#3c3c3c)}
.pp-tab{padding:4px 10px;border-radius:6px 6px 0 0;color:var(--fw-muted,#9a9a9a)}
.pp-tab.on{background:rgba(128,128,128,.2);color:inherit}
.pp-body{flex:1 1 auto;min-height:0;overflow:auto;padding:6px 8px}
.pp-group{margin:2px 0 8px}
.pp-ghead{display:flex;align-items:center;gap:6px;font-size:10.5px;letter-spacing:.09em;text-transform:uppercase;
  color:var(--fw-muted,#9a9a9a);padding:4px 2px}
.pp-row{display:flex;align-items:center;gap:6px;padding:2px 4px;border-radius:5px;cursor:pointer;white-space:nowrap}
.pp-row:hover{background:rgba(128,128,128,.16)}
.pp-row.sel{background:color-mix(in srgb,var(--fw-accent,#4f9cf9) 26%,transparent)}
.pp-row .pp-ic{width:14px;text-align:center;color:var(--fw-muted,#9a9a9a);flex:0 0 auto}
.pp-row .pp-p{font-family:ui-monospace,Menlo,Consolas,monospace;overflow:hidden;text-overflow:ellipsis}
.pp-row .pp-n{color:var(--fw-muted,#9a9a9a);font-size:10.5px;margin-left:auto;flex:0 0 auto}
.pp-dir{margin-left:14px;border-left:1px solid rgba(128,128,128,.28);padding-left:4px}
.pp-commit{display:flex;gap:8px;align-items:flex-start;padding:5px 6px;border-radius:6px;cursor:pointer}
.pp-commit:hover{background:rgba(128,128,128,.16)}
.pp-commit.sel{background:color-mix(in srgb,var(--fw-accent,#4f9cf9) 24%,transparent)}
.pp-cid{font-family:ui-monospace,Menlo,Consolas,monospace;color:var(--fw-accent,#4f9cf9);flex:0 0 auto}
.pp-cmsg{min-width:0}
.pp-cmeta{color:var(--fw-muted,#9a9a9a);font-size:11px;margin-top:1px}
.pp-add{color:#6a9955}
.pp-del{color:#f14c4c}
.pp-view{font:12px/1.5 ui-monospace,Menlo,Consolas,monospace;white-space:pre;overflow:auto;margin:0}
.pp-view .l{display:block;padding:0 4px}
.pp-view .l.add{background:rgba(106,153,85,.16)}
.pp-view .l.del{background:rgba(241,76,76,.14)}
.pp-view .l.ctx{color:var(--fw-muted,#9a9a9a)}
.pp-view .l.hunk{color:var(--fw-accent,#4f9cf9)}
.pp-empty{color:var(--fw-muted,#9a9a9a);padding:8px 4px}
.pp-err{color:#f14c4c;font-size:11px;padding:2px 4px}
.pp-badge{padding:1px 6px;border-radius:999px;font-size:10px;letter-spacing:.05em;text-transform:uppercase;
  border:1px solid rgba(128,128,128,.4);color:var(--fw-muted,#9a9a9a)}
`;

let CSS_DONE = false;
function injectCSS() {
  if (CSS_DONE || typeof document === "undefined") return;
  CSS_DONE = true;
  const s = document.createElement("style");
  s.setAttribute("data-project-panel", "");
  s.textContent = CSS;
  document.head.appendChild(s);
}

function treeRows(node, out, depth, open) {
  for (const child of node.children) {
    if (child.dir) {
      const isOpen = open.has(child.path);
      out.push({ dir: true, path: child.path, name: child.name, depth: depth });
      if (isOpen) treeRows(child, out, depth + 1, open);
    } else {
      out.push({ dir: false, path: child.path, name: child.name, depth: depth });
    }
  }
  return out;
}

export function createProjectPanel(container, opts) {
  opts = opts || {};
  injectCSS();
  const name = opts.generatorName || (typeof window !== "undefined" && window.generatorName) || "generator";
  const scratchPrefix = opts.scratchPrefix == null ? "local/" : opts.scratchPrefix;
  const repoName = opts.repoName || ("project:" + name);
  const store = opts.store || createDefaultStore(name);
  const repo = createRepo({ store: store, name: repoName, maxCommits: opts.maxCommits || 250 });
  const scratchFiles = typeof opts.scratchFiles === "function" ? opts.scratchFiles : null;

  const st = {
    tab: "files",
    projectFiles: {},
    projectName: name,
    projectFailed: [],
    projectDocs: [],
    syncState: "idle",
    commits: [],
    selectedCommit: null,
    viewer: null,
    openDirs: new Set(),
    lastError: "",
  };

  const el = document.createElement("div");
  el.className = "pp";
  container.appendChild(el);

  /* ---------- render ---------- */
  function renderBar() {
    const n = Object.keys(st.projectFiles).length;
    const stateLabel = st.syncState === "syncing" ? "syncing…" : st.syncState === "error" ? "offline" : (n ? n + " files" : "not synced");
    return '<div class="pp-bar">' +
      '<span class="pp-name">' + esc(st.projectName) + '</span>' +
      '<span class="pp-dim">' + esc(stateLabel) + '</span>' +
      '<span class="pp-sp"></span>' +
      '<input class="bp-msg" id="ppMsg" placeholder="commit message (optional)" autocomplete="off" />' +
      '<button id="ppSync" class="primary" title="Fetch the host project and commit any changes">Sync</button>' +
      '</div>';
  }
  function renderTabs() {
    return '<div class="pp-tabs">' +
      ["files", "history"].map((t) => '<button class="pp-tab' + (st.tab === t ? " on" : "") + '" data-tab="' + t + '">' + (t === "files" ? "Files" : "History") + '</button>').join("") +
      '</div>';
  }
  function rowHTML(r) {
    const pad = "padding-left:" + (2 + r.depth * 12) + "px";
    const ic = r.dir ? (st.openDirs.has(r.path) ? "▾" : "▸") : (/\.(md|markdown|txt)$/i.test(r.name) ? "≡" : "·");
    return '<div class="pp-row" data-path="' + esc(r.path) + '" data-dir="' + (r.dir ? "1" : "0") + '" style="' + pad + '">' +
      '<span class="pp-ic">' + ic + '</span><span class="pp-p">' + esc(r.name) + '</span></div>';
  }
  function renderFiles() {
    let html = "";
    /* host project tree */
    html += '<div class="pp-group"><div class="pp-ghead">Project · host clone</div>';
    if (!Object.keys(st.projectFiles).length) html += '<div class="pp-empty">Not synced yet — hit Sync.</div>';
    else {
      const rows = treeRows(buildTree(st.projectFiles), [], 0, st.openDirs);
      html += rows.map(rowHTML).join("");
    }
    html += '</div>';
    /* scratch tree */
    const scratch = scratchFiles ? scratchFiles() : [];
    if (scratch && scratch.length) {
      const map = {};
      for (const f of scratch) map[(scratchPrefix || "") + f.name] = f.text;
      html += '<div class="pp-group"><div class="pp-ghead">Scratch · local (' + scratch.length + ')</div>';
      const rows = treeRows(buildTree(map), [], 0, st.openDirs);
      html += rows.map(rowHTML).join("");
      html += '</div>';
    }
    if (st.projectFailed && st.projectFailed.length) {
      html += '<div class="pp-group"><div class="pp-ghead">Unavailable</div>';
      html += st.projectFailed.slice(0, 12).map((f) => '<div class="pp-err">' + esc(f.path) + " — " + esc(f.error) + '</div>').join("");
      html += '</div>';
    }
    return html;
  }
  function renderHistory() {
    if (!st.commits.length) return '<div class="pp-empty">No commits yet. Sync to record the project version.</div>';
    return '<div class="pp-group">' + st.commits.map((c) =>
      '<div class="pp-commit' + (st.selectedCommit === c.id ? " sel" : "") + '" data-commit="' + esc(c.id) + '">' +
      '<span class="pp-cid">' + esc(c.id.slice(0, 7)) + '</span>' +
      '<div class="pp-cmsg"><div>' + esc(c.message || "(no message)") + '</div>' +
      '<div class="pp-cmeta">' + esc(c.author) + ' · ' + rel(c.at) + ' · ' + c.stats.files + ' file' + (c.stats.files === 1 ? "" : "s") +
      ' <span class="pp-add">+' + c.stats.added + '</span> <span class="pp-del">-' + c.stats.removed + '</span></div></div></div>'
    ).join("") + '</div>';
  }
  function renderViewer() {
    const v = st.viewer;
    if (!v) return "";
    const back = '<div style="margin-bottom:6px"><button id="ppBack">\u2190 back</button> <span class="pp-dim">' + esc(v.title) + '</span></div>';
    let body;
    if (v.kind === "patch") {
      const lines = v.text.split("\n");
      body = '<div class="pp-view">' + lines.map((l) => {
        let cls = "ctx";
        if (l.startsWith("+") && !l.startsWith("+++")) cls = "add";
        else if (l.startsWith("-") && !l.startsWith("---")) cls = "del";
        else if (l.startsWith("@@")) cls = "hunk";
        return '<span class="l ' + cls + '">' + (esc(l) || "&nbsp;") + '</span>';
      }).join("") + '</div>';
    } else {
      const lines = String(v.text == null ? "" : v.text).split("\n");
      body = '<div class="pp-view">' + lines.map((l, i) =>
        '<span class="l"><span class="pp-dim" style="display:inline-block;width:3.2em;text-align:right;padding-right:8px">' + (i + 1) + '</span>' + (esc(l) || "&nbsp;") + '</span>'
      ).join("") + '</div>';
    }
    return back + body;
  }

  function paint() {
    const body = st.viewer ? renderViewer() : (st.tab === "files" ? renderFiles() : renderHistory());
    el.innerHTML = renderBar() + renderTabs() + '<div class="pp-body" id="ppBody">' + body + '</div>';
    bind();
  }

  function bind() {
    const syncBtn = el.querySelector("#ppSync");
    if (syncBtn) syncBtn.onclick = () => sync();
    const msg = el.querySelector("#ppMsg");
    if (msg) msg.addEventListener("keydown", (e) => { if (e.key === "Enter") sync(msg.value.trim()); });
    el.querySelectorAll("[data-tab]").forEach((b) => b.onclick = () => { st.tab = b.dataset.tab; st.viewer = null; paint(); });
    const back = el.querySelector("#ppBack");
    if (back) back.onclick = () => { st.viewer = null; paint(); };
    el.querySelectorAll("[data-path]").forEach((r) => r.onclick = () => {
      const path = r.dataset.path;
      if (r.dataset.dir === "1") { st.openDirs.has(path) ? st.openDirs.delete(path) : st.openDirs.add(path); paint(); return; }
      openFile(path);
    });
    el.querySelectorAll("[data-commit]").forEach((c) => c.onclick = () => openCommit(c.dataset.commit));
  }

  async function openFile(path) {
    const isScratch = scratchFiles && path.startsWith(scratchPrefix);
    let text = null;
    try { text = await repo.read(path); } catch (e) { text = null; }
    if (text == null && !isScratch && st.projectFiles[path] != null) text = st.projectFiles[path];
    if (text == null && isScratch) { const f = (scratchFiles() || []).find((x) => (scratchPrefix + x.name) === path); text = f ? f.text : ""; }
    st.viewer = { kind: "file", title: path + " · " + bytes(text == null ? 0 : text.length), text: text == null ? "(not in history)" : text };
    paint();
  }

  async function openCommit(id) {
    const c = await repo.show(id);
    st.selectedCommit = id;
    const files = c ? c.changes.map((x) => x.path + " (" + x.status + ")").join(", ") : "";
    st.viewer = { kind: "patch", title: c ? (c.id.slice(0, 7) + " · " + (c.message || "(no message)") + " · " + files) : "commit not found", text: c ? c.patch : "" };
    paint();
  }

  async function refresh() {
    st.commits = await repo.log(120);
    paint();
  }

  async function sync(message) {
    if (st.syncState === "syncing") return;
    st.syncState = "syncing";
    st.lastError = "";
    paint();
    try {
      const proj = await fetchHostProject({ generatorName: name, fetchText: opts.fetchText, docs: opts.docs });
      st.projectFiles = proj.files;
      st.projectName = proj.generatorName || name;
      st.projectFailed = proj.failed || [];
      st.projectDocs = proj.docs || [];
      /* Root doc aliases: conventional top-level docs live in src/ on the host
         (workspace root isn't served), so mirror them at the clone root too. */
      for (const n of ["README.md", "SPEC.md", "TODO.md"]) {
        if (st.projectFiles[n] == null && st.projectFiles["src/" + n] != null) {
          st.projectFiles[n] = st.projectFiles["src/" + n];
          if (st.projectDocs.indexOf(n) < 0) st.projectDocs.push(n);
        }
      }
      const tree = Object.assign({}, proj.files);
      /* fold the local scratch workspace into the same history, prefixed */
      if (scratchFiles) for (const f of (scratchFiles() || [])) tree[(scratchPrefix || "") + f.name] = f.text;
      const res = await repo.commit(tree, { message: message || ("sync " + new Date().toLocaleString()) , author: "sync" });
      st.syncState = "done";
      st.lastCommit = res.commit ? res.commit.id : null;
      st.unchanged = !!res.unchanged;
      await refresh();
      if (opts.onChange) opts.onChange({ kind: "sync", commit: res.commit });
    } catch (e) {
      st.syncState = "error";
      st.lastError = String(e && e.message || e);
      paint();
      if (opts.onChange) opts.onChange({ kind: "error", error: st.lastError });
    }
  }

  async function commit(message, author) {
    const tree = Object.assign({}, st.projectFiles);
    if (scratchFiles) for (const f of (scratchFiles() || [])) tree[(scratchPrefix || "") + f.name] = f.text;
    const res = await repo.commit(tree, { message: message || ("commit " + new Date().toLocaleString()), author: author || "user" });
    await refresh();
    return res;
  }

  paint();
  if (opts.autoSync !== false) sync(opts.initialMessage).catch(() => {});

  return {
    el: el, repo: repo, sync: sync, commit: commit, refresh: refresh,
    getState: () => st,
    /* read access to what the panel reveals */
    getFiles: () => Object.assign({}, st.projectFiles),
    getDocs: () => {
      const out = {};
      for (const p of (st.projectDocs || [])) if (st.projectFiles[p] != null) out[p] = st.projectFiles[p];
      return out;
    },
    getFailed: () => (st.projectFailed || []).slice(),
    getScratch: () => (scratchFiles ? scratchFiles() : []).slice(),
    readFile: async (path) => {
      const isScratch = scratchFiles && scratchPrefix && path.startsWith(scratchPrefix);
      if (isScratch) {
        const f = (scratchFiles() || []).find((x) => (scratchPrefix + x.name) === path);
        if (f) return f.text;
      }
      try { const t = await repo.read(path); if (t != null) return t; } catch (e) { /* ignore */ }
      return st.projectFiles[path] != null ? st.projectFiles[path] : null;
    },
    destroy() { el.remove(); },
  };
}

export default { createProjectPanel };
