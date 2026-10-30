/*
  FloatingWindow — a draggable, resizable, collapsible, closable panel.

  Deliberately dependency-free (no framework): the whole thing is one <div>
  with pointer-event drag/resize handles. Used by the plugin to host the
  OffscreenCanvas editor, but generic enough to hold anything.

  const win = createFloatingWindow({ title: "Editor", x: 60, y: 40, width: 640, height: 420 });
  win.body.appendChild(myCanvasHost);
  win.on("collapse", (v) => console.log(v));
*/

let CSS_DONE = false;
function injectCSS() {
  if (CSS_DONE || !document) return;
  CSS_DONE = true;
  const s = document.createElement("style");
  s.setAttribute("data-floating-window", "");
  s.textContent = `
.fw{position:absolute;display:flex;flex-direction:column;box-sizing:border-box;
  background:var(--fw-bg,#252526);color:var(--fw-fg,#d4d4d4);
  border:1px solid var(--fw-border,#3c3c3c);border-radius:9px;
  box-shadow:0 14px 44px rgba(0,0,0,.46);overflow:hidden;
  font:13px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
  text-align:left;touch-action:none;min-width:0;min-height:0}
.fw.raised{box-shadow:0 20px 60px rgba(0,0,0,.6)}
.fw.collapsed{height:auto !important}
.fw.collapsed .fw-body{display:none}
.fw-bar{display:flex;align-items:center;gap:7px;height:30px;flex:0 0 30px;padding:0 4px 0 10px;
  background:var(--fw-bar,#2d2d30);border-bottom:1px solid var(--fw-border,#3c3c3c);
  cursor:grab;user-select:none;touch-action:none}
.fw.collapsed .fw-bar{border-bottom-color:transparent}
.fw-bar:active{cursor:grabbing}
.fw-dot{width:8px;height:8px;border-radius:50%;background:var(--fw-accent,#4f9cf9);flex:0 0 auto}
.fw-title{font-size:11.5px;font-weight:600;letter-spacing:.05em;text-transform:uppercase;
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--fw-muted,#9a9a9a)}
.fw-sub{font-size:11px;color:var(--fw-muted,#9a9a9a);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
.fw-spacer{flex:1 1 auto}
.fw-btn{width:22px;height:22px;display:inline-flex;align-items:center;justify-content:center;
  border:0;background:transparent;color:var(--fw-muted,#9a9a9a);border-radius:5px;cursor:pointer;
  font-size:14px;line-height:1;padding:0;flex:0 0 auto}
.fw-btn:hover{background:rgba(255,255,255,.12);color:var(--fw-fg,#d4d4d4)}
.fw-btn svg{width:12px;height:12px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.fw-body{flex:1 1 auto;position:relative;min-height:0;min-width:0;overflow:hidden}
.fw-rz{position:absolute;z-index:4;touch-action:none}
.fw-rz[data-dir=n]{top:-3px;left:8px;right:8px;height:7px;cursor:ns-resize}
.fw-rz[data-dir=s]{bottom:-3px;left:8px;right:8px;height:7px;cursor:ns-resize}
.fw-rz[data-dir=w]{left:-3px;top:8px;bottom:8px;width:7px;cursor:ew-resize}
.fw-rz[data-dir=e]{right:-3px;top:8px;bottom:8px;width:7px;cursor:ew-resize}
.fw-rz[data-dir=nw]{left:-3px;top:-3px;width:14px;height:14px;cursor:nwse-resize}
.fw-rz[data-dir=ne]{right:-3px;top:-3px;width:14px;height:14px;cursor:nesw-resize}
.fw-rz[data-dir=sw]{left:-3px;bottom:-3px;width:14px;height:14px;cursor:nesw-resize}
.fw-rz[data-dir=se]{right:-3px;bottom:-3px;width:14px;height:14px;cursor:nwse-resize}
.fw.light{--fw-bg:#f3f3f3;--fw-bar:#ececec;--fw-border:#d4d4d4;--fw-fg:#1f1f1f;--fw-muted:#6b6b6b;--fw-accent:#005fb8}
`;
  document.head.appendChild(s);
}

let Z = 4000;
let SEQ = 0;

export function createFloatingWindow(opts) {
  injectCSS();
  const o = Object.assign({
    title: "Window", subtitle: "", x: 48, y: 40, width: 560, height: 380,
    minWidth: 220, minHeight: 120, maxWidth: 0, maxHeight: 0,
    resizable: true, collapsible: true, closable: true, light: false,
    parent: null, className: "", icon: ""
  }, opts || {});

  const parent = o.parent || document.getElementById("app") || document.body;
  const listeners = {};
  const el = document.createElement("div");
  el.className = "fw " + (o.light ? "light " : "") + o.className;
  el.style.left = o.x + "px";
  el.style.top = o.y + "px";
  el.style.width = o.width + "px";
  el.style.height = o.height + "px";
  el.style.zIndex = String(++Z);

  const bar = document.createElement("div");
  bar.className = "fw-bar";
  const dot = document.createElement("span");
  dot.className = "fw-dot";
  if (o.icon) dot.style.background = o.icon;
  const title = document.createElement("span");
  title.className = "fw-title";
  title.textContent = o.title;
  const sub = document.createElement("span");
  sub.className = "fw-sub";
  sub.textContent = o.subtitle || "";
  const spacer = document.createElement("span");
  spacer.className = "fw-spacer";
  bar.appendChild(dot);
  bar.appendChild(title);
  bar.appendChild(sub);
  bar.appendChild(spacer);

  const win = {
    el, bar, body: null, id: "fw" + (++SEQ), expandedHeight: o.height, collapsed: false,
    on(ev, cb) { (listeners[ev] = listeners[ev] || []).push(cb); return () => win.off(ev, cb); },
    off(ev, cb) { listeners[ev] = (listeners[ev] || []).filter((x) => x !== cb); },
    emit(ev, d) { (listeners[ev] || []).slice().forEach((cb) => { try { cb(d); } catch (e) { console.warn("[floating-window]", e); } }); },
    setTitle(t) { title.textContent = t; },
    setSubtitle(t) { sub.textContent = t || ""; },
    raise() { el.style.zIndex = String(++Z); },
    rect() { const r = el.getBoundingClientRect(); return { x: r.left - parent.getBoundingClientRect().left, y: r.top - parent.getBoundingClientRect().top, width: r.width, height: r.height }; },
    setRect(p) {
      if (p.x != null) el.style.left = p.x + "px";
      if (p.y != null) el.style.top = p.y + "px";
      if (p.width != null) el.style.width = Math.max(o.minWidth, p.width) + "px";
      if (p.height != null) { el.style.height = Math.max(o.minHeight, p.height) + "px"; win.expandedHeight = p.height; }
      win.emit("resize", win.rect());
    },
    collapse(v) {
      const next = (v == null) ? !win.collapsed : !!v;
      if (next === win.collapsed) return;
      if (next) { win.expandedHeight = el.getBoundingClientRect().height; el.classList.add("collapsed"); }
      else { el.classList.remove("collapsed"); el.style.height = Math.max(o.minHeight, win.expandedHeight || o.height) + "px"; }
      win.collapsed = next;
      const btn = bar.querySelector('[data-act="collapse"]');
      if (btn) btn.setAttribute("aria-expanded", String(!next));
      win.emit("collapse", next);
      win.emit("resize", win.rect());
    },
    toggle() { win.collapse(); },
    show(v) { el.style.display = v ? "" : "none"; if (v) win.emit("resize", win.rect()); },
    close() { win.emit("close", win); el.remove(); },
    destroy() { el.remove(); }
  };

  if (o.collapsible) {
    const b = document.createElement("button");
    b.className = "fw-btn"; b.dataset.act = "collapse"; b.title = "Collapse";
    b.setAttribute("aria-label", "Collapse window");
    b.innerHTML = '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>';
    b.addEventListener("click", (e) => { e.stopPropagation(); win.collapse(); });
    bar.appendChild(b);
  }
  if (o.closable) {
    const b = document.createElement("button");
    b.className = "fw-btn"; b.dataset.act = "close"; b.title = "Close";
    b.setAttribute("aria-label", "Close window");
    b.innerHTML = '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>';
    b.addEventListener("click", (e) => { e.stopPropagation(); win.close(); });
    bar.appendChild(b);
  }

  const body = document.createElement("div");
  body.className = "fw-body";
  el.appendChild(bar);
  el.appendChild(body);
  win.body = body;

  if (o.resizable) {
    ["n", "s", "w", "e", "nw", "ne", "sw", "se"].forEach((dir) => {
      const h = document.createElement("div");
      h.className = "fw-rz"; h.dataset.dir = dir;
      el.appendChild(h);
    });
  }

  parent.appendChild(el);

  /* bring to front on any interaction */
  el.addEventListener("pointerdown", () => win.raise(), true);

  /* ---- drag ---- */
  let drag = null;
  bar.addEventListener("pointerdown", (e) => {
    if (e.target.closest("button")) return;
    const r = el.getBoundingClientRect();
    drag = { dx: e.clientX - r.left, dy: e.clientY - r.top, w: r.width };
    try { bar.setPointerCapture(e.pointerId); } catch (x) { }
    e.preventDefault();
  });
  /* ---- resize ---- */
  let rz = null;
  el.querySelectorAll(".fw-rz").forEach((h) => {
    h.addEventListener("pointerdown", (e) => {
      const r = el.getBoundingClientRect();
      rz = { dir: h.dataset.dir, sx: e.clientX, sy: e.clientY, x: r.left, y: r.top, w: r.width, h: r.height };
      try { h.setPointerCapture(e.pointerId); } catch (x) { }
      e.preventDefault(); e.stopPropagation();
    });
  });

  function parentRect() { return parent.getBoundingClientRect(); }
  el.addEventListener("pointermove", (e) => {
    const pr = parentRect();
    if (drag) {
      let nx = e.clientX - pr.left - drag.dx;
      let ny = e.clientY - pr.top - drag.dy;
      nx = Math.max(-drag.w + 90, Math.min(pr.width - 40, nx));
      ny = Math.max(0, Math.min(pr.height - 30, ny));
      el.style.left = nx + "px"; el.style.top = ny + "px";
      return;
    }
    if (rz) {
      const dx = e.clientX - rz.sx, dy = e.clientY - rz.sy;
      let x = rz.x - pr.left, y = rz.y - pr.top, w = rz.w, h = rz.h;
      const maxW = o.maxWidth || pr.width, maxH = o.maxHeight || pr.height;
      if (rz.dir.indexOf("e") >= 0) w = Math.max(o.minWidth, Math.min(maxW, rz.w + dx));
      if (rz.dir.indexOf("s") >= 0) h = Math.max(o.minHeight, Math.min(maxH, rz.h + dy));
      if (rz.dir.indexOf("w") >= 0) { const nw = Math.max(o.minWidth, Math.min(maxW, rz.w - dx)); x = rz.x - pr.left + (rz.w - nw); w = nw; }
      if (rz.dir.indexOf("n") >= 0) { const nh = Math.max(o.minHeight, Math.min(maxH, rz.h - dy)); y = rz.y - pr.top + (rz.h - nh); h = nh; }
      el.style.left = x + "px"; el.style.top = y + "px";
      el.style.width = w + "px"; el.style.height = h + "px";
      win.expandedHeight = h;
      win.emit("resize", { x: x, y: y, width: w, height: h });
      return;
    }
  });
  function endPointer() {
    if (drag || rz) { win.emit("resize", win.rect()); }
    drag = null; rz = null;
  }
  el.addEventListener("pointerup", endPointer);
  el.addEventListener("pointercancel", endPointer);
  bar.addEventListener("dblclick", (e) => { if (e.target.closest("button")) return; win.collapse(); });

  return win;
}

export default createFloatingWindow;
