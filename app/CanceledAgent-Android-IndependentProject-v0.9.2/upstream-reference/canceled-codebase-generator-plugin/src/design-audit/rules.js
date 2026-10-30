/*
  rules.js — deterministic design-audit rule registry + checks.

  Ported from Impeccable (github.com/pbakaus/impeccable, Apache-2.0):
  registry rows (id/category/severity/name/description) mirror
  crates/foundation/src/registry.rs ANTIPATTERNS; check logic mirrors
  crates/core/src/checks/{rules,text_rules}.rs and the browser element/page
  drivers, adapted to run on a live DOM (getComputedStyle replaces the
  CDP/snapshot probe). Rules that need hover, a reveal sweep, or a DESIGN.md
  are DEFERRED — see RULES.md. Nothing here needs a network, a key, or an LLM.
*/
export const REGISTRY = [
  { id: "side-tab", cat: "slop", sev: null, name: "Side-tab accent border", desc: "Thick colored border on one side of a card — the most recognizable tell of AI-generated UIs. Use a subtler accent or remove it entirely." },
  { id: "border-accent-on-rounded", cat: "slop", sev: null, name: "Border accent on rounded element", desc: "Thick accent border on a rounded card — the border clashes with the rounded corners. Remove the border or the border-radius." },
  { id: "overused-font", cat: "slop", sev: null, name: "Overused font", desc: "Inter, Roboto, Fraunces, Geist, Plus Jakarta Sans, and Space Grotesk are used on so many sites they no longer feel distinctive. Each new wave of AI-generated UIs converges on the same handful of faces. Choose a face that gives your interface personality." },
  { id: "flat-type-hierarchy", cat: "slop", sev: null, name: "Flat type hierarchy", desc: "Dominant heading and body roles are separated by less than 1.25× at every step, leaving the size hierarchy flat. Add at least one stronger size step." },
  { id: "gradient-text", cat: "slop", sev: null, name: "Gradient text", desc: "Gradient text is decorative rather than meaningful — a common AI tell, especially on headings and metrics. Use solid colors for text." },
  { id: "ai-color-palette", cat: "slop", sev: null, name: "AI color palette", desc: "Purple/violet gradients and cyan-on-dark are the most recognizable tells of AI-generated UIs. A gradient in one of those hues is the tell on its own; flat neon ink on a dark ground is charged once a second tell hue joins it. Choose a distinctive, intentional palette." },
  { id: "cream-palette", cat: "slop", sev: null, name: "Cream / beige palette", desc: "A warm cream or beige page background has become the default \\" },
  { id: "nested-cards", cat: "slop", sev: null, name: "Nested cards", desc: "Cards inside cards create visual noise and excessive depth. Flatten the hierarchy — use spacing, typography, and dividers instead of nesting containers." },
  { id: "monotonous-spacing", cat: "slop", sev: null, name: "Monotonous spacing", desc: "The same spacing value used everywhere — no rhythm, no variation. Use tight groupings for related items and generous separations between sections." },
  { id: "bounce-easing", cat: "slop", sev: "advisory", name: "Bounce or elastic easing", desc: "Bounce and elastic easing feel dated and tacky. Real objects decelerate smoothly — use exponential easing (ease-out-quart/quint/expo) instead." },
  { id: "pulsing-dot", cat: "slop", sev: null, name: "Pulsing status dot", desc: "Small pulsing status dots simulate liveness decoratively. Reserve pulse animation for indicators tied to genuinely live, changing data; a static indicator with clear labeling is honest and calmer." },
  { id: "blinking-cursor", cat: "slop", sev: "advisory", name: "Decorative blinking cursor", desc: "A blinking text cursor animated into a hero or landing section simulates typing where no input exists. It borrows the dev-tool aesthetic as decoration. Real editable fields draw their own caret; anywhere else, let the composition hold attention without a fake prompt." },
  { id: "shape-assembled-illustration", cat: "slop", sev: "advisory", name: "Shape-assembled illustration", desc: "A large inline SVG that builds a pictorial scene from a pile of primitive shapes reads as placeholder clip art, not illustration. Icons, logos, and data graphics are fine at their scale; a hero-sized visual deserves real artwork, a photograph, or a deliberately drawn graphic." },
  { id: "organic-clip-path", cat: "quality", sev: null, name: "Organic contour drawn as clip-path", desc: "A clip-path polygon with many arbitrary vertices, or a curved clip-path path(), is CSS approximating a torn edge, blob, or silhouette. It reads as the cheap version of the effect and is usually a produced or photographic material replaced with code. Derive an alpha matte from the real image, or ship the shape as a cut-out raster; keep clip-path for geometry (cut corners, diagonals, hexagons)." },
  { id: "buried-raster", cat: "quality", sev: null, name: "Raster buried under a wash or opacity", desc: "A background image under a near-opaque gradient wash, or a raster on an element at near-zero opacity, never reaches the screen: the page shows the wash, and the produced texture or photo ships as a compliance token. Let the material show (a tint under 0.9 alpha, a blend mode, an opacity you can see) or remove the file." },
  { id: "dark-glow", cat: "slop", sev: null, name: "Glowing shadow accents", desc: "Colored glow shadows — a zero-offset chromatic halo (box- or text-shadow) on any background, or any colored blurred shadow on a dark background — are the default \\" },
  { id: "radial-halo", cat: "slop", sev: null, name: "Radial-gradient background halo", desc: "A chromatic radial-gradient wash — saturated at the center, fading to transparent — used as a decorative background glow on a dark page. Same tell as glowing shadows, drawn with a gradient instead of a shadow. Ground the surface with a solid or subtly shifted background instead." },
  { id: "radial-spotlight-glow", cat: "slop", sev: null, name: "Decorative radial spotlight glow", desc: "An accent-colored radial gradient fading to transparent, dropped behind a hero or section as a \\" },
  { id: "marquee", cat: "slop", sev: null, name: "Auto-scrolling marquee", desc: "Continuously auto-scrolling content demands attention it has not earned and hides half its content at any moment. Reserve motion for content that changes; let readers move at their own pace." },
  { id: "icon-tile-stack", cat: "slop", sev: null, name: "Icon tile stacked above heading", desc: "A small rounded-square icon container above a heading is the universal AI feature-card template — every generator outputs this exact shape. Try a side-by-side icon and heading, or let the icon sit in flow without its own container." },
  { id: "italic-serif-display", cat: "slop", sev: null, name: "Italic serif display headline", desc: "Oversized italic serif (Fraunces, Recoleta, Playfair, Newsreader-italic) as the primary hero headline reads as taste in isolation but has become the universal AI-startup landing page hero. Set roman, or move to a non-serif display face. Editorial / magazine register may legitimately want this — judge by context." },
  { id: "hero-eyebrow-chip", cat: "slop", sev: null, name: "Hero eyebrow / pill chip", desc: "A tiny uppercase letter-spaced label sitting immediately above an oversized hero headline — or the same shape rendered as a pill chip — is now the default AI SaaS hero. Drop the eyebrow, integrate the kicker into the headline, or run it as a navigation breadcrumb instead." },
  { id: "kicker-above-heading", cat: "slop", sev: null, name: "Kicker / eyebrow label above heading", desc: "A tiny tracked uppercase or small-caps label sitting as its own block directly above a heading is banned outright, repeated or not. Generated kickers never earn their place: the heading carries its own weight. Delete the label and let the heading speak; if the words matter, work them into the heading or the body." },
  { id: "numbered-section-labels", cat: "slop", sev: "advisory", name: "Tiny numbered section labels", desc: "Small numeric index labels riding next to section headings, repeated section after section, are AI editorial scaffolding — a page numbering its own chapters instead of earning structure. Let hierarchy, content, and rhythm carry the sequence." },
  { id: "em-dash-overuse", cat: "slop", sev: "advisory", name: "Em-dash overuse", desc: "Em-dash saturation in body copy is an AI cadence tell. Advisory only: humans use em-dashes legitimately, so this fires only on saturation — at least 8 em-dashes (— or --) at a density near one per 500 characters of body text — never on a long article that uses a few. Prefer commas, colons, periods, or parentheses." },
  { id: "marketing-buzzword", cat: "slop", sev: null, name: "Marketing buzzword", desc: "Generic SaaS phrases (streamline / empower / supercharge / world-class / enterprise-grade / next-generation / cutting-edge / etc) are instant AI tells. Pick a specific verb and noun that says what the product literally does." },
  { id: "aphoristic-cadence", cat: "slop", sev: null, name: "Aphoristic-cadence copy", desc: "Three or more sections landing on a short rebuttal sentence (\\" },
  { id: "oversized-h1", cat: "slop", sev: null, name: "Oversized hero headline", desc: "A full-sentence headline set at display size ends up dominating the viewport, leaving no room for anything else above the fold. A punchy one- or two-word headline at that size is fine — the problem is a long headline blown up too large. Set long headlines smaller, or tighten the copy." },
  { id: "extreme-negative-tracking", cat: "slop", sev: null, name: "Crushed letter spacing", desc: "Letter-spacing pulled tighter than the point where characters keep their own shapes costs legibility. Tighten display type optically, not destructively." },
  { id: "broken-image", cat: "quality", sev: null, name: "Broken or placeholder image", desc: "<img> tags with empty src, missing src, or placeholder values ship as broken-image boxes. Use real images, generated assets, or remove the tag." },
  { id: "script-error", cat: "quality", sev: "error", name: "Uncaught script error on load", desc: "A script threw an uncaught exception or failed to parse while the page loaded. Broken JavaScript silently kills reveals, interactions, and dynamic content, and can leave most of a page invisible. Fix the error before judging anything else." },
  { id: "content-hidden-at-rest", cat: "quality", sev: "error", name: "Content invisible at rest", desc: "A large share of the page text sits at opacity 0 or visibility hidden even after every reveal handler had a chance to run. This is the failed-reveal signature: the content shipped but never becomes visible. Make content visible by default and let JavaScript enhance its entrance instead of gating its existence." },
  { id: "edge-flush-cards", cat: "quality", sev: null, name: "Cards flush against the scroller edge", desc: "Cards inside a horizontal scroller or tab panel sit flush against the container edge at rest while keeping a gutter on the other side, so their edges and rounded corners get cut off. Usually the panel is sized wider than its clip box. Keep a consistent inset on both sides." },
  { id: "text-occlusion", cat: "quality", sev: null, name: "Text occluded by an overlapping element", desc: "Text is painted under an opaque element or a second text run, so part of it cannot be read. A decorative box, a stacked layer, or an inline element with leaked padding lands on the words instead of beside them. Give overlapping layers room, or move the text out from under the layer above it." },
  { id: "first-viewport-column-overflow", cat: "quality", sev: null, name: "One column stretches the first viewport", desc: "A multi-column opening section lets one column run far past the fold while its sibling fits in a single viewport, so the short column floats in dead space and the fold falls deep inside one section. Balance the columns, cap the tall one, or let the long content flow below the opening row." },
  { id: "gray-on-color", cat: "quality", sev: null, name: "Gray text on colored background", desc: "Gray text looks washed out on colored backgrounds. Use a darker shade of the background color instead, or white/near-white for contrast." },
  { id: "low-contrast", cat: "quality", sev: null, name: "Low contrast text", desc: "Text does not meet WCAG AA contrast requirements (4.5:1 for body, 3:1 for large text). Increase the contrast between text and background." },
  { id: "line-length", cat: "quality", sev: null, name: "Line length too long", desc: "Text lines wider than ~80 characters are hard to read. The eye loses its place tracking back to the start of the next line, so it is measured on the lines that rendered and charged when more than one of them runs long. Add a max-width (65ch to 75ch) to text containers." },
  { id: "cramped-padding", cat: "quality", sev: null, name: "Cramped padding", desc: "Text is too close to the edge of its container. Two shapes: (1) an element with its own text where the space between the rendered text and the border box is too small for the font size, and (2) a wrapper whose children's text lands flush against a visible boundary (border, outline, or non-transparent background) with nothing to inset it. Add at least 8px (ideally 12–16px) of space inside bordered, outlined, or colored containers." },
  { id: "body-text-viewport-edge", cat: "quality", sev: null, name: "Body text touching viewport edge", desc: "Body paragraphs render flush against the left or right viewport edge with no container providing horizontal padding: closer than 16px, or 12px on viewports 480px wide or narrower. Wrap content in a container with at least 16px (ideally 24-32px) of horizontal padding, or apply max-width with mx-auto. Body text that runs past the viewport edge is reported once per page as overflow, naming the widest element: constrain that element's width instead of adding padding." },
  { id: "tight-leading", cat: "quality", sev: null, name: "Tight line height", desc: "Line height below 1.3x the font size makes multi-line text hard to read. Use 1.5 to 1.7 for body text so lines have room to breathe." },
  { id: "skipped-heading", cat: "quality", sev: null, name: "Skipped heading level", desc: "Heading levels should not skip (e.g. h1 then h3 with no h2). Screen readers use heading hierarchy for navigation. Skipping levels breaks the document outline." },
  { id: "heading-rhythm", cat: "quality", sev: null, name: "Heading crowded against the previous block", desc: "A heading binds to the content it introduces, so the rendered space above it should exceed the space below it. When headings across a page sit as close or closer to the block above than to their own content, every section reads as if it captions the previous one. Open up the space above each heading." },
  { id: "justified-text", cat: "quality", sev: null, name: "Justified text", desc: "In a column this narrow, justifying without hyphenation stretches the word spaces until vertical rivers of white run down the block. Use text-align: left, widen the measure, or enable hyphens: auto if you must justify. Scripts that justify on a character grid or by elongating glyphs, such as CJK, Thai and Arabic, are not affected and are not reported." },
  { id: "straggler", cat: "quality", sev: "advisory", name: "Straggler", desc: "The last line of this paragraph holds one word, which leaves a hole under the block. Set text-wrap: pretty on body copy (Chrome, Safari) to pull a word down beside it. Where a browser ignores it, edit the last sentence or bind the last two words with a no-break space. Keep it if the break is intended." },
  { id: "unbalanced-heading", cat: "quality", sev: "advisory", name: "Unbalanced heading", desc: "This heading wraps with its last line far shorter than the line above, often one word alone. Set text-wrap: balance on headings so the lines come out near even, or rewrite it to fit. Keep it if the break carries weight on purpose." },
  { id: "tiny-text", cat: "quality", sev: null, name: "Tiny body text", desc: "Body text below 12px is hard to read, especially on high-DPI screens. Use at least 14px for body content, 16px is ideal." },
  { id: "undersized-ui-text", cat: "quality", sev: null, name: "Undersized functional text", desc: "Interactive and content-bearing UI text (links, buttons, nav items, labels, table cells, meta rows, timecodes) below 11px is a legibility failure, not a style choice. WCAG sets no absolute pixel floor, but functional text under 11px is a defensible quality bar: it fails on high-DPI and small viewports and it degrades tap and read targets. The 11px floor holds even inside a footer; only non-interactive legal smallprint gets the softer 10px floor. Being ON the DESIGN.md size ramp does not exempt a value here: adding 8px to the ramp launders the token but not the legibility problem, and that is exactly the escape hatch this rule closes. Exempts sup/sub, visually-hidden (sr-only) text, and code/terminal contexts. Decorative letterspaced micro-labels are still functional and stay in scope." },
  { id: "all-caps-body", cat: "quality", sev: null, name: "All-caps body text", desc: "Long passages in uppercase are hard to read. We recognize words by shape (ascenders and descenders), which all-caps removes. Reserve uppercase for short labels and headings. Only a run that reads as a sentence counts: 80 characters or more of an element's own text. Buttons, nav items, kickers and eyebrows set in caps are a convention and stay silent." },
  { id: "wide-tracking", cat: "quality", sev: null, name: "Wide letter spacing on body text", desc: "Letter spacing above 0.05em on body text disrupts natural character groupings and slows reading. Reserve wide tracking for short uppercase labels only." },
  { id: "text-overflow", cat: "quality", sev: null, name: "Content overflowing its container", desc: "Content renders wider than its container and the spill does harm: a clipping ancestor cuts it off, it runs into another box, or it reaches the viewport edge. Let text wrap, constrain widths, or give the region a deliberate scroll affordance." },
  { id: "repeated-container-text", cat: "quality", sev: null, name: "Same text repeated inside one container", desc: "The same literal text rendered three or more times in structurally different spots inside a single card or panel is redundant messaging — usually a status or label wired into every slot of a template. Say it once, in the slot where it matters most." },
  { id: "clipped-overflow-container", cat: "quality", sev: "advisory", name: "Positioned child clipped by overflow container", desc: "A clipping container (overflow hidden or clip) wrapping an absolutely-positioned child cuts off tooltips, menus, and popovers that need to escape. Let the overflow be visible, or move the positioned layer out of the clip." },
  { id: "design-system-font", cat: "quality", sev: null, name: "Font outside DESIGN.md", desc: "A font is used that is not declared in DESIGN.md typography. Use the documented type system or update DESIGN.md if this is an intentional brand addition." },
  { id: "design-system-color", cat: "quality", sev: "advisory", name: "Color outside DESIGN.md", desc: "A literal color is outside the DESIGN.md palette and sidecar tonal ramps. This may be legitimate, but it should be an intentional design-system addition rather than drift." },
  { id: "design-system-radius", cat: "quality", sev: "advisory", name: "Radius outside DESIGN.md", desc: "A border-radius value is outside the DESIGN.md rounded scale. Use a documented radius token or update the design system if the new shape is intentional." },
  { id: "design-system-font-size", cat: "quality", sev: "advisory", name: "Font size outside DESIGN.md", desc: "A literal font-size is off the type ramp documented in DESIGN.md typography. Use a documented size step or update the design system if the new step is intentional." },
  { id: "gpt-thin-border-wide-shadow", cat: "slop", sev: "advisory", name: "Hairline border with wide shadow", desc: "Every card of this row wears a hairline border paired with a wide, diffuse shadow, which is a recurring generated-UI signature. One floating panel earns both; a whole row wearing them reads as a default nobody chose. Across the row, commit to one: a defined edge or a soft elevation." },
  { id: "repeating-stripes-gradient", cat: "slop", sev: "advisory", name: "Repeating-gradient stripes", desc: "Repeating-gradient stripes used as surface decoration are a recurring generated-UI signature. Reach for a deliberate texture or leave the surface plain." },
  { id: "codex-grid-background", cat: "slop", sev: "advisory", name: "Decorative grid-line background", desc: "A decorative grid or line-field background drawn with hairline linear-gradient layers tiled by a fixed pixel cell is a recurring generated-UI signature. Reserve grid overlays for actual canvas, map, blueprint, or measurement surfaces; elsewhere use product structure or a plain surface." },
  { id: "theater-slop-phrase", cat: "slop", sev: "advisory", name: "Theater framing copy", desc: "Dismissing something as \\" }
];

export function pseverity(row) {
  if (row.sev === "error") return "P0";
  if (row.sev === "advisory") return "P3";
  return row.cat === "slop" ? "P1" : "P2";
}

export const SCORE_W = { P0: 25, P1: 10, P2: 5, P3: 2 };

export function gradeFor(score) {
  if (score >= 90) return "A";
  if (score >= 75) return "B";
  if (score >= 55) return "C";
  if (score >= 35) return "D";
  return "F";
}

export function isJsWhitespace(c) {
  return c === " " || c === "\t" || c === "\n" || c === "\r" || c === "\f" || c === "\v" || c === "\u00a0" || c === "\u1680" || (c >= "\u2000" && c <= "\u200a") || c === "\u2028" || c === "\u2029" || c === "\u202f" || c === "\u205f" || c === "\u3000" || c === "\ufeff";
}

export function countEmDashes(body) {
  const chars = [...body];
  let count = 0, i = 0;
  while (i < chars.length) {
    if (chars[i] === "\u2014") { count += 1; i += 1; }
    else if (chars[i] === "-") {
      const start = i;
      while (i < chars.length && chars[i] === "-") i += 1;
      const run = i - start;
      const next = i < chars.length ? chars[i] : null;
      const isAlnum = (c) => c != null && /[A-Za-z0-9]/.test(c);
      if (run === 2 && next != null && !isJsWhitespace(next)) count += 1;
      else if (run === 3 && start > 0 && isAlnum(chars[start - 1]) && next != null && isAlnum(next)) count += 1;
    } else i += 1;
  }
  return count;
}

export const EM_DASH_FLOOR = 8;
export const EM_DASH_CHARS_PER_DASH = 500;

export function checkEmDashOveruse(text) {
  const body = String(text == null ? "" : text).replace(/\s+/g, " ");
  const count = countEmDashes(body);
  if (count < EM_DASH_FLOOR) return [];
  if (body.length > count * EM_DASH_CHARS_PER_DASH) return [];
  return [{ rule: "em-dash-overuse", snippet: count + " em-dashes in body text" }];
}

export const BUZZWORDS = [
  "streamline your", "empower your", "supercharge your", "unleash your",
  "unleash the power", "leverage the power", "built for the modern",
  "trusted by leading", "trusted by the world", "best-in-class",
  "industry-leading", "world-class", "enterprise-grade", "next-generation",
  "cutting-edge", "transform your business", "revolutionize", "game-changer",
  "game changing", "mission-critical", "best of breed", "future-proof",
  "future proof", "seamless experience", "seamlessly integrate",
  "drive engagement", "drive growth", "drive results", "harness the power",
];

export function checkBuzzwords(text) {
  const body = String(text == null ? "" : text);
  const lower = body.toLowerCase();
  const textLen = body.length;
  let count = 0, firstSample = "";
  for (const phrase of BUZZWORDS) {
    let from = 0;
    for (;;) {
      const idx = lower.indexOf(phrase, from);
      if (idx < 0) break;
      count += 1;
      if (!firstSample) {
        firstSample = body.slice(Math.max(0, idx - 12), Math.min(textLen, idx + phrase.length + 12)).trim();
      }
      from = idx + phrase.length;
    }
  }
  if (!count) return [];
  return [{ rule: "marketing-buzzword", el: null, snippet: count + " buzzword phrase" + (count === 1 ? "" : "s") + ': "' + firstSample + '"' }];
}

const NOT_A_RE = /\bNot an? [a-z][^.!?]{1,40}[.!]\s+[A-Z][^.!?]{1,60}[.!]/g;
const SHORT_REBUTTAL_RE = /\b[A-Z][^.!?]{4,80}[.!]\s+(No|Just)\s+[a-z][^.!?]{2,60}[.!]/g;

export function checkAphoristicCadence(text) {
  const body = String(text == null ? "" : text);
  let count = 0, firstSample = "";
  for (const m of body.matchAll(NOT_A_RE)) {
    count += 1;
    if (!firstSample) firstSample = m[0].trim().slice(0, 80);
  }
  for (const m of body.matchAll(SHORT_REBUTTAL_RE)) {
    count += 1;
    if (!firstSample) firstSample = m[0].trim().slice(0, 80);
  }
  if (count < 3) return [];
  return [{ rule: "aphoristic-cadence", el: null, snippet: count + ' aphoristic constructions: "' + firstSample + '"' }];
}

const THEATER_RE = /\b([A-Za-z0-9_]+)\s+[tT][hH][eE][aA][tT][eE][rR]\b/g;
const THEATER_DISMISSIVE_HEADS = new Set([
  "accessibility", "accountability", "agile", "alignment", "compliance",
  "diversity", "engagement", "governance", "growth", "hiring", "hygiene",
  "innovation", "inclusion", "kindness", "leadership", "metrics", "privacy",
  "process", "productivity", "quality", "safety", "security", "strategy",
  "sustainability", "transparency", "trust", "wellness",
]);

export function checkTheaterSlop(text) {
  const body = String(text == null ? "" : text);
  for (const m of body.matchAll(THEATER_RE)) {
    if (THEATER_DISMISSIVE_HEADS.has(m[1].toLowerCase())) {
      return [{ rule: "theater-slop-phrase", el: null, snippet: '"' + m[0].trim() + '"' }];
    }
  }
  return [];
}

function csOf(el) {
  try {
    const w = el.ownerDocument.defaultView;
    return w ? w.getComputedStyle(el) : null;
  } catch (e) { return null; }
}

const TW_SPACING_RE = /\b(?:p|px|py|pt|pb|pl|pr|m|mx|my|mt|mb|ml|mr|gap)-([0-9]+)\b/g;
const SPACING_SIDES = ["paddingTop", "paddingRight", "paddingBottom", "paddingLeft", "marginTop", "marginRight", "marginBottom", "marginLeft"];

export function collectSpacingValues(root) {
  const vals = [];
  const els = root.getElementsByTagName("*");
  const n = Math.min(els.length, 2000);
  for (let i = 0; i < n; i++) {
    const el = els[i];
    const tag = el.tagName;
    if (tag === "SCRIPT" || tag === "STYLE" || tag === "HEAD" || tag === "META" || tag === "LINK" || tag === "TITLE" || tag === "NOSCRIPT" || tag === "TEMPLATE") continue;
    const cs = csOf(el);
    if (!cs) continue;
    for (const p of SPACING_SIDES) {
      const v = parseFloat(cs[p]);
      if (v > 0 && v < 200) vals.push(v);
    }
    for (const p of ["rowGap", "columnGap"]) {
      const v = parseFloat(cs[p]);
      if (Number.isFinite(v)) vals.push(v);
    }
    const cls = typeof el.className === "string" ? el.className : "";
    if (cls) {
      for (const m of cls.matchAll(TW_SPACING_RE)) vals.push(parseInt(m[1], 10) * 4);
    }
  }
  return vals;
}

export function checkMonotonousSpacing(root) {
  const rounded = collectSpacingValues(root).map((v) => Math.round(v / 4) * 4);
  if (rounded.length < 10) return [];
  const counts = {};
  for (const v of rounded) {
    const k = String(v);
    counts[k] = (counts[k] || 0) + 1;
  }
  const entries = Object.entries(counts);
  let maxCount = 0;
  for (const e of entries) if (e[1] > maxCount) maxCount = e[1];
  const pct = maxCount / rounded.length;
  const unique = [...new Set(rounded)].filter((v) => v > 0);
  if (pct <= 0.6 || unique.length > 3) return [];
  entries.sort((a, b) => b[1] - a[1]);
  const dominant = entries[0][0];
  return [{ rule: "monotonous-spacing", el: null, snippet: "~" + dominant + "px used " + maxCount + "/" + rounded.length + " times (" + Math.round(pct * 100) + "%)" }];
}

export function directText(el) {
  const parts = [];
  for (const n of el.childNodes) {
    if (n.nodeType === 3) parts.push(n.textContent);
  }
  return parts.join(" ").replace(/\s+/g, " ").trim();
}

export function collapsedText(el) {
  return (el.textContent || "").replace(/\s+/g, " ").trim();
}

export function isIdLikeClass(token) {
  return token.split(/[-_]/).some((part) => part.length >= 4 && /^[0-9a-fA-F]+$/.test(part) && /[0-9]/.test(part));
}

const REPEATED_CONTAINER_TAGS = new Set(["div", "section", "article", "aside", "main", "figure", "form", "fieldset", "details", "li"]);
const REPEATED_SKIP = 'table,select,datalist,nav,menu,[role="navigation"],[role="menu"],[role="menubar"],[role="listbox"],[role="grid"],[role="tablist"],[role="radiogroup"],[aria-hidden="true"]';
const ICON_CLASS_RE = /icon|material-symbols|(?:^|\s)fa[srlbd]?(?:\s|-|$)/i;
const ALPHA_RE = /[a-zA-Z]/;

function bgAlpha(color) {
  const m = String(color || "").match(/rgba?\(([^)]*)\)/i);
  if (!m) return 0;
  const parts = m[1].split(",").map((s) => parseFloat(s));
  if (parts.length >= 4) return Number.isFinite(parts[3]) ? parts[3] : 0;
  return 1;
}

export function isRepeatedTextContainer(el) {
  const cs = csOf(el);
  if (!cs) return false;
  const hasShadow = cs.boxShadow !== "none" && !!cs.boxShadow;
  let sides = 0;
  for (const p of ["borderTopWidth", "borderRightWidth", "borderBottomWidth", "borderLeftWidth"]) {
    if ((parseFloat(cs[p]) || 0) >= 1) sides += 1;
  }
  const hasBorder = sides >= 3;
  const hasRadius = (parseFloat(cs.borderRadius) || 0) > 0;
  const hasBg = bgAlpha(cs.backgroundColor) > 0.1;
  if (!hasShadow && !hasBorder) return false;
  return hasRadius || hasBg;
}

export function checkRepeatedContainerText(root) {
  const out = [];
  const all = root.querySelectorAll ? root.querySelectorAll("*") : root.getElementsByTagName("*");
  const containers = [], cset = new Set();
  for (const el of all) {
    if (!REPEATED_CONTAINER_TAGS.has(el.tagName.toLowerCase())) continue;
    if (el.closest(REPEATED_SKIP)) continue;
    if (!isRepeatedTextContainer(el)) continue;
    containers.push(el);
    cset.add(el);
  }
  for (const c of containers) {
    const ccs = csOf(c);
    if (ccs && ccs.display === "none") continue;
    const desc = c.getElementsByTagName("*");
    if (desc.length > 250) continue;
    const groups = [];
    for (const d of desc) {
      let anc = d.parentElement, inner = false;
      while (anc && anc !== c) {
        if (cset.has(anc)) { inner = true; break; }
        anc = anc.parentElement;
      }
      if (inner) continue;
      if (d.closest(REPEATED_SKIP)) continue;
      if (ICON_CLASS_RE.test(typeof d.className === "string" ? d.className : "")) continue;
      const dcs = csOf(d);
      if (dcs && dcs.display === "none") continue;
      const text = directText(d);
      if (text.length < 4 || text.length > 48) continue;
      if (!ALPHA_RE.test(text)) continue;
      const sig = [];
      let cur = d;
      while (cur && cur !== c) {
        const raw = typeof cur.className === "string" ? cur.className.trim() : "";
        const parts = raw ? raw.split(/\s+/).filter((s) => s && !isIdLikeClass(s)) : [];
        parts.sort();
        const cls = parts.join(".");
        sig.push(cls ? cur.tagName.toLowerCase() + "." + cls : cur.tagName.toLowerCase());
        cur = cur.parentElement;
      }
      const joined = sig.join(">");
      const g = groups.find((g) => g[0] === text);
      if (g) g[1].push(joined);
      else groups.push([text, [joined]]);
    }
    for (const g of groups) {
      if (g[1].length < 3) continue;
      if (new Set(g[1]).size < 3) continue;
      const toks = typeof c.className === "string" ? c.className.trim().split(/\s+/).filter(Boolean) : [];
      const where = toks.length ? c.tagName.toLowerCase() + "." + toks.join(".") : c.tagName.toLowerCase();
      out.push({ rule: "repeated-container-text", el: c, snippet: '"' + g[0].slice(0, 40) + '" rendered ' + g[1].length + "× in distinct spots inside " + where });
    }
  }
  return out;
}

const NUMBERED_LABEL_TAGS = new Set(["span", "p", "div", "small", "em", "strong", "b"]);
const HEADING_TAG_SET = new Set(["h1", "h2", "h3", "h4", "h5", "h6"]);
const KICKER_SKIP = 'nav,form,table,thead,tbody,tfoot,figure,figcaption,ol,ul,li,[role="navigation"],[aria-label*="breadcrumb" i],[class*="breadcrumb" i],[aria-hidden="true"],[data-impeccable-allow-kickers]';
const KICKER_CARD_CTX = 'article,button,a,li,[role="listitem"],[role="option"]';
const NUMBERED_LABEL_MEDIA = "img, picture, video, canvas";
const TWO_DIGIT_RE = /^([0-9]{2})$/;
const NUM_SEP_RE = /^([0-9]{1,2})\s*[^0-9A-Za-z_\s]\s*\S/;

export function parseNumberedLabelText(raw) {
  const text = String(raw == null ? "" : raw).replace(/\s+/g, " ").trim();
  if (!text || text.length > 40) return null;
  const m = text.match(TWO_DIGIT_RE) || text.match(NUM_SEP_RE);
  if (!m) return null;
  const index = parseInt(m[1], 10);
  if (!Number.isFinite(index) || index > 40) return null;
  return { index: index, text: text };
}

export function labelSizeCeiling(headingFontSize, base) {
  const rel = Number.isFinite(headingFontSize) && headingFontSize > 0 ? headingFontSize * 0.45 : 0;
  return Math.max(base, Math.min(rel, 16));
}

export function stripEdgeQuotes(text) {
  let s = String(text == null ? "" : text);
  if (s.startsWith('"')) s = s.slice(1);
  if (s.endsWith('"')) s = s.slice(0, -1);
  return s;
}

export function isAccentColor(cssColor) {
  if (!cssColor) return false;
  const s = String(cssColor).trim();
  let m = s.match(/rgba?\(\s*([0-9]+)\s*,\s*([0-9]+)\s*,\s*([0-9]+)/);
  if (m) {
    const r = +m[1], g = +m[2], b = +m[3];
    return Math.max(r, g, b) - Math.min(r, g, b) >= 40;
  }
  m = s.match(/^#([0-9a-fA-F]{3,8})\b/);
  if (m) {
    const raw = m[1];
    let h;
    if (raw.length === 3 || raw.length === 4) h = [...raw].map((c) => c + c).join("").slice(0, 6);
    else h = raw.slice(0, 6);
    if (h.length === 6) {
      const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
      return Math.max(r, g, b) - Math.min(r, g, b) >= 40;
    }
  }
  if (/^oklch\(/i.test(s)) {
    const nums = s.match(/[0-9]*\.[0-9]+|[0-9]+/g) || [];
    if (nums.length >= 2) {
      const c = parseFloat(nums[1]);
      return !Number.isNaN(c) && c >= 0.05;
    }
  }
  m = s.match(/hsl[aA]?\(\s*[0-9.]+\s*,\s*([0-9.]+)%/);
  if (m) {
    const sat = parseFloat(m[1]);
    return !Number.isNaN(sat) && sat >= 20;
  }
  return false;
}

function holdsNumberText(t) {
  const text = String(t == null ? "" : t).replace(/\s+/g, " ").trim();
  return !!text && parseNumberedLabelText(text) != null;
}

function isNumberedLabelCandidate(o) {
  if (o.headingTag !== "h2" && o.headingTag !== "h3" && o.headingTag !== "h4") return false;
  if (!o.headingText || o.headingText.length < 3) return false;
  if (!o.labelTag || !NUMBERED_LABEL_TAGS.has(o.labelTag)) return false;
  if (o.labelIndex == null || !o.labelText) return false;
  if (!(o.labelFontSize > 0 && o.labelFontSize <= labelSizeCeiling(o.headingFontSize, 13))) return false;
  if (o.headingFontSize > 0 && o.headingFontSize < o.labelFontSize * 1.3) return false;
  const wn = Number(o.labelFontWeight);
  const weight = wn ? wn : 400;
  const spacing = o.labelLetterSpacing ? o.labelLetterSpacing : 0;
  return /mono/i.test(o.labelFontFamily) || weight >= 600 || spacing >= 0.5 || o.labelTextTransform === "uppercase" || isAccentColor(o.labelColor);
}

export function checkNumberedSectionLabels(root) {
  const cands = [];
  const seen = new Set();
  const headings = root.querySelectorAll ? root.querySelectorAll("h2, h3, h4") : [];
  for (const h of headings) {
    if (h.closest(KICKER_SKIP)) continue;
    let label = h.previousElementSibling;
    if (!label && h.parentElement && h.parentElement.firstElementChild === h) label = h.parentElement.previousElementSibling;
    if (!label || !label.tagName) continue;
    if (seen.has(label)) continue;
    if (label.closest(KICKER_SKIP)) continue;
    if (HEADING_TAG_SET.has(label.tagName.toLowerCase())) continue;
    const card = h.closest(KICKER_CARD_CTX);
    if (card && card.contains(label)) continue;
    if (label.querySelector(NUMBERED_LABEL_MEDIA)) continue;
    let svgNum = false;
    for (const svg of label.querySelectorAll("svg")) {
      if (svg.parentElement && holdsNumberText(directText(svg.parentElement))) { svgNum = true; break; }
    }
    if (svgNum) continue;
    let t = directText(label);
    if (!t) t = collapsedText(label);
    const parsed = parseNumberedLabelText(t);
    if (!parsed) continue;
    const hcs = csOf(h), lcs = csOf(label);
    if (!hcs || !lcs) continue;
    const inp = {
      headingTag: h.tagName.toLowerCase(),
      headingText: collapsedText(h),
      headingFontSize: parseFloat(hcs.fontSize) || 0,
      labelTag: label.tagName.toLowerCase(),
      labelIndex: parsed.index,
      labelText: parsed.text,
      labelFontSize: parseFloat(lcs.fontSize) || 0,
      labelLetterSpacing: parseFloat(lcs.letterSpacing) || 0,
      labelFontWeight: lcs.fontWeight,
      labelFontFamily: lcs.fontFamily,
      labelTextTransform: lcs.textTransform,
      labelColor: lcs.color,
    };
    if (!isNumberedLabelCandidate(inp)) continue;
    seen.add(label);
    cands.push({ index: parsed.index, labelText: parsed.text.slice(0, 24), headingTag: inp.headingTag, headingText: stripEdgeQuotes(inp.headingText).slice(0, 60), el: label });
  }
  if (cands.length < 2) return [];
  if (new Set(cands.map((c) => c.index)).size < 2) return [];
  return cands.map((c) => ({ rule: "numbered-section-labels", el: c.el, snippet: 'tiny numbered label "' + c.labelText + '" beside ' + c.headingTag + ' "' + c.headingText + '" (' + cands.length + " on page)" }));
}

export const CHECKS = { "em-dash-overuse": "page", "marketing-buzzword": "page", "aphoristic-cadence": "page", "theater-slop-phrase": "page", "monotonous-spacing": "page", "repeated-container-text": "elements", "numbered-section-labels": "elements" };
