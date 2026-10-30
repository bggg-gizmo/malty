# CHAT_LOG

Append-only session log. One section per session: date, requests, decisions, what was built and verified. Never rewrite old sections. Re-orient from this file plus `src/README.md`, `src/SPEC.md`, `src/TODO.md`, `src/MEMORY_CUBE.md` after clearing chat.

## 2026-10-08 — Working guidelines adopted (option A)
- Requests: implement the 12 working guidelines; map test slots to kv backup keys; pause on every unexpected result; full test-harness scope confirmed.
- Decisions: slot 8 = workspace scratch file `__test_slot8_scratch.txt`, slot 9 = kv key `__test_slot9_live_backup` (+ localStorage fallback). Suites live in `src/tests/`, runnable via `page_eval` with `path`. No `index.html`/`main.pjs` behavior changes.
- Built: `src/CHAT_LOG.md`, `src/ISSUES_FIXES.md`, `src/CODE_REFERENCE.md`, `src/change.log`, `src/treeshake-worklist.md`, `src/MEMORY_CUBE.md`, `src/tests/run.js`, `src/tests/docs-suite.js`, `src/tests/workspace-suite.js`. Appended guideline-sync rows to README files table + TODO.
- Verified: docs suite green, workspace suite green (backup → scratch write/read → restore, list identical), aggregator green, page refreshed with no `perchanceErrors`.

## 2026-10-08 — Startup block carries the 12 working guidelines
- Requests: place the working-guideline instructions around `main.pjs` lines 42–48.
- Decisions: comment-only block (no pjs behavior change); startup header now points at the 7 guideline files + `src/tests/`; full 12-rule summary in a WORKING GUIDELINES comment block below the `startup` list. Recalled slot mapping (slot8 file / slot9 kv key) and strict pause protocol from MEMORY_CUBE before editing.
- Built: rewrote `main.pjs` READ FIRST/startup region; updated `src/CODE_REFERENCE.md` working-guidelines row to include the `main.pjs` pointer.
- Verified: refresh clean (no syntax/perchance errors), aggregator green (docs 13/13, workspace 8/8, live list restored identical), live pass: collab v1.0.0 lists 9 files, activity feed node present.

## 2026-10-09 — Explorer files priority + thinking-plugin diagnosis
- Requests: shrink Activity so Files never scrolls; why isn't the AI thinking plugin doing its job.
- Decisions: `#fileList` 46%→62% max-height plus `flex: 0 0 auto` (first attempt still scrolled — activityWrap's flex-shrink was stealing space; measured live: sidebar 504px, files capped at 134px). Comment-only CSS, no hooks changed.
- Built: two-line CSS change in `index.html` (`#fileList` max-height/flex).
- Verified: refresh clean, aggregator green, live: files 258/258 no-scroll, feed 138px scrolls internally, screenshot `scratch/shots/explorer-fix.png` shows all 9 files. Thinking: dock visible at (697,41), pipeline `agentAsk→saveThought→__aiThink.log("build")` intact; proved wire with labeled test entry (PiP 12→13, visible in dock, kv trace stored).

## 2026-10-09 — September thoughts cleared, chat recap populated
- Requests: clear all September thoughts, populate thoughts list from this chat.
- Decisions: explicit consent = the request itself (destructive wipe approved). Used `bridge.thought` per entry so PiP + kv + journal stay consistent; logged oldest-to-newest so newest sits on top.
- Built: 7 recap entries (backup, guidelines adoption, slot mapping, startup block, Explorer semantics, Explorer fix + flex gotcha, thinking diagnosis).
- Verified: after clear PiP 0/kv 0; after populate PiP 7/kv 7, newest = thinking diagnosis, oldest = session backup; aggregator green.

## 2026-10-09 — Landing mode builder
- Requests: toggles for blueprint protocol (Core/Full, sub-modes) building the activation prompt in a bottom text box. Decisions: published landing, no persistence, full prompt (Mode/Overlays + imports + adopt steps).
- Built: `Mode builder` section on `#publishLanding` (radios, checkboxes with protocol descriptions, readonly `#activationBox` + Copy button); import lines concatenated so the dependency scanner never sees a literal import sequence.
- Verified: refresh clean, default prompt (Full + all overlays) correct, toggle combo rebuilt live (Core + tilemap, plugins dropped, sprite import present), toggles reset to defaults, screenshot `scratch/shots/mode-builder.png`, aggregator green.

## 2026-10-09 — Emulate fixes: builder mirror + reachable exit
- Requests: builder missing from emulated preview; no way to exit emulation back to landing.
- Decisions: exit root-caused live (toolbar scrolls ≤820px, button at x=424 on a 390px phone) — one-line `order: -1` fix, no JS change. Builder mirrored statically into `buildEmulateDoc` with its own script + copy fallback (select-for-manual-copy, since sandboxed iframe may lack clipboard).
- Built: `#endEmulateBtn` first in toolbar; `EMULATE_BUILDER_*` consts wired into `buildEmulateDoc`.
- Verified: emulate doc has builder + balanced script tags (4/4); at 390px End button at x=4 visible without scroll, click returns to landing, flag cleared; viewport reset; aggregator green.

## 2026-10-09 — Landing top cut off
- Requests: card top extends above scroll reach.
- Decisions: flex-centering trap — one-line fix on both cards, no layout restructuring.
- Built: `margin: auto` on `#publishLanding .card` and the emulate `.card`.
- Verified: refresh clean, card top at padding offset with scrollTop=0, full scroll to bottom, aggregator green.

## 2026-10-09 — Builder into the starter project (the page actually previewed)
- Requests: user showed the preview-stage card still old — that document is `buildProjectDoc` from workspace starter files, not the landing. Correction accepted: three copies exist, first mirror missed this one.
- Decisions: live `index.html`/`styles.css` matched defaults (guarded replace) so full block update was safe; live `main.js` is user-extended (4209B vs ~700B default) so builder IIFE was APPENDED, never overwritten. Same content into `DEFAULT_FILES`. All edits revisioned, restorable via History.
- Built: starter card now 3 steps + working Mode builder (markup, styles, IIFE); defaults match.
- Verified: live files contain builder trio; link/script tags still match the preview inliner regexes; aggregator green. Hit Preview to see it.

## 2026-10-09 — Overlays untoggled + nested-newline fix
- Requests: overlay toggles removed (always on, baked into top bar); emulate builder defunct — confirmed a real syntax-error bug, not fine.
- Decisions: overlays hardcoded in all three builders, install steps unconditional; `String.fromCharCode(10)` wherever builder code lives inside another string.
- Built: removed overlay groups (landing, emulate, starter default + live); fixed join in emulate const + starter default (live already correct).
- Verified: landing prompt lists all overlays with no toggles, sub-mode toggle rebuilds; emulate doc balanced, no overlay ids; suites green.

## 2026-10-09 — Thinking install text + emulated builder truly interactive
- Requests: thinking overlay install text gets unified-pipeline details; emulated builder must click like the published page.
- Decisions: pipeline one-liner in all three builders + live main.js; click-hijack fixed at the EDIT_SCRIPT guard (not per-button hacks).
- Built: thinking text (seq/kind/parent/Trace/ratings TRACE-BACK); `#modeBuilder,#activationBox` + copy buttons bypass click-to-edit.
- Verified: hidden same-origin harness iframe — defaults build, tile/Core/image clicks rebuild with correct imports; suites green.

## 2026-10-09 — Pasted guidelines broke live main.js (the real no-prompt cause)
- Requests: prompt STILL not generating after all fixes.
- Decisions: stopped claiming composition-verified; compiled the live file — markdown header (`#` lines) glued to JS killed the whole script from char 1. Commented the header, touched nothing else.
- Built: 15-line header → `//` comments via guarded replace (revisioned).
- Verified: syntax OK; cleared console + fresh Preview shows only "Previewing index.html" + "plugin page ready", zero errors; suites green.

## 2026-10-09 — Phantom 404s under Project files (Unavailable group)
- Requests: `src/adopt-main-snippet.pjs`, `src/api.js`, `scratch/shots/explorer-fix.png`, `scratch/shots/mode-builder.png` all HTTP 404 below scratch local.
- Decisions: false positives from the reference crawler, not missing files — activation-prompt prose in index.html names another generator's files; `src/change.log` (a `.log`, outside the old md/txt skip) mentions screenshot paths. Fixed at the seam: 404 on a discovered ref now skips silently, genuine errors still surface.
- Built: `isProsePath()` (md/markdown/txt/log) in `src/canvas-editor/project.js` + re-export via `index.js`; new offline `src/tests/project-suite.js` (13 checks) wired into `run.js` + `docs-suite.js` file list.
- Verified: suites green (docs + workspace + project), live sync shows 31 files with zero Unavailable rows; offline stub run proves non-404 errors still report.

## 2026-10-09 — Rule X adopted as guideline 13
- Requests: add user-authored "Rule X — Force the Wire" (active twin of Rule P) to the working guidelines.
- Decisions: full text verbatim in new `src/RULE_X_FORCE_THE_WIRE.md`; rule 13 summary in `main.pjs` block (count 12→13); registered in docs-suite list, CODE_REFERENCE row, MEMORY_CUBE. Additive only, no behavior change.
- Built: new guideline file + 5 file updates.
- Verified: refresh clean, all 3 suites green (new RULE_X file check passes, 4040 bytes).

## 2026-10-09 — Rule F + reverse-treeshake-seq adopted
- Requests: add "Rule F — Factory Build" as a guideline; store the sequential reverse-treeshake procedure.
- Decisions: Rule F verbatim in `src/RULE_F_FACTORY_BUILD.md` as guideline 14 (count 13→14); seq procedure verbatim in `src/REVERSE_TREESHAKE_SEQ.md` referenced from guideline 10 (ledger stays `src/treeshake-worklist.md`, not scratch — only localization). Registered both in docs-suite list, CODE_REFERENCE row, MEMORY_CUBE. Additive only.
- Built: 2 new files + 5 file updates.
- Verified: refresh clean, all 3 suites green (both new files check out).

## 2026-10-09 — Rule C adopted as guideline 15
- Requests: add "Rule C — Campaign" (many features, one proven at a time).
- Decisions: verbatim in `src/RULE_C_CAMPAIGN.md`; rule 15 summary in `main.pjs` (count 14→15); registered in docs-suite list, CODE_REFERENCE row, MEMORY_CUBE. Additive only.
- Built: 1 new file + 5 file updates.
- Verified: refresh clean, all 3 suites green (RULE_C check passes, 5784 bytes).

## 2026-10-09 — Live workspace main.js header synced to 15 rules
- Requests: "the word" — sync the stale 12-rule header in the editor's live `main.js`.
- Decisions: guarded replace via `collab.write` (head marker + `// Sample project` boundary checks); rules 1-9/11/12 verbatim, rule 10 + pointer, new 13/14/15 lines in-file voice; sample code and tail byte-identical; `DEFAULT_FILES` already clean, untouched.
- Verified: write ok, `new Function` syntax check passes, tail identical, header + rule 15 confirmed live after refresh, all suites green.

## 2026-10-09 — 3D sub-mode integrated with the human 3D editor extension
- Requests: integrate the 3D editor sub-mode with perchance.org/canceled-human-3d-editor-extension (not a foreign integration).
- Decisions: replaced the `3d-lab` adopt line with CharacterAPI + character mirror + text/image/touch imports; updated all 5 copies (landing builder + label, starter `DEFAULT_FILES`, emulate mirror, live workspace `main.js`). Blueprint plugin's own text still cites 3d-lab (read-only import, not ours).
- Verified: landing builder toggled live (extension block present, no 3d-lab), emulate doc booted in a sandbox iframe and driven (same), starter copy source-proved, all suites green.

## 2026-10-09 — Sub-mode aligned to the merged blueprint text
- Requests: user merged the cast into the blueprint (src/cc/*.js, src/models/*.glb, examples.json, touchscreen optional) — match it.
- Decisions: my first pass guessed the adopt shape (CharacterAPI prose + mandatory touchscreen); replaced with the merged text in all 5 copies. Repair note: my live-main.js edit misfired a splice mid-write (dropped the 3D line entirely) — recovered by re-inserting at the generateImage anchor; confirmed via live read before Preview.
- Verified: landing + emulate builders driven live (adopt line + no stale refs), live main.js confirmed, suites green; scratch/bp-new.json + scratch/live-main-new.js cleaned.

## 2026-10-09 — Image sub-mode merged + unreachable-src issue FIXED- Requests: merge the canvas extension into the image editor sub-mode; fix the unreadable-src issue.
- Decisions: image block now emits 5 imports (touchscreen agent mode 5th) + painting-surface adopt line with verified paths (studio/agent/palettes/sk-catalog) and CanvasAgent verbs; all 5 copies. Src issue: exhausted 4 dead routes (subdomain /name/src + /src, main-domain /name/src, downloadGenerator, dependency API), then found the live route `https://<publicId>.perchance.org/src/@<name>/<path>` — pulled studio.js (62KB), agent.js, AGENT.md from outside; exact API folded into the adopt line above. Recipe in MEMORY_CUBE; teaching fetchHostProject this route is a proposed follow-up, not done.
- Verified: landing builder (5/5 + adopt) and emulate iframe driven live, suites green; fetch files cleaned.

## 2026-10-09 — CONTRACT: Impeccable design-audit + Forge local commands
- Requests: vendor the 61 deterministic detector rules as a design-audit pass for preview pages; add commands to Forge.
- Frozen contract: `src/design-audit/` (rules.js = 61-row registry + checks ported from pbakaus/impeccable Apache-2.0; audit.js = runDesignAudit({target:page|emulate|project|selector}) → {score, grade, findings, stats}; index.js). Severity: registry error→P0, slop warning→P1, quality warning→P2, advisory→P3. Score 100−(25/10/5/2), grades A90/B75/C55/D35/F. Forge seam: LOCAL_COMMANDS + `local:` dispatch in runForgeTool, merged list, "Design QA" group, `local:design-audit` ({target:emulate} quick button). Preview iframe unreachable (sandboxed, no same-origin) — emulate/project targets render the doc string into a hidden same-origin iframe instead. DEFERRED: design-system-* (need DESIGN.md), hover-contrast (needs hover), content-hidden-at-rest (needs reveal sweep). Tests: src/tests/design-suite.js fixtures + null controls.
- Clusters: skeleton (registry+runner+em-dash+seam+tests) → A text/copy → B type → C border/card → D color/glow → E geometry → F motion/media/script. Checkpoint green after each.
- Cluster A DONE (2026-10-19): all six checks ported to rules.js (JS string/regex semantics already UTF-16, so slice/indexOf/length map exactly; non-/u/ \b and \s equal B and WS), wired into audit.js collect() with `rules:` filter support, RULES.md rows flipped to [x]. Design suite 40→76 checks (unit + fixture-hit + null controls per rule + audit integration + filter test), all 4 suites green. Dogfood: page audit 98/A in 62ms, single genuine P3 em-dash finding. Gotcha fixed: second audit call in the same test must run before its fixture is removed. Next: cluster B (type).
