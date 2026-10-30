// src/cron.js — autonomous cron-like tasks for Moltbook presence.
//
// READ tasks (feedscan, digest, patchwatch) observe + draft. WRITE tasks:
// - autofold: PATCH-folds MY living comments (free, no budget, capped 6/day)
// - autoreply: posts replies on MY threads (PAID: max 3/day, reserve-gated,
//   70s spacing, challenge solve+verify in one flow, ghosts auto-deleted)
// - autoupvote: upvotes follows' posts (free, max 5/day, default OFF)
// NOTHING posts to others' threads unasked; first contacts stay
// human/session-directed. The governor (src/governor.js) holds the budget
// floor; the solver (src/solver.js) never guesses. Inference via infer():
// CUDOS primary, Perchance fallback.
//
// Dependencies are injected: createScheduler({ getClient, log }) where
// getClient() returns a Moltbook instance or null, log(...) writes lines.
import { infer } from "./fallback-llm.js";
import { parseChallenge } from "./solver.js";
import { loadGov, canAutoCreate, recordCreate, note429, canFold, noteFold, claimRun } from "./governor.js";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Poll a getter until check(last) is true or timeout. Moltbook reads can
// lag writes (cache) — never trust the first re-GET after a write.
export async function pollFor(getter, check, { tries = 20, gapMs = 30000, label = "confirm" } = {}) {
  let last = null;
  for (let i = 0; i < tries; i++) {
    try { last = await getter(); } catch (e) { /* transient — keep polling */ }
    if (last != null) {
      try { if (await check(last)) return { ok: true, last }; } catch (e) {}
    }
    await sleep(gapMs);
  }
  return { ok: false, last };
}

// Machine-owned WIRE line: bump v, fresh timestamp (when the format has
// one), carry signals + append new ones. Protocol is code's job; only the
// fold LINES come from the AI draft.
export function nextWireLine(currentLine, newSignals) {
  const m = /WIRE v(\d+)/.exec(currentLine || "");
  const v = m ? parseInt(m[1], 10) + 1 : 2;
  let line = (currentLine || "WIRE v1").replace(/WIRE v\d+/, "WIRE v" + v);
  const now = new Date().toISOString().slice(0, 19) + "Z";
  if (/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(line)) {
    line = line.replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?Z?/, now);
  }
  // bump this doc's own counter (builds=N / pricing=N / hello=N)
  const docHit = /(builds|pricing|hello)=(\d+)/.exec(line);
  if (docHit) line = line.replace(docHit[0], docHit[1] + "=" + (parseInt(docHit[2], 10) + 1));
  const sig = (newSignals || []).filter(Boolean).join("; ");
  if (sig) line = line.replace(/\s*$/, "") + (line.includes("signals:") ? "; " + sig : " · signals: " + sig);
  return line;
}

// Flatten a comment tree (listings nest replies in `replies` arrays with
// `depth`). Flat-only reads MISS every nested reply — the NESTED-BLINDNESS
// lesson, 2026-10-18: verify receipts are truth, listings need the walk.
export function flattenComments(arr) {
  const out = [];
  (function walk(list) {
    for (const x of (list || [])) {
      out.push(x);
      walk(x.replies);
    }
  })(arr);
  return out;
}

// Living docs the agent may PATCH-fold on its own (free, own comments only).
const DOCS = [
  { doc: "builds", post: "ed1108be-aa8d-41e4-81b2-9d332452c60d", comment: "a683671a-d74a-462b-8d60-1252b6efc20f" },
  { doc: "pricing", post: "f8d3d005-b8f2-4417-8bc4-581cb9ffaa4c", comment: "659a44ed-49b6-45bf-a5ac-fd762e027886" },
];

const WALLET_RE = /0x[0-9a-fA-F]{8,}/;
const BANNED_PHRASES = ["guaranteed", "risk-free", "free ad space", "click here"];

// Extract a strict fold draft from LLM output: {doc?, lines[]}. The WIRE
// line is machine-owned (nextWireLine) — a draft-supplied wire is validated
// if present but never applied. Throws on anything malformed.
export function repairFoldDraft(text) {
  const s = String(text || "");
  const a = s.indexOf("{"), b = s.lastIndexOf("}");
  if (a < 0 || b <= a) throw new Error("no JSON object in draft");
  const d = JSON.parse(s.slice(a, b + 1));
  if (!d || !Array.isArray(d.lines) || d.lines.length > 3) throw new Error("bad fold lines");
  if (d.lines.some((l) => typeof l !== "string" || !l.trim() || l.length > 300)) throw new Error("bad fold lines");
  if (d.wire !== undefined && (typeof d.wire !== "string" || !d.wire.startsWith("WIRE v"))) throw new Error("bad wire line");
  if (d.doc !== undefined && !["builds", "pricing", "hello"].includes(d.doc)) throw new Error("bad doc routing");
  const blob = d.lines.join(" ");
  if (WALLET_RE.test(blob) || /http/i.test(blob)) throw new Error("draft contains links/wallets");
  return { doc: d.doc || null, lines: d.lines.map((l) => l.trim()) };
}

// Pre-post checklist for auto-replies. Returns {ok, reason}.
export function gateReply(text, recentTexts) {
  const t = String(text || "").trim();
  if (!t) return { ok: false, reason: "empty" };
  if (t.length > 700) return { ok: false, reason: "too long (" + t.length + ")" };
  if (WALLET_RE.test(t)) return { ok: false, reason: "wallet-like string" };
  const low = t.toLowerCase();
  const banned = BANNED_PHRASES.find((p) => low.includes(p));
  if (banned) return { ok: false, reason: "banned phrase: " + banned };
  const head = t.slice(0, 60);
  if ((recentTexts || []).some((r) => r && (r.includes(head) || t.includes(String(r).slice(0, 60))))) {
    return { ok: false, reason: "possible duplicate of recent reply" };
  }
  return { ok: true, reason: "passed" };
}

const REPLY_VOICE = "You are canceled-perchance-agent, a terse honest Moltbook agent living inside a Perchance generator. Reply in first person, plain text, max 600 chars. State only checkable facts. Never invent numbers, prices, or promises. Never promise dates. No wallet addresses, no links, no hype words. If unsure about a fact, say so in one clause.";

const LS_TASKS = "cron_tasks_v1";
const DAY = 86400000;

function loadState() {
  try { return JSON.parse(localStorage.getItem(LS_TASKS) || "{}"); } catch (e) { return {}; }
}
function saveState(s) { try { localStorage.setItem(LS_TASKS, JSON.stringify(s)); } catch (e) {} }

function taskState(s, id, today) {
  if (!s[id] || s[id].day !== today) s[id] = { enabled: undefined, lastRun: 0, countDay: 0, day: today };
  return s[id];
}

export const TASKS = [
  {
    id: "feedscan", label: "Feed scan — new posts from follows (log only)",
    intervalMs: 60 * 60 * 1000, kind: "read", defOn: true,
    async run(ctx) {
      const c = ctx.getClient(); if (!c) return "no Moltbook key";
      const r = await c.feed({ sort: "new", limit: 10, filter: "following" });
      const posts = (r.json && (r.json.posts || r.json.data)) || [];
      if (!posts.length) return "nothing new";
      const lines = posts.slice(0, 5).map((p) => "· " + ((p.author && p.author.name) || "?") + ": " + String(p.title || "").slice(0, 70));
      ctx.log("feed:\n" + lines.join("\n"));
      return posts.length + " post(s) scanned";
    },
  },
  {
    id: "digest", label: "Digest — summarize follow activity (AI, failover-ready)",
    intervalMs: 60 * 60 * 1000, kind: "read", defOn: true,
    async run(ctx) {
      const c = ctx.getClient(); if (!c) return "no Moltbook key";
      const { json } = await c.home();
      const act = json.activity_on_your_posts || [];
      if (!act.length) return "no activity to summarize";
      const brief = act.slice(0, 5).map((a) => "- " + String(a.post_title || a.post_id).slice(0, 80) + " (" + (a.new_notification_count || 0) + " new: " + ((a.latest_commenters || []).join(", ")) + ")").join("\n");
      const { text, backend } = await infer({
        system: "You are a terse Moltbook activity summarizer. Two sentences max, plain text.",
        prompt: "Summarize this agent's thread activity for a quick glance:\n" + brief,
        maxTokens: 150,
      });
      ctx.log("digest (" + backend + "): " + String(text).trim().slice(0, 400));
      return "summarized via " + backend;
    },
  },
  {
    id: "patchwatch", label: "Patchwatch — heartbeat + draft fold lines (AI CUDOS-first, drafts logged, never auto-posts)",
    intervalMs: 30 * 60 * 1000, kind: "read", defOn: true,
    async run(ctx, st) {
      const c = ctx.getClient(); if (!c) return "no Moltbook key";
      const { json: home } = await c.home();
      const acct = home.your_account || {};
      const notifs = await c.get("/notifications", { limit: 10 });
      const unread = (((notifs.json || {}).notifications) || []).filter((n) => !n.isRead);
      const act = home.activity_on_your_posts || [];
      const sig = JSON.stringify({
        u: acct.unread_notification_count,
        a: act.map((a) => (a.post_id || "") + ":" + (a.new_notification_count || 0)),
        n: unread.map((n) => n.id),
      });
      const status = "karma " + (acct.karma ?? "?") + ", unread " + (acct.unread_notification_count ?? unread.length) + ", active threads " + act.length;
      if (!unread.length && !act.length) {
        if (st.sig !== "quiet") st.sig = "quiet";
        return status + " — quiet, no draft needed";
      }
      if (st.sig === sig) return status + " — unchanged since last draft, quiet";
      const brief = unread.slice(0, 6).map((n) => "- " + (n.type || "?") + ": " + String(n.content || "").slice(0, 100)).join("\n")
        + "\n" + act.slice(0, 3).map((a) => "- thread: " + String(a.post_title || a.post_id || "").slice(0, 80) + " (" + (a.new_notification_count || 0) + " new)").join("\n");
      const { text, backend } = await infer({
        system: "You draft living-document fold LINES for a Moltbook agent (docs: builds, pricing, hello). Respond with ONLY a JSON object: {\"doc\": \"<one of builds|pricing|hello>\", \"lines\": [\"1-3 new fold lines, terse, plain text, no links\"]}. Max 300 chars per line. Never invent numbers or promises. Never write a WIRE line — protocol is machine-owned.",
        prompt: "Draft the fold for this state:\n" + brief,
        maxTokens: 400,
      });
      let draft;
      try { draft = repairFoldDraft(text); }
      catch (e) {
        ctx.log("patchwatch draft rejected (" + backend + ", " + e.message + "):\n" + String(text).slice(0, 400));
        return status + " — draft failed validation, held";
      }
      ctx.log("patchwatch draft (" + backend + ") [" + (draft.doc || "?") + "]:\n" + draft.lines.map((l) => "- " + l).join("\n"));
      st.sig = sig;
      return status + " — draft logged via " + backend;
    },
  },
  {
    id: "autofold", label: "Autofold — apply structured folds to MY living comments via free PATCH (max 6/day) [AUTO-WRITES]",
    intervalMs: 60 * 60 * 1000, kind: "write", defOn: true,
    async run(ctx, st) {
      const c = ctx.getClient(); if (!c) return "no Moltbook key";
      const gov = loadGov();
      if (!claimRun(gov, "autofold")) return "another tab ran recently";
      const { json: home } = await c.home();
      const me = ((home || {}).your_account || {}).name || "canceled-perchance-agent";
      const act = (home || {}).activity_on_your_posts || [];
      const sig = JSON.stringify({
        u: ((home || {}).your_account || {}).unread_notification_count,
        a: act.map((a) => (a.post_id || "") + ":" + (a.new_notification_count || 0)),
      });
      if (st.sig && st.sig === sig) return "no changes since last check";
      st.sig = sig;
      const gf = canFold(gov);
      if (!gf.ok) return "changes seen, held for session (" + gf.reason + ")";
      const pricingId = DOCS[1].post;
      const pick = act.some((a) => (a.post_id || "") === pricingId) ? DOCS[1] : DOCS[0];
      const getDoc = async () => {
        const rc = await c.comments(pick.post, { sort: "new", limit: 35 });
        return ((rc.json || {}).comments || []).find((x) => x.id === pick.comment);
      };
      await sleep(1500);
      const mine = await getDoc();
      if (!mine) return "living doc not found — held for session";
      const author = (mine.author && mine.author.name) || mine.author_name;
      if (author !== me) return "living doc author mismatch — refusing to touch";
      const current = mine.content || mine.body || "";
      const brief = act.slice(0, 4).map((a) => "- " + String(a.post_title || a.post_id || "").slice(0, 90) + " (" + (a.new_notification_count || 0) + " new)").join("\n") || "(routine heartbeat — bump versions, note the watch)";
      const { text, backend } = await infer({
        system: "You maintain a Moltbook agent's living document. Respond with ONLY a JSON object: {\"lines\": [\"1-3 new fold lines, terse, plain text, no links, no WIRE line\"]}. Max 300 chars per line. Never invent numbers or promises.",
        prompt: "Current doc (new lines go above its WIRE footer):\n" + current.slice(-3500) + "\n\nNew activity:\n" + brief,
        maxTokens: 400,
      });
      let draft;
      try { draft = repairFoldDraft(text); }
      catch (e) {
        ctx.log("autofold draft rejected (" + backend + ", " + e.message + ") — held for session:\n" + String(text).slice(0, 500));
        return "draft failed validation — held for session";
      }
      // freshness: re-GET; abort if the doc moved under us
      await sleep(2000);
      const fresh = await getDoc();
      const freshBody = (fresh && (fresh.content || fresh.body)) || "";
      if (freshBody !== current) return "doc changed during drafting — held for session";
      const lines = current.split("\n");
      const wix = lines.findIndex((l) => l.startsWith("WIRE v"));
      if (wix < 0) return "no WIRE footer found — held for session";
      const wire = nextWireLine(lines[wix], ["autonomous-era"]);
      lines[wix] = wire;
      const newLines = draft.lines.filter((l) => !current.includes(l.slice(0, 40)));
      lines.splice(wix, 0, ...newLines);
      await sleep(2000);
      const pr = await c.patch(`/comments/${pick.comment}`, { content: lines.join("\n") });
      if (!pr.ok || (pr.json && pr.json.success === false)) {
        return "PATCH refused (" + pr.status + ") — held for session";
      }
      const conf = await pollFor(getDoc,
        (d) => d && ((d.content || d.body) || "").includes(wire),
        { tries: 20, gapMs: 30000 });
      if (!conf.ok) return "PATCH sent but unconfirmed after 10 min — held for human check (DO NOT re-PATCH blindly)";
      noteFold(loadGov(), pick.doc);
      return `folded ${pick.doc} via ${backend} (${newLines.length} line(s), confirmed)`;
    },
  },
  {
    id: "autoreply", label: "Auto-reply — answers on MY threads: draft(CUDOS) → gate → create+solve+verify (max 3/day, reserve-gated) [AUTO-POSTS]",
    intervalMs: 60 * 60 * 1000, kind: "write", defOn: true,
    async run(ctx) {
      const c = ctx.getClient(); if (!c) return "no Moltbook key";
      const gov = loadGov();
      if (!claimRun(gov, "autoreply")) return "another tab ran recently";
      const gate0 = canAutoCreate(gov);
      if (!gate0.ok && !gate0.probe) return "governor hold: " + gate0.reason;
      const { json: home } = await c.home();
      const me = ((home || {}).your_account || {}).name || "canceled-perchance-agent";
      await sleep(1200);
      const prof = await c.profileOf(me);
      const pj = prof.json || {};
      const myIds = new Set(((pj.recentPosts || pj.posts) || []).map((p) => p.id || p.post_id).filter(Boolean));
      const recentTexts = ((pj.recentComments || []) || []).map((x) => x.content || x.body || "").slice(0, 5);
      await sleep(1200);
      const nr = await c.get("/notifications", { limit: 15 });
      const cands = (((nr.json || {}).notifications) || []).filter((n) =>
        !n.isRead && ["comment_reply", "mention", "post_comment"].includes(n.type) && myIds.has(n.relatedPostId));
      if (!cands.length) return "nothing addressed to me (" + gate0.reason + ")";
      cands.sort((a, b) => String(a.createdAt || "") < String(b.createdAt || "") ? -1 : 1);
      let examined = 0;
      for (const n of cands.slice(0, 3)) {
        examined++;
        await sleep(1500);
        const th = await c.comments(n.relatedPostId, { sort: "new", limit: 50 });
        const thread = flattenComments((th.json || {}).comments);
        const tgt = thread.find((x) => x.id === n.relatedCommentId);
        if (!tgt || (tgt.content || tgt.body || "") === "Deleted comment") { ctx.log("autoreply: target gone, skipping"); continue; }
        const tAuthor = (tgt.author && tgt.author.name) || tgt.author_name;
        if (tAuthor === me) continue;
        const tBody = tgt.content || tgt.body || "";
        const tParent = tgt.parent_id || tgt.parentId || "";
        const myInThread = thread.filter((x) => ((x.author && x.author.name) || x.author_name) === me);
        const addressed = tBody.toLowerCase().includes(me.toLowerCase()) || myInThread.some((m) => m.id === tParent);
        if (!addressed) { ctx.log("autoreply: not addressed to me, skipping"); continue; }
        const tTs = tgt.createdAt || tgt.created_at || "";
        const answered = tTs && myInThread.some((m) => (m.createdAt || m.created_at || "") > tTs);
        if (answered) { ctx.log("autoreply: already answered, skipping"); continue; }
        if (!tTs) { ctx.log("autoreply: no timestamps — held for session"); continue; }
        const lastR = (loadGov().lastReply || {})[n.relatedPostId] || 0;
        if (Date.now() - lastR < 2 * 3600 * 1000) { ctx.log("autoreply: thread cooling down, skipping"); continue; }
        // draft + gate
        const { text: draft, backend } = await infer({
          system: REPLY_VOICE,
          prompt: "Someone wrote this to you on your thread:\n\"" + tBody.slice(0, 500) + "\"\nWrite the reply (max 600 chars, plain text, no greeting fluff):",
          maxTokens: 400,
        });
        const g = gateReply(draft, recentTexts);
        if (!g.ok) { ctx.log("autoreply: gate rejected (" + g.reason + ") — held for session:\n" + String(draft).slice(0, 400)); continue; }
        // create + solve + verify in ONE flow
        const cr = await c.createComment(n.relatedPostId, { content: draft.trim(), parent_id: tgt.id });
        const cj = cr.json || {};
        if (cr.status === 429 || cj.statusCode === 429) { note429(loadGov(), cj); return "rate-limited — backing off"; }
        const cm = cj.comment || cj.data || {};
        const cid = cm.id || cj.id || null;
        const v = cm.verification || cj.verification;
        const need = cj.verification_required || !!v;
        if (!need) {
          if (cr.ok && cid) {
            recordCreate(loadGov(), cj, cid, "autoreply");
            const gg = loadGov(); gg.lastReply = { ...(gg.lastReply || {}), [n.relatedPostId]: Date.now() };
            const { saveGov } = await import("./governor.js");
            saveGov(gg);
            return `replied (no challenge) via ${backend}`;
          }
          ctx.log("autoreply: create failed (" + cr.status + ") — held for session");
          return "create failed — held for session";
        }
        if (!v || !v.verification_code) {
          ctx.log("autoreply: challenge shape unknown — held for session");
          if (cid) { try { await c.del(`/comments/${cid}`); } catch (e) {} }
          return "challenge shape unknown — held for session";
        }
        ctx.log("autoreply: challenge code " + v.verification_code);
        const s = parseChallenge(v.challenge_text || "");
        if (s.unsure) {
          if (cid) { try { await c.del(`/comments/${cid}`); } catch (e) {} }
          ctx.log("autoreply: solver unsure (" + s.reason + ") — ghosted, queued for session");
          continue;
        }
        await sleep(2000);
        const vr = await c.verify(v.verification_code, s.answer);
        if (vr.json && vr.json.success) {
          recordCreate(loadGov(), { ...(vr.json || {}), ...(cj || {}) }, cid, "autoreply");
          const gg = loadGov(); gg.lastReply = { ...(gg.lastReply || {}), [n.relatedPostId]: Date.now() };
          const { saveGov } = await import("./governor.js");
          saveGov(gg);
          // visibility is bonus (reads lag writes): confirm, never block the receipt on it
          const vis = await pollFor(async () => {
            const t2 = await c.comments(n.relatedPostId, { sort: "new", limit: 35 });
            return ((t2.json || {}).comments || []).some((x) => x.id === cid);
          }, (found) => found === true, { tries: 10, gapMs: 20000 });
          return `replied + verified (${s.answer}) via ${backend}${vis.ok ? ", visible" : " (visibility unconfirmed — cache lag?)"}`;
        }
        if (cid) { try { await c.del(`/comments/${cid}`); } catch (e) {} }
        ctx.log("autoreply: verify failed — ghosted if possible, queued for session");
        return "verify failed — held for session";
      }
      return `examined ${examined}, none qualified — held for session`;
    },
  },
  {
    id: "autoupvote", label: "Auto-upvote follows' new posts (max 5/day)",
    intervalMs: 60 * 60 * 1000, kind: "write", defOn: false, cap: 5,
    async run(ctx, st) {
      const c = ctx.getClient(); if (!c) return "no Moltbook key";
      const r = await c.feed({ sort: "new", limit: 10, filter: "following" });
      const posts = (r.json && (r.json.posts || r.json.data)) || [];
      let n = 0;
      for (const p of posts) {
        if (st.countDay >= 5) break;
        if (!p.id) continue;
        try { await c.upvotePost(p.id); st.countDay++; n++; }
        catch (e) { /* already voted etc. — skip */ }
      }
      return n ? n + " upvote(s)" : "nothing new to upvote";
    },
  },
];

export function createScheduler({ getClient, log, onCheck }) {
  const ctx = { getClient, log, onCheck };
  function today() { return new Date().toISOString().slice(0, 10); }
  function isOn(t) {
    const s = loadState();
    const st = taskState(s, t.id, today());
    const en = st.enabled === undefined ? t.defOn : st.enabled;
    saveState(s);
    return en;
  }
  function setOn(id, on) {
    const s = loadState();
    taskState(s, id, today()).enabled = !!on;
    saveState(s);
  }
  function lastRun(id) {
    const s = loadState();
    return (s[id] && s[id].lastRun) || 0;
  }
  async function runTask(t, force) {
    const s = loadState();
    const st = taskState(s, t.id, today());
    const en = st.enabled === undefined ? t.defOn : st.enabled;
    if (!en && !force) return null;
    if (!force && Date.now() - st.lastRun < t.intervalMs) return null;
    st.lastRun = Date.now(); saveState(s);
    try {
      const r = await t.run(ctx, st);
      saveState(s);
      log("cron [" + t.id + "] " + r);
      return r;
    } catch (e) {
      log("cron [" + t.id + "] error: " + String((e && e.message) || e).slice(0, 200));
      return null;
    }
  }
  async function tick() {
    for (const t of TASKS) {
      try { await runTask(t, false); } catch (e) {}
    }
  }
  return { TASKS, isOn, setOn, lastRun, runTask, tick };
}
