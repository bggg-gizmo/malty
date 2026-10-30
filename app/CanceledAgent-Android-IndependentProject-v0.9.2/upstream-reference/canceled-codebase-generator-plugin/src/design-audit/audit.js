import { REGISTRY, pseverity, SCORE_W, gradeFor, checkEmDashOveruse, checkBuzzwords, checkAphoristicCadence, checkTheaterSlop, checkMonotonousSpacing, checkRepeatedContainerText, checkNumberedSectionLabels } from "./rules.js";

const SKIP_TAGS = new Set(["SCRIPT", "STYLE", "HEAD", "META", "LINK", "TITLE", "NOSCRIPT", "TEMPLATE"]);
const MAX_ELS = 2000;

function cssPath(el, root) {
  const parts = [];
  let n = el;
  for (let d = 0; n && n !== root && n.nodeType === 1 && d < 6; n = n.parentElement, d++) {
    let s = n.tagName.toLowerCase();
    if (n.id) { parts.unshift(s + "#" + n.id); break; }
    const sib = n.parentElement ? [...n.parentElement.children].filter((c) => c.tagName === n.tagName) : [];
    if (sib.length > 1) s += ":nth-of-type(" + (sib.indexOf(n) + 1) + ")";
    if (n.className && typeof n.className === "string") {
      const c0 = n.className.trim().split(/\s+/)[0];
      if (c0) s += "." + c0;
    }
    parts.unshift(s);
  }
  return parts.join(" > ") || "(root)";
}

function ownText(el) {
  let out = "";
  for (const n of el.childNodes) {
    if (n.nodeType === 3) out += n.textContent;
  }
  return out.trim();
}

function visibleText(root) {
  const walker = root.ownerDocument.createTreeWalker(root, 4);
  const skip = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE"]);
  let out = "";
  let n;
  while ((n = walker.nextNode())) {
    const p = n.parentElement;
    if (p && skip.has(p.tagName)) continue;
    out += n.textContent + " ";
    if (out.length > 200000) break;
  }
  return out;
}

export async function runDesignAudit(opts) {
  opts = opts || {};
  const t0 = Date.now();
  const max = Math.max(1, Math.min(500, opts.max == null ? 120 : opts.max));
  const only = opts.rules ? new Set(opts.rules) : null;
  const t = String(opts.target || "page");

  let doc, root, note = "";
  if (t === "page") {
    doc = document;
    root = document.body;
  } else if (t === "emulate" || t === "project") {
    const gate = window.__editorGate;
    if (!gate) throw new Error("design-audit: editor gate missing");
    const html = t === "emulate" ? gate.buildEmulateDoc() : gate.buildProjectDoc();
    const f = document.createElement("iframe");
    f.setAttribute("sandbox", "allow-scripts allow-same-origin");
    f.style.cssText = "position:fixed;left:-9999px;top:0;width:1280px;height:900px;border:0;visibility:hidden;";
    document.body.appendChild(f);
    try {
      f.srcdoc = html;
      await new Promise((r) => setTimeout(r, 1800));
      doc = f.contentDocument;
      if (!doc || !doc.body) throw new Error("design-audit: target doc did not boot");
      root = doc.body;
      note = "rendered " + t + " doc offscreen";
    } catch (e) {
      f.remove();
      throw e;
    }
    const findings = collect(doc, root, only, max);
    f.remove();
    return finish(findings, only, Date.now() - t0, t, note);
  } else {
    doc = document;
    root = document.querySelector(t);
    if (!root) throw new Error("design-audit: no element matches " + t);
    note = "subtree " + t;
  }
  const findings = collect(doc, root, only, max);
  return finish(findings, only, Date.now() - t0, t, note);
}

function collect(doc, root, only, max) {
  const byId = new Map(REGISTRY.map((r) => [r.id, r]));
  const out = [];
  const push = (rule, selector, snippet) => {
    if (only && !only.has(rule)) return;
    if (out.length >= max) return;
    const row = byId.get(rule);
    if (!row) return;
    out.push({ rule: rule, name: row.name, severity: pseverity(row), selector: selector, snippet: snippet });
  };
  if (!only || only.has("em-dash-overuse")) {
    for (const h of checkEmDashOveruse(visibleText(root))) {
      push(h.rule, cssPath(root, root.ownerDocument.body || root), h.snippet);
    }
  }
  const bodyText = visibleText(root);
  const scope = root.ownerDocument.body || root;
  if (!only || only.has("marketing-buzzword")) {
    for (const h of checkBuzzwords(bodyText)) {
      push(h.rule, cssPath(root, scope), h.snippet);
    }
  }
  if (!only || only.has("aphoristic-cadence")) {
    for (const h of checkAphoristicCadence(bodyText)) {
      push(h.rule, cssPath(root, scope), h.snippet);
    }
  }
  if (!only || only.has("theater-slop-phrase")) {
    for (const h of checkTheaterSlop(bodyText)) {
      push(h.rule, cssPath(root, scope), h.snippet);
    }
  }
  if (!only || only.has("monotonous-spacing")) {
    for (const h of checkMonotonousSpacing(root)) {
      push(h.rule, cssPath(h.el || root, scope), h.snippet);
    }
  }
  if (!only || only.has("repeated-container-text")) {
    for (const h of checkRepeatedContainerText(root)) {
      push(h.rule, cssPath(h.el || root, scope), h.snippet);
    }
  }
  if (!only || only.has("numbered-section-labels")) {
    for (const h of checkNumberedSectionLabels(root)) {
      push(h.rule, cssPath(h.el || root, scope), h.snippet);
    }
  }
  const els = root.getElementsByTagName("*");
  const n = Math.min(els.length, MAX_ELS);
  for (let i = 0; i < n; i++) {
    if (out.length >= max) break;
    const el = els[i];
    if (SKIP_TAGS.has(el.tagName)) continue;
    void el;
  }
  return out;
}

function finish(findings, only, ms, target, note) {
  const counts = { P0: 0, P1: 0, P2: 0, P3: 0 };
  for (const f of findings) counts[f.severity] = (counts[f.severity] || 0) + 1;
  const score = Math.max(0, 100 - counts.P0 * SCORE_W.P0 - counts.P1 * SCORE_W.P1 - counts.P2 * SCORE_W.P2 - counts.P3 * SCORE_W.P3);
  return {
    target: target, note: note, score: score, grade: gradeFor(score),
    counts: counts, rules: only ? only.size : REGISTRY.length,
    findings: findings, stats: { findings: findings.length, ms: ms },
  };
}

export default { runDesignAudit };
