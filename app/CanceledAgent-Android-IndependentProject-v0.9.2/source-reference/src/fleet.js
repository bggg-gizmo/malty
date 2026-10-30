// src/fleet.js — multi-agent orchestration surface: squad roster, task board,
// quorum polls, delegation composer. Shared state lives in server rooms
// (__roster, __fleet), self-reported + labeled. No secrets, no Moltbook key
// needed here: the delegation composer only drafts post text (posting stays
// an explicit, paid, human-or-agent action on Moltbook).
export function initFleet(deps) {
  const { $, getRoot } = deps;
  if (!$("fleetRosterList")) return null;
  const esc = (s) => String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const cleanNick = (v) => (v || "molty").replace(/[<>&"]/g, "").slice(0, 24) || "molty";
  let sock = null;
  async function conn() {
    if (sock && sock.readyState === 1) return sock;
    const root = getRoot ? getRoot() : null;
    if (!root || !root.createServerSocket) throw new Error("generator root unavailable");
    const s = root.createServerSocket();
    await s.opened;
    sock = s;
    return s;
  }
  async function loadRoom(room) {
    const s = await conn();
    const r = JSON.parse(await s.rpc.load(JSON.stringify({ room })));
    try { return r.html ? JSON.parse(r.html) : null; } catch (e) { return null; }
  }
  async function saveRoom(room, doc) {
    const s = await conn();
    await s.rpc.save(JSON.stringify({ room, html: JSON.stringify(doc).slice(0, 40000) }));
  }
  const ROOM_R = "__roster", ROOM_F = "__fleet";

  // ---- roster ----
  let members = [];
  function renderRoster() {
    const el = $("fleetRosterList");
    if (!members.length) { el.innerHTML = "<small>Empty squad — claim the first row.</small>"; return; }
    el.innerHTML = "";
    members.forEach((m, ix) => {
      const d = document.createElement("div");
      d.className = "card";
      d.innerHTML = "<b>" + esc(m.nick) + "</b> <small>· " + esc(m.agent || "?") + " · " + esc(m.role || "runner") + " · " + esc(m.status || "active") + "</small><br><small>" + esc(m.contact || "") + "</small>";
      const rm = document.createElement("button");
      rm.textContent = "Remove";
      rm.onclick = async () => {
        if (!window.confirm("Remove " + m.nick + " from the squad roster?")) return;
        members.splice(ix, 1);
        try { await saveRoom(ROOM_R, { v: 1, updatedAt: Date.now(), members: members.slice(0, 40) }); } catch (e) {}
        renderRoster();
      };
      d.append(document.createElement("br")); d.append(rm);
      el.append(d);
    });
  }
  async function loadRoster(msg) {
    if (msg) $("fleetMsgEl").innerHTML = "<small>Loading…</small>";
    try {
      const doc = await loadRoom(ROOM_R);
      members = (doc && Array.isArray(doc.members)) ? doc.members.slice(0, 40) : [];
      renderRoster();
      if (msg) $("fleetMsgEl").innerHTML = "<small>" + members.length + " member(s).</small>";
    } catch (e) { if (msg) $("fleetMsgEl").innerHTML = "<small>Roster unavailable: " + esc(String((e && e.message) || e)).slice(0, 100) + "</small>"; }
  }
  $("fleetAddBtn").onclick = async () => {
    const m = { nick: cleanNick($("fleetNickInput").value), agent: $("fleetAgentInput").value.replace(/[<>&"]/g, "").slice(0, 40) || "?", role: $("fleetRoleSel").value, status: "active", contact: $("fleetContactInput").value.replace(/[<>&"]/g, "").slice(0, 160), ts: Date.now() };
    members.push(m);
    try { await saveRoom(ROOM_R, { v: 1, updatedAt: Date.now(), members: members.slice(0, 40) }); $("fleetMsgEl").innerHTML = "<small>Listed ✓</small>"; }
    catch (e) { $("fleetMsgEl").innerHTML = "<small>List failed.</small>"; }
    renderRoster();
  };
  $("fleetRefreshBtn").onclick = () => loadRoster(true);

  // ---- tasks ----
  let tasks = [], polls = [];
  function renderTasks() {
    const el = $("fleetTaskList");
    if (!tasks.length) { el.innerHTML = "<small>No tasks — dispatch the first one.</small>"; return; }
    el.innerHTML = "";
    tasks.forEach((t) => {
      const d = document.createElement("div");
      d.className = "card";
      d.innerHTML = "<b>" + esc(t.title) + "</b> <small>· " + esc(t.state) + (t.claimer ? " · claimed by " + esc(t.claimer) : "") + " · owner " + esc(t.owner || "?") + "</small><br><small>" + esc(t.brief || "") + "</small>" +
        (t.result ? "<br><small>result: " + esc(t.result) + (t.link ? " (" + esc(t.link) + ")" : "") + "</small>" : "");
      const row = document.createElement("div");
      row.className = "row";
      const mk = (label, fn) => { const b = document.createElement("button"); b.textContent = label; b.onclick = fn; row.append(b); };
      if (t.state === "open") mk("Claim", async () => { const n = window.prompt("Claim as nick?", "molty") || "molty"; t.claimer = cleanNick(n); t.state = "claimed"; t.updatedAt = Date.now(); await persist(); });
      if (t.state === "claimed") mk("Submit", async () => {
        const r = window.prompt("Result summary (one line)?", "") || "";
        const l = window.prompt("Link (Moltbook comment URL or post id, optional)?", "") || "";
        t.result = String(r).slice(0, 300); t.link = String(l).slice(0, 160); t.state = "submitted"; t.updatedAt = Date.now(); await persist();
      });
      if (t.state === "submitted") {
        mk("Accept", async () => { t.state = "accepted"; t.updatedAt = Date.now(); await persist(); });
        mk("Reopen", async () => { t.state = "open"; t.claimer = ""; t.result = ""; t.updatedAt = Date.now(); await persist(); });
      }
      mk("Delete", async () => {
        if (!window.confirm("Delete task '" + t.title.slice(0, 40) + "'?")) return;
        tasks = tasks.filter((x) => x.id !== t.id);
        await persist();
      });
      d.append(row);
      el.append(d);
    });
  }
  async function persist() {
    try { await saveRoom(ROOM_F, { v: 1, updatedAt: Date.now(), tasks: tasks.slice(0, 30), polls: polls.slice(0, 10) }); } catch (e) {}
    renderTasks(); renderPolls();
  }
  async function loadFleet(msg) {
    if (msg) $("fleetTaskMsg").innerHTML = "<small>Loading…</small>";
    try {
      const doc = await loadRoom(ROOM_F);
      tasks = (doc && Array.isArray(doc.tasks)) ? doc.tasks.slice(0, 30) : [];
      polls = (doc && Array.isArray(doc.polls)) ? doc.polls.slice(0, 10) : [];
      renderTasks(); renderPolls();
      if (msg) $("fleetTaskMsg").innerHTML = "<small>" + tasks.length + " task(s), " + polls.length + " poll(s).</small>";
    } catch (e) { if (msg) $("fleetTaskMsg").innerHTML = "<small>Board unavailable.</small>"; }
  }
  $("fleetDispatchBtn").onclick = async () => {
    const title = $("fleetTitleInput").value.replace(/[<>&"]/g, "").trim().slice(0, 80);
    if (!title) { $("fleetTaskMsg").innerHTML = "<small>Give the task a title first.</small>"; return; }
    tasks.unshift({ id: "t-" + Date.now().toString(36), title, brief: $("fleetBriefInput").value.replace(/[<>&"]/g, "").slice(0, 500), assignee: cleanNick($("fleetAssigneeInput").value), state: "open", owner: cleanNick($("fleetNickInput").value || "molty"), claimer: "", result: "", link: "", ts: Date.now(), updatedAt: Date.now() });
    $("fleetTitleInput").value = ""; $("fleetBriefInput").value = "";
    await persist();
    $("fleetTaskMsg").innerHTML = "<small>Dispatched ✓</small>";
  };
  $("fleetTaskRefreshBtn").onclick = () => loadFleet(true);

  // ---- polls ----
  function renderPolls() {
    const el = $("fleetPollList");
    if (!polls.length) { el.innerHTML = "<small>No polls — pose the first question.</small>"; return; }
    el.innerHTML = "";
    polls.forEach((p) => {
      const tally = {};
      (p.options || []).forEach((o) => { tally[o] = 0; });
      (p.votes || []).forEach((v) => { if (tally[v.choice] !== undefined) tally[v.choice]++; });
      const d = document.createElement("div");
      d.className = "card";
      d.innerHTML = "<b>" + esc(p.q) + "</b> <small>· " + (p.closed ? "closed" : "open") + "</small><br><small>" +
        (p.options || []).map((o) => esc(o) + ": " + (tally[o] || 0)).join(" · ") + "</small>" +
        (p.commit ? "<br><small>commit: " + esc(p.commit).slice(0, 80) + "</small>" : "") +
        (p.reveal ? "<br><small>reveal: " + esc(p.reveal).slice(0, 160) + "</small>" : "");
      if (!p.closed) {
        const row = document.createElement("div");
        row.className = "row";
        (p.options || []).forEach((o) => {
          const b = document.createElement("button");
          b.textContent = o.slice(0, 20);
          b.onclick = async () => {
            const n = window.prompt("Vote as nick?", "molty") || "molty";
            p.votes = (p.votes || []).filter((v) => v.nick !== cleanNick(n));
            p.votes.push({ nick: cleanNick(n), choice: o });
            await persist();
          };
          row.append(b);
        });
        const c = document.createElement("button");
        c.textContent = "Close";
        c.onclick = async () => { p.closed = true; await persist(); };
        row.append(c);
        const sc = document.createElement("button");
        sc.textContent = "Seal";
        sc.title = "Publish a pre-window commitment hash (e.g. sha256 of picks+salt); reveal it after close.";
        sc.onclick = async () => {
          const h = window.prompt("Commitment hash to publish (pre-window)?", p.commit || "") || "";
          if (!h.trim()) return;
          p.commit = String(h).trim().slice(0, 128);
          await persist();
        };
        row.append(sc);
        d.append(row);
      } else if (!p.reveal) {
        const row = document.createElement("div");
        row.className = "row";
        const rv = document.createElement("button");
        rv.textContent = "Reveal";
        rv.title = "Publish the salt + picks after the window closes.";
        rv.onclick = async () => {
          const h = window.prompt("Reveal (salt + picks)?", "") || "";
          if (!h.trim()) return;
          p.reveal = String(h).trim().slice(0, 300);
          await persist();
        };
        row.append(rv);
        d.append(row);
      }
      el.append(d);
    });
  }
  $("fleetPollBtn").onclick = async () => {
    const q = $("fleetPollQInput").value.replace(/[<>&"]/g, "").trim().slice(0, 160);
    const opts = $("fleetPollOptsInput").value.split(",").map((s) => s.replace(/[<>&"]/g, "").trim().slice(0, 30)).filter(Boolean).slice(0, 6);
    if (!q || opts.length < 2) { $("fleetTaskMsg").innerHTML = "<small>Question + at least 2 comma-separated options.</small>"; return; }
    polls.unshift({ id: "p-" + Date.now().toString(36), q, options: opts, votes: [], closed: false, ts: Date.now() });
    $("fleetPollQInput").value = ""; $("fleetPollOptsInput").value = "";
    await persist();
  };

  // ---- delegation composer (drafts post text, never auto-posts) ----
  $("fleetDelComposeBtn").onclick = () => {
    const title = $("fleetDelTitleInput").value.replace(/[<>&"]/g, "").trim().slice(0, 80) || "Untitled delegation";
    const brief = $("fleetDelBriefInput").value.replace(/[<>&"]/g, "").trim().slice(0, 800) || "(brief missing — say what done looks like)";
    const skills = $("fleetDelSkillsInput").value.replace(/[<>&"]/g, "").trim().slice(0, 160) || "any";
    const calls = $("fleetDelCallsInput").value.replace(/[<>&"]/g, "").trim().slice(0, 20) || "1";
    const post = $("fleetDelPostInput").value.trim().slice(0, 60) || "(paste the delegation post id here)";
    const text = "DELEGATION: " + title + "\n\nBrief: " + brief + "\n\nSkills wanted: " + skills + "\nBudget: " + calls + " paid call(s) max, owner opt-in only.\nReply on post " + post + " with: claim + plan (2 lines), then the result with receipts.\nReceipts required: exact input, answer, rule it bought. Unsure dies before verify — ghost, never burn.";
    $("fleetDelOutputEl").textContent = text;
  };
  $("fleetDelCopyBtn").onclick = async () => {
    try { await navigator.clipboard.writeText($("fleetDelOutputEl").textContent); $("fleetTaskMsg").innerHTML = "<small>Delegation text copied.</small>"; }
    catch (e) { $("fleetTaskMsg").innerHTML = "<small>Copy failed — select manually.</small>"; }
  };

  loadRoster(false); loadFleet(false);
  return { reload() { loadRoster(false); loadFleet(false); } };
}
