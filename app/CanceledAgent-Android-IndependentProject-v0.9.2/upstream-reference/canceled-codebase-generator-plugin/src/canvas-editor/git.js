/*
  git.js — a tiny, dependency-free, content-addressed version-control store.

  It is deliberately git-shaped: a commit pins a *tree* (path -> blob hash),
  blobs are deduplicated by content hash, commits form a parent chain, and each
  commit stores the unified patch that produced it so the log/diff view is
  instant. It is *not* a real git implementation (no branches, merges, packs) —
  but it records a faithful, append-only history of "the project it is plugged
  into", with changes logged.

  Storage is injected so it can sit on kv-plugin, localStorage, memory, etc:
    const repo = createRepo({ name: "collab", store: {
      get: (k) => kv.folder.get(k), set: (k, v) => kv.folder.set(k, v), delete: (k) => kv.folder.delete(k),
    }});
    await repo.commit({ "main.js": "..." }, { message: "first", author: "user" });
    const log = await repo.log();
    const diff = await repo.show(log[0].id);
*/

import { unifiedDiff, diffStat } from "./diff.js";

/* 53-bit FNV-1a-ish content hash, hex. Includes length to shrink collisions. */
export function hashText(text) {
  text = String(text == null ? "" : text);
  let h1 = 0x811c9dc5, h2 = 0x01000193;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    h1 ^= c; h1 = (h1 * 0x01000193) >>> 0;
    h2 = ((h2 + c) * 0x85ebca6b) >>> 0; h2 ^= h2 >>> 13;
  }
  return (h1 >>> 0).toString(16).padStart(8, "0") + (h2 >>> 0).toString(16).padStart(8, "0") + "-" + text.length.toString(16);
}

const J = (v) => JSON.stringify(v);
const P = (v, d) => { try { return v == null ? d : JSON.parse(v); } catch (e) { return d; } };

export function createRepo(opts) {
  opts = opts || {};
  const store = opts.store;
  if (!store) throw new Error("createRepo: a store with get/set/delete is required");
  const name = opts.name || "default";
  const maxCommits = opts.maxCommits || 250;
  const key = {
    refs: "git:" + name + ":refs",
    commits: "git:" + name + ":commits",
    blobs: "git:" + name + ":blobs",
    blob: (h) => "git:" + name + ":blob:" + h,
  };

  async function readRefs() { return P(await store.get(key.refs), { head: null }); }
  async function writeRefs(r) { await store.set(key.refs, J(r)); }
  async function readCommits() { return P(await store.get(key.commits), []); }
  async function writeCommits(list) { await store.set(key.commits, J(list)); }
  async function readBlobIndex() { return P(await store.get(key.blobs), []); }
  async function writeBlobIndex(list) { await store.set(key.blobs, J(list)); }

  async function readBlob(h) { const t = await store.get(key.blob(h)); return t == null ? "" : t; }
  async function writeBlob(h, text) {
    const existing = await store.get(key.blob(h));
    if (existing != null) return;
    await store.set(key.blob(h), text);
    const idx = await readBlobIndex();
    if (!idx.includes(h)) { idx.push(h); await writeBlobIndex(idx); }
  }

  async function head() {
    const refs = await readRefs();
    if (!refs.head) return null;
    const commits = await readCommits();
    return commits.find((c) => c.id === refs.head) || null;
  }

  async function commit(files, meta) {
    meta = meta || {};
    const parent = await head();
    const parentTree = parent ? parent.tree : {};
    const tree = {};
    const paths = Object.keys(files || {}).sort();
    for (const p of paths) tree[p] = hashText(files[p]);

    /* work out what changed relative to the parent tree */
    const changed = [];
    const all = new Set([...Object.keys(parentTree), ...Object.keys(tree)]);
    for (const p of [...all].sort()) {
      const before = parentTree[p] || null;
      const after = tree[p] || null;
      if (before === after) continue;
      const status = before == null ? "added" : after == null ? "deleted" : "modified";
      changed.push({ path: p, status: status, before: before, after: after });
    }

    if (!changed.length) return { unchanged: true, commit: null, parent: parent };

    /* persist blobs for the new tree (only the changed content is new) */
    for (const c of changed) {
      if (c.after != null) await writeBlob(c.after, files[c.path]);
    }

    /* per-file patches + aggregate stats */
    const patches = [];
    let added = 0, removed = 0;
    for (const c of changed) {
      const beforeText = c.before == null ? "" : (parentTree[c.path] ? await readBlob(c.before) : "");
      const afterText = c.after == null ? "" : files[c.path];
      const st = diffStat(beforeText.split("\n"), afterText.split("\n"));
      added += st.added; removed += st.removed;
      const patch = unifiedDiff(beforeText, afterText, { path: c.path });
      patches.push({ path: c.path, status: c.status, added: st.added, removed: st.removed, patch: patch });
    }

    const at = meta.at || Date.now();
    const author = meta.author || "user";
    const id = hashText(J({ tree: tree, parent: parent ? parent.id : null, message: meta.message || "", author: author, at: at })).slice(0, 16);
    const record = {
      id: id, parent: parent ? parent.id : null, tree: tree,
      message: meta.message || "", author: author, at: at,
      stats: { files: changed.length, added: added, removed: removed },
      changes: patches.map((p) => ({ path: p.path, status: p.status, added: p.added, removed: p.removed })),
      patch: patches.map((p) => p.patch).join(""),
    };

    const commits = await readCommits();
    commits.unshift(record);
    const kept = commits.slice(0, maxCommits);
    await writeCommits(kept);
    await writeRefs({ head: id });
    await gc(kept);
    return { unchanged: false, commit: record, parent: parent };
  }

  /* drop blobs no longer referenced by any kept commit */
  async function gc(commits) {
    const referenced = new Set();
    for (const c of commits) for (const h of Object.values(c.tree)) referenced.add(h);
    const idx = await readBlobIndex();
    const keptIndex = [];
    for (const h of idx) {
      if (referenced.has(h)) { keptIndex.push(h); continue; }
      await store.delete(key.blob(h));
    }
    await writeBlobIndex(keptIndex);
  }

  async function log(limit) {
    const commits = await readCommits();
    return limit ? commits.slice(0, limit) : commits;
  }

  async function show(id) {
    const commits = await readCommits();
    const c = id ? commits.find((x) => x.id === id || x.id.startsWith(id)) : commits[0];
    return c || null;
  }

  async function diff(id) {
    const c = await show(id);
    return c ? c.patch : "";
  }

  async function files(id) {
    const c = await show(id);
    return c ? Object.keys(c.tree).sort() : [];
  }

  async function read(path, id) {
    const c = await show(id);
    if (!c) return null;
    const h = c.tree[path];
    if (h == null) return null;
    return readBlob(h);
  }

  async function changelog(limit) {
    const commits = await log(limit);
    return commits.map((c) => ({
      id: c.id, at: c.at, author: c.author,
      message: c.message || "(no message)",
      files: c.changes.map((x) => x.path),
      filesChanged: c.stats.files, added: c.stats.added, removed: c.stats.removed,
    }));
  }

  async function reset() {
    const idx = await readBlobIndex();
    for (const h of idx) await store.delete(key.blob(h));
    await writeBlobIndex([]);
    await writeCommits([]);
    await writeRefs({ head: null });
  }

  return {
    name: name,
    commit: commit, log: log, show: show, diff: diff, files: files, read: read,
    head: head, changelog: changelog, reset: reset, hashText: hashText,
  };
}

export default { createRepo, hashText };
