// src/intel.js — Moltbook intel surface: /home snapshot, semantic search,
// feed reader. Page-key only (localStorage moltbook_key via getKey), reads
// plus free upvotes only — replies stay in the Replies tab, posts stay human.
export function initIntel(deps) {
  const { $, Moltbook, getKey } = deps;
  if (!$("intelHomeEl")) return null;
  const esc = (s) => String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  function clientOrNull(quiet) {
    const k = (getKey() || "").trim();
    if (!k) { if (!quiet) $("intelHomeEl").innerHTML = "<small>No key saved — paste it in the Dashboard tab first.</small>"; return null; }
    return new Moltbook({ apiKey: k, base: "https://www.moltbook.com/api/v1" });
  }
  function clean(s) { return String(s == null ? "" : s).replace(/[[⟦]\/?HL[\]⟧]/g, ""); }
  function authorOf(it) {
    const a = it.author_name ?? it.author ?? it.agent ?? "?";
    if (typeof a === "object" && a) return a.name || a.agent || "?";
    return a;
  }
  function copyBtn(text, label) {
    const b = document.createElement("button");
    b.textContent = label || "Copy ID";
    b.onclick = async () => { try { await navigator.clipboard.writeText(text); b.textContent = "Copied ✓"; } catch (e) { b.textContent = "Copy failed"; } };
    return b;
  }

  // ---- home snapshot ----
  let intelTimer = null;
  async function loadHome() {
    const cl = clientOrNull(false);
    if (!cl) return;
    $("intelHomeEl").innerHTML = "<small>Loading home…</small>";
    try {
      const r = await cl.home();
      const h = r.json || {};
      const el = $("intelHomeEl");
      el.innerHTML = "";
      const acct = h.your_account || {};
      const d0 = document.createElement("div");
      d0.className = "card";
      d0.innerHTML = "<b>" + esc(acct.name || "?") + "</b> <small>· karma " + esc(acct.karma) + " · unread " + esc(acct.unread_notification_count) + "</small>";
      el.append(d0);
      const acts = h.activity_on_your_posts || [];
      const d1 = document.createElement("div");
      d1.className = "card";
      d1.innerHTML = "<b>Activity on your posts (" + acts.length + ")</b>";
      acts.forEach((a) => {
        const row = document.createElement("div");
        row.innerHTML = "<small><b>" + esc(a.post_title || a.post_id || "?") + "</b> · " + esc(a.new_notification_count) + " new · latest: " + esc((a.latest_commenters || []).join(", ")) + "<br>" + esc(a.preview || "") + "</small> ";
        row.append(copyBtn(a.post_id || "", "Copy post ID"));
        const mr = document.createElement("button");
        mr.textContent = "Mark read";
        mr.onclick = async () => { try { await cl.markReadByPost(a.post_id); mr.textContent = "Read ✓"; } catch (e) { mr.textContent = "Failed"; } };
        row.append(mr);
        d1.append(row);
      });
      if (!acts.length) d1.innerHTML += "<br><small>Quiet — nothing new on your posts.</small>";
      el.append(d1);
      const ann = h.latest_moltbook_announcement;
      if (ann) {
        const d2 = document.createElement("div");
        d2.className = "card";
        d2.innerHTML = "<b>Announcement</b><br><small><b>" + esc(ann.title || "") + "</b><br>" + esc(ann.preview || "") + "</small> ";
        d2.append(copyBtn(ann.post_id || "", "Copy post ID"));
        el.append(d2);
      }
      const fol = (h.posts_from_accounts_you_follow && h.posts_from_accounts_you_follow.posts) || [];
      const d3 = document.createElement("div");
      d3.className = "card";
      d3.innerHTML = "<b>Following (" + fol.length + " shown)</b>";
      fol.forEach((p) => {
        const row = document.createElement("div");
        row.innerHTML = "<small><b>" + esc(clean(p.title || "(untitled)")) + "</b> · " + esc(authorOf(p)) + " · +" + esc(p.upvotes) + " · " + esc(p.comment_count) + " comments<br>" + esc(clean(p.content_preview || "").slice(0, 160)) + "</small> ";
        row.append(copyBtn(p.post_id || "", "Copy post ID"));
        d3.append(row);
      });
      el.append(d3);
      const wtdn = h.what_to_do_next || [];
      if (wtdn.length) {
        const d4 = document.createElement("div");
        d4.className = "card";
        d4.innerHTML = "<b>What to do next</b><br><small>" + wtdn.map(esc).join("<br>") + "</small>";
        el.append(d4);
      }
      if (h.your_direct_messages) {
        const d5 = document.createElement("div");
        d5.className = "card";
        d5.innerHTML = "<b>Direct messages</b><br><small>" + esc(JSON.stringify(h.your_direct_messages).slice(0, 500)) + "<br>Note: new DM threads need human approval before chatting.</small>";
        el.append(d5);
      }
    } catch (e) { $("intelHomeEl").innerHTML = "<small>Home failed: " + esc(String((e && e.message) || e)).slice(0, 120) + "</small>"; }
  }
  $("intelHomeBtn").onclick = loadHome;
  $("intelAutoInput").onchange = () => {
    if (intelTimer) { clearInterval(intelTimer); intelTimer = null; }
    if ($("intelAutoInput").checked) intelTimer = setInterval(() => {
      try { if (document.getElementById("tab-intel") && document.getElementById("tab-intel").classList.contains("on") && document.visibilityState === "visible") loadHome(); } catch (e) {}
    }, 60000);
  };

  // ---- semantic search ----
  $("intelSearchBtn").onclick = async () => {
    const cl = clientOrNull(true);
    if (!cl) { $("intelSearchEl").innerHTML = "<small>No key — Dashboard tab first.</small>"; return; }
    const q = $("intelQInput").value.trim().slice(0, 500);
    if (!q) { $("intelSearchEl").innerHTML = "<small>Type a question or idea first.</small>"; return; }
    $("intelSearchEl").innerHTML = "<small>Searching…</small>";
    try {
      const r = await cl.search(q, { type: $("intelTypeSel").value, limit: parseInt($("intelLimitSel").value, 10) || 10 });
      const items = r.json.results || r.json.items || r.json.posts || [];
      const el = $("intelSearchEl");
      el.innerHTML = items.length ? "" : "<small>No hits — try broader wording.</small>";
      items.forEach((it) => {
        const row = document.createElement("div");
        row.className = "card";
        row.innerHTML = "<small><b>" + esc(clean(it.title || it.post_title || "(comment)")) + "</b> · " + esc(authorOf(it)) + "<br>" + esc(clean(it.content_preview || it.preview || it.content || "").slice(0, 220)) + "</small> ";
        if (it.post_id || it.postId || it.id) row.append(copyBtn(it.post_id || it.postId || it.id, "Copy ID"));
        el.append(row);
      });
    } catch (e) { $("intelSearchEl").innerHTML = "<small>Search failed: " + esc(String((e && e.message) || e)).slice(0, 120) + "</small>"; }
  };

  // ---- feed reader ----
  $("intelFeedBtn").onclick = async () => {
    const cl = clientOrNull(true);
    if (!cl) { $("intelFeedEl").innerHTML = "<small>No key — Dashboard tab first.</small>"; return; }
    $("intelFeedEl").innerHTML = "<small>Loading feed…</small>";
    try {
      const r = await cl.feed({ sort: $("intelSortSel").value, limit: 15, filter: $("intelFilterSel").value === "all" ? undefined : "following" });
      const posts = r.json.posts || r.json.items || [];
      const el = $("intelFeedEl");
      el.innerHTML = posts.length ? "" : "<small>Empty feed.</small>";
      posts.forEach((p) => {
        const row = document.createElement("div");
        row.className = "card";
        row.innerHTML = "<small><b>" + esc(clean(p.title || "(untitled)")) + "</b> · " + esc(authorOf(p)) + " · +" + esc(p.upvotes) + " · " + esc(p.comment_count) + " comments · m/" + esc(p.submolt_name || p.submolt || "?") + "<br>" + esc(clean(p.content_preview || p.content || "").slice(0, 200)) + "</small> ";
        const pid = p.post_id || p.id || "";
        if (pid) {
          row.append(copyBtn(pid, "Copy ID"));
          const up = document.createElement("button");
          up.textContent = "Upvote";
          up.onclick = async () => { try { await cl.upvotePost(pid); up.textContent = "Upvoted ✓"; } catch (e) { up.textContent = "Failed"; } };
          row.append(up);
        }
        el.append(row);
      });
    } catch (e) { $("intelFeedEl").innerHTML = "<small>Feed failed: " + esc(String((e && e.message) || e)).slice(0, 120) + "</small>"; }
  };
  return { reload() {} };
}
