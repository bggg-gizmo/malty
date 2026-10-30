/* ============================================================================
   Perchance Plugin Inspector — headless plugin-registry tool
   ----------------------------------------------------------------------------
   Loaded by index.html via <script src="src/plugin-tools.js"></script>.
   Defines window.createPluginTools(), which powers TWO things:

     1. the inspector UI's per-plugin enable/disable toggles, and
     2. a NO-IMPORT tool for an AI helper: `window.perchancePluginTools`.

   Because this is a plain classic script, the singleton tool is on `window` as
   soon as the page loads, so an AI helper (or anything else running in the page)
   can decide which plugins to use and toggle them WITHOUT importing anything:

       const t = window.perchancePluginTools;
       await t.suggest("remember a high score between visits");  // -> kv-plugin, ...
       t.toggle("kv-plugin");                                    // on/off
       t.importLines();                                          // paste-ready block

   The enabled set persists in localStorage under ENABLED_KEY. The inspector
   itself is appended to the registry as an entry with `self: true`; its own
   source is read live from #preloaded-generator-data (no network needed).

   Registry entry shape (src/plugins/index.json):
     {name, url, official, desc, hasHtml, imports, codeBytes, codeLines,
      htmlBytes, exposes}
   ============================================================================ */
(function () {
  "use strict";

  var INDEX_URL = "src/plugins/index.json";
  var ENABLED_KEY = "perchance-plugin-tools:enabled:v1";

  var SELF_DESC =
    "The Perchance Plugin Inspector itself: a searchable, previewable browser for the " +
    "source of every Perchance plugin (official + community), with a per-plugin " +
    "enable/disable toggle and a no-import tool API (window.perchancePluginTools) an " +
    "AI helper can call to list, search, suggest, inspect and toggle plugins.";

  /* Rough domain vocabulary so a plain-language request ("remember a score",
     "let people chat", "read a web page") lands on the right plugins. Mapped
     one way only: query token -> extra tokens to match against. */
  var SYNONYMS = {
    picture: ["image", "img", "photo"], photo: ["image"], img: ["image"],
    drawing: ["image", "draw", "art"], draw: ["image", "draw"], art: ["image", "draw"],
    painting: ["image", "draw"], thumbnail: ["image"], sprite: ["sprite", "image"],
    voice: ["audio", "speech", "sound"], speech: ["text-to-speech", "speak", "audio"],
    say: ["text-to-speech", "speak"], tts: ["text-to-speech"], sound: ["audio"],
    music: ["audio", "music"], song: ["audio", "music"], soundscape: ["audio", "audio"],
    play: ["audio", "video"], video: ["video"], movie: ["video"], youtube: ["video"],
    save: ["storage", "localstorage", "kv", "store", "persist", "remember"],
    saves: ["storage", "kv"], persistence: ["storage", "kv", "localstorage"],
    persist: ["storage", "kv", "localstorage"], remember: ["storage", "kv", "localstorage"],
    memory: ["storage", "kv"], store: ["storage", "kv", "localstorage"],
    load: ["storage", "kv", "localstorage"], score: ["storage", "kv"], highscore: ["storage", "kv"],
    random: ["random", "dice"], dice: ["dice", "random"], chance: ["random", "dice"],
    luck: ["random", "dice"], gamble: ["random", "dice", "wheel"], spin: ["wheel", "random"],
    chat: ["chat", "comments", "message", "talk"], comments: ["comments", "chat"],
    comment: ["comments"], guestbook: ["comments"], message: ["comments", "chat"],
    messages: ["comments", "chat"], forum: ["comments"], talk: ["comments", "chat"],
    ai: ["ai", "generate", "text"], llm: ["ai-text", "generate"], gpt: ["ai-text", "generate"],
    generate: ["generate", "ai"], write: ["generate", "text", "story"],
    story: ["story", "markov", "generate"], prompt: ["generate", "ai-text"],
    password: ["secret", "encrypt"], secret: ["secret", "encrypt"], private: ["secret", "encrypt"],
    encrypt: ["secret", "encrypt"], secure: ["secret", "encrypt"], cipher: ["secret", "encrypt"],
    security: ["secret", "encrypt", "lock"], auth: ["secret", "locker", "lock"],
    lock: ["locker", "lockable", "lock"], protect: ["locker", "secret", "lockable"],
    multiplayer: ["server", "socket", "realtime"], online: ["server", "realtime", "socket"],
    realtime: ["server", "socket", "realtime"], sync: ["server", "socket"],
    lobby: ["server", "socket", "chat"], players: ["server", "socket"],
    fetch: ["super-fetch", "http", "url", "websocket"], api: ["super-fetch", "http", "url"],
    http: ["super-fetch", "url"], scrape: ["super-fetch", "http"], request: ["super-fetch", "url"],
    url: ["url", "super-fetch"], link: ["link", "url", "goto"], redirect: ["goto", "url"],
    download: ["download", "upload"], export: ["download", "json", "upload"],
    upload: ["upload", "file"], file: ["upload", "download"], json: ["json"],
    date: ["date", "time"], time: ["date", "time"], timer: ["date", "time"],
    countdown: ["date", "time"], clock: ["date", "time"], calendar: ["date"],
    button: ["button"], fullscreen: ["fullscreen"], print: ["print"], copy: ["copy"],
    clipboard: ["copy"], style: ["font", "color", "palette", "css", "theme"],
    font: ["font"], fonts: ["font"], color: ["color", "palette"], colour: ["color", "palette"],
    palette: ["palette", "color"], theme: ["theme", "css", "style"], css: ["css", "style"],
    layout: ["layout", "grid", "table"], grid: ["layout", "table"], table: ["table", "table"],
    tabs: ["tabs", "tab"], tab: ["tabs"], accordion: ["tabs", "spoiler"],
    spoiler: ["spoiler", "hide"], hide: ["spoiler", "exclude"], toggle: ["flag", "tabs"],
    popup: ["popup", "modal"], modal: ["popup"], tooltip: ["tooltip", "hover"],
    hover: ["tooltip"], icon: ["icon", "emoji"], emoji: ["emoji", "icon"],
    avatar: ["avatar"], image: ["image"], markdown: ["markdown"], md: ["markdown"],
    text: ["text", "string"], string: ["text", "string"], editor: ["text-editor", "editor"],
    code: ["code", "editor"], highlight: ["markdown", "code"], syntax: ["code", "markdown"],
    filter: ["filter", "exclude"], exclude: ["exclude", "filter"], remove: ["exclude", "filter"],
    unique: ["select-unique", "select"], many: ["select-many", "select"],
    select: ["select", "select-one"], picker: ["select", "select-one"],
    loop: ["loop", "consumable", "repeat"], repeat: ["loop", "consumable"],
    number: ["number", "integer", "decimal"], integer: ["integer", "number"],
    decimal: ["decimal", "number"], maths: ["number", "sum", "decimal"],
    sum: ["sum", "number"], word: ["words", "numeral"], words: ["words", "numeral"],
    letters: ["words", "numeral"], roman: ["roman", "numeral"], ordinal: ["ordinals", "ordinal"],
    name: ["name", "pronoun", "gender"], names: ["name"], pronoun: ["pronoun", "gender"],
    gender: ["gender", "pronoun"], plural: ["plural"], title: ["title-case"],
    markov: ["markov"], typewriter: ["typewriter"], typing: ["typewriter"],
    wheel: ["wheel", "random"], spinner: ["wheel", "random"], roulette: ["wheel", "random"],
    dice: ["dice", "random"], roll: ["dice", "random"], table: ["table", "roll-table"],
    tornado: ["tornado", "nuke"], nuke: ["tornado"], explosion: ["tornado"],
    iframe: ["iframe", "embed"], embed: ["embed", "iframe"], frame: ["iframe", "embed"],
    audio: ["audio", "sound"], background: ["background"], wallpaper: ["background-image"],
    keyboard: ["press-enter", "key"], keypress: ["press-enter"], click: ["tap", "tap-anywhere"],
    tap: ["tap"], touch: ["tap"], drag: ["drag", "layout-maker"],
    stats: ["stats"], analytics: ["stats"], counter: ["stats", "number"],
    wikipedia: ["wiki"], wiki: ["wiki"], dictionary: ["wiki", "words"],
    spreadsheet: ["google-sheets", "sheet"], sheet: ["google-sheets", "sheet"],
    convert: ["base64", "conjugate", "numeral"], encode: ["base64", "encode"],
    decode: ["base64", "decode"], base64: ["base64"], parse: ["parser", "parse"]
  };

  function stopword(w) {
    return w.length < 2 ||
      "the a an to of and or for with in on is are was it that this my your you i we they he she be as at by can could would should will from into out up do does did how what when where which who why use using used want need make made get got add adds all any not but if then than so such there here have has had more most some".split(" ").indexOf(w) !== -1;
  }

  function tokenize(s) {
    var raw = String(s == null ? "" : s).toLowerCase().split(/[^a-z0-9]+/);
    var out = [];
    for (var i = 0; i < raw.length; i++) if (raw[i] && !stopword(raw[i])) out.push(raw[i]);
    return out;
  }

  function variants(t) {
    var v = [t];
    if (t.length > 3) {
      if (/ies$/.test(t)) v.push(t.replace(/ies$/, "y"));
      if (/es$/.test(t)) v.push(t.slice(0, -2));
      if (/s$/.test(t)) v.push(t.slice(0, -1));
      if (/ing$/.test(t)) v.push(t.slice(0, -3));
      if (/ed$/.test(t)) v.push(t.slice(0, -2));
      if (/er$/.test(t)) v.push(t.slice(0, -2));
    }
    return v;
  }

  function expand(tokens) {
    var seen = {};
    var out = [];
    for (var i = 0; i < tokens.length; i++) {
      var t = tokens[i];
      var all = variants(t);
      var syn = SYNONYMS[t];
      if (syn) all = all.concat(syn);
      for (var j = 0; j < all.length; j++) {
        var w = all[j];
        if (w && w.length > 1 && !seen[w]) { seen[w] = 1; out.push(w); }
      }
    }
    return out;
  }

  function scoreEntry(p, tokens) {
    var name = (p.name || "").toLowerCase();
    var desc = (p.desc || "").toLowerCase();
    var exp = (p.exposes || []).join(" ").toLowerCase();
    var imp = (p.imports || []).join(" ").toLowerCase();
    var score = 0;
    var hits = [];
    for (var i = 0; i < tokens.length; i++) {
      var t = tokens[i];
      var s = 0;
      if (name === t) s = 60;
      else if (name.split("-").indexOf(t) !== -1) s = 34;
      else if (name.indexOf(t) !== -1) s = 16;
      else if (exp.indexOf(t) !== -1) s = 10;
      else if (desc.indexOf(t) !== -1) s = 5;
      else if (imp.indexOf(t) !== -1) s = 4;
      if (s) { score += s; if (hits.indexOf(t) === -1) hits.push(t); }
    }
    if (p.official && score > 0) score += 2;
    return { score: score, hits: hits };
  }

  function fallbackTextarea(text, done) {
    var ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand("copy"); } catch (e) {}
    document.body.removeChild(ta);
    if (done) done();
  }

  function readPreloaded() {
    try {
      var el = document.getElementById("preloaded-generator-data");
      if (!el) return null;
      return JSON.parse(el.textContent);
    } catch (e) { return null; }
  }

  window.createPluginTools = function () {
    var enabled = null;             // array of names; null until loaded
    var indexPromise = null;        // Promise<registry object>
    var known = null;               // {name: 1} once the registry has loaded
    var listeners = [];
    var CHANGE_EVENT = "perchance-plugins-changed";

    function emit() {
      var detail = { enabled: enabled ? enabled.slice() : [], source: "tools" };
      for (var i = 0; i < listeners.length; i++) {
        try { listeners[i](detail); } catch (e) {}
      }
      try { window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: detail })); } catch (e) {}
    }

    function load() {
      if (enabled) return enabled;
      var arr = [];
      try { arr = JSON.parse(window.localStorage.getItem(ENABLED_KEY) || "[]"); } catch (e) { arr = []; }
      if (!Array.isArray(arr)) arr = [];
      var clean = [];
      for (var i = 0; i < arr.length; i++) if (typeof arr[i] === "string" && clean.indexOf(arr[i]) === -1) clean.push(arr[i]);
      enabled = clean;
      return enabled;
    }

    function persist(arr) {
      enabled = arr;
      try { window.localStorage.setItem(ENABLED_KEY, JSON.stringify(arr)); } catch (e) {}
      emit();
      return arr.slice();
    }

    function selfName() { return window.generatorName || "i-am-canceled-presents-all-of-the-plugins"; }

    function selfEntry() {
      return {
        name: selfName(),
        url: "https://perchance.org/" + selfName(),
        official: false,
        desc: SELF_DESC,
        hasHtml: true,
        imports: [],
        codeBytes: 0,
        codeLines: 0,
        htmlBytes: 0,
        exposes: ["pluginTools()", "window.perchancePluginTools"],
        self: true,
        added: "self"
      };
    }

    function registry() {
      if (!indexPromise) {
        indexPromise = fetch(INDEX_URL + "?cb=" + Date.now())
          .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
          .then(function (j) {
            var list = Array.isArray(j.plugins) ? j.plugins.slice() : [];
            var self = selfEntry();
            if (!list.some(function (p) { return p.name === self.name; })) {
              var at = 0;
              while (at < list.length && String(list[at].name).toLowerCase() < self.name) at++;
              list.splice(at, 0, self);
            }
            j.plugins = list;
            known = {};
            for (var i = 0; i < list.length; i++) known[list[i].name] = 1;
            // drop any toggled name that isn't a real plugin (typos, removed plugins)
            var cur = load();
            var kept = cur.filter(function (n) { return known[n]; });
            if (kept.length !== cur.length) persist(kept);
            return j;
          });
      }
      return indexPromise;
    }

    function decorate(p) {
      var e = load();
      var out = {};
      for (var k in p) if (Object.prototype.hasOwnProperty.call(p, k)) out[k] = p[k];
      out.enabled = e.indexOf(p.name) !== -1;
      out.importLine = p.name + " = {import:" + p.name + "}";
      return out;
    }

    function find(reg, name) {
      if (!name) return null;
      var want = String(name).trim().replace(/^\{?import\s*:?\s*/i, "").replace(/\}?$/, "").trim();
      for (var i = 0; i < reg.plugins.length; i++) if (reg.plugins[i].name === want) return reg.plugins[i];
      for (var j = 0; j < reg.plugins.length; j++) if (reg.plugins[j].name.toLowerCase() === want.toLowerCase()) return reg.plugins[j];
      return null;
    }

    var api = {
      VERSION: 1,
      INDEX_URL: INDEX_URL,
      ENABLED_KEY: ENABLED_KEY,

      /* ---- registry ---- */
      ready: function () { return registry().then(function (j) { return j.plugins; }); },
      index: function () { return registry(); },
      plugins: function (opts) {
        return registry().then(function (reg) {
          opts = opts || {};
          var out = reg.plugins.map(decorate);
          if (opts.enabled) out = out.filter(function (p) { return p.enabled; });
          if (opts.official === true) out = out.filter(function (p) { return p.official; });
          if (opts.official === false) out = out.filter(function (p) { return !p.official; });
          if (opts.q) {
            var toks = expand(tokenize(opts.q));
            out = out.map(function (p) { var s = scoreEntry(p, toks); p.score = s.score; p.hits = s.hits; return p; })
                     .filter(function (p) { return p.score > 0; })
                     .sort(function (a, b) { return b.score - a.score || (b.official ? 1 : 0) - (a.official ? 1 : 0); });
          }
          if (opts.limit) out = out.slice(0, opts.limit);
          return out;
        });
      },
      names: function () {
        return registry().then(function (reg) { return reg.plugins.map(function (p) { return p.name; }); });
      },
      info: function (name) {
        return registry().then(function (reg) {
          var p = find(reg, name);
          if (!p) return null;
          var d = decorate(p);
          d.help = "Toggle with perchancePluginTools.enable('" + p.name + "') / .disable('" + p.name + "') / .toggle('" + p.name + "').";
          return d;
        });
      },
      search: function (query, limit) { return api.plugins({ q: query, limit: limit || 15 }); },
      suggest: function (description, limit) {
        return registry().then(function (reg) {
          var toks = expand(tokenize(description));
          if (!toks.length) return [];
          return reg.plugins.map(function (p) {
            var s = scoreEntry(p, toks);
            var d = decorate(p);
            d.score = s.score;
            d.why = s.hits.slice(0, 6);
            return d;
          }).filter(function (p) { return p.score > 0; })
            .sort(function (a, b) { return b.score - a.score || (b.official ? 1 : 0) - (a.official ? 1 : 0); })
            .slice(0, limit || 8);
        });
      },
      stats: function () {
        return registry().then(function (reg) {
          var e = load();
          return {
            total: reg.plugins.length,
            official: reg.plugins.filter(function (p) { return p.official; }).length,
            community: reg.plugins.filter(function (p) { return !p.official; }).length,
            enabled: e.length,
            enabledNames: e.slice()
          };
        });
      },

      /* ---- source (works for the inspector's own self entry too) ---- */
      source: function (name, file) {
        file = file === "index.html" ? "index.html" : "main.pjs";
        return registry().then(function (reg) {
          var p = find(reg, name);
          if (!p) throw new Error("unknown plugin: " + name);
          if (p.self) {
            var pre = readPreloaded();
            if (!pre) throw new Error("self source unavailable");
            return file === "main.pjs" ? (pre.modelText || "") : (pre.outputTemplate || "");
          }
          return fetch("src/plugins/" + encodeURIComponent(p.name) + "/" + file + "?cb=" + Date.now())
            .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.text(); });
        });
      },

      /* ---- toggles (synchronous, localStorage-backed) ---- */
      isEnabled: function (name) { return load().indexOf(String(name)) !== -1; },
      enabled: function () { return load().slice(); },
      enable: function (name) {
        var n = String(name);
        if (known && !known[n]) return false; // not a plugin in the registry
        var e = load();
        if (e.indexOf(n) === -1) persist(e.concat([n]));
        return true;
      },
      disable: function (name) {
        var e = load();
        var i = e.indexOf(String(name));
        if (i !== -1) persist(e.slice(0, i).concat(e.slice(i + 1)));
        return false;
      },
      toggle: function (name) { return api.isEnabled(name) ? api.disable(name) : api.enable(name); },
      setEnabled: function (names) {
        var clean = [];
        (names || []).forEach(function (n) {
          n = String(n);
          if (clean.indexOf(n) === -1 && (!known || known[n])) clean.push(n);
        });
        return persist(clean);
      },
      enableMany: function (names) { return api.setEnabled(api.enabled().concat(names || [])); },
      disableMany: function (names) {
        var drop = (names || []).map(String);
        return api.setEnabled(api.enabled().filter(function (n) { return drop.indexOf(n) === -1; }));
      },
      clear: function () { return persist([]); },
      importLines: function (names) {
        var list;
        if (names && names.length) {
          list = names.map(String);
        } else if (names === true) {
          list = api.enabled();
        } else {
          list = api.enabled();
        }
        list = list.filter(function (n, i) { return list.indexOf(n) === i; });
        list.sort();
        return list.map(function (n) { return n + " = {import:" + n + "}"; }).join("\n");
      },
      copyImportLines: function (names, done) {
        var text = api.importLines(names);
        if (navigator.clipboard && window.isSecureContext) {
          navigator.clipboard.writeText(text).then(function () { if (done) done(true); })
            .catch(function () { fallbackTextarea(text, function () { if (done) done(true); }); });
        } else fallbackTextarea(text, function () { if (done) done(true); });
        return text;
      },

      /* ---- change notifications (used by the inspector UI) ---- */
      onChange: function (fn) {
        if (typeof fn !== "function") return function () {};
        listeners.push(fn);
        return function () { var i = listeners.indexOf(fn); if (i !== -1) listeners.splice(i, 1); };
      },
      offChange: function (fn) { var i = listeners.indexOf(fn); if (i !== -1) listeners.splice(i, 1); },
      emit: emit,

      /* ---- self-documentation for the AI helper ---- */
      help: function () {
        return [
          "perchancePluginTools — no-import plugin registry tool (v" + api.VERSION + ")",
          "",
          "It is already on window: `const t = window.perchancePluginTools;` — no {import:} needed.",
          "",
          "DISCOVER",
          "  await t.suggest(\"let visitors leave comments\")  -> ranked matches, each with .name/.why/.importLine/.enabled",
          "  await t.search(\"image\", 10)                     -> same, plain keyword search",
          "  await t.plugins({q,official,enabled,limit})      -> filter the whole registry",
          "  await t.info(name)                               -> one entry + .help + .importLine",
          "  await t.stats()                                  -> {total,official,community,enabled,enabledNames}",
          "  await t.names()                                  -> every plugin name",
          "",
          "SOURCE (read before deciding — no import required)",
          "  await t.source(name, \"main.pjs\"|\"index.html\")   -> the plugin's raw source text",
          "",
          "TOGGLE (synchronous, persisted in localStorage)",
          "  t.toggle(name) / t.enable(name) / t.disable(name) -> new boolean state (unknown names are rejected)",
          "  t.enabled()                                      -> array of enabled names",
          "  t.setEnabled([names]) / t.enableMany([...]) / t.disableMany([...]) / t.clear()",
          "  t.importLines()                                  -> paste-ready \"name = {import:name}\" block",
          "  t.onChange(fn)                                   -> subscribe; returns an unsubscribe fn",
          "",
          "Typical flow: suggest(...) -> skim source() of the top hits -> toggle(true) the ones you want -> importLines()."
        ].join("\n");
      }
    };

    return api;
  };
})();
