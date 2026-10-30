window.__TEST_OFFLINE__ = true;
const checks = [];
const push = (name, cond, detail) => checks.push({check: name, pass: !!cond, detail: detail || ""});
const mod = await import("./src/design-audit/rules.js");
const audit = await import("./src/design-audit/audit.js");

push("registry-61", mod.REGISTRY.length === 61, mod.REGISTRY.length + " rows");
for (const id of ["side-tab", "low-contrast", "em-dash-overuse", "script-error", "nested-cards", "skipped-heading"]) {
  push("registry-has-" + id, mod.REGISTRY.some((r) => r.id === id), id);
}
const sevOf = (id) => mod.pseverity(mod.REGISTRY.find((r) => r.id === id));
push("sev-error-P0", sevOf("script-error") === "P0", "script-error");
push("sev-slop-P1", sevOf("side-tab") === "P1", "side-tab");
push("sev-quality-P2", sevOf("low-contrast") === "P2", "low-contrast");
push("sev-advisory-P3", sevOf("em-dash-overuse") === "P3", "em-dash-overuse");
push("grade-A", mod.gradeFor(95) === "A", "95");
push("grade-B", mod.gradeFor(80) === "B", "80");
push("grade-C", mod.gradeFor(60) === "C", "60");
push("grade-D", mod.gradeFor(40) === "D", "40");
push("grade-F", mod.gradeFor(10) === "F", "10");

const dashCases = [
  ["a — b — c", 2], ["a--b", 1], ["a-- b", 0], ["----x", 0], ["---x", 0],
  ["word---word", 1], ["|---|---|", 0], ["word----word", 0],
  ["----- Global (CDN) -----", 0], ["a--b ---- c--d", 2], ["--", 0], ["--x", 1],
];
for (const [text, want] of dashCases) {
  push("emdash-" + JSON.stringify(text), mod.countEmDashes(text) === want, "got " + mod.countEmDashes(text));
}
const dense = Array(9).join("word — ") + "end";
push("emdash-hit", mod.checkEmDashOveruse(dense).length === 1, "dense fires");
push("emdash-sparse", mod.checkEmDashOveruse("one — two, and a very long calm paragraph with no more dashes at all, just prose").length === 0, "sparse silent");
push("emdash-null", mod.checkEmDashOveruse(null).length === 0, "null silent");

const fix = document.createElement("div");
fix.id = "designAuditFixture";
fix.innerHTML = "<p>" + dense + "</p>";
document.body.appendChild(fix);
let fired = null;
try {
  fired = await audit.runDesignAudit({ target: "#designAuditFixture", max: 20 });
} finally { fix.remove(); }
push("audit-fires", !!fired && fired.findings.some((f) => f.rule === "em-dash-overuse"), fired ? fired.stats.findings + " findings" : "no result");
push("audit-shape", !!fired && typeof fired.score === "number" && !!fired.grade && !!fired.counts && !!fired.stats, "score/grade/counts/stats");

const clean = document.createElement("div");
clean.id = "designAuditClean";
clean.innerHTML = "<p>Plain calm prose with no dashes at all, just ordinary words.</p>";
document.body.appendChild(clean);
let calm = null;
try {
  calm = await audit.runDesignAudit({ target: "#designAuditClean", max: 20 });
} finally { clean.remove(); }
push("audit-clean-zero", !!calm && calm.findings.length === 0, calm ? calm.stats.findings + " findings" : "no result");
push("audit-clean-score", !!calm && calm.score === 100 && calm.grade === "A", calm ? calm.score + "/" + calm.grade : "no result");

push("buzz-hit", mod.checkBuzzwords("Our platform will streamline your workflow and supercharge your team").length === 1, "2 phrases fire once");
push("buzz-snippet", (mod.checkBuzzwords("Our platform will streamline your workflow and supercharge your team")[0] || {}).snippet.indexOf("2 buzzword phrases") === 0, "count + sample");
push("buzz-case", mod.checkBuzzwords("STREAMLINE YOUR day").length === 1, "case-insensitive");
push("buzz-repeat", mod.checkBuzzwords("revolutionize everything. Yes, revolutionize it all").length === 1, "repeat counts");
push("buzz-clean", mod.checkBuzzwords("Plain calm prose with ordinary words.").length === 0, "clean silent");
push("buzz-null", mod.checkBuzzwords(null).length === 0, "null silent");
push("buzzwords-29", mod.BUZZWORDS.length === 29, mod.BUZZWORDS.length + " phrases");
const aphor3 = "Not a feature. A platform. Not a product. A promise. Not a tool. A friend.";
const aphorHit = mod.checkAphoristicCadence(aphor3);
push("aphor-hit", aphorHit.length === 1, "3x NOT_A fires");
push("aphor-snippet", (aphorHit[0] || {}).snippet.indexOf("3 aphoristic constructions") === 0, "count + sample");
push("aphor-mix", mod.checkAphoristicCadence("We ship fast. No drama. We stay lean. Just results. Not a feature. A platform.").length === 1, "both patterns count");
push("aphor-two", mod.checkAphoristicCadence("Not a feature. A platform. Not a product. A promise.").length === 0, "2 stays silent");
push("aphor-clean", mod.checkAphoristicCadence("Plain calm prose with ordinary words.").length === 0, "clean silent");
push("theater-hit", mod.checkTheaterSlop("Another round of security theater, nothing more.").length === 1, "security theater fires");
push("theater-snippet", (mod.checkTheaterSlop("Another round of security theater.")[0] || {}).snippet === '"security theater"', "exact match quoted");
push("theater-building", mod.checkTheaterSlop("The historic grand theater reopens Friday.").length === 0, "building silent");
push("theater-clean", mod.checkTheaterSlop("Plain calm prose with ordinary words.").length === 0, "clean silent");
push("accent-rgb", mod.isAccentColor("rgb(180, 83, 9)") === true && mod.isAccentColor("rgb(120, 120, 130)") === false, "chroma spread 40");
push("accent-hex", mod.isAccentColor("#f00") === true && mod.isAccentColor("#888") === false, "hex");
push("accent-hsl", mod.isAccentColor("hsl(200, 50%, 50%)") === true && mod.isAccentColor("hsla(200, 10%, 50%, 0.5)") === false, "saturation 20");
push("accent-empty", mod.isAccentColor("") === false && mod.isAccentColor("var(--x)") === false, "empty/var silent");
push("numparse-two", (mod.parseNumberedLabelText("01") || {}).index === 1, "bare two-digit");
push("numparse-sep", (mod.parseNumberedLabelText("02 — Scale") || {}).index === 2, "index + separator + label");
push("numparse-long", mod.parseNumberedLabelText("This is just a sentence with words.") === null, "sentence silent");
push("numparse-big", mod.parseNumberedLabelText("99 — Beyond") === null, "index > 40 silent");
push("numparse-toolong", mod.parseNumberedLabelText("01 — " + Array(45).join("x")) === null, ">40 chars silent");
push("ceiling-follows", mod.labelSizeCeiling(36, 13) === 16 && mod.labelSizeCeiling(24, 13) === 13 && mod.labelSizeCeiling(0, 13) === 13, "13 / ratio / cap 16");
push("quotes-strip", mod.stripEdgeQuotes('"a"') === "a" && mod.stripEdgeQuotes("a") === "a", "edge quotes");
push("idlike", mod.isIdLikeClass("a565c83") === true && mod.isIdLikeClass("card") === false && mod.isIdLikeClass("text-gray-500") === false, "generated-id tokens");

const monoFix = document.createElement("div");
monoFix.id = "designMonoFixture";
let monoHtml = "";
for (let i = 0; i < 12; i++) monoHtml += '<span style="padding:16px">item ' + i + "</span>";
monoFix.innerHTML = monoHtml;
document.body.appendChild(monoFix);
let monoHit = null, monoClean = null;
try {
  monoHit = mod.checkMonotonousSpacing(monoFix);
  monoClean = mod.checkMonotonousSpacing(clean);
} finally { monoFix.remove(); }
push("mono-hit", monoHit && monoHit.length === 1, monoHit ? monoHit[0].snippet : "no result");
push("mono-snippet", !!monoHit && monoHit[0].snippet.indexOf("~16px used 48/48 times (100%)") === 0, monoHit ? monoHit[0].snippet : "no result");
push("mono-clean", !!monoClean && monoClean.length === 0, "varied spacing silent");

const repFix = document.createElement("div");
repFix.id = "designRepFixture";
repFix.innerHTML = '<div class="card" style="border:1px solid #999;border-radius:8px;background:#fff;padding:16px"><p class="a">Live status</p><span class="b">Live status</span><div class="c">Live status</div></div>';
document.body.appendChild(repFix);
let repHit = null;
try { repHit = mod.checkRepeatedContainerText(repFix); } finally { repFix.remove(); }
push("rep-hit", !!repHit && repHit.length === 1, repHit ? repHit[0].snippet : "no result");
push("rep-snippet", !!repHit && /3× in distinct spots/.test(repHit[0].snippet), repHit ? repHit[0].snippet : "no result");
const rep2 = document.createElement("div");
rep2.innerHTML = '<div class="card" style="border:1px solid #999;border-radius:8px;background:#fff"><p class="a">Live status</p><span class="b">Live status</span></div>';
document.body.appendChild(rep2);
let repTwo = null;
try { repTwo = mod.checkRepeatedContainerText(rep2); } finally { rep2.remove(); }
push("rep-two", !!repTwo && repTwo.length === 0, "2x silent");
const repSame = document.createElement("div");
repSame.innerHTML = '<div class="card" style="border:1px solid #999;border-radius:8px;background:#fff"><p class="dup">Same words here</p><p class="dup">Same words here</p><p class="dup">Same words here</p></div>';
document.body.appendChild(repSame);
let repSameRes = null;
try { repSameRes = mod.checkRepeatedContainerText(repSame); } finally { repSame.remove(); }
push("rep-samesig", !!repSameRes && repSameRes.length === 0, "same spot silent");

const numFix = document.createElement("div");
numFix.id = "designNumFixture";
numFix.innerHTML = '<div class="sec"><div class="lbl" style="font-size:11px;font-weight:700">01 — Deploy</div><h2>Instant deployment platform</h2></div><div class="sec"><div class="lbl" style="font-size:11px;font-weight:700">02 — Scale</div><h2>Global scale rehearsal</h2></div>';
document.body.appendChild(numFix);
let numHit = null;
try { numHit = mod.checkNumberedSectionLabels(numFix); } finally { numFix.remove(); }
push("num-hit", !!numHit && numHit.length === 2, numHit ? numHit.length + " findings" : "no result");
push("num-snippet", !!numHit && /tiny numbered label "01 — Deploy" beside h2/.test(numHit[0].snippet) && /\(2 on page\)/.test(numHit[0].snippet), numHit ? numHit[0].snippet : "no result");
const numOne = document.createElement("div");
numOne.innerHTML = '<div class="sec"><div class="lbl" style="font-size:11px;font-weight:700">01 — Deploy</div><h2>Instant deployment platform</h2></div>';
document.body.appendChild(numOne);
let numSingle = null;
try { numSingle = mod.checkNumberedSectionLabels(numOne); } finally { numOne.remove(); }
push("num-single", !!numSingle && numSingle.length === 0, "lone label silent");

const buzzFix = document.createElement("div");
buzzFix.id = "designBuzzFixture";
buzzFix.innerHTML = "<p>Our toolkit will streamline your release process today.</p>";
document.body.appendChild(buzzFix);
let buzzAudit = null, buzzFiltered = null;
try {
  buzzAudit = await audit.runDesignAudit({ target: "#designBuzzFixture", max: 20 });
  buzzFiltered = await audit.runDesignAudit({ target: "#designBuzzFixture", max: 20, rules: ["em-dash-overuse"] });
} finally { buzzFix.remove(); }
push("audit-buzz", !!buzzAudit && buzzAudit.findings.some((f) => f.rule === "marketing-buzzword"), buzzAudit ? buzzAudit.stats.findings + " findings" : "no result");
push("audit-only-filter", !!buzzFiltered && buzzFiltered.findings.length === 0, "rule filter holds");

let badTarget = "";
try { await audit.runDesignAudit({ target: "#no-such-el-anywhere" }); } catch (e) { badTarget = String((e && e.message) || e); }
push("audit-bad-target-loud", /no element matches/.test(badTarget), badTarget.slice(0, 60));

return {suite: "design", pass: checks.every(c => c.pass), checks: checks};
