/*
  canceled-codebase-generator-plugin — an OffscreenCanvas code editor in a
  floating, resizable, collapsible window.

  The editor is a real, editable code view (text, syntax colouring, cursor,
  selection, scrolling, undo/redo, find, line numbers, indent guides) that is
  painted by a Web Worker into an OffscreenCanvas and blitted to the page. The
  host page only supplies a container.

  Quick start
  -----------
    // 1. import the plugin in main.pjs:
    //    canvasEditor = {import:canceled-codebase-generator-plugin}
    // 2. then, from page code:
    const { editor, win } = await root.canvasEditor.createEditorWindow({
      title: "Editor", path: "main.js", language: "javascript",
      text: "console.log('hi')", theme: "dark",
    });

  API
  ---
    createEditorWindow(opts) -> { win, editor }   floating window + editor
    mount(container, opts)   -> editor            bare editor in a container
    CanvasEditor                                  the editor class
    createFloatingWindow(opts)                    the window primitive
    createProjectPanel(container, opts)           clone + mini-git of the host project
    createProjectWindow(opts)                     createProjectPanel in a floating window
    fetchHostProject(opts)                        fetch {path:text} of the host project
    createRepo(opts)                              the content-addressed mini-git
*/
import { CanvasEditor } from "./canvas-editor.js";
import { createFloatingWindow } from "./floating-window.js";
import { createProjectPanel } from "./project-panel.js";
import { fetchHostProject, discoverRefs, discoverDocs, isProsePath, buildTree } from "./project.js";
import { createRepo, hashText } from "./git.js";
import { diffRows, diffStat, unifiedDiff } from "./diff.js";
import { createMemoryStore, createIdbStore, createDefaultStore } from "./store.js";

export { CanvasEditor, createFloatingWindow, createProjectPanel, fetchHostProject, discoverRefs, discoverDocs, isProsePath, buildTree, createRepo, hashText, diffRows, diffStat, unifiedDiff, createMemoryStore, createIdbStore, createDefaultStore };
export const version = "1.2.0";

const STATUS_STYLE = "font-size:11px;color:var(--fw-muted,#9a9a9a);font-family:ui-monospace,Menlo,Consolas,monospace;white-space:nowrap;";

function buildFindBar(editor, light) {
  const bar = document.createElement("div");
  bar.hidden = true;
  bar.style.cssText = "display:none;align-items:center;gap:6px;padding:4px 6px;border-bottom:1px solid rgba(128,128,128,.28);background:" +
    (light ? "#ececec" : "#2d2d30") + ";flex:0 0 auto";
  const inp = document.createElement("input");
  inp.type = "text";
  inp.placeholder = "find…";
  inp.style.cssText = "flex:1 1 auto;min-width:0;height:24px;padding:2px 7px;border-radius:5px;border:1px solid rgba(128,128,128,.4);" +
    "background:" + (light ? "#fff" : "#1e1e1e") + ";color:" + (light ? "#1f1f1f" : "#d4d4d4") + ";font:12px ui-monospace,Menlo,Consolas,monospace;outline:none";
  const count = document.createElement("span");
  count.style.cssText = STATUS_STYLE;
  const mk = (label, title) => {
    const b = document.createElement("button");
    b.textContent = label; b.title = title;
    b.style.cssText = "border:0;background:transparent;color:inherit;cursor:pointer;padding:2px 6px;border-radius:4px;font-size:12px";
    return b;
  };
  const prev = mk("\u2191", "Previous match");
  const next = mk("\u2193", "Next match");
  const close = mk("\u00d7", "Close find");
  bar.appendChild(inp); bar.appendChild(count); bar.appendChild(prev); bar.appendChild(next); bar.appendChild(close);

  const run = () => { editor.setSearch(inp.value); editor.findNext(); };
  let t = 0;
  inp.addEventListener("input", () => { clearTimeout(t); t = setTimeout(run, 90); });
  inp.addEventListener("keydown", (e) => {
    if (e.key === "Enter") { e.preventDefault(); e.shiftKey ? editor.findPrev() : editor.findNext(); }
    else if (e.key === "Escape") { e.preventDefault(); hide(); editor.focus(); }
  });
  prev.addEventListener("click", () => editor.findPrev());
  next.addEventListener("click", () => editor.findNext());
  close.addEventListener("click", () => { hide(); editor.focus(); });

  function show() { bar.hidden = false; bar.style.display = "flex"; inp.focus(); inp.select(); }
  function hide() { bar.hidden = true; bar.style.display = "none"; editor.setSearch(""); }
  editor.on("find", show);
  editor.on("cursor", (c) => {
    if (!inp.value) { count.textContent = ""; return; }
    count.textContent = c.stats.matchTotal ? ((c.stats.matchIndex + 1) + "/" + c.stats.matchTotal) : "0/0";
  });
  return { bar, show, hide };
}

export async function createEditorWindow(opts) {
  opts = opts || {};
  const light = opts.theme === "light";
  const win = createFloatingWindow({
    title: opts.title || "Editor",
    subtitle: opts.path || "",
    x: opts.x != null ? opts.x : 56,
    y: opts.y != null ? opts.y : 44,
    width: opts.width || 640,
    height: opts.height || 420,
    minWidth: opts.minWidth || 260,
    minHeight: opts.minHeight || 120,
    parent: opts.parent,
    light: light,
    collapsible: opts.collapsible !== false,
    closable: opts.closable !== false,
    resizable: opts.resizable !== false
  });

  const col = document.createElement("div");
  col.style.cssText = "display:flex;flex-direction:column;height:100%;min-height:0";
  const host = document.createElement("div");
  host.style.cssText = "flex:1 1 auto;position:relative;min-height:0";
  col.appendChild(host);
  win.body.appendChild(col);

  const editor = new CanvasEditor({
    theme: light ? "light" : "dark",
    tabSize: opts.tabSize || 2,
    fontSize: opts.fontSize || 13,
    language: opts.language || "plaintext",
    path: opts.path || "",
    gutter: opts.gutter !== false,
    indentGuides: opts.indentGuides !== false,
    currentLine: opts.currentLine !== false
  });
  await editor.mount(host, { text: opts.text || "", language: opts.language, path: opts.path });

  const find = buildFindBar(editor, light);
  col.insertBefore(find.bar, host);

  const fmt = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  editor.on("cursor", (c) => {
    win.setSubtitle((opts.path || editor.opts.path || "") + "   \u00b7   Ln " + (c.line + 1) + ", Col " + (c.col + 1) +
      (editor.sel && editor.sel.chars ? "   \u00b7   " + fmt(editor.sel.chars) + " selected" : "") +
      "   \u00b7   " + fmt(editor.stats.lines) + " lines");
  });
  editor.on("change", () => win.emit("change", { text: editor.getValue(), path: editor.opts.path }));

  win.editor = editor;
  return { win: win, editor: editor };
}

export async function mount(container, opts) {
  opts = opts || {};
  const editor = new CanvasEditor(opts);
  await editor.mount(container, { text: opts.text || "", language: opts.language, path: opts.path });
  return editor;
}

export function createProjectWindow(opts) {
  opts = opts || {};
  const light = opts.theme === "light";
  const win = createFloatingWindow({
    title: opts.title || "Project",
    subtitle: opts.generatorName || "",
    x: opts.x != null ? opts.x : 72,
    y: opts.y != null ? opts.y : 60,
    width: opts.width || 560,
    height: opts.height || 440,
    minWidth: opts.minWidth || 260,
    minHeight: opts.minHeight || 160,
    parent: opts.parent,
    light: light,
    collapsible: opts.collapsible !== false,
    closable: opts.closable !== false,
    resizable: opts.resizable !== false
  });
  const panel = createProjectPanel(win.body, opts);
  win.panel = panel;
  return { win: win, panel: panel, repo: panel.repo };
}

export default { createEditorWindow, mount, createProjectWindow, createProjectPanel, fetchHostProject, createRepo, createDefaultStore, CanvasEditor, createFloatingWindow, version };
