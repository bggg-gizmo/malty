window.__TEST_OFFLINE__ = true;
const checks = [];
const push = (name, cond, detail) => checks.push({check: name, pass: !!cond, detail: detail || ""});
const mod = await import("./src/canvas-editor/project.js");
push("project-exports", !!(mod.fetchHostProject && mod.discoverRefs && mod.discoverDocs && mod.isProsePath && mod.buildTree), "fetch/discover/prose/tree");
push("prose-md", mod.isProsePath("src/CHAT_LOG.md") === true, "md skipped");
push("prose-markdown", mod.isProsePath("notes.markdown") === true, "markdown skipped");
push("prose-txt", mod.isProsePath("AGENTS.md".replace("md", "txt")) === true, "txt skipped");
push("prose-log", mod.isProsePath("src/change.log") === true, "log skipped");
push("prose-no-js", mod.isProsePath("src/canvas-editor/index.js") === false, "js scanned");
push("prose-no-json", mod.isProsePath("src/plugins/index.json") === false, "json scanned");
push("prose-no-pjs", mod.isProsePath("main.pjs") === false, "pjs scanned");
const proseLine = "Paste the pointer block from src/adopt-main-snippet.pjs at the very top of main.pjs.";
push("discover-broad", [...mod.discoverRefs(proseLine)].includes("src/adopt-main-snippet.pjs"), "heuristic still finds mentions");
const realRef = 'import { x } from "./x.js"; see src/canvas-editor/git.js';
push("discover-real", [...mod.discoverRefs(realRef)].includes("src/canvas-editor/git.js"), "real ref found");
const realFetch = window.fetch;
let saw404 = false;
window.fetch = async (url) => {
  if (String(url).indexOf("perchance.org/api/") >= 0) throw new Error("offline");
  saw404 = true;
  return {ok: false, status: 404, text: async () => ""};
};
let proj = null, fetchErr = "";
try {
  proj = await mod.fetchHostProject({
    generatorName: "__offline_probe__",
    fetchText: async () => { throw new Error("offline"); },
    extraFiles: ["src/mentioned-only.pjs", "scratch/shots/gone.png"]
  });
} catch (e) { fetchErr = String((e && e.message) || e); }
window.fetch = realFetch;
push("fetch-offline-ran", !!proj, fetchErr || "ok");
push("fetch-404-silent", !!proj && proj.failed.filter((f) => /HTTP 404/.test(f.error)).length === 0, proj ? JSON.stringify(proj.failed.slice(0, 4)) : fetchErr);
push("fetch-404-unlisted", !!proj && proj.files["src/mentioned-only.pjs"] == null && proj.files["scratch/shots/gone.png"] == null, "mentions absent from tree");
return {suite: "project", pass: checks.every(c => c.pass), checks: checks};
