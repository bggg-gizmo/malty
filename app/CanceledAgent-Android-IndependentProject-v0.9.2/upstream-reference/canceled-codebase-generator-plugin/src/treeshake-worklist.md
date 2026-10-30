# treeshake-worklist

Reverse-treeshake audits: one bounded scope per row. For each row, read producer + consumer side-by-side, diff the contract by eye (names, shapes, units, IDs, nullability), state one concrete failing scenario per finding (or no finding), fix at the seam, lock the shape in a test, mark DONE/CLEAN/DEFERRED. Null-controls required: prove off-means-unchanged and neutral-input-stays-neutral as test assertions.

| Scope | Producer ↔ Consumer | Contract | Failing scenario | Status |
| --- | --- | --- | --- | --- |
| Test suites ↔ `collab` API | `src/tests/*.js` ↔ `api` object in `index.html` (`list/read/create/remove/open/getActive`, kv folder `__test_slot9_live_backup`) | `create(name,text)` → file readable via `read(name)`; `remove(name)` → name absent from `list()`; backup key round-trips exact snapshot string | Suite writes scratch file but `list()` still contains it after `remove()` → restore check fails, live session polluted | DONE 2026-10-08 — locked by `workspace-suite.js` restore assertion |
| — | Next audit scope goes here | — | — | OPEN |
