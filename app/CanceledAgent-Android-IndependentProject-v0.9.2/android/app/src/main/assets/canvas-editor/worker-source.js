/*
  Resolves the Canvas Editor worker source.

  In development (this generator's own page) the worker file is fetched from
  `src/canvas-editor/editor-worker.js` next to this module. In the published
  plugin bundle the entire worker source is inlined at build time as
  `__CE_WORKER_SRC__` (see src/canvas-editor/BUILD.md), so the bundle is a
  single self-contained ES module with no runtime fetches.
*/
export async function getWorkerSource() {
  if (typeof __CE_WORKER_SRC__ === "string" && __CE_WORKER_SRC__) return __CE_WORKER_SRC__;
  const res = await fetch(new URL("./editor-worker.js", import.meta.url));
  if (!res.ok) throw new Error("canvas-editor: cannot load worker (" + res.status + ")");
  return await res.text();
}
