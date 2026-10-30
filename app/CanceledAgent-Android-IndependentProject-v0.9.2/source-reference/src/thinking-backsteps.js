// src/thinking-backsteps.js — backstep tracking for the aiThinking plugin.
// Classic script (no imports): extends window.__aiThink in place, so the
// vendored plugin import keeps working and localStorage state is shared.
// A backstep = a thought that abandons/supersedes an earlier entry. It is a
// first-class entry (kind "backstep"): parent-linked to what it supersedes
// (so trace-back shows the retreat), badged on its card, and folded into
// the copy-feedback output as an avoid-list.
(function () {
  var T = window.__aiThink;
  if (!T || T.__backstepsInstalled) return;
  T.__backstepsInstalled = true;

  // Log a backstep. o.supersedes = id of the abandoned entry (parent-links
  // to it and marks it superseded). Without it, parents onto the last entry.
  T.backstep = function (k, text, o) {
    o = o || {};
    var target = o.supersedes || o.supersedeId || o.targetId || null;
    var parent = target || o.parentId || o.parent || this.lastIds[k] || "";
    var id = this.log(k, text, {
      kind: "backstep", pivot: true, source: o.source || "ai",
      changeLabel: o.changeLabel || "", snapshot: o.snapshot || "",
      parentId: parent, actor: o.actor || "helper", turn: o.turn || ""
    });
    if (target) {
      var t = this.byId(k, target);
      if (t) { t.supersededBy = id; this.save(k); this.refresh(k); }
    }
    return id;
  };
  T.getBacksteps = function (k) {
    return this.stateFor(k).filter(function (e) { return e.kind === "backstep"; });
  };
  T.backstepCount = function (k) { return this.getBacksteps(k).length; };
  // Every revert is a backstep: record one automatically.
  var origRevert = T.revert;
  T.revert = function (k, id) {
    var e = this.byId(k, id);
    origRevert.call(this, k, id);
    if (e) this.backstep(k, "Reverted change" + (e.changeLabel ? " (" + e.changeLabel + ")" : "") + " — back to pre-change state.", { supersedes: id, source: e.source || "ai", actor: "helper" });
  };
  // Card button starter (editor-only UI; prompt() may be blocked — then no-op).
  T.startBackstep = function (k, id) {
    var e = this.byId(k, id);
    var t = null;
    try { t = window.prompt("Backstep — what are you abandoning" + (e && e.seq ? " in #" + e.seq : "") + ", and what replaces it?", ""); } catch (err) { t = null; }
    if (t && String(t).trim()) this.backstep(k, String(t).trim(), { supersedes: id });
  };
  // Render: badge pills + a ↩ button on every card.
  var origItemHtml = T.itemHtml;
  T.itemHtml = function (k, e) {
    var h = origItemHtml.call(this, k, e);
    try {
      var kindPill = "<span style=\"border:1px solid #3a4563;color:#c8d0e4;border-radius:999px;padding:2px 8px\">" + this.esc(e.kind || "think") + " " + this.esc(e.seq ? "#" + e.seq : "") + "</span>";
      var extra = "";
      if (e.kind === "backstep") {
        extra = "<span style=\"border:1px solid #c9a227;color:#ffd76a;border-radius:999px;padding:2px 8px\">↩ backstep</span>";
        var tgt = (e.parentId && this.byId(k, e.parentId)) || null;
        if (tgt && tgt.seq) extra += "<span style=\"border:1px solid #3a4563;color:#9aa3b8;border-radius:999px;padding:2px 8px\">supersedes #" + tgt.seq + "</span>";
      } else if (e.supersededBy) {
        extra = "<span style=\"border:1px solid #3a4563;color:#9aa3b8;border-radius:999px;padding:2px 8px\">↩ superseded</span>";
      }
      if (extra && h.indexOf(kindPill) >= 0) h = h.replace(kindPill, kindPill + extra);
      var traceBtn = ">⤴ Trace</button>";
      if (h.indexOf(traceBtn) >= 0) h = h.replace(traceBtn, traceBtn + "<button onclick=\"window.__aiThink.startBackstep('" + k + "','" + e.id + "')\" style=\"cursor:pointer;border:1px solid #c9a227;background:#3a2f10;color:#ffd76a;border-radius:8px;padding:6px 10px\">↩ Backstep</button>");
    } catch (err) { /* render must never break: fall back to unbadged */ }
    return h;
  };
  // Feedback: abandoned directions become an explicit avoid-list.
  var origFeedback = T.buildFeedback;
  T.buildFeedback = function (k, nextText) {
    var out = origFeedback.call(this, k, nextText);
    try {
      var bs = this.getBacksteps(k);
      if (bs.length) {
        var self = this;
        out += "\nBACKSTEPS — tried, then abandoned (" + bs.length + "):\n";
        out += bs.map(function (t) {
          var sup = "";
          var p = (t.parentId && self.byId(k, t.parentId)) || null;
          if (p) sup = " (supersedes #" + (p.seq || "?") + ": " + String(p.text).slice(0, 80) + ")";
          return "- ↩ [thinking " + t.id + (t.seq ? " #" + t.seq : "") + "] " + t.text + sup;
        }).join("\n") + "\n";
      }
    } catch (err) { /* feedback must never break */ }
    return out;
  };
  if (!window.logAiBackstep) {
    window.logAiBackstep = function (text, o) { return window.__aiThink.backstep("build", text, o); };
  }
  // Upstream helpers hardcode the "default" key, which has no visible panel
  // in this generator (only key "build" is embedded). Re-point them at the
  // visible log so helper thoughts never vanish into the invisible store.
  // Guarded: only takes over while they still target "default".
  try {
    var _probe = "__aiThinkKeyProbe";
    var _looksDefault = function (fn) {
      if (typeof fn !== "function") return false;
      var s = Function.prototype.toString.call(fn);
      return s.indexOf('"default"') >= 0 || s.indexOf("'default'") >= 0;
    };
    if (_looksDefault(window.logAiThought)) {
      window.logAiThought = function (text, o) { return window.__aiThink.log("build", text, o); };
      window.helperThink = window.logAiThought;
    }
    if (_looksDefault(window.getHelperFeedback)) {
      window.getHelperFeedback = function (next) { return window.__aiThink.getFeedback("build", next); };
    }
  } catch (err) { /* key re-point must never break install */ }
})();
