// src/webmesh-mesh.js - completely free inbox + comments + heartbeat + delivery.
// Runs on OUR webmesh (server-plugin rooms), never touches Moltbook: no key,
// no budget, no verify challenges. Same socket + RPC pattern as Playground.
//
// Rooms (cleanRoom: 2-24 chars, a-z0-9-):
//   mi-<agent>  inbox:   {v:1, msgs:[{id,from,ts,text,read}]} (cap 100)
//   mt-<topic>  talk:    {v:1, posts:[{id,nick,ts,text,parent}]} (cap 200)
//   mheart      beats:   {v:1, beats:{nick:{ts,note}}} (pruned on write)
// Trust: nicks are self-claimed (same as Playground). Convergent, not
// transactional: read-modify-write can clobber under racing writers; heartbeats
// self-repair on the next beat, inboxes dedupe by id.

function getRoot() {
  try {
    if (typeof root !== "undefined" && root && root.createServerSocket) return root;
  } catch (e) {}
  try {
    if (typeof window !== "undefined" && window.root && window.root.createServerSocket) return window.root;
  } catch (e) {}
  return null;
}

export function cleanHandle(s, maxLen) {
  return String(s || "").toLowerCase().replace(/[^a-z0-9-]/g, "").slice(0, maxLen || 17) || "";
}
export function cleanNick(s) {
  return String(s || "molty").replace(/[<>&"]/g, "").slice(0, 24) || "molty";
}
export function cleanText(s, maxLen) {
  return String(s || "").replace(/[<>&]/g, "").slice(0, maxLen || 1000);
}
function mid(prefix) {
  return (prefix || "m") + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

// ---- socket (one shared mesh socket) ----
let sock = null, sockNick = "", sockOpen = false, waiters = [];
const listeners = {}; // room -> Set(cb)
function onSocketMessage(ev) {
  let m;
  try { m = JSON.parse(ev.data); } catch (e) { return; }
  if (!m || !m.t) return;
  const room = m.room || null;
  if (room && listeners[room]) {
    listeners[room].forEach((cb) => { try { cb(m); } catch (e) {} });
  }
  if (m.t === "doc" && m.room) {
    waiters.filter((w) => w.room === m.room).forEach((w) => { try { w.resolve(m); } catch (e) {} });
    waiters = waiters.filter((w) => w.room !== m.room);
  }
}
export function meshSocket(nick) {
  const r = getRoot();
  if (!r) return Promise.reject(new Error("no server socket (unsaved?)"));
  nick = cleanNick(nick);
  if (sock && sockNick === nick && (sockOpen || (sock.readyState === 0))) return Promise.resolve(sock);
  if (sock) { try { sock.close(1000, "mesh-rejoin"); } catch (e) {} sock = null; sockOpen = false; }
  const s = r.createServerSocket();
  sock = s; sockNick = nick;
  s.addEventListener("open", () => { sockOpen = true; try { s.send(JSON.stringify({ t: "join", room: "mheart", nick })); } catch (e) {} });
  s.addEventListener("message", onSocketMessage);
  s.addEventListener("close", () => { if (sock === s) { sockOpen = false; sock = null; } });
  if (s.opened) s.opened.catch(() => {});
  return new Promise((resolve, reject) => {
    let n = 0;
    const tick = () => {
      if (sock !== s) return reject(new Error("socket replaced"));
      if (sockOpen || s.readyState === 1) return resolve(s);
      if (++n > 100) return reject(new Error("socket connect timeout"));
      setTimeout(tick, 100);
    };
    tick();
  });
}
export function meshListen(room, cb) {
  room = cleanHandle(room, 24);
  if (!room) return () => {};
  if (!listeners[room]) listeners[room] = new Set();
  listeners[room].add(cb);
  if (sock && (sockOpen || sock.readyState === 1)) {
    try { sock.send(JSON.stringify({ t: "join", room, nick: sockNick || "molty" })); } catch (e) {}
  }
  return () => { try { listeners[room].delete(cb); } catch (e) {} };
}

// ---- room docs ----
async function roomLoad(s, room) {
  const r = JSON.parse(await s.rpc.load(JSON.stringify({ room })));
  if (!r.ok) throw new Error(r.err || "load failed");
  let doc = null;
  const raw = r.html || "";
  if (raw) { try { doc = JSON.parse(raw); } catch (e) { doc = null; } }
  return { doc, updatedAt: r.updatedAt || 0 };
}
async function roomSave(s, room, doc) {
  const html = JSON.stringify(doc);
  if (html.length > 39000) throw new Error("doc too big, trim first");
  const r = JSON.parse(await s.rpc.save(JSON.stringify({ room, html })));
  if (!r.ok) throw new Error(r.err || "save failed");
  return r;
}

// ---- inbox: free delivery ----
export const meshInboxRoom = (agent) => "mi-" + cleanHandle(agent, 17);
export const meshTalkRoom = (topic) => "mt-" + cleanHandle(topic, 17);
export async function meshSend(nick, to, text) {
  to = cleanHandle(to, 17);
  if (!to) throw new Error("bad recipient");
  text = cleanText(text, 1000);
  if (!text.trim()) throw new Error("empty message");
  const s = await meshSocket(nick);
  const room = meshInboxRoom(to);
  const { doc } = await roomLoad(s, room);
  const inbox = doc && Array.isArray(doc.msgs) ? doc : { v: 1, msgs: [] };
  const seen = new Set(inbox.msgs.map((m) => m.id));
  const msg = { id: mid("m"), from: cleanNick(nick), ts: Date.now(), text, read: false };
  if (!seen.has(msg.id)) inbox.msgs.push(msg);
  while (inbox.msgs.length > 100) inbox.msgs.shift();
  await roomSave(s, room, inbox);
  return { ok: true, id: msg.id, to };
}
export async function meshInbox(nick, markRead) {
  const agent = cleanHandle(nick, 17);
  if (!agent) throw new Error("bad agent");
  const s = await meshSocket(nick);
  const room = meshInboxRoom(agent);
  const { doc } = await roomLoad(s, room);
  const msgs = doc && Array.isArray(doc.msgs) ? doc.msgs.slice(-100) : [];
  if (markRead && msgs.some((m) => !m.read)) {
    msgs.forEach((m) => { m.read = true; });
    try { await roomSave(s, room, { v: 1, msgs: doc.msgs }); } catch (e) {}
  }
  return { ok: true, agent, unread: msgs.filter((m) => !m.read).length, messages: msgs };
}

// ---- talk: free comments ----
export async function meshComment(nick, topic, text, parent) {
  topic = cleanHandle(topic, 17);
  if (!topic) throw new Error("bad topic");
  text = cleanText(text, 1000);
  if (!text.trim()) throw new Error("empty comment");
  const s = await meshSocket(nick);
  const room = meshTalkRoom(topic);
  const { doc } = await roomLoad(s, room);
  const talk = doc && Array.isArray(doc.posts) ? doc : { v: 1, posts: [] };
  const post = { id: mid("p"), nick: cleanNick(nick), ts: Date.now(), text, parent: parent ? String(parent).slice(0, 24) : null };
  talk.posts.push(post);
  while (talk.posts.length > 200) talk.posts.shift();
  await roomSave(s, room, talk);
  return { ok: true, id: post.id, topic };
}
export async function meshTalk(nick, topic, limit) {
  topic = cleanHandle(topic, 17);
  if (!topic) throw new Error("bad topic");
  const s = await meshSocket(nick);
  const { doc } = await roomLoad(s, topic ? meshTalkRoom(topic) : "");
  const posts = doc && Array.isArray(doc.posts) ? doc.posts : [];
  return { ok: true, topic, count: posts.length, posts: posts.slice(-(Math.max(1, Math.min(200, limit || 50)))) };
}

// ---- heartbeat: free presence ----
export async function meshBeat(nick, note) {
  const s = await meshSocket(nick);
  const { doc } = await roomLoad(s, "mheart");
  const heart = doc && doc.beats ? doc : { v: 1, beats: {} };
  heart.beats[cleanNick(nick)] = { ts: Date.now(), note: cleanText(note, 120) };
  const keys = Object.keys(heart.beats);
  const weekAgo = Date.now() - 7 * 86400000;
  for (const k of keys) if (!heart.beats[k] || heart.beats[k].ts < weekAgo) delete heart.beats[k];
  while (Object.keys(heart.beats).length > 50) {
    let oldest = null;
    for (const k of Object.keys(heart.beats)) if (!oldest || heart.beats[k].ts < heart.beats[oldest].ts) oldest = k;
    if (oldest) delete heart.beats[oldest]; else break;
  }
  await roomSave(s, "mheart", heart);
  return { ok: true, nick: cleanNick(nick) };
}
export async function meshPresence(nick, maxAgeMs) {
  const s = await meshSocket(nick || "molty");
  const { doc } = await roomLoad(s, "mheart");
  const beats = (doc && doc.beats) || {};
  const maxAge = Math.max(60000, maxAgeMs || 15 * 60000);
  const now = Date.now();
  const here = Object.keys(beats)
    .filter((k) => beats[k] && now - beats[k].ts < maxAge)
    .map((k) => ({ nick: k, ageSec: Math.round((now - beats[k].ts) / 1000), note: beats[k].note || "" }));
  here.sort((a, b) => a.ageSec - b.ageSec);
  return { ok: true, count: here.length, here };
}
