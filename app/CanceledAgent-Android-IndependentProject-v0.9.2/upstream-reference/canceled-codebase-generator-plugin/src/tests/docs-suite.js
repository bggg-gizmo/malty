window.__TEST_OFFLINE__ = true;
const files = ["src/CHAT_LOG.md","src/ISSUES_FIXES.md","src/CODE_REFERENCE.md","src/change.log","src/treeshake-worklist.md","src/MEMORY_CUBE.md","src/README.md","src/SPEC.md","src/TODO.md","src/tests/run.js","src/tests/docs-suite.js","src/tests/workspace-suite.js","src/tests/project-suite.js","src/RULE_X_FORCE_THE_WIRE.md","src/RULE_F_FACTORY_BUILD.md","src/REVERSE_TREESHAKE_SEQ.md","src/RULE_C_CAMPAIGN.md","src/tests/design-suite.js","src/design-audit/RULES.md"];
const checks = [];
for (const p of files) {
  let pass = false, detail = "";
  try {
    const r = await fetch(p);
    const t = r.ok ? await r.text() : "";
    pass = r.ok && t.length > 0;
    detail = r.ok ? (t.length + " bytes") : ("http " + r.status);
  } catch (e) {
    detail = String((e && e.message) || e);
  }
  checks.push({check: p, pass: pass, detail: detail});
}
const rootDocs = await (async () => {
  try {
    const r = await fetch("src/CODE_REFERENCE.md");
    const t = await r.text();
    return t.includes("CHAT_LOG") && t.includes("change.log") && t.includes("MEMORY_CUBE");
  } catch (e) { return false; }
})();
checks.push({check: "code-reference-indexes-guideline-files", pass: rootDocs, detail: rootDocs ? "map present" : "map missing"});
return {suite: "docs", pass: checks.every(c => c.pass), checks: checks};
