/*
  Line diffing + unified-diff rendering. No dependencies.

  diffRows(A, B)  -> [{t:"ctx"|"add"|"del", a, b, an, bn}]
  diffStat(A, B)  -> {added, removed}
  unifiedDiff(oldText, newText, opts) -> text
*/

export function diffRows(A, B) {
  const n = A.length, m = B.length;
  const rows = [];
  if ((n + 1) * (m + 1) > 4_000_000) {
    for (const l of A) rows.push({ t: "del", a: l, b: null, an: 0, bn: null });
    for (const l of B) rows.push({ t: "add", a: null, b: l, an: null, bn: 0 });
    return rows;
  }
  const w = m + 1;
  const dp = new Int32Array((n + 1) * w);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i * w + j] = A[i] === B[j] ? dp[(i + 1) * w + j + 1] + 1 : Math.max(dp[(i + 1) * w + j], dp[i * w + j + 1]);
    }
  }
  let i = 0, j = 0, an = 1, bn = 1;
  while (i < n && j < m) {
    if (A[i] === B[j]) { rows.push({ t: "ctx", a: A[i], b: B[j], an: an++, bn: bn++ }); i++; j++; }
    else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) { rows.push({ t: "del", a: A[i], b: null, an: an++, bn: null }); i++; }
    else { rows.push({ t: "add", a: null, b: B[j], an: null, bn: bn++ }); j++; }
  }
  while (i < n) rows.push({ t: "del", a: A[i++], b: null, an: an++, bn: null });
  while (j < m) rows.push({ t: "add", a: null, b: B[j++], an: null, bn: bn++ });
  return rows;
}

export function diffStat(A, B) {
  let added = 0, removed = 0;
  for (const r of diffRows(A, B)) { if (r.t === "add") added++; else if (r.t === "del") removed++; }
  return { added, removed };
}

const CONTEXT = 3;

export function unifiedDiff(oldText, newText, opts) {
  opts = opts || {};
  const path = opts.path || "";
  const a = String(oldText == null ? "" : oldText).split("\n");
  const b = String(newText == null ? "" : newText).split("\n");
  const ctx = opts.context == null ? CONTEXT : opts.context;
  const rows = diffRows(a, b);
  const header = opts.header === false ? [] : ["--- a/" + path, "+++ b/" + path];

  /* collect change runs + surrounding context lines */
  const keep = new Array(rows.length).fill(false);
  rows.forEach((r, idx) => {
    if (r.t === "ctx") return;
    for (let k = Math.max(0, idx - ctx); k <= Math.min(rows.length - 1, idx + ctx); k++) keep[k] = true;
  });

  const out = header.slice();
  let i = 0;
  while (i < rows.length) {
    if (!keep[i]) { i++; continue; }
    let j = i;
    while (j < rows.length && keep[j]) j++;
    /* hunk [i, j) */
    const hunk = rows.slice(i, j);
    const aStart = hunk.find((r) => r.an != null);
    const bStart = hunk.find((r) => r.bn != null);
    const aCount = hunk.filter((r) => r.an != null).length;
    const bCount = hunk.filter((r) => r.bn != null).length;
    out.push("@@ -" + (aStart ? aStart.an : 1) + "," + aCount + " +" + (bStart ? bStart.bn : 1) + "," + bCount + " @@");
    for (const r of hunk) {
      if (r.t === "add") out.push("+" + r.b);
      else if (r.t === "del") out.push("-" + r.a);
      else out.push(" " + r.a);
    }
    i = j;
  }
  return out.join("\n") + (out.length ? "\n" : "");
}

export function countLines(text) {
  if (text == null || text === "") return 0;
  return String(text).split("\n").length;
}

export default { diffRows, diffStat, unifiedDiff, countLines };
