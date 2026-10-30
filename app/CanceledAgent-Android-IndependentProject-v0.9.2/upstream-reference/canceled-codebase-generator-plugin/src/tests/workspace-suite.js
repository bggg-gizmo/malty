window.__TEST_OFFLINE__ = true;
const checks = [];
const push = (name, cond, detail) => checks.push({check: name, pass: !!cond, detail: detail || ""});
const collab = window.collab || (window.root && window.root.collab);
push("collab-api-exists", !!(collab && collab.list && collab.create && collab.read && collab.remove), typeof collab);
if (!collab || !collab.list) return {suite: "workspace", pass: false, checks: checks};
try { await collab.ready; } catch (e) {}
const names = () => collab.list().map(f => f.name).sort();
const before = names();
let activeBefore = null;
try { const a = collab.getActive ? collab.getActive() : null; activeBefore = a ? a.name : null; } catch (e) {}
const ws = ((window.root && window.root.collabConfig) || {}).workspace || "collab";
const folder = (window.root && window.root.kv && window.root.kv[ws]) || null;
const SLOT9 = "__test_slot9_live_backup";
const SLOT8 = "__test_slot8_scratch.txt";
const CONTENT = "slot8 deterministic probe 2026-10-08";
const snap = JSON.stringify({names: before, active: activeBefore, at: "2026-10-08"});
let backupOk = false;
try {
  if (folder) { await folder.set(SLOT9, snap); backupOk = (await folder.get(SLOT9)) === snap; }
  else { localStorage.setItem("collab:" + ws + ":" + SLOT9, JSON.stringify(snap)); backupOk = localStorage.getItem("collab:" + ws + ":" + SLOT9) === JSON.stringify(snap); }
} catch (e) { backupOk = false; }
push("slot9-backup-write-read", backupOk, folder ? "kv" : "localStorage");
try { if (collab.read(SLOT8) != null) collab.remove(SLOT8); } catch (e) {}
let created = null;
try { created = await collab.create(SLOT8, CONTENT); } catch (e) { created = null; }
push("slot8-create", !!created, created ? created.name : "create failed");
push("slot8-read-matches", collab.read(SLOT8) === CONTENT, String((collab.read(SLOT8) || "").length) + " bytes");
push("slot8-listed", names().includes(SLOT8), names().join(","));
const feed = document.querySelector("#activityFeed");
push("activity-feed-node-present", !!feed, feed ? "non-null" : "null");
try { collab.remove(SLOT8); } catch (e) {}
try { if (activeBefore) collab.open(activeBefore); } catch (e) {}
const after = names();
push("live-restored-identical", JSON.stringify(after) === JSON.stringify(before), after.join(","));
try {
  if (folder) { await folder.delete(SLOT9); }
  else { localStorage.removeItem("collab:" + ws + ":" + SLOT9); }
} catch (e) {}
return {suite: "workspace", pass: checks.every(c => c.pass), checks: checks};
