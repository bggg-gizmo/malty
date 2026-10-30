/*
  store.js — the tiny async {get,set,delete} storage interface the mini-git
  needs, with dependency-free default implementations:

    createIdbStore(name)    -> IndexedDB-backed (large capacity, survives reload)
    createMemoryStore()     -> in-memory (fallback when IDB is unavailable)

  When this plugin is imported into another generator there is nothing to wire
  up: createProjectPanel() falls back to an IndexedDB store named after the host
  generator, so history persists per-generator without the host needing kv.
*/

export function createMemoryStore() {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    set: async (k, v) => { m.set(k, v); },
    delete: async (k) => { m.delete(k); },
  };
}

export function createIdbStore(name) {
  name = name || "canvas-editor-git";
  let dbP = null;

  function open() {
    if (dbP) return dbP;
    dbP = new Promise((resolve, reject) => {
      if (typeof indexedDB === "undefined") { reject(new Error("no indexedDB")); return; }
      const req = indexedDB.open(name, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains("kv")) db.createObjectStore("kv");
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error || new Error("indexedDB open failed"));
    });
    return dbP;
  }

  const done = (t) => new Promise((resolve, reject) => {
    t.oncomplete = () => resolve();
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });

  async function get(k) {
    const db = await open();
    const req = db.transaction("kv", "readonly").objectStore("kv").get(k);
    const v = await new Promise((resolve, reject) => {
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return v === undefined ? null : v;
  }

  async function set(k, v) {
    const db = await open();
    const t = db.transaction("kv", "readwrite");
    t.objectStore("kv").put(v, k);
    await done(t);
  }

  async function del(k) {
    const db = await open();
    const t = db.transaction("kv", "readwrite");
    t.objectStore("kv").delete(k);
    await done(t);
  }

  return { get: get, set: set, delete: del };
}

/* Best available default store for a given generator/scope name. */
export function createDefaultStore(name) {
  try {
    if (typeof indexedDB !== "undefined") return createIdbStore("canvas-editor-git:" + (name || "default"));
  } catch (e) { /* fall through */ }
  return createMemoryStore();
}

export default { createMemoryStore, createIdbStore, createDefaultStore };
