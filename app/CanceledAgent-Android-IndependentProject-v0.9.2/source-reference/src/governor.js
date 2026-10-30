// src/governor.js — reserve-credit governor for autonomous posting.
//
// The scarcest resource (comment creates) is guarded here. Bodies of POST
// responses carry `daily_remaining`; 429s carry `reset_at`. Everything is
// persisted in localStorage so all tabs/sessions share one balance.
// Pure helpers take state so they can be unit-tested; load/save wrap them.

export const LS_GOV = "gov_state_v1";
export const RESERVE = 2; // never auto-create at or below this
export const SPACING_MS = 70000; // ≥70s between creates
export const AUTO_CAP = 3; // max auto-replies per UTC day
export const FOLD_CAP = 6; // max living-doc PATCH folds per UTC day

export function todayUTC() {
  return new Date().toISOString().slice(0, 10);
}

export function defaultState() {
  return {
    remaining: 0, // seeded exhausted; self-heals via probe + responses
    probeAfter: "2026-10-18T03:00:00Z", // single probe create allowed after this when work is queued
    resetAt: null,
    lastCreateAt: 0,
    spentToday: [],
    autoDay: "", autoCount: 0,
    foldDay: "", foldCount: 0,
    lastReply: {}, // postId -> ts
    lastFoldSig: "",
    lock: {}, // taskId -> ts (cross-tab run lock)
  };
}

export function loadGov() {
  try {
    const s = { ...defaultState(), ...(JSON.parse(localStorage.getItem(LS_GOV) || "{}")) };
    if (s.autoDay !== todayUTC()) { s.autoDay = todayUTC(); s.autoCount = 0; }
    if (s.foldDay !== todayUTC()) { s.foldDay = todayUTC(); s.foldCount = 0; }
    return s;
  } catch (e) { return defaultState(); }
}
export function saveGov(s) {
  try {
    s.spentToday = (s.spentToday || []).slice(-60);
    localStorage.setItem(LS_GOV, JSON.stringify(s));
  } catch (e) {}
}

// Can we spend one auto-create right now? Returns {ok, reason, probe}.
export function canAutoCreate(s, now = Date.now()) {
  if (s.autoCount >= AUTO_CAP) return { ok: false, reason: `auto cap ${AUTO_CAP}/day reached` };
  if (now - (s.lastCreateAt || 0) < SPACING_MS) {
    return { ok: false, reason: `spacing (${Math.ceil((SPACING_MS - (now - s.lastCreateAt)) / 1000)}s left)` };
  }
  if (s.remaining == null || s.remaining <= RESERVE) {
    if (new Date().toISOString() > (s.probeAfter || "")) return { ok: true, reason: "probe (uncalibrated)", probe: true };
    return { ok: false, reason: `reserve floor (remaining=${s.remaining ?? "?"})` };
  }
  return { ok: true, reason: `remaining=${s.remaining}` };
}

function pickRemaining(j) {
  if (!j || typeof j !== "object") return null;
  const cands = [j.daily_remaining, j.dailyRemaining, j.remaining,
    j.comment && j.comment.daily_remaining, j.data && j.data.daily_remaining];
  for (const c of cands) {
    const n = Number(c);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

// Record a create response. Returns nothing; mutates s.
export function recordCreate(s, json, id, why, now = Date.now()) {
  const r = pickRemaining(json);
  if (r != null) s.remaining = r;
  s.lastCreateAt = now;
  s.autoCount = (s.autoCount || 0) + 1;
  s.spentToday = [...(s.spentToday || []), { ts: new Date(now).toISOString(), id: id || null, why }];
  if (s.probeAfter && r != null && r <= RESERVE) {
    // probe showed we're still walled: don't probe again until reset
    s.probeAfter = s.resetAt || new Date(now + 6 * 3600 * 1000).toISOString();
  }
  saveGov(s);
}

export function note429(s, json, now = Date.now()) {
  s.remaining = 0;
  const ra = (json && (json.reset_at || json.resetAt || json.reset)) || null;
  s.resetAt = ra || new Date(now + 3600 * 1000).toISOString();
  s.probeAfter = s.resetAt;
  saveGov(s);
}

export function canFold(s) {
  if ((s.foldCount || 0) >= FOLD_CAP) return { ok: false, reason: `fold cap ${FOLD_CAP}/day reached` };
  return { ok: true, reason: `folds ${s.foldCount || 0}/${FOLD_CAP} today` };
}
export function noteFold(s, doc) {
  s.foldCount = (s.foldCount || 0) + 1;
  s.lastFoldSig = new Date().toISOString() + " " + doc;
  saveGov(s);
}

// Cross-tab run lock: only one tab runs a task per ttl.
export function claimRun(s, taskId, ttlMs = 10 * 60 * 1000, now = Date.now()) {
  const last = (s.lock && s.lock[taskId]) || 0;
  if (now - last < ttlMs) return false;
  s.lock = { ...(s.lock || {}), [taskId]: now };
  saveGov(s);
  return true;
}

export function govStatus() {
  const s = loadGov();
  const rem = s.remaining == null ? "?" : s.remaining;
  return `budget remaining=${rem} · auto ${s.autoCount || 0}/${AUTO_CAP} today · folds ${s.foldCount || 0}/${FOLD_CAP} today`;
}

export function runGovernorTests() {
  const out = [];
  const s = { ...defaultState(), remaining: 5, lastCreateAt: 0, autoDay: todayUTC(), autoCount: 0 };
  out.push({ name: "allow when funded", pass: canAutoCreate(s, 1e12).ok === true });
  const s2 = { ...s, remaining: 1, probeAfter: "2030-01-01T00:00:00Z" };
  const r2 = canAutoCreate(s2, 1e12);
  out.push({ name: "hold at reserve", pass: r2.ok === false });
  const s3 = { ...s, remaining: 0, probeAfter: "2000-01-01T00:00:00Z" };
  out.push({ name: "probe when past probeAfter", pass: canAutoCreate(s3, 1e12).probe === true });
  const s4 = { ...s, lastCreateAt: 1e12 - 10000 };
  out.push({ name: "spacing hold", pass: canAutoCreate(s4, 1e12).ok === false });
  const s5 = { ...s };
  recordCreate(s5, { daily_remaining: 4 }, "x", "test", 1e12);
  out.push({ name: "record calibrates", pass: s5.remaining === 4 && s5.autoCount === 1 });
  const s6 = { ...s, remaining: 5 };
  note429(s6, { reset_at: "2030-01-01T00:00:00Z" }, 1e12);
  out.push({ name: "429 walls + sets probe", pass: s6.remaining === 0 && s6.probeAfter === "2030-01-01T00:00:00Z" });
  return out;
}

export default { loadGov, saveGov, canAutoCreate, recordCreate, note429, canFold, noteFold, claimRun, govStatus, runGovernorTests };
