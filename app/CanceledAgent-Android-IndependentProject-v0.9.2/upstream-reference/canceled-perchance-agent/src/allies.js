// src/allies.js — ally directory (localStorage nicks, live profiles +
// follow/unfollow) and submolt management (list/subscribe/unsubscribe/info).
// Reads plus free follows only. No secrets leave the page.
export function initAllies(deps) {
  const { $, Moltbook, getKey } = deps;
  if (!$("allyListEl")) return null;
  const esc = (s) => String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const LS = "allies_v1";
  function load() { try { const v = JSON.parse(localStorage.getItem(LS) || "[]"); return Array.isArray(v) ? v : []; } catch (e) { return []; } }
  function save(v) { try { localStorage.setItem(LS, JSON.stringify(v)); } catch (e) {} }
  function clientOrNull() {
    const k = (getKey() || "").trim();
    if (!k) return null;
    return new Moltbook({ apiKey: k, base: "https://www.moltbook.com/api/v1" });
  }
  let allies = load();
  allies.sort((a, b) => (b.karma || 0) - (a.karma || 0));
  function render() {
    const el = $("allyListEl");
    el.innerHTML = "";
    if (!allies.length) { el.innerHTML = "<small>No allies pinned — add the moltys whose work you actually read.</small>"; return; }
    allies.forEach((a, ix) => {
      const d = document.createElement("div");
      d.className = "card";
      d.innerHTML = "<b>" + esc(a.nick || a.agent) + "</b> <small>· " + esc(a.agent) + "</small><br><small class=\"prof\">profile not loaded</small> ";
      const f = document.createElement("button");
      f.textContent = "Follow";
      f.onclick = async () => {
        const cl = clientOrNull();
        if (!cl) { f.textContent = "Need key"; return; }
        try { await cl.follow(a.agent); f.textContent = "Following ✓"; } catch (e) { f.textContent = "Failed"; }
      };
      const u = document.createElement("button");
      u.textContent = "Unfollow";
      u.onclick = async () => {
        const cl = clientOrNull();
        if (!cl) { u.textContent = "Need key"; return; }
        try { await cl.unfollow(a.agent); u.textContent = "Unfollowed ✓"; f.textContent = "Follow"; } catch (e) { u.textContent = "Failed"; }
      };
      const rm = document.createElement("button");
      rm.textContent = "Remove";
      rm.onclick = () => { allies.splice(ix, 1); save(allies); render(); };
      d.append(f); d.append(u); d.append(rm);
      el.append(d);
    });
  }
  $("allyAddBtn").onclick = () => {
    const agent = $("allyAgentInput").value.replace(/[<>&"]/g, "").trim().slice(0, 40);
    if (!agent) { $("allyMsgEl").innerHTML = "<small>Type the Moltbook agent name first.</small>"; return; }
    const nick = $("allyNickInput").value.replace(/[<>&"]/g, "").trim().slice(0, 24) || agent;
    if (!allies.some((a) => a.agent === agent)) allies.push({ nick, agent });
    save(allies); render(); refreshProfiles();
    $("allyMsgEl").innerHTML = "<small>Pinned ✓</small>";
  };
  async function refreshProfiles() {
    const cl = clientOrNull();
    const cards = $("allyListEl").querySelectorAll(".card");
    if (!cl) {
      cards.forEach((c) => { const p = c.querySelector(".prof"); if (p) p.textContent = "paste key in Dashboard for live profiles"; });
      return;
    }
    for (let ix = 0; ix < allies.length; ix++) {
      const p = cards[ix] && cards[ix].querySelector(".prof");
      if (!p) continue;
      try {
        const r = await cl.profileOf(allies[ix].agent);
        const prof = r.json.agent || r.json.profile || r.json || {};
        allies[ix].karma = prof.karma ?? allies[ix].karma ?? 0;
        p.textContent = "karma " + (prof.karma ?? "?") + (prof.description ? " — " + String(prof.description).slice(0, 120) : "");
      } catch (e) { p.textContent = "profile lookup failed"; }
    }
    allies.sort((a, b) => (b.karma || 0) - (a.karma || 0));
    save(allies); render();
  }
  $("allyRefreshBtn").onclick = () => refreshProfiles();

  // ---- submolts ----
  $("allySubsBtn").onclick = async () => {
    const cl = clientOrNull();
    if (!cl) { $("allySubsEl").innerHTML = "<small>No key — Dashboard tab first.</small>"; return; }
    $("allySubsEl").innerHTML = "<small>Loading…</small>";
    try {
      const r = await cl.submolts();
      const subs = r.json.submolts || r.json.items || [];
      const el = $("allySubsEl");
      el.innerHTML = subs.length ? "" : "<small>No submolts listed.</small>";
      subs.forEach((s) => {
        const row = document.createElement("div");
        row.className = "card";
        const nm = s.name || s.submolt_name || "?";
        row.innerHTML = "<small><b>m/" + esc(nm) + "</b> · " + esc(s.subscriber_count ?? s.subscribers ?? "?") + " subs<br>" + esc((s.description || "").slice(0, 160)) + "</small> ";
        const sub = document.createElement("button");
        sub.textContent = "Subscribe";
        sub.onclick = async () => { try { await cl.subscribe(nm); sub.textContent = "Subscribed ✓"; } catch (e) { sub.textContent = "Failed"; } };
        const un = document.createElement("button");
        un.textContent = "Unsubscribe";
        un.onclick = async () => { try { await cl.unsubscribe(nm); un.textContent = "Left ✓"; sub.textContent = "Subscribe"; } catch (e) { un.textContent = "Failed"; } };
        row.append(sub); row.append(un);
        el.append(row);
      });
    } catch (e) { $("allySubsEl").innerHTML = "<small>List failed: " + esc(String((e && e.message) || e)).slice(0, 120) + "</small>"; }
  };
  $("allySubInfoBtn").onclick = async () => {
    const cl = clientOrNull();
    if (!cl) { $("allySubsEl").innerHTML = "<small>No key — Dashboard tab first.</small>"; return; }
    const nm = $("allySubInput").value.replace(/[<>&"]/g, "").trim().slice(0, 40);
    if (!nm) return;
    try {
      const r = await cl.submoltInfo(nm);
      const s = r.json.submolt || r.json || {};
      $("allySubsEl").innerHTML = "<small><b>m/" + esc(nm) + "</b><br>" + esc(JSON.stringify(s).slice(0, 600)) + "</small>";
    } catch (e) { $("allySubsEl").innerHTML = "<small>Info failed.</small>"; }
  };
  render(); refreshProfiles();
  return { reload() { refreshProfiles(); } };
}
