# reverse-treeshake (sequential) — hunt wired-but-dark code, one seam at a time

Sequential procedure for guideline 10. Single agent, no sub-agents, no fan-out: one written
worklist (`src/treeshake-worklist.md`), top to bottom, checkpoint after each cluster.

A normal tree-shake removes code nothing calls. A **reverse** tree-shake finds the opposite:
code that IS called, IS reached, passes its own tests — and is still wrong, because it is wired to
the wrong field, unit, id, or orientation, or is applied twice, silently overridden downstream, failing
open on error, or dead-on-arrival. **The bug hides behind green.**

Why it accumulates: an author writes plausible code against a seam they half-remember, and never
verifies the seam because they were only told to make their own unit pass. The defect is dark: present,
reached, untested at the boundary.

**The recurrent-laryngeal-nerve test.** A giraffe's laryngeal nerve runs from brain to larynx via a
15-foot detour down the neck, around the aorta, and back up. It *works* — it reaches the larynx fine.
It took the absurd route because it grew one stretch at a time and nothing ever went back to redraw it.
Reached-but-dark code is the same organism: it runs, it does its job, and no one has traced its full
length. The one-question form of this whole skill: **"this works — but has anyone traced its whole
path, producer to consumer?"**

## This is a strictly sequential, single-agent procedure

- **Do not spawn sub-agents, fan out, or parallelize.** You are one agent working one worklist from top
  to bottom.
- You make up for that with **a written worklist ledger** (a scratch file) and **context discipline**:
  enumerate the seams once, then process them one at a time, reading only the specific lines each seam
  needs — never whole large files.
- You **checkpoint** (build / compile / run the touched tests) after each cluster so breakage cannot
  silently accumulate across a long run.

## When to use
- Right after writing or regenerating a batch of modules, or any burst of machine-written code.
- On inherited or long-drifted code you are about to trust or extend.
- When something "passes the tests but does the wrong thing" — the signature of a wire defect.
- Before a release gate that depends on correctness at integration boundaries (determinism, replay,
  serialization, cross-process contracts).

## Core discipline: verify AS WIRED, not as-written

A self-test proves a module runs against **its own assumptions**. It cannot prove the module works
where it is plugged in. For each suspect, go to the **integration seam** and confirm, by reading the
producer and the real consumer **side by side**:

- **Real consumer** — the actual caller on the live path, not a harness.
- **Real input shape** — field names, units, id format, nullability, winding/orientation, exactly as the
  consumer passes them. **Copy the live value; never infer it from memory.**
- **Reached** — the path actually runs in a real session (instrument or trace it; do not assume).
- **Real environment** — the runtime, globals, encoding, and config it truly runs in.

Prefer observing the running product (drive it, read its state/output/logs) over a green test.

## The sequential procedure (the heart of it)

### 0. Scope
Pick ONE bounded target: a feature, a wave of new modules, a file family, one subsystem. Write the
scope line at the top of your ledger. Do not widen it mid-run.

### 1. Enumerate into a worklist ledger
Create a scratch file, e.g. `treeshake-worklist.md`. Search the scope and list every **seam** to check —
each producer→consumer pair, each cross-module read, each pair of lists/registries that must agree. One
row per seam, status `TODO`. This file is your memory; keeping the list only in context will lose it.
Group rows into **clusters** (subsystems) so you can checkpoint per cluster.

### 2. Work the list top to bottom — for each row, do all of:
1. **Open producer AND real consumer together.** Read only those spans.
2. **Diff the contract by eye:** names, shapes, units, ids, winding, nullability, scale, zero-point.
   Write the literal producer shape and the literal consumer expectation next to each other.
3. **Apply the defect catalog and the null-control checks** (both below) to this seam.
4. **Reproduce at the seam:** run the live path or inspect the real value, and state a concrete
   **failing scenario** (specific input/state → wrong or unsafe output). No scenario, no finding.
5. **Fix at the seam** — correct the wiring; do not paper over it with another transform (that creates a
   double-apply). If the fix is a judgment call or changes behavior someone may want on purpose, record
   it and leave it for the owner instead of silently changing it.
6. **Re-verify as wired**, and make the module's own test assert the WIRED shape so it cannot rot back
   to dark.
7. **Record** the finding (producer file:line, consumer file:line, the literal mismatch, the failing
   scenario, the fix) and set the row to `DONE` / `CLEAN` / `DEFERRED`.

### 3. Checkpoint after each cluster
Build / compile / lint / run the unit and integration tests the cluster touched. Fix any breakage
before the next cluster. A sequential run that saves all verification for the end debugs blind.

### 4. Cluster-done review
Defects cluster — one wire bug predicts siblings. When a cluster turns up a defect, re-scan its rows
for the same class before moving on.

## The defect catalog — what to search and read for

1. **Reached-but-dark (contract mismatch).** Wired and executed, but a field/shape/id/unit mismatch
   makes it a no-op. Read producer and consumer literally.
   - A consumer reads a field name the producer renamed or reshaped (`item.tags` vs `item.tagList[].name`).
   - A lookup keyed on a string id while live records are indexed numerically → it never matches.
   - Two lists/registries that must mirror each other have **drifted** (one enumerates sixteen keys, the
     mirror used for backup/delete/export lists twelve → four are silently dropped and later orphaned).
   - A value or directive **computed every call but never concatenated into the string/payload actually
     sent** (two builders diverged; the default path omits what only a rarely-taken path includes).
2. **Double-apply.** The same transform applied twice — clamp, scale, offset, normalize. Search for the
   transform in the module AND again in the caller. (A value pushed into a shared pipeline *and*
   multiplied again downstream is squared: the low end runs too small, the high end clamps dead.)
3. **Cascade collision / silent override / fail-open.** A later rule or default with equal-or-higher
   precedence overrides an earlier decision, leaving the earlier one dead. An empty `catch` that **fails
   open** swallows a real error so a guarded path proceeds unguarded. A cleanup/teardown that targets
   only the *current* instance misses a *previously-installed or stacked* one.
4. **Sweep / collector traps.** A prefix or glob sweep that grabs more than intended, or two collectors
   over one namespace that stack or trap entries; a backup/restore sweep that **misses** keys the code
   actually writes.
5. **Stale / mismatched read.** Reads a field renamed upstream; a unit mismatch (radians vs degrees,
   0..1 vs 0..255, ms vs s); wrong winding/orientation; an accumulator (`last*`/`prev*`/moving average)
   updated on the main path but desynced by an alternate path (idle, tick, offline, background).
6. **Dead-on-arrival.** Built and imported but never reached on any live path. A validator/guard
   constructed but never consulted; a hook whose registration call is never made, so the thing it was meant
   to gate is a pass-through. Confirm it is truly unreachable before deleting — and if the dark thing is a
   *safety or correctness* control, that is a finding, not just cleanup.

## Null-control checks — prove the negative was even possible

A green golden test proves nothing if it would pass whether or not the mechanism works. For each
"it works / it helps / it fires" claim on a seam you fix, name and run the run that would make it
**false**:
- **On vs off** — does the effect vanish when the feature is off? Prove any "off = unchanged" claim with
  an actual identity check on the output, not "the suite still passes."
- **Cause present vs removed / normal input vs the triggering input** — does it stay neutral when nothing
  is wrong? (This is where a scale-mismatched blend shows: two signals from different subsystems combined
  as if they shared a range and a zero when they do not.)
- **The do-nothing baseline** — does an inert configuration score worse than an active one?
- **Is the control clean?** — a negative control that still receives the treatment proves nothing.
Lock the null as an assertion in the test suite, not just the golden case.

## Context discipline for a lone agent
- **Read spans, not files.** Find the exact producer and consumer lines first, then read a tight window
  around each. Never load a whole large file to "look around."
- **Keep findings in the ledger, not in your head.** One compact row per seam.
- **Do not re-read what the ledger already records.** The ledger is your working set.
- If a large file is generated or compiled from smaller sources, read the **source** (and its build
  manifest) rather than the built artifact.

## Worklist ledger format
```
# treeshake — <scope>
## cluster: <subsystem A>
- [ ] seam: <producer file:line> -> <consumer file:line> | contract: <field/unit/id> | TODO
- [x] seam: ... | DEFECT: <one line> | scenario: <input -> wrong output> | FIX: <what> | DONE
- [~] seam: ... | DEFERRED (judgment call / behavior change — ask the owner)
## cluster: <subsystem B>
...
## checkpoints: [A built ✓] [B built ✓]
```

## Sweep checklist (per cluster)
- [ ] Every suspect read against its REAL consumer, shapes compared literally.
- [ ] Searched for double-clamp / double-scale / double-normalize across module and caller.
- [ ] Checked for cascade collisions and fail-open empty catches on guarded paths.
- [ ] Checked sweeps/collectors and any mirrored lists/registries for over-capture, under-capture, drift.
- [ ] Laryngeal-nerve test: each suspect's full producer→consumer path traced, not just "does it run?"
- [ ] Each path confirmed REACHED in a live session, in its real environment.
- [ ] Null control named and asserted for each fix; any "off = unchanged" re-checked by identity.
- [ ] Cluster built and its tests run before moving on.

## Anti-patterns
- Trusting a green self-test as proof of integration (the exact blind spot).
- Reading only the module, never its consumer.
- Describing the input shape from memory instead of copying the live value.
- "Fixing" by stacking another transform on top (creates a double-apply instead of removing the bug).
- Deferring all verification to the end of a long sequential run.
- Reaching for parallel helpers — this procedure forbids them; use the ledger instead.
- Silently "fixing" a control that is inert by deliberate design — surface it to the owner rather than
  re-enabling behavior.
