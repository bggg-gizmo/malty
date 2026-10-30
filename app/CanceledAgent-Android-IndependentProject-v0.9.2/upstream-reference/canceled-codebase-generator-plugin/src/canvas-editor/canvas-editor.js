/*
  Canvas Editor — main-thread half.

  Renders a real code editor (text, syntax colouring, cursor, selection,
  scrolling, undo) entirely inside an OffscreenCanvas owned by a Web Worker,
  and forwards input to it. The DOM only ever contains a <canvas> (blitted from
  the worker's frames) plus an invisible <textarea> that captures keystrokes,
  IME composition and paste.

  Usage:
    import { CanvasEditor } from "./canvas-editor.js";
    const ed = new CanvasEditor({ theme: "dark", tabSize: 2 });
    await ed.mount(container, { language: "javascript", path: "main.js", text });
    ed.on("change", ({ text }) => { ... });
*/
import { getWorkerSource } from "./worker-source.js";

const HANDLED_KEYS = {
  ArrowLeft: 1, ArrowRight: 1, ArrowUp: 1, ArrowDown: 1,
  Home: 1, End: 1, PageUp: 1, PageDown: 1,
  Enter: 1, Tab: 1, Backspace: 1, Delete: 1, Escape: 1
};
const HANDLED_CTRL = { a: 1, z: 1, y: 1, d: 1, "/": 1, f: 1 };

let SHARED_WORKER_URL = null;
async function sharedWorkerUrl() {
  if (!SHARED_WORKER_URL) {
    const src = await getWorkerSource();
    SHARED_WORKER_URL = URL.createObjectURL(new Blob([src], { type: "text/javascript" }));
  }
  return SHARED_WORKER_URL;
}

export class CanvasEditor {
  constructor(opts) {
    this.opts = Object.assign({
      theme: "dark", tabSize: 2, fontSize: 13, language: "plaintext", path: "",
      gutter: true, indentGuides: true, currentLine: true, dprCap: 2
    }, opts || {});
    this.listeners = {};
    this.text = "";
    this.lines = [""];
    this.sel = null;
    this.cursor = { line: 0, col: 0 };
    this.stats = { lines: 1, chars: 0, matchTotal: 0, matchIndex: -1 };
    this.worker = null;
    this.root = null;
    this.canvas = null;
    this.input = null;
    this.ready = false;
    this.seq = 0;
    this._resolveReady = null;
    this._readyPromise = null;
    this._dragging = false;
    this._composing = false;
    this._ro = null;
    this._destroyed = false;
    this._lastSize = { w: 0, h: 0, dpr: 0 };
  }

  /* ------------------------------------------------------------- events */
  on(ev, cb) { (this.listeners[ev] = this.listeners[ev] || []).push(cb); return () => this.off(ev, cb); }
  off(ev, cb) { this.listeners[ev] = (this.listeners[ev] || []).filter((x) => x !== cb); }
  emit(ev, data) { (this.listeners[ev] || []).slice().forEach((cb) => { try { cb(data); } catch (e) { console.warn("[canvas-editor]", e); } }); }

  /* -------------------------------------------------------------- mount */
  async mount(container, opts) {
    if (opts) Object.assign(this.opts, opts);
    if (this.opts.text != null && !this.text) { this.text = String(this.opts.text); this.lines = this.text.split("\n"); }
    if (typeof container === "string") container = document.querySelector(container);
    if (!container) throw new Error("canvas-editor: no container");
    if (getComputedStyle(container).position === "static") container.style.position = "relative";

    const root = document.createElement("div");
    root.className = "ce-root";
    root.style.cssText = "position:absolute;inset:0;overflow:hidden;background:" + (this.opts.theme === "light" ? "#fff" : "#1e1e1e");
    const canvas = document.createElement("canvas");
    canvas.className = "ce-canvas";
    canvas.style.cssText = "position:absolute;inset:0;width:100%;height:100%;display:block";
    const input = document.createElement("textarea");
    input.className = "ce-input";
    input.setAttribute("autocomplete", "off");
    input.setAttribute("autocorrect", "off");
    input.setAttribute("autocapitalize", "off");
    input.setAttribute("spellcheck", "false");
    input.setAttribute("wrap", "off");
    input.setAttribute("aria-label", "code editor");
    input.tabIndex = 0;
    input.style.cssText = [
      "position:absolute", "left:0", "top:0", "width:2px", "height:2px",
      "padding:0", "margin:0", "border:0", "outline:none", "resize:none",
      "overflow:hidden", "opacity:0.01", "background:transparent", "color:transparent",
      "caret-color:transparent", "white-space:pre", "font-size:1px", "z-index:3",
      "pointer-events:none"
    ].join(";");
    root.appendChild(canvas);
    root.appendChild(input);
    container.appendChild(root);

    this.root = root;
    this.canvas = canvas;
    this.input = input;

    this._bctx = canvas.getContext("bitmaprenderer");

    const url = await sharedWorkerUrl();
    if (this._destroyed) return this;
    this.worker = new Worker(url);
    this.worker.onmessage = (e) => this._onWorker(e.data || {});

    this._bindMouse();
    this._bindKeys();

    const off = new OffscreenCanvas(1, 1);
    this.worker.postMessage({
      t: "init", canvas: off, opts: this._optionsPayload(), theme: this.opts.theme,
      doc: { text: this.opts.text || "", language: this.opts.language, path: this.opts.path }
    }, [off]);
    this._readyPromise = new Promise((res) => { this._resolveReady = res; });

    if (window.ResizeObserver) {
      this._ro = new ResizeObserver(() => this.resize());
      this._ro.observe(root);
    }
    this.resize();
    if (window.devicePixelRatio && window.matchMedia) {
      try { window.matchMedia("(resolution: " + window.devicePixelRatio + "dppx)").addEventListener("change", () => this.resize()); } catch (e) { }
    }
    return this;
  }

  whenReady() { return this._readyPromise || Promise.resolve(this); }

  _optionsPayload() {
    return { tabSize: this.opts.tabSize, fontSize: this.opts.fontSize, gutter: this.opts.gutter, indentGuides: this.opts.indentGuides, currentLine: this.opts.currentLine };
  }
  _post(m) { if (this.worker && !this._destroyed) { try { this.worker.postMessage(m); } catch (e) { } } }

  /* ------------------------------------------------------------ worker in */
  _onWorker(m) {
    if (m.t === "frame") {
      if (this._bctx) { try { this._bctx.transferFromImageBitmap(m.bmp); } catch (e) { } }
      return;
    }
    if (m.t === "ready") {
      this.ready = true;
      this._resolveReady && this._resolveReady(this);
      this.emit("ready", this);
      return;
    }
    if (m.t === "change") {
      this.text = m.text; this.lines = m.text.split("\n"); this.seq = m.seq;
      this.emit("change", { text: this.text, seq: m.seq });
      return;
    }
    if (m.t === "cursor") {
      this.cursor = { line: m.line, col: m.col };
      this.sel = m.sel || null;
      this.stats = { lines: m.lines, chars: m.chars, matchTotal: m.matchTotal, matchIndex: m.matchIndex, scroll: m.scroll };
      this._placeInput(m.cx, m.cy, m.lh);
      this.emit("cursor", { line: m.line, col: m.col, sel: this.sel, stats: this.stats });
      return;
    }
    if (m.t === "clipboard") {
      this.emit("clipboard", m);
      if (this._pendingClipboard) { this._pendingClipboard(m.text); this._pendingClipboard = null; }
      return;
    }
  }

  _placeInput(cx, cy, lh) {
    if (!this.input || cx == null) return;
    this.input.style.left = Math.max(0, cx) + "px";
    this.input.style.top = Math.max(0, cy) + "px";
    this.input.style.height = Math.max(2, lh || 2) + "px";
  }

  /* -------------------------------------------------------------- sizing */
  resize() {
    if (!this.root || !this.canvas || !this.worker) return;
    const r = this.root.getBoundingClientRect();
    const dpr = Math.max(1, Math.min(this.opts.dprCap || 2, window.devicePixelRatio || 1));
    const w = Math.max(1, Math.round(r.width)), h = Math.max(1, Math.round(r.height));
    if (w === this._lastSize.w && h === this._lastSize.h && dpr === this._lastSize.dpr) return;
    this._lastSize = { w: w, h: h, dpr: dpr };
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this._post({ t: "resize", w: w, h: h, dpr: dpr });
  }

  /* --------------------------------------------------------------- input */
  _bindMouse() {
    const cv = this.canvas;
    const pos = (e) => {
      const r = cv.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    };
    cv.addEventListener("mousedown", (e) => {
      e.preventDefault();
      try { this.input.focus({ preventScroll: true }); } catch (x) { this.input.focus(); }
      const p = pos(e);
      this._dragging = true;
      this._post({ t: "mouse", kind: "down", x: p.x, y: p.y, detail: e.detail, shift: e.shiftKey, alt: e.altKey, ctrl: e.ctrlKey, meta: e.metaKey });
    });
    window.addEventListener("mousemove", (e) => {
      if (!this._dragging) return;
      const p = pos(e);
      this._post({ t: "mouse", kind: "move", x: p.x, y: p.y, shift: e.shiftKey, alt: e.altKey });
    });
    window.addEventListener("mouseup", () => {
      if (!this._dragging) return;
      this._dragging = false;
      this._post({ t: "mouse", kind: "up" });
    });
    cv.addEventListener("wheel", (e) => {
      e.preventDefault();
      const mult = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? 100 : 1);
      this._post({ t: "wheel", dx: e.deltaX * mult, dy: e.deltaY * mult });
    }, { passive: false });
    cv.addEventListener("contextmenu", (e) => e.preventDefault());
    cv.addEventListener("dblclick", (e) => e.preventDefault());
  }

  _bindKeys() {
    const input = this.input, self = this;

    input.addEventListener("focus", () => { this._post({ t: "focus" }); this.emit("focus", {}); });
    input.addEventListener("blur", () => { this._post({ t: "blur" }); this.emit("blur", {}); });

    input.addEventListener("keydown", (e) => {
      const k = e.key;
      let handled = false;
      const ctrlLike = e.ctrlKey || e.metaKey;
      if (HANDLED_KEYS[k]) {
        // Ctrl/Cmd+Enter is the host app's "run" shortcut — let it bubble.
        if (!(ctrlLike && k === "Enter")) handled = true;
      } else if (ctrlLike && HANDLED_CTRL[k.toLowerCase()]) handled = true;
      else if (e.altKey && (k === "ArrowUp" || k === "ArrowDown")) handled = true;
      if (!handled) return;
      e.preventDefault();
      e.stopPropagation();
      if (ctrlLike && k.toLowerCase() === "f") { this.emit("find", {}); return; }
      this._post({
        t: "key", key: k, ctrl: e.ctrlKey, meta: e.metaKey, shift: e.shiftKey, alt: e.altKey
      });
    });

    input.addEventListener("input", () => {
      if (this._composing) return;
      const v = input.value;
      input.value = "";
      if (v) this._post({ t: "insert", text: v });
    });
    input.addEventListener("compositionstart", () => { this._composing = true; });
    input.addEventListener("compositionend", () => {
      this._composing = false;
      const v = input.value;
      input.value = "";
      if (v) this._post({ t: "insert", text: v });
    });

    input.addEventListener("paste", (e) => {
      const t = e.clipboardData ? e.clipboardData.getData("text/plain") : "";
      e.preventDefault();
      if (t) this._post({ t: "insert", text: t });
    });
    input.addEventListener("copy", (e) => {
      const t = self.getSelection();
      if (!t) return;
      e.preventDefault();
      if (e.clipboardData) e.clipboardData.setData("text/plain", t);
      self.emit("copy", { text: t });
    });
    input.addEventListener("cut", (e) => {
      const t = self.getSelection();
      e.preventDefault();
      if (t && e.clipboardData) e.clipboardData.setData("text/plain", t);
      self._post({ t: "cmd", name: "deleteSelection" });
      self.emit("cut", { text: t });
    });
  }

  /* ---------------------------------------------------------------- api */
  setDoc(text, o) {
    o = o || {};
    if (o.language) this.opts.language = o.language;
    if (o.path != null) this.opts.path = o.path;
    if (String(text) === this.text && !o.force) { return; }
    this.text = String(text);
    this.lines = this.text.split("\n");
    this._post({ t: "setDoc", text: this.text, language: this.opts.language, path: this.opts.path });
  }
  getValue() { return this.text; }
  getSelection() {
    if (!this.sel) return "";
    const s = this.sel;
    if (s.al === s.bl) return (this.lines[s.al] || "").slice(s.ac, s.bc);
    const out = [(this.lines[s.al] || "").slice(s.ac)];
    for (let l = s.al + 1; l < s.bl; l++) out.push(this.lines[l] || "");
    out.push((this.lines[s.bl] || "").slice(0, s.bc));
    return out.join("\n");
  }
  focus() { try { this.input.focus({ preventScroll: true }); } catch (e) { this.input.focus(); } }
  blur() { this.input.blur(); }
  isFocused() { return document.activeElement === this.input; }
  command(name, args) { this._post({ t: "cmd", name: name, args: args }); return this; }
  setTheme(theme) { this.opts.theme = theme; this._post({ t: "setTheme", theme: theme }); if (this.root) this.root.style.background = theme === "light" ? "#fff" : "#1e1e1e"; }
  setOptions(o) { Object.assign(this.opts, o || {}); this._post({ t: "setOptions", opts: this._optionsPayload() }); }
  setSearch(q) { this.command("setSearch", q); }
  findNext() { this.command("findNext"); }
  findPrev() { this.command("findPrev"); }
  undo() { this.command("undo"); }
  redo() { this.command("redo"); }
  selectAll() { this.command("selectAll"); }
  goToLine(n) { this.command("goToLine", n); }
  insert(text) { this._post({ t: "insert", text: String(text) }); }
  destroy() {
    this._destroyed = true;
    if (this._ro) { try { this._ro.disconnect(); } catch (e) { } }
    if (this.worker) { try { this.worker.postMessage({ t: "close" }); } catch (e) { } try { this.worker.terminate(); } catch (e) { } }
    if (this.root && this.root.parentNode) this.root.parentNode.removeChild(this.root);
    this.listeners = {};
  }
}

export default CanvasEditor;
