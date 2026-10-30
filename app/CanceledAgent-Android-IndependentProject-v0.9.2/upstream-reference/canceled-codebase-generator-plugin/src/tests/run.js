window.__TEST_OFFLINE__ = true;
const AsyncFn = Object.getPrototypeOf(async () => {}).constructor;
const suites = ["src/tests/docs-suite.js", "src/tests/workspace-suite.js", "src/tests/project-suite.js", "src/tests/design-suite.js"];
const results = [];
for (const p of suites) {
  const r = await fetch(p);
  const t = await r.text();
  const out = await (new AsyncFn(t))();
  results.push(out);
}
return {suite: "run", pass: results.every(s => s && s.pass), suites: results};
