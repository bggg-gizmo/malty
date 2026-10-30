# RULES.md — design-audit coverage ledger

Port of Impeccable's deterministic detectors (Apache-2.0, pbakaus/impeccable).
One row per registry id. Status: [x] DONE (ported + fixture-tested), [~] PARTIAL
(registry row only — check lands in its cluster), [ ] DEFERRED (with reason).

Severity map: registry `error`→P0, `warning`→P1 (slop) / P2 (quality),
`advisory`→P3. Score 100−(25/10/5/2), grades A90/B75/C55/D35/F.

## slop

- [~] side-tab — DONE (skeleton: registry row)
- [~] border-accent-on-rounded — DONE (skeleton: registry row)
- [~] overused-font — DONE (skeleton: registry row)
- [~] flat-type-hierarchy — DONE (skeleton: registry row)
- [~] gradient-text — DONE (skeleton: registry row)
- [~] ai-color-palette — DONE (skeleton: registry row)
- [~] cream-palette — DONE (skeleton: registry row)
- [~] nested-cards — DONE (skeleton: registry row)
- [x] monotonous-spacing — DONE (ported: computed padding/margin px + gap + Tailwind tokens; round/4, ≥10, dominant >0.6, ≤3 unique)
- [~] bounce-easing — DONE (skeleton: registry row)
- [~] pulsing-dot — DONE (skeleton: registry row)
- [~] blinking-cursor — DONE (skeleton: registry row)
- [~] shape-assembled-illustration — DONE (skeleton: registry row)
- [~] dark-glow — DONE (skeleton: registry row)
- [~] radial-halo — DONE (skeleton: registry row)
- [~] radial-spotlight-glow — DONE (skeleton: registry row)
- [~] marquee — DONE (skeleton: registry row)
- [~] icon-tile-stack — DONE (skeleton: registry row)
- [~] italic-serif-display — DONE (skeleton: registry row)
- [~] hero-eyebrow-chip — DONE (skeleton: registry row)
- [~] kicker-above-heading — DONE (skeleton: registry row)
- [x] numbered-section-labels — DONE
- [x] em-dash-overuse — DONE (ported: countEmDashes + floor 8 + density 500)
- [x] marketing-buzzword — DONE (ported: 29 phrases, case-insensitive, count + first sample)
- [x] aphoristic-cadence — DONE (ported: NOT_A_RE + SHORT_REBUTTAL_RE, fires at ≥3)
- [~] oversized-h1 — DONE (skeleton: registry row)
- [~] extreme-negative-tracking — DONE (skeleton: registry row)
- [~] gpt-thin-border-wide-shadow — DONE (skeleton: registry row)
- [~] repeating-stripes-gradient — DONE (skeleton: registry row)
- [~] codex-grid-background — DONE (skeleton: registry row)
- [x] theater-slop-phrase — DONE (ported: THEATER_RE + 27-word dismissive-head gate)

(skeleton rows carry id/category/severity/name/description; checks land per cluster.)

## quality

- [~] organic-clip-path — DONE (skeleton: registry row)
- [~] buried-raster — DONE (skeleton: registry row)
- [~] broken-image — DONE (skeleton: registry row)
- [~] script-error — DONE (skeleton: registry row)
- [ ] content-hidden-at-rest — DEFERRED (needs reveal sweep; cheap visibility check would false-positive)
- [~] edge-flush-cards — DONE (skeleton: registry row)
- [~] text-occlusion — DONE (skeleton: registry row)
- [~] first-viewport-column-overflow — DONE (skeleton: registry row)
- [~] gray-on-color — DONE (skeleton: registry row)
- [~] low-contrast — DONE (skeleton: registry row)
- [~] line-length — DONE (skeleton: registry row)
- [~] cramped-padding — DONE (skeleton: registry row)
- [~] body-text-viewport-edge — DONE (skeleton: registry row)
- [~] tight-leading — DONE (skeleton: registry row)
- [~] skipped-heading — DONE (skeleton: registry row)
- [~] heading-rhythm — DONE (skeleton: registry row)
- [~] justified-text — DONE (skeleton: registry row)
- [~] straggler — DONE (skeleton: registry row)
- [~] unbalanced-heading — DONE (skeleton: registry row)
- [~] tiny-text — DONE (skeleton: registry row)
- [~] undersized-ui-text — DONE (skeleton: registry row)
- [~] all-caps-body — DONE (skeleton: registry row)
- [~] wide-tracking — DONE (skeleton: registry row)
- [~] text-overflow — DONE (skeleton: registry row)
- [x] repeated-container-text — DONE (ported: card-like containers, 4–48 char texts, 3× in 3 distinct signatures)
- [~] clipped-overflow-container — DONE (skeleton: registry row)
- [ ] design-system-font — DEFERRED (needs DESIGN.md)
- [ ] design-system-color — DEFERRED (needs DESIGN.md)
- [ ] design-system-radius — DEFERRED (needs DESIGN.md)
- [ ] design-system-font-size — DEFERRED (needs DESIGN.md)
- [ ] hover-contrast — DEFERRED (needs hover simulation; no such rule id in registry — covered by gray-on-color/low-contrast pair)

## clusters

- skeleton: registry + runner + em-dash + Forge seam + tests
- A: text/copy (buzzword, aphoristic, theater, repeated, numbered, monotonous) — DONE
- B: type (fonts, hierarchy, tracking, leading, lengths, headings, kickers, labels)
- C: border/card (side-tab, accent, nested, stripes, tiles, eyebrow, flush, paper?)
- D: color/glow/contrast (palettes, gray-on-color, low-contrast, glow, halo, marquee/motion bewteen D/F)
- E: geometry (occlusion, overflow, viewport, edge, cramped, clipped)
- F: motion/media/script (marquee, pulse, cursor, easing, broken-image, script-error)
