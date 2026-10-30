/*
  Canvas Editor — worker half.
  Owns an OffscreenCanvas, the document model, syntax tokenisation, cursor /
  selection, scrolling and the undo stack. The main thread only forwards input
  events and blits the ImageBitmaps this worker posts back.

  Protocol (worker -> main):
    { t:"frame", bmp, w, h }         a rendered frame (transferred ImageBitmap)
    { t:"ready" }                    initialised
    { t:"change", text, seq }        document text changed by the editor
    { t:"cursor", ... }              cursor / selection / scroll / stats
    { t:"clipboard", kind, text }    copy|cut payload for the main thread
  Protocol (main -> worker):
    { t:"init", canvas, opts, theme, doc }
    { t:"resize", w, h, dpr }        { t:"setDoc", text, language, path }
    { t:"setTheme", theme }          { t:"setOptions", opts }
    { t:"focus" } { t:"blur" }       { t:"insert", text }
    { t:"key", key, ctrl, meta, shift, alt }
    { t:"mouse", kind, x, y, detail, shift, alt, ctrl, meta }
    { t:"wheel", dx, dy }            { t:"cmd", name, args }
*/
'use strict';

var cv = null, ctx = null, W = 1, H = 1, DPR = 1;
var FONT_FAMILY = 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", "Courier New", monospace';
var fs = 13, lh = 20, cw = 8;
var padTop = 8, padLeft = 8, gutterPad = 12, gutterW = 48;

/* ------------------------------------------------------------------ themes */
var THEMES = {
  dark: {
    bg: '#1e1e1e', fg: '#d4d4d4', gutter: '#858585', gutterActive: '#c6c6c6', curLine: '#262626',
    sel: '#264f78', selInactive: '#3a3d41', cursor: '#e8e8e8', indent: '#404040', scroll: '#4e4e4e',
    search: '#613214', searchCur: '#9e6a03',
    kw: '#569cd6', str: '#ce9178', num: '#b5cea8', com: '#6a9955', fn: '#dcdcaa', type: '#4ec9b0',
    op: '#d4d4d4', punc: '#808080', tag: '#569cd6', attr: '#9cdcfe', prop: '#9cdcfe',
    vari: '#9cdcfe', builtin: '#4fc1ff', hdr: '#569cd6', link: '#4fc1ff', meta: '#c586c0'
  },
  light: {
    bg: '#ffffff', fg: '#1f1f1f', gutter: '#237893', gutterActive: '#0b216f', curLine: '#f4f4f4',
    sel: '#add6ff', selInactive: '#e5ebf1', cursor: '#000000', indent: '#e0e0e0', scroll: '#c1c1c1',
    search: '#f8d78a', searchCur: '#ffb648',
    kw: '#0000ff', str: '#a31515', num: '#098658', com: '#008000', fn: '#795e26', type: '#267f99',
    op: '#1f1f1f', punc: '#6b6b6b', tag: '#800000', attr: '#e50000', prop: '#0451a5',
    vari: '#001080', builtin: '#0070c1', hdr: '#800000', link: '#0451a5', meta: '#af00db'
  }
};
var TH = THEMES.dark;

/* -------------------------------------------------------------- tokenisers */
var JS_KW = {}, JS_BUILTIN = {};
('as async await break case catch class const continue debugger default delete do else enum export extends false finally for from function get if implements import in instanceof interface let new null of package private protected public return set static super switch this throw true try typeof var void while with yield').split(' ').forEach(function (k) { JS_KW[k] = 1; });
('console Math JSON Object Array String Number Boolean Promise Map Set WeakMap WeakSet Date RegExp Error Symbol BigInt Reflect Proxy Infinity NaN undefined globalThis window document self fetch setTimeout setInterval clearTimeout clearInterval queueMicrotask structuredClone require module exports process root location navigator performance').split(' ').forEach(function (k) { JS_BUILTIN[k] = 1; });

var JS_RE = /\s+|(\/\*[\s\S]*?(?:\*\/|$))|(\/\/[^\n]*)|(`(?:\\[\s\S]|[^\\`])*`?)|("(?:\\[\s\S]|[^\\"\n])*"?)|('(?:\\[\s\S]|[^\\'\n])*'?)|(0[xX][0-9a-fA-F_]+n?|0[bB][01_]+n?|0[oO][0-7_]+n?|\d[\d_]*(?:\.[\d_]*)?(?:[eE][+-]?\d+)?n?)|([A-Za-z_$][\w$]*)|([{}()\[\]])|([+\-*/%=<>!&|^~?:;,.@#\\])|([\s\S])/y;

function scanJS(text) {
  var out = [], i = 0, n = text.length, prevSig = '';
  while (i < n) {
    JS_RE.lastIndex = i;
    var m = JS_RE.exec(text);
    if (!m) break;
    var s = i, e = i + m[0].length; i = e;
    if (m[1] || m[2]) { out.push([s, e, 'com']); prevSig = ''; continue; }
    if (m[3] || m[4] || m[5]) { out.push([s, e, 'str']); prevSig = 'v'; continue; }
    if (m[6]) { out.push([s, e, 'num']); prevSig = 'v'; continue; }
    if (m[7]) {
      var w = m[7], nx = /^\s*(.)/.exec(text.slice(e, e + 24));
      var t;
      if (JS_KW[w]) t = 'kw';
      else if (JS_BUILTIN[w]) t = 'builtin';
      else if (prevSig === '.') t = 'prop';
      else if (nx && nx[1] === '(') t = 'fn';
      else if (/^[A-Z]/.test(w)) t = 'type';
      else t = 'vari';
      out.push([s, e, t]); prevSig = 'v'; continue;
    }
    if (m[8]) { out.push([s, e, 'punc']); prevSig = m[0]; continue; }
    if (m[9]) { out.push([s, e, 'op']); prevSig = m[0]; continue; }
    prevSig = '';
  }
  return out;
}

function scanHTML(text) {
  var out = [], i = 0, n = text.length;
  while (i < n) {
    var lt = text.indexOf('<', i);
    if (lt < 0) { out.push([i, n, 'fg']); break; }
    if (lt > i) out.push([i, lt, 'fg']);
    if (text.substr(lt, 4) === '<!--') {
      var ce = text.indexOf('-->', lt + 4); ce = ce < 0 ? n : ce + 3;
      out.push([lt, ce, 'com']); i = ce; continue;
    }
    var gt = text.indexOf('>', lt);
    if (gt < 0) { out.push([lt, n, 'fg']); break; }
    out.push([lt, lt + 1, 'punc']);
    var j = lt + 1;
    if (text[j] === '!') { out.push([lt, gt + 1, 'meta']); i = gt + 1; continue; }
    if (text[j] === '/') { out.push([j, j + 1, 'punc']); j++; }
    var ns = j;
    while (j < gt && /[A-Za-z0-9:_.\-]/.test(text[j])) j++;
    if (j > ns) out.push([ns, j, 'tag']);
    while (j < gt) {
      var ch = text[j];
      if (/\s/.test(ch)) { j++; continue; }
      if (ch === '"' || ch === "'") {
        var q = ch, k = text.indexOf(q, j + 1); k = (k < 0 || k > gt) ? gt : k + 1;
        out.push([j, k, 'str']); j = k; continue;
      }
      if (ch === '=') { out.push([j, j + 1, 'op']); j++; continue; }
      if (ch === '/' || ch === '{' || ch === '}' || ch === '(' || ch === ')' || ch === '!') { out.push([j, j + 1, 'punc']); j++; continue; }
      var as = j;
      while (j < gt && !/[\s=/>"'{}()]/.test(text[j])) j++;
      if (j > as) out.push([as, j, 'attr']); else j++;
    }
    if (gt < n) out.push([gt, gt + 1, 'punc']);
    i = gt + 1;
  }
  return out;
}

var CSS_RE = /(\/\*[\s\S]*?(?:\*\/|$))|("(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*')|(@[A-Za-z\-]+)|(#[0-9a-fA-F]{3,8}\b)|(-?\d*\.?\d+(?:[a-z%]+)?)|([A-Za-z\-][\w\-]*)|([{}();:,>+~*\[\]=])|(\s+)/y;
function scanCSS(text) {
  var out = [], i = 0, n = text.length, depth = 0;
  while (i < n) {
    CSS_RE.lastIndex = i;
    var m = CSS_RE.exec(text);
    if (!m) { out.push([i, i + 1, 'fg']); i++; continue; }
    var s = i, e = i + m[0].length;
    if (m[1]) out.push([s, e, 'com']);
    else if (m[2]) out.push([s, e, 'str']);
    else if (m[3]) out.push([s, e, 'meta']);
    else if (m[4] || m[5]) out.push([s, e, 'num']);
    else if (m[6]) out.push([s, e, depth === 0 ? 'tag' : (/^\s*:/.test(text.slice(e, e + 12)) ? 'prop' : 'vari')]);
    else if (m[7]) { out.push([s, e, 'punc']); if (m[7] === '{') depth++; else if (m[7] === '}') depth = Math.max(0, depth - 1); }
    i = e;
  }
  return out;
}

var JSON_RE = /(\s+)|("(?:\\[\s\S]|[^"\\])*")|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(true|false|null)|([{}\[\],:])|([\s\S])/y;
function scanJSON(text) {
  var out = [], i = 0, n = text.length;
  while (i < n) {
    JSON_RE.lastIndex = i;
    var m = JSON_RE.exec(text);
    if (!m) break;
    var s = i, e = i + m[0].length; i = e;
    if (m[2]) out.push([s, e, /^\s*:/.test(text.slice(e, e + 8)) ? 'prop' : 'str']);
    else if (m[3]) out.push([s, e, 'num']);
    else if (m[4]) out.push([s, e, 'kw']);
    else if (m[5]) out.push([s, e, 'punc']);
  }
  return out;
}

function scanMarkdown(text) {
  var out = [], pos = 0, ls = text.split('\n');
  for (var li = 0; li < ls.length; li++) {
    var line = ls[li], base = pos; pos += line.length + 1;
    var m;
    if ((m = /^#{1,6}\s.*$/.exec(line))) out.push([base, base + m[0].length, 'hdr']);
    else if ((m = /^\s*([-*+]|\d+\.)\s/.exec(line))) out.push([base, base + m[0].length, 'kw']);
    else if ((m = /^\s*>.*$/.exec(line))) out.push([base, base + m[0].length, 'com']);
    var re = /(`[^`]*`)|(\*\*[^*]+\*\*)|(\*[^*]+\*)|(\[[^\]]*\]\([^)]*\))/g, mm;
    while ((mm = re.exec(line))) out.push([base + mm.index, base + mm.index + mm[0].length, mm[4] ? 'link' : mm[1] ? 'str' : 'kw']);
  }
  return out;
}

function tokenize(text, lang) {
  try {
    if (lang === 'javascript' || lang === 'typescript') return scanJS(text);
    if (lang === 'html' || lang === 'xml') return scanHTML(text);
    if (lang === 'css') return scanCSS(text);
    if (lang === 'json') return scanJSON(text);
    if (lang === 'markdown') return scanMarkdown(text);
  } catch (e) { }
  return [];
}
function colorOf(t) {
  switch (t) {
    case 'kw': return TH.kw; case 'com': return TH.com; case 'str': return TH.str;
    case 'num': return TH.num; case 'fn': return TH.fn; case 'type': return TH.type;
    case 'op': return TH.op; case 'punc': return TH.punc; case 'tag': return TH.tag;
    case 'attr': return TH.attr; case 'prop': return TH.prop; case 'vari': return TH.vari;
    case 'builtin': return TH.builtin; case 'hdr': return TH.hdr; case 'link': return TH.link;
    case 'meta': return TH.meta;
    default: return TH.fg;
  }
}

/* -------------------------------------------------------------- document */
var doc = { text: '', language: 'plaintext', path: '' };
var LINES = [''], STARTS = [0], LINE_TOKENS = [], MAXCOLS = 0;
var cur = { line: 0, col: 0 };
var anchor = null;
var scrollX = 0, scrollY = 0;
var focused = false, blink = true, blinkTimer = null, drawQueued = false, didInit = false;
var search = { q: '', matches: [], index: -1 };
var undoStack = [], redoStack = [], lastEdit = null;
var tabSize = 2, showGutter = true, showIndent = true, showCurLine = true;
var seq = 0;

function splitStarts(ls) {
  var starts = new Array(ls.length), p = 0;
  for (var i = 0; i < ls.length; i++) { starts[i] = p; p += ls[i].length + 1; }
  return starts;
}
function recomputeTokens() {
  var TOK = tokenize(doc.text, doc.language);
  LINE_TOKENS = new Array(LINES.length);
  var ti = 0;
  for (var l = 0; l < LINES.length; l++) {
    var s = STARTS[l], e = s + LINES[l].length, arr = [];
    while (ti < TOK.length && TOK[ti][1] <= s) ti++;
    var k = ti;
    while (k < TOK.length && TOK[k][0] < e) {
      arr.push([Math.max(TOK[k][0], s) - s, Math.min(TOK[k][1], e) - s, TOK[k][2]]);
      k++;
    }
    LINE_TOKENS[l] = arr;
  }
}
function recomputeMetrics() {
  var max = 0;
  for (var i = 0; i < LINES.length; i++) if (LINES[i].length > max) max = LINES[i].length;
  MAXCOLS = max;
}
function setText(text) {
  LINES = String(text).split('\n');
  if (!LINES.length) LINES = [''];
  STARTS = splitStarts(LINES);
  doc.text = LINES.join('\n');
  recomputeTokens(); recomputeMetrics(); recomputeMatches(); updateGutter();
  cur = clampPos(cur); if (anchor) anchor = clampPos(anchor);
  contentClamp();
}
function commit(emit) {
  STARTS = splitStarts(LINES);
  doc.text = LINES.join('\n');
  recomputeTokens(); recomputeMetrics(); recomputeMatches(); updateGutter();
  contentClamp();
  if (emit !== false) { seq++; self.postMessage({ t: 'change', text: doc.text, seq: seq, language: doc.language, path: doc.path }); }
}
function line(i) { return LINES[i] == null ? '' : LINES[i]; }
function lineLen(i) { return line(i).length; }
function updateGutter() {
  var digits = String(LINES.length).length;
  gutterW = showGutter ? Math.round(digits * cw + gutterPad * 2) : 0;
}

/* ------------------------------------------------------------- utilities */
function clampPos(p) {
  var l = Math.max(0, Math.min(LINES.length - 1, p.line | 0));
  return { line: l, col: Math.max(0, Math.min(lineLen(l), p.col | 0)) };
}
function cmp(a, b) { return a.line === b.line ? a.col - b.col : a.line - b.line; }
function samePos(a, b) { return a && b && a.line === b.line && a.col === b.col; }
function selRange() {
  if (!anchor || samePos(anchor, cur)) return null;
  return cmp(anchor, cur) < 0 ? { a: anchor, b: cur } : { a: cur, b: anchor };
}
function offsetOf(p) { return STARTS[Math.max(0, Math.min(LINES.length - 1, p.line))] + p.col; }
function posOf(off) {
  off = Math.max(0, Math.min(doc.text.length, off));
  var lo = 0, hi = STARTS.length - 1;
  while (lo < hi) { var mid = (lo + hi + 1) >> 1; if (STARTS[mid] <= off) lo = mid; else hi = mid - 1; }
  return { line: lo, col: Math.min(off - STARTS[lo], LINES[lo].length) };
}
function wordRange(p) {
  var l = line(p.line), s = p.col, e = p.col;
  var isW = function (c) { return !!c && /[\w$]/.test(c); };
  if (!isW(l[s]) && isW(l[s - 1])) s--;
  while (s > 0 && isW(l[s - 1])) s--;
  while (e < l.length && isW(l[e])) e++;
  return { a: { line: p.line, col: s }, b: { line: p.line, col: e } };
}
function leadingWS(s) { var m = /^[ \t]*/.exec(s); return m ? m[0].length : 0; }
function clamp(a, b, v) { return Math.max(a, Math.min(b, v)); }

/* ------------------------------------------------------------ geometry */
function measure() {
  if (!ctx) return;
  ctx.font = fs + 'px ' + FONT_FAMILY;
  cw = Math.round(ctx.measureText('M').width * 100) / 100 || fs * 0.6;
  lh = Math.round(fs * 1.5);
  updateGutter();
}
function viewW() { return W - gutterW - 2; }
function contentW() { return MAXCOLS * cw; }
function contentH() { return LINES.length * lh + padTop * 2; }
function maxScrollY() { return Math.max(0, contentH() - H); }
function maxScrollX() { return Math.max(0, contentW() + padLeft * 2 - viewW()); }
function contentClamp() {
  scrollX = clamp(0, maxScrollX(), scrollX);
  scrollY = clamp(0, maxScrollY(), scrollY);
}
function screenX(col) { return gutterW + padLeft + col * cw - scrollX; }
function screenY(l) { return padTop + l * lh - scrollY; }
function colAtX(x) { return Math.max(0, Math.round((x - gutterW - padLeft + scrollX) / cw)); }
function lineAtY(y) { return Math.max(0, Math.floor((y - padTop + scrollY) / lh)); }
function firstVisible() { return Math.max(0, Math.floor((scrollY - padTop) / lh)); }
function lastVisible() { return Math.min(LINES.length - 1, Math.ceil((scrollY + H - padTop) / lh) + 1); }
function ensureVisible() {
  var y = cur.line * lh;
  if (y < scrollY) scrollY = Math.max(0, y - lh);
  else if (y + lh > scrollY + H - padTop) scrollY = Math.min(maxScrollY(), y - H + lh * 2);
  var x = cur.col * cw;
  if (x < scrollX) scrollX = Math.max(0, x - cw * 4);
  else if (x + cw > scrollX + viewW()) scrollX = Math.min(maxScrollX(), x - viewW() + cw * 4);
}

/* ------------------------------------------------------------- selection */
function selectAll() { anchor = { line: 0, col: 0 }; cur = { line: LINES.length - 1, col: lineLen(LINES.length - 1) }; }
function selectedText() {
  var r = selRange(); if (!r) return '';
  if (r.a.line === r.b.line) return line(r.a.line).slice(r.a.col, r.b.col);
  var out = [line(r.a.line).slice(r.a.col)];
  for (var l = r.a.line + 1; l < r.b.line; l++) out.push(LINES[l]);
  out.push(line(r.b.line).slice(0, r.b.col));
  return out.join('\n');
}
function replaceRange(a, b, text) {
  var head = line(a.line).slice(0, a.col);
  var tail = line(b.line).slice(b.col);
  var ins = String(text).split('\n');
  ins[0] = head + ins[0];
  ins[ins.length - 1] = ins[ins.length - 1] + tail;
  Array.prototype.splice.apply(LINES, [a.line, b.line - a.line + 1].concat(ins));
  cur = { line: a.line + ins.length - 1, col: ins[ins.length - 1].length - tail.length };
  anchor = null;
}

/* --------------------------------------------------------------- editing */
function snapshot() { return { lines: LINES.slice(), cur: { line: cur.line, col: cur.col }, anchor: anchor ? { line: anchor.line, col: anchor.col } : null }; }
function pushUndo(kind) {
  if (lastEdit && lastEdit.kind === kind && Date.now() - lastEdit.at < 500) { lastEdit.at = Date.now(); return; }
  undoStack.push(snapshot());
  if (undoStack.length > 400) undoStack.shift();
  redoStack.length = 0;
  lastEdit = { kind: kind, at: Date.now() };
}
function applySnapshot(s) {
  LINES = s.lines.slice();
  cur = { line: s.cur.line, col: s.cur.col };
  anchor = s.anchor ? { line: s.anchor.line, col: s.anchor.col } : null;
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}
function doUndo() { var s = undoStack.pop(); if (!s) return; redoStack.push(snapshot()); lastEdit = null; applySnapshot(s); }
function doRedo() { var s = redoStack.pop(); if (!s) return; undoStack.push(snapshot()); lastEdit = null; applySnapshot(s); }

function clearSelIfAny() { var r = selRange(); if (r) { pushUndo('del'); replaceRange(r.a, r.b, ''); } return !!r; }

function insertText(text) {
  text = String(text == null ? '' : text);
  if (!text) return;
  pushUndo('type');
  var r = selRange(); if (r) replaceRange(r.a, r.b, '');
  var parts = text.split('\n');
  if (parts.length === 1) {
    var l = line(cur.line);
    LINES[cur.line] = l.slice(0, cur.col) + text + l.slice(cur.col);
    cur.col += text.length;
  } else {
    var head = line(cur.line).slice(0, cur.col), tail = line(cur.line).slice(cur.col);
    var ins = parts.slice();
    ins[0] = head + ins[0];
    ins[ins.length - 1] = ins[ins.length - 1] + tail;
    Array.prototype.splice.apply(LINES, [cur.line, 1].concat(ins));
    cur = { line: cur.line + ins.length - 1, col: ins[ins.length - 1].length };
  }
  anchor = null;
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}

function newline() {
  pushUndo('enter');
  if (selRange()) replaceRange(selRange().a, selRange().b, '');
  var l = line(cur.line), before = l.slice(0, cur.col), after = l.slice(cur.col);
  var ind = /^[ \t]*/.exec(before)[0];
  var open = /[{([]\s*$/.test(before);
  var close = /^\s*[})\]]/.test(after);
  LINES[cur.line] = before;
  if (open && close) {
    var mid = ind + '  ', rest = ind + after.replace(/^\s+/, '');
    LINES.splice(cur.line + 1, 0, mid, rest);
    cur = { line: cur.line + 1, col: mid.length };
  } else {
    var pre = open ? ind + '  ' : ind;
    LINES.splice(cur.line + 1, 0, pre + after);
    cur = { line: cur.line + 1, col: pre.length };
  }
  anchor = null;
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}

function backspace() {
  if (selRange()) { clearSelIfAny(); commit(); ensureVisible(); postCursor(); scheduleDraw(); return; }
  if (cur.col === 0) {
    if (cur.line === 0) return;
    pushUndo('del');
    var prevLen = lineLen(cur.line - 1);
    LINES[cur.line - 1] += line(cur.line);
    LINES.splice(cur.line, 1);
    cur = { line: cur.line - 1, col: prevLen };
  } else {
    var l = line(cur.line), c = cur.col;
    var allWS = l.slice(0, c).trim() === '';
    var back = (allWS && /[ \t]/.test(l[c - 1])) ? Math.min(c, (c % tabSize) || tabSize) : 1;
    pushUndo('del');
    LINES[cur.line] = l.slice(0, c - back) + l.slice(c);
    cur.col = c - back;
  }
  anchor = null; commit(); ensureVisible(); postCursor(); scheduleDraw();
}
function del() {
  if (selRange()) { clearSelIfAny(); commit(); ensureVisible(); postCursor(); scheduleDraw(); return; }
  var l = line(cur.line);
  if (cur.col >= l.length) {
    if (cur.line >= LINES.length - 1) return;
    pushUndo('del');
    LINES[cur.line] = l + line(cur.line + 1);
    LINES.splice(cur.line + 1, 1);
  } else { pushUndo('del'); LINES[cur.line] = l.slice(0, cur.col) + l.slice(cur.col + 1); }
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}
function killTo(p) {
  var a = cmp(cur, p) < 0 ? cur : p, b = cmp(cur, p) < 0 ? p : cur;
  if (samePos(a, b)) { backspace(); return; }
  pushUndo('del'); replaceRange(a, b, '');
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}

function indentSelection(dir) {
  var r = selRange();
  pushUndo('indent');
  if (!r) {
    if (dir < 0) {
      var l = line(cur.line), rm = Math.min(leadingWS(l), tabSize);
      LINES[cur.line] = l.slice(rm); cur.col = Math.max(0, cur.col - rm);
    } else {
      var pad = ' '.repeat(tabSize - (cur.col % tabSize));
      LINES[cur.line] = line(cur.line).slice(0, cur.col) + pad + line(cur.line).slice(cur.col);
      cur.col += pad.length;
    }
  } else {
    for (var i = r.a.line; i <= r.b.line; i++) {
      if (dir > 0) LINES[i] = ' '.repeat(tabSize) + LINES[i];
      else LINES[i] = LINES[i].replace(new RegExp('^[ \\t]{1,' + tabSize + '}'), '');
    }
    anchor = { line: r.a.line, col: 0 };
    cur = { line: r.b.line, col: lineLen(r.b.line) };
  }
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}
function moveLine(dir) {
  var l = cur.line;
  if ((dir < 0 && l === 0) || (dir > 0 && l === LINES.length - 1)) return;
  pushUndo('move');
  var t = LINES[l]; LINES[l] = LINES[l + dir]; LINES[l + dir] = t;
  cur = { line: l + dir, col: cur.col };
  if (anchor) anchor = { line: anchor.line + dir, col: anchor.col };
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}
function duplicateLine() {
  pushUndo('dup');
  LINES.splice(cur.line, 0, LINES[cur.line]);
  cur = { line: cur.line + 1, col: cur.col };
  commit(); ensureVisible(); postCursor(); scheduleDraw();
}
function commentToggle() {
  var lang = doc.language;
  if (lang === 'json' || lang === 'markdown' || lang === 'plaintext') return;
  var tok = (lang === 'html' || lang === 'xml') ? ['<!--', '-->'] : (lang === 'css' ? ['/*', '*/'] : ['//', '']);
  var r = selRange() || { a: cur, b: cur };
  pushUndo('comment');
  var allCommented = true;
  for (var i = r.a.line; i <= r.b.line; i++) if (line(i).trim() && line(i).trim().indexOf(tok[0]) !== 0) allCommented = false;
  for (var j = r.a.line; j <= r.b.line; j++) {
    var s = LINES[j];
    if (!s.trim()) continue;
    var ind = leadingWS(s);
    if (allCommented) {
      var body = s.slice(ind + tok[0].length);
      if (tok[1] && body.slice(-tok[1].length) === tok[1]) body = body.slice(0, -tok[1].length);
      LINES[j] = s.slice(0, ind) + body.replace(/^ /, '');
    } else LINES[j] = s.slice(0, ind) + tok[0] + ' ' + s.slice(ind) + (tok[1] ? ' ' + tok[1] : '');
  }
  commit(); postCursor(); scheduleDraw();
}

/* ---------------------------------------------------------- cursor moves */
function setCur(p, extend) {
  var q = clampPos(p);
  if (extend) { if (!anchor) anchor = { line: cur.line, col: cur.col }; } else anchor = null;
  cur = q; ensureVisible(); postCursor(); scheduleDraw();
}
function moveH(delta, extend) {
  if (delta < 0) {
    if (cur.col > 0) setCur({ line: cur.line, col: cur.col - 1 }, extend);
    else if (cur.line > 0) setCur({ line: cur.line - 1, col: lineLen(cur.line - 1) }, extend);
  } else {
    if (cur.col < lineLen(cur.line)) setCur({ line: cur.line, col: cur.col + 1 }, extend);
    else if (cur.line < LINES.length - 1) setCur({ line: cur.line + 1, col: 0 }, extend);
  }
}
function moveV(delta, extend) { setCur({ line: cur.line + delta, col: cur.col }, extend); }
function moveWord(dir, extend) {
  var t = doc.text, o = offsetOf(cur), n = t.length;
  if (dir < 0) { while (o > 0 && /\s/.test(t[o - 1])) o--; while (o > 0 && /[\w$]/.test(t[o - 1])) o--; }
  else { while (o < n && /[\w$]/.test(t[o])) o++; while (o < n && /\s/.test(t[o])) o++; }
  setCur(posOf(o), extend);
}
function moveLineEdge(home, extend) {
  var l = line(cur.line), ind = leadingWS(l);
  setCur({ line: cur.line, col: home ? (cur.col === ind ? 0 : ind) : l.length }, extend);
}

/* -------------------------------------------------------------- search */
function recomputeMatches() {
  search.matches = [];
  if (!search.q) { search.index = -1; return; }
  var t = doc.text.toLowerCase(), q = search.q.toLowerCase();
  if (!q) return;
  var from = 0, at;
  while ((at = t.indexOf(q, from)) >= 0) { search.matches.push({ start: at, end: at + q.length }); from = at + Math.max(1, q.length); if (search.matches.length > 5000) break; }
  if (search.index >= search.matches.length) search.index = search.matches.length - 1;
  if (search.index < -1) search.index = -1;
}
function findNext(dir) {
  if (!search.matches.length) return;
  var n = search.matches.length;
  search.index = ((search.index + (dir < 0 ? -1 : 1)) % n + n) % n;
  var m = search.matches[search.index];
  anchor = posOf(m.start); cur = posOf(m.end);
  ensureVisible(); postCursor(); scheduleDraw();
}
function setSearch(q) { search.q = String(q == null ? '' : q); search.index = -1; recomputeMatches(); postCursor(); scheduleDraw(); }

/* ---------------------------------------------------------------- render */
function blit() {
  if (!cv) return;
  try { var bmp = cv.transferToImageBitmap(); self.postMessage({ t: 'frame', bmp: bmp, w: W, h: H }, [bmp]); } catch (e) { }
}
function roundRect(x, y, w, h, r) {
  r = Math.max(0, Math.min(r, w / 2, h / 2));
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}
function draw() {
  drawQueued = false;
  if (!ctx || !didInit) return;
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  ctx.fillStyle = TH.bg; ctx.fillRect(0, 0, W, H);
  ctx.textBaseline = 'middle';
  var f0 = firstVisible(), f1 = lastVisible();
  var sel = selRange();

  if (showCurLine && focused) { ctx.fillStyle = TH.curLine; ctx.fillRect(gutterW, screenY(cur.line), W - gutterW, lh); }

  function selFor(l) {
    if (!sel || l < sel.a.line || l > sel.b.line) return null;
    return [(l === sel.a.line) ? sel.a.col : 0, (l === sel.b.line) ? sel.b.col : lineLen(l)];
  }
  var sm = {};
  if (search.q && search.matches.length) {
    for (var q = 0; q < search.matches.length; q++) {
      var mm = search.matches[q], pa = posOf(mm.start), pb = posOf(mm.end);
      for (var l = pa.line; l <= pb.line; l++) {
        if (l < f0 || l > f1) continue;
        var s2 = (l === pa.line) ? pa.col : 0, e2 = (l === pb.line) ? pb.col : lineLen(l);
        (sm[l] = sm[l] || []).push([s2, e2, q === search.index]);
      }
    }
  }

  ctx.save();
  ctx.beginPath(); ctx.rect(gutterW, 0, W - gutterW, H); ctx.clip();
  if (showIndent) {
    ctx.fillStyle = TH.indent;
    for (var li = f0; li <= f1; li++) {
      var ind = leadingWS(LINES[li]);
      for (var c = tabSize; c < ind; c += tabSize) ctx.fillRect(screenX(c) + 0.5, screenY(li), 1, lh);
    }
  }
  for (var l2 = f0; l2 <= f1; l2++) {
    var y = screenY(l2), sr = selFor(l2);
    if (sr && sr[1] >= sr[0]) {
      ctx.fillStyle = focused ? TH.sel : TH.selInactive;
      var x1 = screenX(sr[0]), x2 = screenX(sr[1]);
      if (sr[0] === sr[1]) x2 = x1 + cw * 0.6;
      ctx.fillRect(x1, y, Math.max(cw * 0.6, x2 - x1), lh);
    }
    var hl = sm[l2];
    if (hl) for (var h = 0; h < hl.length; h++) {
      ctx.fillStyle = hl[h][2] ? TH.searchCur : TH.search;
      var hx = screenX(hl[h][0]), hw = Math.max(cw, screenX(hl[h][1]) - hx);
      ctx.fillRect(hx, y, hw, lh);
    }
  }
  ctx.font = fs + 'px ' + FONT_FAMILY;
  ctx.textAlign = 'left';
  for (var lt = f0; lt <= f1; lt++) {
    var yy = screenY(lt), raw = LINES[lt], tks = LINE_TOKENS[lt] || [], pos = 0;
    for (var ti = 0; ti <= tks.length; ti++) {
      var seg, type;
      if (ti < tks.length) { seg = raw.slice(pos, tks[ti][1]); type = tks[ti][2]; }
      else { seg = raw.slice(pos); type = 'fg'; }
      if (seg) { ctx.fillStyle = colorOf(type); ctx.fillText(seg, screenX(pos), yy + lh / 2); }
      if (ti < tks.length) pos = tks[ti][1];
    }
  }
  if (focused && blink) {
    ctx.fillStyle = TH.cursor;
    if (cur.col <= lineLen(cur.line)) ctx.fillRect(screenX(cur.col), screenY(cur.line) + 2, Math.max(1.5, cw * 0.14), lh - 4);
    else ctx.fillRect(screenX(cur.col), screenY(cur.line) + 2, cw * 0.55, lh - 4);
  }
  ctx.restore();

  if (showGutter) {
    ctx.fillStyle = TH.bg; ctx.fillRect(0, 0, gutterW, H);
    ctx.font = fs + 'px ' + FONT_FAMILY; ctx.textAlign = 'right';
    for (var lg = f0; lg <= f1; lg++) {
      ctx.fillStyle = lg === cur.line ? TH.gutterActive : TH.gutter;
      ctx.fillText(String(lg + 1), gutterW - gutterPad, screenY(lg) + lh / 2);
    }
    ctx.fillStyle = TH.curLine; ctx.fillRect(gutterW - 1, 0, 1, H);
    ctx.textAlign = 'left';
  }

  if (maxScrollY() > 0) {
    var th = Math.max(24, H * H / contentH()), ty = (H - th) * (scrollY / maxScrollY());
    ctx.fillStyle = TH.scroll; roundRect(W - 8, ty, 6, th, 3); ctx.fill();
  }
  if (maxScrollX() > 0) {
    var tw = Math.max(24, viewW() * viewW() / (contentW() + padLeft * 2)), tx = gutterW + (viewW() - tw) * (scrollX / maxScrollX());
    ctx.fillStyle = TH.scroll; roundRect(tx, H - 7, tw, 6, 3); ctx.fill();
  }
  blit();
}
function scheduleDraw() { if (drawQueued) return; drawQueued = true; queueMicrotask(draw); }

function postCursor() {
  var r = selRange();
  self.postMessage({
    t: 'cursor', line: cur.line, col: cur.col,
    sel: r ? { al: r.a.line, ac: r.a.col, bl: r.b.line, bc: r.b.col, chars: selectedText().length } : null,
    lines: LINES.length, chars: doc.text.length, scroll: { x: Math.round(scrollX), y: Math.round(scrollY) },
    cx: Math.round(screenX(cur.col)), cy: Math.round(screenY(cur.line)), cw: cw, lh: lh,
    matchTotal: search.matches.length, matchIndex: search.index
  });
}

/* --------------------------------------------------------------- keymap */
function handleKey(m) {
  var k = m.key, ctrl = !!(m.ctrl || m.meta), shift = !!m.shift, alt = !!m.alt;
  if (alt && k === 'ArrowUp') { moveLine(-1); return true; }
  if (alt && k === 'ArrowDown') { moveLine(1); return true; }
  switch (k) {
    case 'ArrowLeft': (ctrl || alt) ? moveWord(-1, shift) : moveH(-1, shift); return true;
    case 'ArrowRight': (ctrl || alt) ? moveWord(1, shift) : moveH(1, shift); return true;
    case 'ArrowUp': moveV(-1, shift); return true;
    case 'ArrowDown': moveV(1, shift); return true;
    case 'PageUp': moveV(-Math.max(1, Math.floor(H / lh) - 2), shift); return true;
    case 'PageDown': moveV(Math.max(1, Math.floor(H / lh) - 2), shift); return true;
    case 'Home': ctrl ? setCur({ line: 0, col: 0 }, shift) : moveLineEdge(true, shift); return true;
    case 'End': ctrl ? setCur({ line: LINES.length - 1, col: lineLen(LINES.length - 1) }, shift) : moveLineEdge(false, shift); return true;
    case 'Enter': newline(); return true;
    case 'Tab': indentSelection(shift ? -1 : 1); return true;
    case 'Backspace': ctrl ? killTo({ line: cur.line, col: 0 }) : backspace(); return true;
    case 'Delete': del(); return true;
    case 'Escape': anchor = null; setSearch(''); postCursor(); scheduleDraw(); return true;
    case 'a': case 'A': if (ctrl) { selectAll(); postCursor(); scheduleDraw(); return true; } return false;
    case 'z': case 'Z': if (ctrl) { shift ? doRedo() : doUndo(); return true; } return false;
    case 'y': case 'Y': if (ctrl) { doRedo(); return true; } return false;
    case 'd': case 'D': if (ctrl) { duplicateLine(); return true; } return false;
    case '/': if (ctrl) { commentToggle(); return true; } return false;
    default: return false;
  }
}

/* --------------------------------------------------------------- messages */
function applyOptions(o) {
  if (!o) return;
  if (o.tabSize != null) tabSize = Math.max(1, o.tabSize | 0);
  if (o.fontSize != null) fs = Math.max(9, Math.min(40, o.fontSize | 0));
  if (o.gutter != null) showGutter = !!o.gutter;
  if (o.indentGuides != null) showIndent = !!o.indentGuides;
  if (o.currentLine != null) showCurLine = !!o.currentLine;
}
function doCommand(name, args) {
  switch (name) {
    case 'undo': doUndo(); break;
    case 'redo': doRedo(); break;
    case 'selectAll': selectAll(); postCursor(); scheduleDraw(); break;
    case 'goToLine': setCur({ line: Math.max(0, ((args | 0) || 1) - 1), col: 0 }); break;
    case 'setSearch': setSearch(args); break;
    case 'findNext': findNext(1); break;
    case 'findPrev': findNext(-1); break;
    case 'copy': { var t = selectedText(); if (t) self.postMessage({ t: 'clipboard', kind: 'copy', text: t }); break; }
    case 'cut': {
      var t2 = selectedText();
      if (t2) { self.postMessage({ t: 'clipboard', kind: 'cut', text: t2 }); var r = selRange(); if (r) { pushUndo('del'); replaceRange(r.a, r.b, ''); commit(); postCursor(); scheduleDraw(); } }
      break;
    }
    case 'selectRange': if (args && args.a && args.b) { anchor = clampPos(args.a); cur = clampPos(args.b); ensureVisible(); postCursor(); scheduleDraw(); } break;
    case 'deleteSelection': { var r3 = selRange(); if (r3) { pushUndo('del'); replaceRange(r3.a, r3.b, ''); commit(); postCursor(); scheduleDraw(); } break; }
    case 'revealOffset': { anchor = null; cur = posOf(args | 0); ensureVisible(); postCursor(); scheduleDraw(); break; }
    case 'center': ensureVisible(); scheduleDraw(); break;
    case 'stats': postCursor(); break;
    case 'insert': insertText(args); break;
  }
}

var drag = null;
function handleMouse(m) {
  var x = m.x, y = m.y;
  if (m.kind === 'down') {
    focused = true; startBlink();
    var p = { line: clamp(0, LINES.length - 1, lineAtY(y)), col: 0 };
    p.col = Math.min(lineLen(p.line), colAtX(x));
    if (m.detail === 2) { var w = wordRange(p); anchor = w.a; cur = w.b; }
    else if (m.detail >= 3) { anchor = { line: p.line, col: 0 }; cur = { line: p.line, col: lineLen(p.line) }; }
    else if (m.shift) { if (!anchor) anchor = { line: cur.line, col: cur.col }; cur = p; }
    else { anchor = null; cur = p; }
    drag = true; postCursor(); scheduleDraw();
  } else if (m.kind === 'move') {
    if (!drag) return;
    var q = { line: clamp(0, LINES.length - 1, lineAtY(y)), col: 0 };
    q.col = Math.min(lineLen(q.line), colAtX(x));
    if (!anchor) anchor = { line: cur.line, col: cur.col };
    cur = q;
    if (y < padTop + 4) scrollY = Math.max(0, scrollY - lh);
    else if (y > H - 8) scrollY = Math.min(maxScrollY(), scrollY + lh);
    if (x > W - 8) scrollX = Math.min(maxScrollX(), scrollX + cw);
    else if (x < gutterW + 4 && scrollX > 0) scrollX = Math.max(0, scrollX - cw);
    postCursor(); scheduleDraw();
  } else if (m.kind === 'up') { drag = null; }
}

function startBlink() {
  blink = true;
  if (blinkTimer) return;
  blinkTimer = setInterval(function () { if (!focused) return; blink = !blink; scheduleDraw(); }, 530);
}
function stopBlink() { blink = false; if (blinkTimer) { clearInterval(blinkTimer); blinkTimer = null; } }

self.onmessage = function (e) {
  var m = e.data || {};
  switch (m.t) {
    case 'init':
      cv = m.canvas; ctx = cv.getContext('2d');
      if (m.theme && THEMES[m.theme]) TH = THEMES[m.theme];
      if (m.doc) { doc.language = m.doc.language || doc.language; doc.path = m.doc.path || ''; setText(m.doc.text || ''); }
      applyOptions(m.opts);
      measure();
      ensureVisible();
      didInit = true;
      postCursor(); scheduleDraw();
      self.postMessage({ t: 'ready' });
      break;
    case 'resize':
      DPR = m.dpr || 1; W = Math.max(1, m.w | 0); H = Math.max(1, m.h | 0);
      cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
      measure(); contentClamp(); postCursor(); scheduleDraw();
      break;
    case 'setDoc':
      if (m.language) doc.language = m.language;
      if (m.path != null) doc.path = m.path;
      if (String(m.text) !== doc.text) { setText(String(m.text)); postCursor(); }
      scheduleDraw();
      break;
    case 'setTheme': if (THEMES[m.theme]) TH = THEMES[m.theme]; scheduleDraw(); break;
    case 'setOptions': applyOptions(m.opts); if (ctx) measure(); contentClamp(); postCursor(); scheduleDraw(); break;
    case 'focus': focused = true; startBlink(); scheduleDraw(); break;
    case 'blur': focused = false; stopBlink(); scheduleDraw(); break;
    case 'insert': insertText(m.text); break;
    case 'key': handleKey(m); break;
    case 'mouse': handleMouse(m); break;
    case 'wheel':
      scrollY = clamp(0, maxScrollY(), scrollY + (m.dy || 0));
      scrollX = clamp(0, maxScrollX(), scrollX + (m.dx || 0));
      postCursor(); scheduleDraw();
      break;
    case 'cmd': doCommand(m.name, m.args); break;
  }
};
