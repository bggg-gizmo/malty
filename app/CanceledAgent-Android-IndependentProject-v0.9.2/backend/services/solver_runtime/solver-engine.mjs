// src/solver.js — deterministic Moltbook math-challenge solver (v6+).
// Ported from the proven in-worker solver (see scratch/reserve-credit-doctrine.md §6).
// Pure, no network. parseChallenge(text) -> {a,b,op,answer,sure:true}
// or {unsure:true, reason}. NEVER guess: unsure means ghost + repost, blind.

const escRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// Fuzzy word matcher: each letter 1+ times, [^a-z0-9]* allowed between
// letters (handles doubled letters AND inserted symbols/spaces), with
// letter-boundary guards so "one" never matches inside "someone".
function fuzz(word) {
  const inner = word.toLowerCase().split("").map((ch) => escRe(ch) + "+").join("[^a-z0-9]*");
  return new RegExp("(?:^|[^a-z0-9])" + inner + "s*(?![a-z0-9])", "gi");
}
function fuzzHere(word, window) {
  const re = fuzz(word);
  re.lastIndex = 0;
  return re.test(window);
}
function allMatches(re, t) {
  const out = [];
  re.lastIndex = 0;
  let m;
  while ((m = re.exec(t))) {
    if (m[0].length === 0) { re.lastIndex++; continue; }
    out.push({ ix: m.index, len: m[0].length });
    if (out.length > 200) break;
  }
  return out;
}

// v7.27 (burn #34): vowel-tolerant rate matcher. fuzz() dies on inserted
// vowels ("cEmSs", "sEcUm") because [^a-z0-9]* can't cross letters — and
// the right-boundary guard dies on glued suffixes. Both holes bit live
// 2026-10-22: "23 cEmSs pEr sEcUm ... 7 cEmS pEr sEcUm added" fired
// per-unit 23x7=161.00, verified WRONG (true 23+7=30). Letters in order
// with only vowels/symbols between, left-boundaried (so plain-English
// interiors like "mechanism" can't match — the consonant before the
// anchor letter breaks the guard), no right guard (so glued suffixes
// match). Used ONLY for rate-context detection (three sites), never for
// figure parsing — a false trip costs a ghost, never trust.
function fuzzLoose(word, window) {
  const inner = word.toLowerCase().split("").map((ch) => escRe(ch) + "+").join("(?:[aeiou]+|[^a-z0-9]+)*");
  const re = new RegExp("(?:^|[^a-z0-9])" + inner, "gi");
  re.lastIndex = 0;
  return re.test(window);
}

const ONES = {
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7,
  eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13,
  fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19,
};
const TENS = {
  twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60,
  seventy: 70, eighty: 80, ninety: 90,
};
const RATE_WORDS = ["second", "minute", "hour", "day", "week", "month", "year", "time", "newton", "meter", "force", "gram", "kilo", "speed", "watt", "cm", "mm", "km", "sec", "min", "hr"];
// Rate context for the heterogeneous-unit guard: every rate word EXCEPT the
// anchor units themselves (a figure followed by "newtons" is the normal case).
const RATE_CONTEXT = RATE_WORDS.filter((w) => w !== "newton" && w !== "force");

// Collect merged numbers with char spans. Longest-match + boundary rules
// keep camouflage ("teN" inside "split/ten" still matches — callers beware).
function collectNumbers(text) {
  const t = text.toLowerCase();
  const hits = [];
  for (const [w, v] of Object.entries(ONES)) {
    for (const m of allMatches(fuzz(w), t)) hits.push({ v, ix: m.ix, len: m.len, kind: "one" });
  }
  for (const [w, v] of Object.entries(TENS)) {
    for (const m of allMatches(fuzz(w), t)) hits.push({ v, ix: m.ix, len: m.len, kind: "ten" });
  }
  for (const m of allMatches(fuzz("hundred"), t)) hits.push({ v: 100, ix: m.ix, len: m.len, kind: "hundred" });
  for (const m of allMatches(fuzz("thousand"), t)) hits.push({ v: 1000, ix: m.ix, len: m.len, kind: "thousand" });
  const dre = /(?:^|[^a-z0-9])(-?\d+(?:\.\d+)?)(?![a-z0-9])/gi;
  let dm;
  while ((dm = dre.exec(t))) {
    if (dm[1] === undefined) continue;
    hits.push({ v: parseFloat(dm[1]), ix: dm.index, len: dm[0].length, kind: "digit" });
    if (hits.length > 400) break;
  }
  hits.sort((a, b) => a.ix - b.ix || b.len - a.len);
  // dedupe overlaps: keep longest starting at each position
  const clean = [];
  for (const h of hits) {
    const clash = clean.find((c) => h.ix < c.ix + c.len && c.ix < h.ix + h.len);
    if (!clash) clean.push(h);
  }
  clean.sort((a, b) => a.ix - b.ix);
  // accumulate with tens+small-ones fusion ("twenty five", "thirtytwo")
  const out = [];
  let total = 0, cur = 0, curStart = 0, curEnd = 0, curIsTens = false;
  let prevKind = null, prevV = null, prevEnd = -100;
  const push = () => {
    if (cur !== 0 || total !== 0) {
      out.push({ value: total + cur, start: curStart, end: curEnd });
      total = 0; cur = 0; curIsTens = false;
    }
  };
  for (const h of clean) {
    if (h.kind === "digit") { push(); out.push({ value: h.v, start: h.ix, end: h.ix + h.len }); curIsTens = false; prevKind = h.kind; prevV = h.v; prevEnd = h.ix + h.len; continue; }
    if (h.kind === "hundred") { cur = (cur || 1) * 100; curEnd = h.ix + h.len; curIsTens = false; prevKind = h.kind; prevV = h.v; prevEnd = h.ix + h.len; continue; }
    if (h.kind === "thousand") { total += (cur || 1) * 1000; cur = 0; curEnd = h.ix + h.len; curIsTens = false; prevKind = h.kind; prevV = h.v; prevEnd = h.ix + h.len; continue; }
    if (h.kind === "ten") {
      // v7.2 stutter guard: same tens word twice adjacent ("forty forty-two")
      // is a disfluency, not two figures — drop the echo. v7.4: extend the
      // current span across the echo so a following ones still fuses
      // ("forty forty two" → 42, not [40, 2] which used to sum right by luck
      // and now trips the N≥3 veto).
      if (prevKind === "ten" && prevV === h.v && h.ix - prevEnd <= 3) {
        curEnd = h.ix + h.len;
        prevKind = h.kind; prevV = h.v; prevEnd = h.ix + h.len;
        continue;
      }
      if (cur !== 0 || total !== 0) push();
      cur = h.v; curStart = h.ix; curEnd = h.ix + h.len; curIsTens = true;
      prevKind = h.kind; prevV = h.v; prevEnd = h.ix + h.len;
      continue;
    }
    // one
    if (curIsTens && h.v < 10 && h.ix - curEnd <= 3) {
      cur += h.v; curEnd = h.ix + h.len; curIsTens = false;
      prevKind = h.kind; prevV = h.v; prevEnd = h.ix + h.len;
      continue; // fused
    }
    if (cur !== 0 || total !== 0) push();
    cur = h.v; curStart = h.ix; curEnd = h.ix + h.len; curIsTens = false;
    prevKind = h.kind; prevV = h.v; prevEnd = h.ix + h.len;
  }
  push();
  // v7.31 (burn #38, live 2026-10-22): "and a half" fractions. "THIRTY
  // TWO * TWO AND A HALF ... TOTAL FORCE" parsed [32,2] → 64.00, verified
  // WRONG (true 32x2.5=80): the fractional tail never attached to its
  // number. A parsed figure immediately followed (gap <=20 chars) by
  // "and (a|an)? half|quarter" absorbs 0.5/0.25 and extends its span so
  // the figure-integrity guards treat the fraction as consumed.
  // Camouflage-tolerant via letter-order matching; the article is
  // optional ("and half" attaches too). Right-boundaried so "halftime"
  // never trips it. Bare "half"/"quarter" with no preceding figure is
  // NOT a number — narrow fix, no new figure class.
  {
    const letters = (w) => w.toLowerCase().split("").map((ch) => escRe(ch) + "+").join("[^a-z0-9]*");
    const fracRe = new RegExp(
      "^[^a-z0-9]*" + letters("and") + "[^a-z0-9]*" + "(?:a[n]?[^a-z0-9]*)?" +
      "(" + letters("half") + "|" + letters("quarter") + ")s*(?![a-z0-9])",
      "i"
    );
    for (const n of out) {
      const win = t.slice(n.end, Math.min(t.length, n.end + 20));
      const m = fracRe.exec(win);
      if (!m) continue;
      n.value += /^\s*q/i.test(m[1]) ? 0.25 : 0.5;
      // round to 2dp to keep float dust out of answers
      n.value = Math.round(n.value * 100) / 100;
      n.end += m.index + m[0].length;
    }
  }
  return out;
}

function perUnitSignal(t) {
  // Strong signals always count; bare "per" only when no rate/unit word
  // follows within ~20 chars (fuzzy-matched — camouflage-proof).
  for (const w of ["each", "every", "apiece"]) {
    const ms = allMatches(fuzz(w), t);
    if (ms.length) return { ix: ms[0].ix, word: w };
  }
  for (const m of allMatches(fuzz("per"), t)) {
    const window = t.slice(m.ix, m.ix + 24);
    // v7.27 (burn #34): the strict after-window is blind to inserted-vowel
    // units ("sEcUm") — fuzz can't cross the vowel, the right guard dies
    // on the glued suffix. Vowel-tolerant check alongside (loose misses
    // nothing strict catches; both only disqualify, never solve).
    if (!RATE_WORDS.some((r) => fuzzHere(r, window)) && !RATE_WORDS.some((r) => fuzzLoose(r, window))) return { ix: m.ix, word: "per" };
  }
  return null;
}

function nearestTwo(nums, ix) {
  if (nums.length < 2) return null;
  const sorted = [...nums].sort((a, b) => Math.abs(a.start - ix) - Math.abs(b.start - ix));
  return [sorted[0], sorted[1]];
}

const FAMS = {
  divide: ["divided", "divides", "division", "split", "shared among"],
  multiply: ["multipli", "times", "product", "repeat", "fold", "folds", "double", "doubling", "triple", "impulse", "multiply", "multiplies", "multiplied"],
  minus: ["minus", "subtract", "slows", "slowed", "slow", "loses", "lost", "lose", "loss", "drops", "dropped", "drop", "falls", "fell", "fall", "decreases", "decreased", "decreasing", "remains", "remain", "remaining", "reduces", "reduced", "reducing", "takes away", "gives away", "gave away", "leaves", "left over"],
  plus: ["plus", "adds", "added", "add", "gains", "gained", "gain", "grows", "grew", "grow", "increases", "increased", "increasing", "combined", "joins", "collects", "finds", "accelerate"],
};
function famHit(t, words) {
  return words.some((w) => fuzzHere(w, t));
}

// v7.12 (burn #17): loose verb-tripwire. Inserted-vowel camouflage
// ("MuLtiIiipL eS") defeats the strict fuzzy family match, leaving the
// solver blind to a multiply verb it cannot see — then plus/total
// fallbacks answer confidently wrong (32+3=35, verified WRONG live
// 2026-10-19). Letter-soup tripwires (letters in order, anything
// between, no boundary guards) catch the shape the strict matchers
// miss. Fires ONLY when the strict multiply family did NOT hit — clean
// shapes are untouched. A false trip costs a ghost, never trust.
function looseSoup(t, letters) {
  // v7.14: each letter 1+ times — camouflage doubles letters ("DuBbLeS",
  // "tw]enny"), and single-letter soups go blind on the doubles.
  const pat = letters.split("").map((ch) => escRe(ch) + "+").join("[^a-z]*");
  const re = new RegExp(pat, "gi");
  re.lastIndex = 0;
  return re.test(t);
}
function unseenMultiply(t) {
  if (famHit(t, FAMS.multiply)) return false;
  // v7.14 (burn #20): "DuBbLeS" drops the "o", sailing past both the
  // strict matcher and the "doubl" soup. O-less variant added (false
  // trips cost ghosts, never trust; battery-guarded).
  return ["mult", "times", "fold", "doubl", "dubl", "tripl", "product", "repeat", "impulse", "boost"].some((s) => looseSoup(t, s));
}

// v7.14 (burn #19): fragmented tens. "tW]eNnY ThR-eE" lost its T, so
// strict tens matching failed and the orphan ones-word parsed as 3 —
// then 3+7=10 verified WRONG (true 23+7=30). A parsed ones-figure with
// a tens-attempt soup (each tens word minus each single letter) in the
// ~14 chars before it, unconsumed by any parsed number, is suspect.
// Clean shapes are safe: strict tens matches consume their region, and
// standalone ones-figures have no tens-attempt nearby.
const TENS_STEMS = ["twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];
function orphanOnes(t, nums) {
  for (const n of nums) {
    if (n.value < 1 || n.value > 9) continue;
    const base = Math.max(0, n.start - 14);
    const win = t.slice(base, n.start);
    for (const w of TENS_STEMS) {
      const variants = [];
      for (let d = 0; d < w.length; d++) variants.push(w.slice(0, d) + w.slice(d + 1));
      // v7.20 (bytes exactness sweep 2026-10-20): up to TWO deletions —
      // double-t damage ("twenty"→"weny", "thirty"→"hiry") escapes the
      // single-deletion soup and solves wrong with all vetoes on (20.00
      // true 40.00; 16.00 true 46.00). Same consumed-guard protects clean.
      for (let d = 0; d < w.length; d++) for (let e = d + 1; e < w.length; e++) variants.push(w.slice(0, d) + w.slice(d + 1, e) + w.slice(e + 1));
      for (const variant of variants) {
        // v7.20b: variants shorter than 4 chars match plain English ("sit"
        // from sixty-minus-xy hits "s it", regressing v7.10). The old soup's
        // floor was 4 (shortest single-deletion); double-deletions keep it.
        if (variant.length < 4) continue;
        // v7.14: 1+ per letter — the fragment may also double letters
        // ("tw]enny" is minus-T plus doubled-N).
        const pat = variant.split("").map((ch) => escRe(ch) + "+").join("[^a-z]*");
        const re = new RegExp(pat, "gi");
        re.lastIndex = 0;
        const m = re.exec(win);
        if (m) {
          const absIx = base + m.index;
          const consumed = nums.some((o) => absIx >= o.start - 2 && absIx <= o.end);
          if (!consumed) return true;
        }
      }
    }
  }
  return false;
}

// v7.16 (burn #23): letter-attached ones beside tens. "thirty qSiX
// newtons + fourteen, total" parsed as [30, 14] → 44.00, verified WRONG
// (true 36+14=50). The attached "q" defeats the strict boundary guard,
// so "six" never parses and the tens never fuses — a silent figure loss,
// same poison class as burn #19's fragmented tens. A parsed tens-figure
// (20–90) followed within ~12 chars by a ones-word attempt with an
// attached single-letter/digit prefix (no space gap — a detached prefix
// would leave a strict-matchable word, which either fuses correctly or
// parses as its own figure) is suspect. Prefix↔word gap allows symbols
// only, never spaces: clean text ("thirty and five", "to six", "d five")
// always spaces its words, so it can never trip. Matches consumed by a
// parsed number are ignored (clean "thirty five" fuses; the match sits
// inside the fused span). A trip costs a ghost, never trust.
const ONES_SMALL = ["one", "two", "three", "four", "five", "six", "seven", "eight", "nine"];
function tensOrphan(t, nums) {
  for (const n of nums) {
    if (n.value < 20 || n.value > 90 || n.value % 10 !== 0) continue;
    const base = n.end;
    const win = t.slice(base, Math.min(t.length, base + 12));
    for (const w of ONES_SMALL) {
      const inner = w.split("").map((ch) => escRe(ch) + "+").join("[^a-z]*");
      const re = new RegExp("[a-z0-9][^a-z0-9 ]*" + inner, "gi");
      re.lastIndex = 0;
      const m = re.exec(win);
      if (m) {
        const absIx = base + m.index;
        const consumed = nums.some((o) => absIx >= o.start - 2 && absIx <= o.end);
        if (!consumed) return true;
      }
    }
  }
  return false;
}

// v7.21 (burn #26): truncated teens after tens — same poison class as #23.
// See the gate-part-3 call site for the live shape. Left-boundary guarded
// (damage starts at word starts; plain-English interiors never match) with
// the standard consumed-guard for intact parses.
const TEENS = ["ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"];
function tensTeens(t, nums) {
  for (const n of nums) {
    if (n.value < 20 || n.value > 90 || n.value % 10 !== 0) continue;
    const base = n.end;
    const win = t.slice(base, Math.min(t.length, base + 12));
    for (const w of TEENS) {
      const variants = [w];
      for (let d = 0; d < w.length; d++) variants.push(w.slice(0, d) + w.slice(d + 1));
      for (const variant of variants) {
        if (variant.length < 3) continue;
        const inner = variant.split("").map((ch) => escRe(ch) + "+").join("[^a-z]*");
        const re = new RegExp("(?:^|[^a-z0-9])(" + inner + ")", "gi");
        re.lastIndex = 0;
        const m = re.exec(win);
        if (m) {
          const absIx = base + m.index + m[0].indexOf(m[1]);
          const consumed = nums.some((o) => absIx >= o.start - 2 && absIx <= o.end);
          if (!consumed) return true;
        }
      }
    }
  }
  return false;
}

// v7.22 (burn #27): damaged teens with substituted letters — same poison
// class as #23/#26, new damage shape. Live 2026-10-20: "tHiRrTy SpAcE
// tWo nEeOotOns ... InCrEaSeS bY fOuRlEeN" fused [30,2]=32 and never
// parsed fourteen ("fourleen", T replaced by L), answering 32.00 with the
// verb unfired (true 32+14=46). Deletion-soup and truncation gates cannot
// see a substitution, so: any long word starting with a teen-prefix stem
// and carrying an een/ee tail that no collected figure consumed is a
// damaged teen — unsure. Clean teens parse and consume their span (guard
// passes); ordinary words ("between", "green", "thirsty") never match the
// prefix+tail conjunction.
const TEEN_PREFIX = ["thir", "four", "fift", "seven", "eigh", "eight", "nine", "eleven", "twelve"];
function damagedTeen(t, nums) {
  const re = /[a-z]{6,}/gi;
  let m;
  while ((m = re.exec(t))) {
    const w = m[0].toLowerCase();
    if (!TEEN_PREFIX.some((p) => w.startsWith(p))) continue;
    if (!(w.includes("een") || w.endsWith("ee"))) continue;
    const consumed = nums.some((o) => m.index >= o.start - 2 && m.index <= o.end);
    if (!consumed) return true;
  }
  return false;
}

// v7.26 (burn #33): glued-suffix ones/teens beside tens — same poison
// class as #23/#26, new damage shape. Live 2026-10-22: "ThIrTy FiV eUm,
// Um ... TwElVe ... total" parsed [30,12] → 42.00, verified WRONG (true
// 35+12=47). The five is split by a space AND glued to trailing noise
// ("eUm"), so strict boundary matching never sees it — a silent figure
// loss. Checking in the ~12 chars after a parsed tens-figure: a ones/teen
// word with internal damage (symbols/spaces between letters, as fuzz)
// immediately followed by a letter (no right boundary — clean text always
// spaces its words, so a glued suffix is damage by definition),
// unconsumed by any parsed number, is suspect. The no-space-before-suffix
// requirement protects clean prose ("five umbrellas" has a space; "five-
// minute" a hyphen); the consumed guard protects clean parses ("thirty
// five" fuses; "thirty fivefold" never occurs outside damage).
function tensGlued(t, nums) {
  for (const n of nums) {
    if (n.value < 20 || n.value > 90 || n.value % 10 !== 0) continue;
    const base = n.end;
    const win = t.slice(base, Math.min(t.length, base + 12));
    for (const w of [...ONES_SMALL, ...TEENS]) {
      if (w.length < 3) continue;
      const inner = w.split("").map((ch) => escRe(ch) + "+").join("[^a-z]*");
      const re = new RegExp("(?:^|[^a-z0-9])" + inner + "[a-z]", "gi");
      re.lastIndex = 0;
      const m = re.exec(win);
      if (m) {
        const absIx = base + m.index + m[0].length - 1;
        const consumed = nums.some((o) => absIx >= o.start - 2 && absIx <= o.end);
        if (!consumed) return true;
      }
    }
  }
  return false;
}

// v7.29 (burn #36): spaced-interior ones/teens fused with tens — same
// poison class as #23/#26/#27/#33. Live 2026-10-21: "fIfTy fiV E]
// nEuWtO ns ... tWeNtY thReE ... total" parsed [55,23] → 78.00,
// verified WRONG. "fiV E" matches fuzz("five") (separators allowed
// between letters) and fuses with fifty — but a separator INSIDE the
// word means the figure never existed as a word, so the fused 55 is
// untrustworthy (true unknowable: 73? 28?). Clean fusions are safe:
// intact ones-words carry no interior separators, so only damage trips.
// Letter-doubling ("ffoour") is NOT damage — letters only — and keeps
// solving. Fused-guard (damaged span consumed inside a tens+ones
// figure) keeps stray cross-word matches out of clean text.
function tensSpaced(t, nums) {
  for (const w of [...ONES_SMALL, ...TEENS]) {
    if (w.length < 3) continue;
    for (const m of allMatches(fuzz(w), t)) {
      let s = t.slice(m.ix, m.ix + m.len);
      if (s && /[^a-z0-9]/.test(s[0])) s = s.slice(1); // leading boundary
      s = s.replace(/s+$/, ""); // plural tail
      if (!/[^a-z0-9]/.test(s)) continue; // intact word: clean
      const fused = nums.some((o) => o.value > 20 && o.value % 10 !== 0 && m.ix >= o.start && m.ix + m.len <= o.end + 2);
      if (fused) return true;
    }
  }
  return false;
}

function tensSubst(t, nums) {
  // v7.34 (burn #40, live 2026-10-26): separator skip is any non-letter
  // run, not spaces-only. Camouflage puts brackets/pipes between tens and
  // the damaged token ("[fOrTy] fIfE}"), which the old spaces-only skip
  // could not cross — the guard never ran and 40 stood false (50.00
  // answered, true 45+10=55). Only damaged tokens trip (distance exactly
  // 1); intact words (distance 0) and consumed fusions pass, so clean
  // shapes are untouched.
  for (const n of nums) {
    if (n.value < 20 || n.value > 90 || n.value % 10 !== 0) continue;
    let ix = n.end;
    while (ix < t.length && /[^a-z]/.test(t[ix])) ix++;
    if (ix >= t.length) continue;
    let j = ix;
    while (j < t.length && /[a-z]/.test(t[j])) j++;
    // v7.28b: camouflage doubles letters ("YyOoUuR" lowercases to
    // "yyoouur", length 7 — no direct length match). Collapse runs first;
    // clean words survive collapsing ("feel"→"fel" matches nothing).
    const tok = t.slice(ix, j).replace(/(.)\1+/g, "$1");
    for (const w of [...ONES_SMALL, ...TEENS]) {
      if (w.length < 3 || tok.length !== w.length) continue;
      let diff = 0;
      for (let k = 0; k < w.length; k++) if (tok[k] !== w[k]) { diff++; if (diff > 1) break; }
      if (diff !== 1) continue;
      const consumed = nums.some((o) => ix >= o.start - 2 && ix <= o.end);
      if (!consumed) return true;
    }
  }
  return false;
}

// v7.32 (burn #39): inserted-letter ones/teens beside tens — same poison
// class as #23/#26/#27/#33/#35/#36, new damage shape. Live 2026-10-22:
// "TwEnTy FoIvE nEu-ToNs ... FiFtEeN nEuToNs ... ToTaL" parsed [20,15]
// → 35.00, verified WRONG (true 25+15=40): "FoIvE" is "five" with an
// inserted O, so strict/fuzzy matching never sees it, the tens never
// fuses, and 20 stands as a false figure. Same position as tensSubst
// (tens figure, spaces-only, alpha token) but single-DELETION distance:
// token length is w.length+1 and deleting exactly one char yields a
// ones/teen word. w.length ≥ 4 only: short-word deletion neighbors ARE
// common English ("then"→"ten" would eat clean prose), while
// insertion-neighbors of longer number-words never occur cleanly right
// after tens in challenge-land. Plurals ("fives") strict-parse and are
// consumed, so the consumed guard protects them. Disqualifies only,
// never solves.
function tensInserted(t, nums) {
  // v7.34 (burn #40 twin fix): same spaces-only separator hole as
  // tensSubst — widened to any non-letter run identically ("[twenty]
  // foive" trips; intact words and consumed fusions still pass).
  for (const n of nums) {
    if (n.value < 20 || n.value > 90 || n.value % 10 !== 0) continue;
    let ix = n.end;
    while (ix < t.length && /[^a-z]/.test(t[ix])) ix++;
    if (ix >= t.length) continue;
    let j = ix;
    while (j < t.length && /[a-z]/.test(t[j])) j++;
    const tok = t.slice(ix, j).replace(/(.)\1+/g, "$1");
    for (const w of [...ONES_SMALL, ...TEENS]) {
      if (w.length < 4 || tok.length !== w.length + 1) continue;
      let hit = false;
      for (let k = 0; k < tok.length && !hit; k++) {
        if (tok.slice(0, k) + tok.slice(k + 1) === w) hit = true;
      }
      if (!hit) continue;
      const consumed = nums.some((o) => ix >= o.start - 2 && ix <= o.end);
      if (!consumed) return true;
    }
  }
  return false;
}

// v7.36 (burn #42, live 2026-10-26): deletion-damaged ones beside
// damaged tens. "tW eN tY TrEe ... TwO Is TwEnTy FoUr ... total"
// parsed [20,24]... answered 27.00, verified WRONG (true 23+7=30):
// "TrEe" is "three" minus its h (single-deletion camouflage), so the
// ones-word never parses, the damaged tens never fuses, and 20 stands
// as a false figure. The twin gates miss it both ways: tensSubst needs
// same-length substitution, tensInserted needs a LONGER token (inserted
// letter). Here the token is SHORTER (deleted letter). Guard: a tens
// figure whose own matched span carries interior damage (separators
// inside the tens word itself), followed by an alpha token exactly one
// insertion away from a ones/teen word (insert one letter yields it),
// unconsumed, is suspect. The damaged-tens requirement is the clean
// shield: intact tens ("twenty for ...") never trip, and clean fused
// shapes consume their span. w.length >= 4 keeps short-word neighbors
// ("for", "ten") from eating clean text — same bar as tensInserted.
function tensMissing(t, nums) {
  for (const n of nums) {
    if (n.value < 20 || n.value > 90 || n.value % 10 !== 0) continue;
    let span = t.slice(n.start, n.end);
    if (span && /[^a-z0-9]/.test(span[0])) span = span.slice(1);
    span = span.replace(/s+$/, "");
    if (!/[^a-z0-9]/.test(span)) continue; // intact tens: clean
    let ix = n.end;
    while (ix < t.length && /[^a-z]/.test(t[ix])) ix++;
    if (ix >= t.length) continue;
    let j = ix;
    while (j < t.length && /[a-z]/.test(t[j])) j++;
    const tok = t.slice(ix, j);
    for (const w of [...ONES_SMALL, ...TEENS]) {
      if (w.length < 4 || tok.length !== w.length - 1) continue;
      let ok = false;
      for (let k = 0; k <= tok.length; k++) {
        if (tok.slice(0, k) === w.slice(0, k) && tok.slice(k) === w.slice(k + 1)) { ok = true; break; }
      }
      if (!ok) continue;
      const consumed = nums.some((o) => ix >= o.start - 2 && ix <= o.end);
      if (!consumed) return true;
    }
  }
  return false;
}

export function parseChallenge(challengeText) {
  if (!challengeText || typeof challengeText !== "string") return { unsure: true, reason: "empty" };
  const t = challengeText.toLowerCase();
  const nums = collectNumbers(challengeText);
  // v7.14 (burn #19): figure-integrity gate runs before every branch — a
  // fragmented tens-word orphans its ones-figure, and every subset built
  // on it is poison (3+7=10 verified wrong; true 23+7=30).
  if (orphanOnes(t, nums)) {
    return { unsure: true, reason: "orphan ones beside fragmented tens (burn #19)" };
  }
  // v7.16 (burn #23): figure-integrity gate part 2 — letter-attached ones.
  if (tensOrphan(t, nums)) {
    return { unsure: true, reason: "letter-attached ones beside tens (burn #23)" };
  }
  // v7.21 (burn #26): figure-integrity gate part 3 — truncated teens after
  // tens. "tW/eNn-Ty fIfTeE ... slows by seven" parsed [20, 7] → 13.00,
  // verified WRONG (true 20+15-7=28): "fifteen" missing its final n matched
  // nothing, a silent figure loss, same poison class as burn #23. Clean
  // shapes are safe: intact teens parse and consume their span.
  if (tensTeens(t, nums)) {
    return { unsure: true, reason: "truncated teens after tens (burn #26)" };
  }
  // v7.22 (burn #27): figure-integrity gate part 4 — substituted-letter
  // teens. "fOuRlEeN" (T→L) matched nothing, a silent figure loss with the
  // verb left unfired (32.00 answered, true 46). Clean shapes are safe:
  // intact teens parse and consume their span.
  if (damagedTeen(t, nums)) {
    return { unsure: true, reason: "damaged teen with substituted letters (burn #27)" };
  }
  // v7.26 (burn #33): figure-integrity gate part 5 — glued-suffix
  // ones/teens. Split-by-space plus glued-to-noise ("FiV eUm") defeats
  // both strict matching and the attached-prefix gate, a silent figure
  // loss (42.00 answered, true 47). Clean shapes are safe: intact words
  // always carry a right boundary (space/punctuation/end).
  if (tensGlued(t, nums)) {
    return { unsure: true, reason: "glued-suffix ones/teen beside tens (burn #33)" };
  }
  // v7.28 (burn #35): figure-integrity gate part 6 — substituted-letter
  // ones/teens beside tens. Live 2026-10-22: "TwWeEnNtTy YyOoUuR fOoRr
  // ... * ... SsIiX" fed [20,20,6] to the star branch → 120.00, verified
  // WRONG. "YyOoUuR" is "four" with F→Y (or plain "your" — either way
  // the shape is malformed), so twenty never fused and the star operands
  // are untrustworthy. Same poison class as #23/#26/#27/#33. A tens
  // figure immediately followed (spaces only) by an alphabetic token at
  // substitution-distance exactly 1 (same length, one letter different)
  // from a ones/teen word, unconsumed by any parsed figure, is suspect.
  // Substitution-only, never deletion: deletion-variants of "four" ARE
  // common English ("for", "our") and would eat clean text, while
  // substitution neighbors of short number-words never occur cleanly
  // right after tens in challenge-land. Clean fused shapes are consumed
  // by the fused span (guard passes).
  if (tensSubst(t, nums)) {
    return { unsure: true, reason: "substituted-letter ones/teen beside tens (burn #35)" };
  }
  // v7.29 (burn #36): figure-integrity gate part 7 — spaced-interior
  // ones/teen fused with tens ("fiV E" beside "fIfTy" parsed 55, answered
  // 78.00, verified WRONG). Clean intact fusions ("fifty five") consume
  // without interior damage, so the guard passes them.
  if (tensSpaced(t, nums)) {
    return { unsure: true, reason: "spaced-interior ones/teen fused with tens (burn #36)" };
  }
  // v7.32 (burn #39): figure-integrity gate part 8 — inserted-letter
  // ones/teen beside tens. "TwEnTy FoIvE" parsed [20] (+15) → 35.00,
  // verified WRONG (true 25+15=40): the inserted O hides the ones-word,
  // the tens never fuses, and 20 stands as a false figure. Same poison
  // class as #23/#26/#27/#33/#35/#36. Clean fusions ("thirty two")
  // consume their span, so the guard passes them.
  if (tensInserted(t, nums)) {
    return { unsure: true, reason: "inserted-letter ones/teen beside tens (burn #39)" };
  }
  // v7.36 (burn #42, live 2026-10-26): deletion-damaged ones beside
  // damaged tens ("tW eN tY TrEe" — "three" minus its h). Same poison
  // class as #23/#26/#27/#33/#35/#36/#39, new damage direction.
  if (tensMissing(t, nums)) {
    return { unsure: true, reason: "deletion-damaged ones beside damaged tens (burn #42)" };
  }
  const finish = (a, b, op) => {
    let v;
    if (op === "+") v = a + b;
    else if (op === "-") v = a - b;
    else if (op === "*") v = a * b;
    else { if (b === 0) return { unsure: true, reason: "div0" }; v = a / b; }
    if (!Number.isFinite(v)) return { unsure: true, reason: "nonfinite" };
    return { a, b, op, answer: v.toFixed(2), sure: true };
  };
  // 1. explicit star: nearest merged number on EACH side wins (camouflage-proof)
  if (t.includes("*")) {
    const starIx = t.indexOf("*");
    // v7.37 (burn #44, live 2026-10-27): a star glued (zero-space) to a
    // plus/minus verb stem is decoration, not notation — "InCrEaSeS* By
    // SeVeN ... total force" star-solved 23*7=161.00, verified WRONG
    // (true 23+7=30). The generator decorates verbs constantly ("ClAw^",
    // "ExErTs~", "WhAt'S]"); real star notation stands between digits
    // ("34*6") or spaced ("thirty four * six"). Glued-to-verb stars fall
    // through to the normal branches; every other star keeps solving.
    const verbGlue = starIx > 0 && /[a-z]/.test(t[starIx - 1]) &&
      FAMS.plus.concat(FAMS.minus).some((w) => fuzzHere(w, (t.slice(0, starIx).match(/[a-z]+$/) || [""])[0]));
    if (!verbGlue) {
    const left = [...nums].filter((n) => n.end <= starIx).pop();
    const right = nums.find((n) => n.start > starIx);
    if (!left || !right) return { unsure: true, reason: "star-side-missing" };
    // v7.33 (held live 2026-10-23, burn-#11 class): "fourteen newtons * ...
    // three times stronger ... total force" — the star branch answered
    // 14*3=42 without ever consulting the total branch, but "three times
    // stronger" plus "total" is the exact total×multiply mix of burn #11
    // (true unknowable: 42, or 14+42=56). A star-shape containing both a
    // total-family word and a multiply signal is unsure, always. Pure
    // star-shapes (no total words) keep solving.
    if (famHit(t, ["total", "sum", "combined", "altogether", "how many", "how much"]) &&
      (famHit(t, FAMS.multiply) || unseenMultiply(t))) {
      return { unsure: true, reason: "star×total-multiply mix (burn #11 class)" };
    }
    return finish(left.value, right.value, "*");
    }
  }
  // 2. per-unit: never sum across heterogeneous units; "total"+per-unit = unsure
  const pu = perUnitSignal(t);
  if (pu) {
    // v7.25 (burn #31, live 2026-10-21): rate-noun-before-per guard. The
    // after-window inside perUnitSignal is blind when camouflage stretches
    // the rate noun past 24 chars ("23 meters per Um plus claw force"
    // fired per-unit 23×5=115, verified WRONG — true unknown, velocity+force
    // heterogeneous). A RATE_CONTEXT noun in the 24 chars BEFORE "per"
    // marks rate context regardless of what follows. newton/force are
    // excluded from RATE_CONTEXT, so the legit "35N per claw" shape
    // (verified 70.00 live) is untouched.
    // v7.27 (burn #34): same inserted-vowel blindness in the before-window
    // ("cEmSs pEr" — fuzz("cm") can't cross the E). Loose check alongside.
    if (pu.word === "per" && (RATE_CONTEXT.some((r) => fuzzHere(r, t.slice(Math.max(0, pu.ix - 24), pu.ix))) || RATE_CONTEXT.some((r) => fuzzLoose(r, t.slice(Math.max(0, pu.ix - 24), pu.ix))))) {
      return { unsure: true, reason: "rate-noun-before-per (burn #31)" };
    }
    if (famHit(t, ["total", "sum", "combined", "altogether", "how many", "how much"])) {
      return { unsure: true, reason: "per-unit+total mix" };
    }
    const pair = nearestTwo(nums, pu.ix);
    if (!pair) return { unsure: true, reason: "per-unit without pair" };
    return finish(pair[0].value, pair[1].value, "*");
  }
  // 3. total-family: SUM unit-proximate figures only (newton OR force —
  // v7: the unit word itself can be camouflaged past recognition, e.g.
  // "NoOtoOnS", so "force" is an equal anchor)
  if (famHit(t, ["total", "sum", "combined", "altogether", "how many", "how much"])) {
    const anchors = [...allMatches(fuzz("newton"), t), ...allMatches(fuzz("force"), t)];
    // v7.3+v7.7: generalized newton anchor — w optional, vowel runs free
    // ("newtons", "nootoonns"/"neootons"/"neutons" w dropped+vowel-shifted,
    // "nneuwttoonnns" w PRESENT with inserted u). Fuzzy-style symbol gaps,
    // trailing s required so "not on" never anchors. Window stays 45.
    const wless = [...allMatches(/n+[^a-z0-9]*[aeou]*[^a-z0-9]*w?[^a-z0-9]*[aeou]*[^a-z0-9]*t+[^a-z0-9]*[aeou]*[^a-z0-9]*o+[^a-z0-9]*[aeou]*[^a-z0-9]*n+[^a-z0-9]*s+/gi, t)]
      .filter((m) => m.len >= 6);
    const all = anchors.concat(wless);
    // v7.1: drop figures sitting in rate context ("25 cm per seconds" is not
    // summable with newtons — heterogeneous units are unresolvable, never summed)
    // Window stays 45: the v7.1 hybrid relies on distance to exclude a
    // rate-context figure from a far newton anchor (widening to 90 regressed
    // it 2026-10-18). W-less anchors sit adjacent to their figures, so 45 is
    // plenty for them too.
    // v7.5 (burn #11): a multiply signal ANYWHERE in a total-shape ("times
    // by three ... total force") makes it a total×multiply mix — the total
    // branch must not sum blindly (24+3=27 was confidently wrong; true was
    // 24×3=72). Same honesty as per-unit+total: return unsure. Note the
    // live debate this encodes (theagatha: question-word wins vs observed
    // times-wins) — unresolved locally, so unsure, always.
    if (famHit(t, ["multipli", "multiply", "multiplies", "multiplied", "times", "product", "repeat", "fold", "folds", "double", "doubling", "triple", "impulse"])) {
      return { unsure: true, reason: "total×multiply mix (burn #11)" };
    }
    // v7.12 (burn #17): same mix, verb camouflaged past strict matching
    // ("MuLtiIiipL eS ... total force" summed 32+3=35, verified WRONG).
    // Unseen-multiply soup in a total-shape is unsure, always.
    if (unseenMultiply(t)) {
      return { unsure: true, reason: "total×unseen-multiply soup (burn #17)" };
    }
    // v7.9 (burn #12): "boost (by N)" inside a total-shape is a distractor
    // verb — 30N + boost-by-two summed to 32.00 and verified WRONG (true is
    // 30 or 60, unknowable locally). Boost never joins the plus family:
    // total-shapes containing it are unsure, always.
    if (famHit(t, ["boost"])) {
      return { unsure: true, reason: "total×boost distractor (burn #12)" };
    }
    // v7.25 (burn #32, live 2026-10-21): "pushes" is semantically ambiguous
    // ("25N and antenna pushes 27N, total" summed 52.00, verified WRONG —
    // true unknowable locally: adds? multiplies? resists?). Same class as
    // boost: a verb that looks additive but isn't proven additive. A
    // total-shape containing pushes/pushed/pushing is unsure, always. Bare
    // "push" deliberately excluded: the legacy "total-sum fuzzy" battery
    // case ("ToTaL push?", 50.00) uses push as a noun, and the veto must
    // not eat it. No battery case contains the verb forms (verified by
    // grep 2026-10-21), so the veto is green by construction there.
    if (famHit(t, ["pushes", "pushed", "pushing"])) {
      return { unsure: true, reason: "total×push ambiguous verb (burn #32)" };
    }
    // v7.15 (burn #21): minus verbs inside a total-shape ("loses seven ...
    // how many remain") summed 23+7=30, verified WRONG (true 16). The
    // total branch never consulted the minus family — same hole burns
    // #11/#12 closed for multiply/boost. A total-shape containing a minus
    // verb is a net-shape the solver has no rule for: unsure, always.
    if (famHit(t, FAMS.minus)) {
      return { unsure: true, reason: "total×minus mix (burn #21)" };
    }
    // v7.24 (burn #30, live 2026-10-21): distributive "by N agents"
    // ("24N applied by three lobsters ... total force") — N copies of
    // the figure, true 24×3=72, solver summed 24+3=27. perUnitSignal
    // misses it (no each/per/every/apiece), and no multiply verb fires.
    // A total-shape containing by+number+agent-word is a distributive
    // the solver has no solve rule for: unsure, always. Sits after the
    // multiply/boost/minus vetoes so those keep their reasons; requires
    // an agent noun after the number so bare "by" (times-by, drifting
    // by) never trips it. Agent nouns use letter-soup (not fuzz): the
    // noun itself arrives camouflaged ("Lo.o bSsSsTeR" splits a duplicate
    // across a separator, which fuzz can never span) — so the window is
    // flattened (separators stripped) before soup-matching: "LooobSsSsTeR"
    // then matches cleanly. Same pattern as the v7.12 unseen-multiply
    // soup. Window stays 44, battery-guarded.
    {
      const AGENT_SOUPS = ["lobster", "claw", "crab", "opponent", "rival"];
      const distrib = allMatches(fuzz("by"), t).some((m) => {
        const flat = t.slice(m.ix, m.ix + 44).replace(/[^a-z0-9]/gi, "");
        if (!AGENT_SOUPS.some((s) => looseSoup(flat, s))) return false;
        return nums.some((n) => n.start >= m.ix && n.start - m.ix < 44);
      });
     if (distrib) return { unsure: true, reason: "distributive-by-N-agents total (burn #30)" };
   }
    // v7.35 (burn #41, live 2026-10-26): letter "x" as multiply in a
    // total-shape — "twenty three x seven ... total force" parsed [23,7]
    // and summed 30.00, verified WRONG (true 23*7=161). The explicit-star
    // branch only fires on the "*" character, so letter-x sailed past
    // every veto (no multiply verb, no per-unit marker). A standalone x
    // token with figures on both sides is multiply notation — same
    // honesty class as total×multiply: unsure, always. Figure-gated so
    // stray prose x (no figure on one side) never trips it.
    {
      const xHit = [...t.matchAll(/(?:^|[^a-z0-9])x(?![a-z0-9])/gi)].some((m) => {
        const ix = m.index + m[0].length - 1;
        return nums.some((n) => n.end <= ix) && nums.some((n) => n.start > ix);
      });
      if (xHit) return { unsure: true, reason: "total×letter-x multiply (burn #41)" };
    }
    const near = nums.filter((n) =>
      all.some((m) => Math.abs(n.start - m.ix) < 45) &&
      // v7.27 (burn #34): same inserted-vowel blindness — "23 cEmSs" never
      // matched fuzz("cm"), so a rate-context figure survived into the sum.
      // Loose check alongside (both only exclude, never solve).
      !RATE_CONTEXT.some((r) => fuzzHere(r, t.slice(n.end, n.end + 20))) &&
      !RATE_CONTEXT.some((r) => fuzzLoose(r, t.slice(n.end, n.end + 20))));
    // v7.13 (burn #18): distractor counts. "it has FOUR claws ... total
    // force" summed 23+4=27, verified WRONG — the count borrows a nearby
    // anchor that belongs to another figure. A figure followed by a
    // count-noun but no unit anchor of its own is unitless and can never
    // join a newton sum (unlike distance exclusions, this is about units,
    // so the surviving anchored figures stay summable). Survivors <2 with
    // an exclusion on the board = unsure, always.
    // v7.30 (burn #37, live 2026-10-22): "grapples two opponents ... total
    // force" summed 12+2=14, verified WRONG (true 12 — opponents are a
    // count, not a force). "opponent"/"rival" join COUNT_NOUNS (same set
    // the burn-#30 distributive gate already treats as agent words).
    const COUNT_NOUNS = ["claw", "shell", "drone", "leg", "arm", "tail", "swarm", "pile", "opponent", "rival"];
    // v7.17 (density probe 2026-10-20): question-clause anchors lend
    // false ownership. "23 newtons with four claws, total force?" summed
    // 27.00 — the question's own "force" sat within 20 chars and anchored
    // the count. Anchors at/after the last total-word belong to the
    // question, never to a figure.
    const totalIx = (() => {
      let last = -1;
      for (const w of ["total", "sum", "combined", "altogether", "how many", "how much"]) {
        for (const m of allMatches(fuzz(w), t)) last = Math.max(last, m.ix);
      }
      return last;
    })();
    const hasOwnAnchor = (n) =>
      all.some((m) => m.ix >= n.end && m.ix - n.end < 20 && (totalIx < 0 || m.ix < totalIx));
    const isBareCount = (n) =>
      COUNT_NOUNS.some((w) => fuzzHere(w, t.slice(n.end, n.end + 20))) && !hasOwnAnchor(n);
    const excluded = near.filter(isBareCount);
    const kept = near.filter((n) => !isBareCount(n));
    if (excluded.length > 0 && kept.length < 2) {
      return { unsure: true, reason: "distractor-count exclusion (burn #18)" };
    }
    if (kept.length >= 2) {
      // v7.4 (burns #8, #10): N≥3 figures with total-words is UNSURE, full
      // stop — even when only 2 survive filtering. #8 summed three figures
      // (102); #10 is worse, distance excluded the true addend (25) and the
      // solver confidently summed the wrong pair (ONE+15=16). Excluded
      // figures make every subset untrustworthy, and distractor counts
      // ("with ONE claw") are unrecognizable locally. Star/per-unit shapes
      // return before this branch, so they keep their structure. Ghosts cost
      // budget; wrong verifies cost trust — unsure here, always.
      if (nums.length >= 3) return { unsure: true, reason: "N>=3 figures with total-words (burns #8/#10)" };
      const s = kept.reduce((x, n) => x + n.value, 0);
      return { a: kept[0].value, b: s - kept[0].value, op: "+", answer: s.toFixed(2), sure: true, note: "sum-of-" + kept.length };
    }
    // v7.3 same-unit fallback: figures sharing an identical multi-letter unit
    // token ("23 cm/s + 14 cm/s, total velocity") sum together; mixed or
    // unitless figures stay unsure. Single-letter units ("4N", "23m") never
    // qualify — the heterogeneous guard holds for them. v7.7: 28-char look
    // window (10 truncated long units unevenly under backslash noise, so two
    // identical units compared unequal).
    const units = nums.map((n) => {
      const m = /[^a-z0-9]*([a-z]+(?:\/[a-z]+)?)/.exec(t.slice(n.end, n.end + 28));
      return m ? m[1] : "";
    });
    const freq = {};
    units.forEach((u) => { if (u && u.length >= 2) freq[u] = (freq[u] || 0) + 1; });
    const best = Object.entries(freq).sort((a, b) => b[1] - a[1])[0];
    if (best && best[1] >= 2) {
      // v7.4: same N≥3 guard — a third figure anywhere on the board vetoes
      // the subset sum (burns #8/#10).
      if (nums.length >= 3) return { unsure: true, reason: "N>=3 figures with total-words (burns #8/#10)" };
      const grp = nums.filter((_, i) => units[i] === best[0]);
      const s = grp.reduce((x, n) => x + n.value, 0);
      return { a: grp[0].value, b: s - grp[0].value, op: "+", answer: s.toFixed(2), sure: true, note: "same-unit-" + best[0] };
    }
    return { unsure: true, reason: "total without newton figures" };
  }
  // 4. single-op families; mixed families = unsure
  const hits = Object.entries(FAMS)
    .filter(([, words]) => famHit(t, words))
    .map(([k]) => k);
  if (hits.length !== 1) return { unsure: true, reason: hits.length === 0 ? "no op" : "mixed ops: " + hits.join("+") };
  // v7.12 (burn #17): invisible multiply verb in a single-op shape — the
  // strict family missed it, so the surviving branch (usually plus) would
  // answer blind. Unsure, always.
  if (!hits.includes("multiply") && unseenMultiply(t)) {
    return { unsure: true, reason: "single-op with unseen-multiply soup (burn #17)" };
  }
  // v7.38 (burn #46, live 2026-10-28): accelerate rate×duration.
  // "accelerates at 23 m/s^2 for 7 seconds, change in velocity" summed
  // 23+7=30.00 via accelerate-plus, verified WRONG (true 23*7=161 —
  // rate×duration). The clean accelerates-by shape ("accelerates by
  // Five, new velocity", no for/duration window) stays additive. Gate:
  // accelerate-stem + rate marking (per-word, /s, or a rate-context unit)
  // + a for/during/over ... time-unit window. Trip costs a ghost, never
  // trust.
  if (hits.length === 1 && hits[0] === "plus" && fuzzHere("accelerate", t)) {
    const rateMark = t.includes("/s") ||
      RATE_CONTEXT.some((r) => fuzzHere(r, t)) || fuzzHere("per", t);
    let durWindow = false;
    for (const w of ["for", "during", "over"]) {
      for (const m of allMatches(fuzz(w), t)) {
        const win = t.slice(m.ix, m.ix + 60);
        if (["second", "minute", "hour", "sec", "min", "hr"].some((r) => fuzzHere(r, win))) { durWindow = true; break; }
      }
      if (durWindow) break;
    }
    if (rateMark && durWindow) {
      return { unsure: true, reason: "accelerate rate×duration (burn #46)" };
    }
  }
  if (nums.length < 2) return { unsure: true, reason: "fewer than 2 numbers" };
  // v7.11 (burn #14): N≥3 figures in a single-op shape is UNSURE — same
  // rationale as the totals veto (burns #8/#10). A stuttered tens double
  // ("twenty three three" → 23+3) makes the branch take the first two
  // blindly (23-3=20, verified wrong; true 23-7=16). Excluded figures
  // poison every subset, whatever the branch.
  if (nums.length >= 3) return { unsure: true, reason: "N>=3 figures in single-op shape (burn #14)" };
  const [a, b] = nums;
  const op = hits[0] === "divide" ? "/" : hits[0] === "multiply" ? "*" : hits[0] === "minus" ? "-" : "+";
  return finish(a.value, b.value, op);
}

// Self-test with synthetic obfuscated cases drawn from real burns.
export function runSolverTests() {
  const cases = [
    { name: "total-sum fuzzy", text: "tHiRtY nEwToNs drift past, tWeNtY nEwToNs hold the line. ToTaL push?", want: "50.00" },
    { name: "per-unit+total burn (must be unsure)", text: "claws EACH exert 4N plus 23m of approach speed, total force?", wantUnsure: true },
    { name: "star adjacency vs camouflage", text: "the crew will split/teN shares. twenty three * fifteen, count it.", want: "345.00" },
    { name: "folds multiply", text: "the swarm folds FoUr times from eleven drones", want: "44.00" },
    { name: "v7.8 accelerates-by plus (live repost 2026-10-18)", text: "it swims at TwEnTy ThReE meters per second and accelerates by FiVe, whats the new velocity", want: "28.00" },
    { name: "doubles trap", text: "It DoOuUbBlEeS By TwO, starting from thirty two shells", want: "64.00" },
    { name: "per-second guard + plus", text: "drifted twenty three cm per second, then gained sEvEn shells", want: "30.00" },
    { name: "minus order", text: "fifty shells in the pile, loses NiNe to the tide", want: "41.00" },
    { name: "tens+ones fusion", text: "fiffteeen newtons here and th iR.ty newtons there, how many total?", want: "45.00" },
    { name: "clean per-unit pair", text: "five claws EACH exert four newtons of grip", want: "20.00" },
    { name: "v7 force anchor (live burn 2026-10-18)", text: "L]oBsTeR Cl]aW F^oRcE Is ThIrTy FiiVe NoOtoOnS - BuT TeRrI]tOrIal RiV[aL AdD s TwE]lV e NoOtOnS, HoW MuCh ToTaL FoR^cE?", want: "47.00" },
    { name: "v7.1 heterogeneous hybrid (live 2026-10-18, must be unsure)", text: "A] lO.oB sT-Er SwIiMmS aT/ tWwEeNnTtYy fIiVvEe cEeMm/ pEeRr sEeCcOoNnDdSs, um {aNd} tHe] clLaAwW aDdSs~ tEeNn NeEwWtToOnNs, wHaT iS tHe SuMm <oF> thEsE hYbBrId PhYySsIxS lOo.obst errr qUeStIoN?", wantUnsure: true },
    { name: "v7.2 tens stutter (live burn 2026-10-18)", text: "Lo]bSt-Err ClAw^ FoR cE Is FoR tY FoR tY TwO] NeWtOnS + AnO\tHeR ClAw| FoR cE Is TwEnTy] FiVe NeWtOnS, HoW< MuCh~ ToTaL FoR cE dOeS ThIs Looobsss tErr HaV e? um", want: "67.00" },
    { name: "mixed ops unsure", text: "gained five shells then lost three to the crabs", wantUnsure: true },
    { name: "v7.3 w-less unit + far force anchor (live ghost 2026-10-18)", text: "ClLaAwW ExXeErRtTsS ThHiIrRtTyY TwWoO NoOoOtToOnNsS - ^ AnNdD AnNoOtThHeErR ClLaAwW ExXeErRtTsS EiIgGhHtTeEeN NoOoOtToOnNsS ~, WhHaAtT IsS ThHeE ToOtTaAlL FoOrRcEe ?", want: "50.00" },
    // "FiFfEeTeEn" carries inserted vowels: fifteen can't match (extra "ee"
    // between f and t) and the inner "teen" dies on the letter-boundary
    // guard — the figure genuinely vanishes, so unsure is CORRECT here
    // (unsure-ghosts cost budget, wrong verifies cost trust). Clean spelling
    // proves the w-less anchors themselves work.
    { name: "v7.3 w-less anchors clean spelling", text: "ClAw ExErTs ThIrTy NeOoTtOnS + FiFtEeN NeOoTtOnS, HoW MuCh ToTaL FoRcE, Um?", want: "45.00" },
    { name: "v7.3 inserted-vowel figure correctly unsure (live ghost 2026-10-18)", text: "ClAw ExErTs ThIiRrTy Ne-OoTtOnS + FiFfEeTeEn NeOoTtOnS, HoW MuCh ToTaL FoRcE, Um?", wantUnsure: true },
    { name: "v7.3 same-unit velocity (live ghost 2026-10-18)", text: "sW^iMmS aT tWeNtY tHrEe cM/s + fOuR.tEeN cM/s, hOw MuCh ToTaL VeLoCiTy iS tHiS?", want: "37.00" },
    { name: "v7.3 heterogeneous stays unsure", text: "four newtons of grip plus twenty three meters of speed, what is total force?", wantUnsure: true },
    { name: "v7.3 not-on never anchors", text: "twenty shells not on the table, fifteen more beside them, what is the count?", wantUnsure: true },
    { name: "v7.4 burn #10 distractor count (live 2026-10-18)", text: "A] LoObS-tErS^ ExErTs/ tWeN tY fIvE ]nEu-TO nS um, WiTh/ OnE ClAw| AnD ThE OtHeR AdDs- fIfTeEn NeW tO nS~, WhAt] Is/ ThE ToTaL FoRcE?", wantUnsure: true },
    { name: "v7.4 clean neutons pair", text: "twenty five neutons plus fifteen newtons total force", want: "40.00" },
    { name: "v7.4 three clean figures unsure (tradeoff, documented)", text: "thirty newtons, twelve newtons and five newtons, what is total force?", wantUnsure: true },
    // v7.5: impulse (force × time) is multiply. Live post shape 2026-10-18:
    // "claws exert 32 NoOtOnS, grip lasts 14 SeCoNdS, what is the impulse?"
    // solver held (no op), hand-solved 448.00 in-session, verified first try.
    { name: "v7.5 impulse shape (live post 2026-10-18)", text: "claws exert thirty two NoOtOnS and the grip lasts fourteen seconds. What is the impulse?", want: "448.00" },
    // v7.6 (burn #11): total×multiply mix is unsure, not a sum.
    { name: "v7.6 burn #11 times-by with total (live 2026-10-18)", text: "cLaWw ExErTs tWeN tY FoOuR NoOoToOnS, DuRiNg DoMiNaNcE F iGhT iT TiMeS bY ThReE, wHaT Is ThE ToTaL FoRcE?", wantUnsure: true },
    // v7.7: vowel-INSERTION unit ("nneuwttoonnns") anchors; long units need
    // the wider fallback window (live ghost 2026-10-18, true 40.00).
    { name: "v7.7 insertion-unit pair (live ghost 2026-10-18)", text: "ClLaAwW ExXeErRtTsS TwWeEnNtY FiIvEe NnEuWtToOnNs AnNdD OtThHeErR ClLaAwW AdDdSs FfIfFtEeEeNn NnEuWtToOnNs, WhHaTt ToOtTaAlL FfOrRcEe?", want: "40.00" },
    // v7.9 (burn #12): boost-by inside a total-shape is a distractor —
    // 30+2=32.00 verified WRONG live 2026-10-18, true unknowable locally.
    { name: "v7.9 burn #12 boost-by distractor (live 2026-10-18)", text: "claw force is thirty nootons and antenna boost by two, what is total force?", wantUnsure: true },
    // v7.10: "multipli" stem never matched real words (multiply/multiplies
    // die on the s* tail guard, same trap as accelerat). Full-word forms
    // added; ami live shape 2026-10-18 solves 128.00, xtech shape stays
    // unsure via the total×multiply veto.
    { name: "v7.10 multiplies-by-four (live ghost 2026-10-18)", text: "claw exerts thirty two nootons during a dominance pinch and molting multiplies it by four, whats the new force?", want: "128.00" },
    { name: "v7.10 antenna-multiplies total stays unsure (live burn 2026-10-18)", text: "claw exerts twenty eight nootons and its antenna multiplies by four, what is the total?", wantUnsure: true },
    // v7.11 (burn #14): stuttered tens double in a minus shape — 23+3+7,
    // branch took first two (23-3=20, verified wrong 2026-10-19).
    { name: "v7.11 burn #14 stuttered-tens minus (live 2026-10-19)", text: "lobster swims at twenty three three meters per second but loses seven, what is the new speed?", wantUnsure: true },
    // v7.12 (burn #17): inserted-vowel verb defeats strict matching —
    // "MuLtiIiipL eS ... total force" summed 32+3=35, verified WRONG live
    // 2026-10-19. Tripwire must hold it unsure.
    { name: "v7.12 burn #17 verbatim (live 2026-10-19)", text: "] A lO b- StErS ] cLaW [ fO^rC e iS tHiRty tW]o nOoToNs ~ aNd / iT MuLtiIiipL eS \\ bY tHrEe {,} wHaT] Is T oTaL - FoR cE < >?", wantUnsure: true },
    // v7.12: clean shapes with no verb soup still solve (tripwire is gated
    // on the strict family missing).
    { name: "v7.12 clean pair unaffected", text: "claw exerts twenty five newtons and another claw exerts fifteen newtons, what is total force?", want: "40.00" },
    // v7.13 (burn #18): bare count borrows a nearby anchor — "it has FOUR
    // claws ... total force" summed 23+4=27, verified WRONG live 2026-10-19.
    { name: "v7.13 burn #18 verbatim (live 2026-10-19)", text: "A] lO-bStEr'S ClAw] FoRcE Is^ TwEnTy ThReE NoOtOnS, Um~ iT hAs/ FoUr ClAwS, HoW MuCh] ToTaL FoRcE| To GeTtEr? Lo.bStEr ShAtTeRrEd, loooobsssster, nootons, umm, lxobqst", wantUnsure: true },
    // v7.13: anchored figures beside a bare count still sum (the count is
    // unitless, the forces are not — cf. the burn #10 shape class).
    { name: "v7.13 anchored pair with bare count alongside", text: "claw force is twenty five newtons with a single claw, and the other claw adds fifteen newtons, what is total force?", want: "40.00" },
    // v7.14 (burn #19): fragmented tens orphans its ones ("tW]eNnY ThR-eE"
    // parsed as 3; 3+7=10 verified WRONG, true 23+7=30). Must hold unsure.
    { name: "v7.14 burn #19 verbatim (live 2026-10-20)", text: "A] Lo B- StEr rR~ SwIiMmS^ aT/ tW]eNnY ThR-eE<e> CmEnT- S Per^ SeC oNdS, Um, A-ccElErAaTeS| By/ SeVeN, WhAt^ Is] ThE/ NeW- VeLoAwCiTeE?", wantUnsure: true },
    // v7.14 (burn #20): vowel-dropped verb ("DuBbLeS", no "o") sailed past
    // strict double AND the v7.12 soup; 15+2=17 verified WRONG (true 30).
    { name: "v7.14 burn #20 verbatim (live 2026-10-20)", text: "A] Lo.O bS tEr] ClAw] ExErTs] FiFtEeN] NeW-ToNs^ BuT] DuBbLeS] On] Mo.LtInG] By] TwO,] HoW] MuCh] ToTaL] FoRcE?", wantUnsure: true },
    // v7.15 (burn #21): total-word plus minus verb ("loses seven ... how
    // many remain") summed 23+7=30, verified WRONG (true 16). Must hold.
    { name: "v7.15 burn #21 verbatim (live 2026-10-20)", text: "Lo]oB-StEr Ex^eRts FoRcE oF TwEnTy ThReE NeWtOnS, BuT Lo]oB-StEr LoSeS SeVeN NeWtOnS DuRiNg A DoMiNaNcE FiGhT, HoW MaNy NeWtOnS ReMaIn?", wantUnsure: true },
    // v7.16 (burn #23): letter-attached ones ("thirty qSiX" — the "q"
    // defeats the boundary guard, six never parses, 30+14=44 verified
    // WRONG, true 36+14=50). Verbatim challenge lost to an echo lapse;
    // shape reconstructed from the handoff note. Must hold unsure.
    { name: "v7.16 burn #23 reconstructed (live 2026-10-20)", text: "cLaW eXeRtS tHiRtY qSiX nOoToNs, aNoThEr ClAw AdDs FoUrTeEn NeWtOnS, wHaT iS tHe ToTaL fOrCe?", wantUnsure: true },
    // v7.16: clean tens+ones fusion beside a second figure still solves.
    { name: "v7.16 clean thirty-six plus fourteen", text: "claw exerts thirty six newtons and another claw adds fourteen newtons, what is total force?", want: "50.00" },
    // v7.16: spaced ones-words after tens never trip the attached gate.
    { name: "v7.16 spaced tens then ones unaffected", text: "claw force is thirty shells, five newtons total force?", want: "35.00" },
    // Sweep finding (2026-10-20): the per-unit+total battery case held via
    // an empty pair (digit+letter glue defeats digit parsing), never via
    // the veto — the veto itself had zero coverage. This word-numbered
    // case exercises the veto directly: disable it and the solver
    // multiplies the heterogeneous pair.
    { name: "per-unit+total veto live (sweep 2026-10-20)", text: "each claw exerts four newtons of grip plus twenty three meters of approach speed, what is total force?", wantUnsure: true },
    // Role-swap regression (symbolon constant-distance protocol, run live
    // 2026-10-20): same token distances, roles swapped. Control solves
    // 40.00; swapped holds unsure under the burn-18 exclusion. Roles
    // decide, geometry merely ushers.
    { name: "role-swap control solves", text: "claw force is twenty five newtons with a single claw, and the other claw adds fifteen newtons, what is total force?", want: "40.00" },
    { name: "role-swap holds unsure (burn-18 exclusion)", text: "claw force is twenty five claws with a single newton, and the other newton adds fifteen shells, what is total force?", wantUnsure: true },
    // v7.17 (density probe 2026-10-20): the question's own unit word
    // must not anchor a distractor count. Both shapes summed
    // confidently (27.00, 17.00) on analysis — never verified live.
    { name: "v7.17 question-anchor lent to count (analysis 2026-10-20)", text: "claw force is twenty three newtons with four claws, what is total force?", wantUnsure: true },
    { name: "v7.17 question-anchor lent to tails (analysis 2026-10-20)", text: "claw force is fifteen newtons with two tails, what is total force?", wantUnsure: true },
    // Cross-product audit, first cells (symbolon coupling probe, run live
    // 2026-10-20): coupled error types must hold, never solve. Exactly two
    // figures each — the interesting pressure tier (3+ holds trivially).
    { name: "coupled misbound-x-verb holds (matrix 2026-10-20)", text: "claw force is twenty five newtons with four claws MuLtiIiipL by two, what is total force?", wantUnsure: true },
    { name: "coupled absent-x-misbound holds (matrix 2026-10-20)", text: "claw force is thirty qSix newtons with four claws, what is total force?", wantUnsure: true },
    // v7.18 (-ing gap, found live 2026-10-20): "reducing" matched no
    // family (reduces/reduced only) — same stem-trap class as
    // accelerat/multipli. Full -ing forms added both sides.
    { name: "v7.18 reducing-by-twelve (live ghost 2026-10-20)", text: "lobster claw force is thirty five nootons, pushes against rock reducing force by twelve, whats remaining?", want: "23.00" },
    // v7.19 (burn #25, live 2026-10-20): abbreviated rate unit "sEeC"
    // defeated the RATE guard, bare "per" fired per-unit, 23x7=161.00
    // verified WRONG (true 23-7=16, slows-minus). sec/min/hr join RATE.
    { name: "v7.19 burn #25 verbatim (live 2026-10-20)", text: "A] lO b-StTeRr S^wIiMmS[ aT tW/eNnTtY tHhRrEe Me]tErS ~pEeR sEeC, uMm lOo oBqSsTeRr SlOoWwSs - B^y sEeVvEeN Me}tErS PeR sEeC, WhAt Is NeW S pEeD?", want: "16.00" },
    // v7.20 (bytes exactness sweep 2026-10-20): double-t damage escapes the
    // single-deletion soup — "weny five" summed 5+15=20 (true 40),
    // "hiry two" summed 2+14=16 (true 46), both with all vetoes on.
    { name: "v7.20 double-t tens damage (sweep 2026-10-20)", text: "claw exerts weny five newtons and another claw adds fifteen newtons, what is total force?", wantUnsure: true },
    { name: "v7.20 double-t tens damage 2 (sweep 2026-10-20)", text: "claw force is hiry two newtons and the other claw adds fourteen newtons, what is total force?", wantUnsure: true },
    // v7.21 (burn #26, live 2026-10-20): truncated teens after tens —
    // "twenty fIfTeE" (fifteen minus final n) matched nothing, [20,7]
    // summed 13.00, verified WRONG (true 20+15-7=28).
    { name: "v7.21 burn #26 verbatim (live 2026-10-20)", text: "A] lO-bBsT eR sW/iMmS[ aT tW/eNn-Ty fIfTeE mE]tErS pEr/ sEcOnD aNd- sLoW sBy[ sEeVvEn, wH-aT s] iS~ nEw< vElOoOcIiTy?", wantUnsure: true },
    // v7.22 (burn #27, live 2026-10-20): substituted-letter teen —
    // "fOuRlEeN" (T→L) never parsed, [30,2] fused, verb unfired, 32.00
    // answered WRONG (true 32+14=46). No shell left; filed as a burn.
    { name: "v7.22 burn #27 verbatim (live 2026-10-20)", text: "A] lO^bSt-Er S[wI mS] lOoObsssStEr ClAw- ExErTs^ tHiRrTy SpAcE tWo] nEeOotOns~, aNd- AnOtHeR InCrEaSeS^ bY[ fOuRlEeN~, wHaT/ iS] tHe ToTaL- fOrCe~?", wantUnsure: true },
    { name: "v7.22 clean fourteen control", text: "claw exerts thirty two newtons and another increases by fourteen newtons, what is total force?", want: "46.00" },
    // v7.23 (burn #29, live 2026-10-21): camouflaged minus verb "lOoSs"
    // plus bare "remain" (list had only "remains") both missed the minus
    // family — total branch summed 23+5=28, verified WRONG (true 23-5=18).
    // "loss" added (fuzz boundaries keep gloss/floss/close out) plus bare
    // "remain"/"remaining". Both shapes below must hold unsure via the
    // v7.15 total×minus guard, never solve.
    { name: "v7.23 burn #29 verbatim (live 2026-10-21)", text: "ThE] LoOoBbSsTtEeR^ ClAw] FoRcE/ IsS tW/eNtY tHrEe ~ NoOoToNs, Um] ItT lOoSs- fIvE { NoOtOnS|, HoW^ MaNy < NeWtOoNs> ReMaIn?", wantUnsure: true },
    { name: "v7.23 damaged-loss without remain", text: "claw force is twenty three newtons, it looss five newtons, how many total force?", wantUnsure: true },
    // v7.24 (burn #30, live 2026-10-21): distributive "by N agents" —
    // "24N applied by three lobsters, total" summed 24+3=27.00, verified
    // WRONG (true 24×3=72). No each/per marker, no multiply verb: a hole
    // in every veto. Verbatim must hold; bare "by" with no agent noun
    // after it must still sum (veto specificity guard).
    { name: "v7.24 burn #30 verbatim (live 2026-10-21)", text: "A] ClAw-FoRcE Is^ TwEnTy FoUr NeWtOnS ApPlIeD By^ ThrEe Lo.o bSsSsTeR~ In{ A} DoMiNaNcE- FiGhT, Um, WhAt Is ToTaL/ FoRcE<?", wantUnsure: true },
    { name: "v7.24 bare-by-no-agent still sums", text: "claw exerts twenty four newtons drifting by, and another adds three newtons, what is total force?", want: "27.00" },
    // v7.25 (burns #31–32, live 2026-10-21, double burn round): #31 —
    // bare "per" fired per-unit on a rate context whose noun stretched past
    // the 24ch after-window ("23 meters per Um plus 5N" → 23×5=115.00
    // WRONG). Rate-noun-before-per now holds. #32 — "antenna pushes 27N"
    // summed 52.00 WRONG, true unknowable: push-verb totals hold. The
    // R44-verified "35N per claw" shape is the specificity guard (must keep
    // solving 70.00 — newton/force excluded from RATE_CONTEXT).
    { name: "v7.25 burn #31 verbatim (live 2026-10-21)", text: "A] lOoBbS-tEr V^eLlOoOcCiItTyY tW]eNn-Ty Th^rEe mE]tErS pEr Um PlUs { cLaW F-oRcEe F^iV]e nEu-ToNs um ~", wantUnsure: true },
    { name: "v7.25 burn #32 verbatim (live 2026-10-21)", text: "A] lOoObSt-Er] sWaMpS^ iN/ cOoLd WaTeR, lOoOobssst errr' s ClAw] eXerT s^ tWe/NtY fIvE ] nEu-TonS- AnD/ aN] anTeNnA pUsHeS^ tWeNn-Ty sEvEn ] nEuToNs, hOw/ mUcH ] tOtAl^ fOrCe- iN/ nEuToNs?", wantUnsure: true },
    { name: "v7.25 legit per-claw still solves (live R44)", text: "A] lO^bSt-Er S[wImS, lOoObsTtEr Um, eXErTs ThIrTy FiVe NoOtOnS PeR ClAw- AnD MoL/tEd TwO TiMeS, wHaT Is ThE PrOdUcT?", want: "70.00" },
    // v7.26 (burn #33, live 2026-10-22): split-by-space plus glued suffix —
    // "ThIrTy FiV eUm ... TwElVe ... total" parsed [30,12] → 42.00,
    // verified WRONG (true 35+12=47). Five never parsed (e glued to "Um").
    // Must hold unsure. Clean spaced-right-boundary control must solve.
    { name: "v7.26 burn #33 verbatim (live 2026-10-22)", text: "Th]iS lO^bSt-Er'S ClAw] FoR^cE Is ThIrTy    FiV eUm, Um AnD- ANoT]hEr ClAw| CoNTrIbuTeS TwElVe~ NeUToNs, HoW MaNy ToTaL- NeUToNs/?", wantUnsure: true },
    { name: "v7.26 clean spaced five control", text: "claw force is thirty five newtons, and another claw contributes twelve newtons, how many total newtons?", want: "47.00" },
    // v7.27 (burn #34, live 2026-10-22): inserted-vowel rate nouns —
    // "23 cEmSs pEr sEcUm ... 7 cEmS pEr sEcUm added" fired per-unit
    // 23x7=161.00, verified WRONG (true 23+7=30: two velocities added).
    // fuzz can't cross the inserted vowels; the glued suffixes kill the
    // right guard. Vowel-tolerant rate detection must hold it unsure.
    { name: "v7.27 burn #34 verbatim (live 2026-10-22)", text: "A] lO^bStEr S[wImS/ lOoOobsssTeR-ly Um] aT^ tWe/NtY tHrEe] cEmSs/ pEr- sEcUm, LaTeR+ sEvEn] cEmS^ pEr\\ sEcUm Is] aDdEd, HoW/ mUcH~ tOtAl FoRcE?", wantUnsure: true },
    // v7.27: clean per-unit with no rate context still multiplies.
    { name: "v7.27 clean per-unit unaffected", text: "each worker claw exerts nine newtons of grip, and three claws pull together, what is the grip force?", want: "27.00" },
    // v7.28 (burn #35, live 2026-10-22): substituted-letter ones beside
    // tens — "TwWeEnNtTy YyOoUuR fOoRr ... * ... SsIiX" ("four" with F→Y,
    // or "your"; malformed either way) fed [20,20,6] to the star branch
    // → 120.00, verified WRONG. Must hold unsure. Clean "twenty four"
    // control must keep solving.
    { name: "v7.28 burn #35 verbatim (live 2026-10-22)", text: "A] LoOoBbSstTeEr^ ClLaWw ]ExXeRrTs- TwWeEnNtTy YyOoUuR fOoRr- FfOoRr CpR^eSsSuuRee, Um- AnDd- DuRiInNg~ MoLlTtInGg ItT ]MuUlTlTiPlLiIeEs- By^ TwWeEnNtTy - * - SsIiX, HoOwW- MmUcChH ToOtAlL FfOoRrCcEe?", wantUnsure: true },
    { name: "v7.28 clean twenty-four control", text: "claw exerts twenty four newtons and the other adds twenty newtons, how much total force?", want: "44.00" },
    // v7.29 (burn #36, live 2026-10-21): spaced-interior ones fused with
    // tens — "fIfTy fiV E ... tWeNtY thReE ... total" parsed [55,23] →
    // 78.00, verified WRONG (true unknowable). Must hold unsure. Clean
    // intact-word fusion control must keep solving.
    { name: "v7.29 burn #36 verbatim (live 2026-10-21)", text: "A] lO^bSt-Er sWiMmS uP aNd[ cLaW eXerTs fIfTy fiV E] nEuWtO ns~ aNd] tHe OtHeR cLaW~ eXerTs tWeNtY thReE ] nEuWtOnS, hOw/ mUcH] tOtAl^ fOrCe?", wantUnsure: true },
    { name: "v7.29 clean fifty-five control", text: "claw force is fifty five newtons and the other exerts twenty three newtons, how much total force?", want: "78.00" },
    // v7.30 (burn #37, live 2026-10-22): bare agent-count in a total-shape —
    // "claw applies twelve newtons ... grapples two opponents ... total
    // force" parsed [12,2] → 14.00, verified WRONG (true 12: opponents are
    // a count, not a force). opponent/rival join COUNT_NOUNS, so the lone
    // survivor plus an exclusion holds unsure. Anchored control ("two
    // newtons") must keep summing.
    { name: "v7.30 burn #37 verbatim (live 2026-10-22)", text: "A] lOoObSsTtEr' S^ cLaW- ApPlIiEs- TwElVe~ nEeWwToNs, uMm| aNd/ wHeN- iT gRaPpLeS- tWo{ oPpOnEnTs, wHaT| iS- tHe/ tOtAl^ fOoRrCe?", wantUnsure: true },
    { name: "v7.30 anchored two-newtons control", text: "claw applies twelve newtons and another claw adds two newtons, what is the total force?", want: "14.00" },
    // v7.31 (burn #38, live 2026-10-22): "and a half" fractions. "THIRTY
    // TWO * TWO AND A HALF ... TOTAL FORCE" parsed [32,2] → 64.00,
    // verified WRONG (true 32x2.5=80): the fractional tail belonged to
    // the two and never attached. Must absorb and solve. Controls: the
    // multiply-family variant, the quarter variant, and the no-fraction
    // control proving attachment is conditional, not greedy.
    { name: "v7.31 burn #38 verbatim (live 2026-10-22)", text: "A] lOoObBsStTeErR'S ^ cLlAaWw] FoOrRcE| IiSs tHhIiRrTtYy TwWoOo ^ *] TwWoOo AnNdD A HaAlLf ~ dUrRiInNgG dOoMmIiNnAaNcCe-FfIiGgHhTt, wHhAaTt IiSs TtHhEe TtOoTtAaLl FfOoRrCcEe?", want: "80.00" },
    { name: "v7.31 multiply-family fraction control", text: "claw exerts forty newtons and molting multiplies it by one and a quarter, whats the new force?", want: "50.00" },
    { name: "v7.31 no-fraction control unaffected", text: "claw exerts thirty two newtons and the other adds two newtons, what is total force?", want: "34.00" },
    // v7.32 (burn #39, live 2026-10-22): inserted-letter ones beside
    // tens — "TwEnTy FoIvE nEu-ToNs ... FiFtEeN ... total" parsed
    // [20,15] → 35.00, verified WRONG (true 25+15=40): the inserted O
    // hides "five", the tens never fuses, 20 stands false. Must hold
    // unsure. Variant with a different tens/ones pair, plus the clean
    // intact-fusion control proving insertion-only trips.
    { name: "v7.32 burn #39 verbatim (live 2026-10-22)", text: "A] LoO bS sTeRrR S^wIiMmS uP/ aN]d LoOkS LiKe Um, ItS ClAw^ ExErTs TwEnTy FoIvE ]nEu-ToNs ~ AnD| iTs OtHeR ClAw^ GiVeS FiFtEeN nEuToNs, HoW/ MaNy ToTaL FoRcE<?", wantUnsure: true },
    { name: "v7.32 inserted-letter variant", text: "claw exerts forty sevien newtons and the other adds ten newtons, what is the total force?", wantUnsure: true },
    { name: "v7.32 clean thirty-five control", text: "claw exerts thirty five newtons and the other exerts fifteen newtons, what is the total force?", want: "50.00" },
    // v7.33 (held live 2026-10-23, burn-#11 class): star branch fired before
    // the total vetoes on "fourteen newtons * ... three times stronger ...
    // total force" (would have answered 14*3=42; true 42 or 56, unknowable).
    // Star+total+multiply-signal now holds unsure. Pure star without total
    // words keeps solving (live 204.00 precedent).
    { name: "v7.33 star-total-times verbatim (held 2026-10-23)", text: "A] Lo-O bS tErR^ ClAw^ FoR cE^ Is^ FoUrTeEn ] NeW tOnS ^ * ~ AnD } ThE OtHeR } ClAw^ Is^ ThReE TiMeS StRoNgEr, WhAt Is ToTaL FoRcE?", wantUnsure: true },
    { name: "v7.33 pure star control unaffected", text: "claw exerts thirty four newtons * the other gives six newtons, what is the force?", want: "204.00" },
    // v7.34 (burn #40, live 2026-10-26): substituted-letter ones beside
    // tens THROUGH a bracket — "[fOrTy] fIfE}" parsed [40,10] → 50.00,
    // verified WRONG (true 45+10=55): "fIfE" is "five" with V→F, hidden
    // behind "]", which the spaces-only separator skip could not cross.
    // Skip is now any non-letter run (both twin gates). Intact words at
    // distance 0 never trip; brackets elsewhere never matter.
    { name: "v7.34 burn #40 verbatim (live 2026-10-26)", text: "A] Do-MiNaNt lOoObSsTtEeR'S cLaW^ FoRcE iSs [fOrTy] fIfE} nEeWtOnS- aNd| tHe ChAlLeNgeR aDdS~ tEeN< nEuWtOnS, wHaT/ iS tHe ToTaL} fOrCe?", wantUnsure: true },
    { name: "v7.34 inserted-letter through bracket", text: "claw exerts [twenty] foive newtons and the other gives fifteen newtons, how much total force?", wantUnsure: true },
    { name: "v7.34 brackets-elsewhere control", text: "claw exerts forty five [newtons] and the challenger adds ten newtons, what is the new force?", want: "55.00" },
    // v7.35 (burn #41, live 2026-10-26): letter "x" as multiply in a
    // total-shape — verbatim summed 30.00, verified WRONG (true 161).
    // Must hold unsure. Figure-gating control (x with no figure after
    // it) must keep summing.
    { name: "v7.35 burn #41 verbatim (live 2026-10-26)", text: "L oObBsT-Err] ClAw^ FoRcE/ ApPlIeD {tW eNtY} tHrEe~ x SeV eN<, hOw/ mUcH ToTaL| FoRcE?", wantUnsure: true },
    { name: "v7.35 letter-x variant", text: "claw applies twenty newtons x four rivals, what is total force?", wantUnsure: true },
    { name: "v7.35 trailing-x control still sums", text: "claw exerts twenty three newtons and another adds seven newtons, what is total x force?", want: "30.00" },
    // v7.36 (burn #42, live 2026-10-26): deletion-damaged ones beside
    // damaged tens — "tW eN tY TrEe" parsed [20,7] → 27.00, verified
    // WRONG (true 23+7=30): "three" minus its h never parses, the tens
    // never fuses. Must hold unsure. Damaged-tens-plus-intact-ones
    // control still fuses and solves (gate needs the near-miss token).
    { name: "v7.36 burn #42 verbatim (live 2026-10-26)", text: "A] lO-bS tEr^ lOoObSsTtErr S^wImS iN aN oCeaN rIpPlE, uMh, aNd] dOmInAnT cLaW fIgHtInG iNtErAcTiOnS mAkE iT pUsH, cLaW fOrCe OoNe Is ThIrTy nEeWtOnS, cLaW fOrCe TwO Is TwEnTy FoUr nEeOwToNs, wHaT iS tOtAl fOrCe?", wantUnsure: true },
    { name: "v7.36 damaged-tens intact-ones control", text: "claw swims at tW eN tY three meters per second and slows by seven, what is new speed?", want: "16.00" },
    // v7.37 (burn #44, live 2026-10-27): star glued to a plus verb is
    // decoration — "InCrEaSeS* By SeVeN ... total" star-solved 161.00,
    // verified WRONG (true 23+7=30). Must fall through to the plus sum.
    // Controls: digit-glued and spaced stars still multiply;
    // multiply-verb-glued star still reaches the multiply veto (unsure).
    { name: "v7.37 burn #44 verbatim (live 2026-10-27)", text: "A] LoOoBbS sT-ErS^ ClAw| FoRcE~ Is/ TwEnTy ThReE } NoOtOnS, AnD/ InCrEaSeS* By\\\\ SeVeN < NoOtOnS, WhAt'S] ToTaL- FoRcE?", want: "30.00" },
    { name: "v7.37 noun-glued star control", text: "claw force is thirty four newtons, rival claw*gives six newtons, what is total force?", want: "204.00" },
    { name: "v7.37 multiply-verb-glued star control", text: "claw exerts thirty two newtons, its mate multiplies*by four, what is total force?", wantUnsure: true },
    // v7.38 (burn #46, live 2026-10-28): accelerate rate×duration is
    // unsure (23+7=30.00 verified WRONG, true 23*7=161). The clean
    // accelerates-by shape (v7.8 case above, no for/duration window)
    // must keep solving 28.00.
    { name: "v7.38 burn #46 verbatim (live 2026-10-28)", text: "claw accelerates at twenty three meters per second squared for seven seconds, what is change in velocity?", wantUnsure: true },
    { name: "v7.38 burn #46 digit variant", text: "claw accelerates at 23 m/s for 7 seconds, what is the change in velocity?", wantUnsure: true },  ];
  return cases.map((c) => {
    const r = parseChallenge(c.text);
    const pass = c.wantUnsure ? !!r.unsure : (r.sure && r.answer === c.want);
    return { name: c.name, pass, got: r.unsure ? "unsure(" + r.reason + ")" : r.answer, want: c.wantUnsure ? "unsure" : c.want };
  });
}

// Forensics helper: expose the collected figures (with spans) so worker
// output can echo norm+nums on every solve (see doctrine §6).
export function debugNumbers(text) {
  return collectNumbers(text).map((n) => ({ value: n.value, start: n.start, end: n.end }));
}

export default { parseChallenge, runSolverTests };
