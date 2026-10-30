# canceled-ai-helper-thinking-plugin

Editor-only thought log for the AI helper, distributed as a standard Perchance `{import:...}` plugin.

## How it works

- `main.pjs` holds all plugin logic. `$output(opts)` returns an HTML string (a String object with `.log / .logChange / .getFeedback / .entries / .clear / .revert / .refresh` methods attached, same pattern as `comments-plugin`).
- The widget renders only in edit mode (`generatorIsInEditMode`, `generatorIsUnsaved`, `#edit` hash, or `?showThinking=1`). Published pages get an empty string, so no thought text leaks into public HTML. `forceShow:true` overrides for previews.
- State lives in `window.__aiThink` (per-key arrays in memory + localStorage `ai-thinking-plugin:<generatorName>:<key>`). Legacy `ai-thought-ratings-v1` entries migrate into the `default` key once.
- `index.html` is docs + live demo only; it never ships into host generators (imports run `main.pjs`, not the plugin's HTML).

## Host integration

```
aiThinking = {import:canceled-ai-helper-thinking-plugin}
[aiThinking({key:"build"})]
Default mode is a floating picture-in-picture dock (draggable header, collapse, close-to-bubble, position persisted per generator+key). Pass {mode:"inline"} for the classic embedded panel, {title:"..."} to rename the window.
let h = root.aiThinking({key:"build"});
h.log("...", {pivot:true});
h.logChange("v3 sidebar", snapshotString, "thought behind it");
window.addEventListener("ai-thinking-revert", e => { apply(e.detail.entry.snapshot); });
```

## Unified thought pipeline + trace-back

- One store per key: `pipe({kind:"think"|"action"|"change", text, changeLabel, snapshot, parentId})`, plus `think()`, `act()`, `log()`, `logChange()` shortcuts. Every entry gets `seq`, `kind`, `parentId` (defaults to previous entry).
- Trace-back: `trace(id)` returns the root-to-entry chain; ⤴ Trace on any card isolates its chain; Trace view renders indented chains; copy output includes `[thinking <id> #<seq>]` refs plus a TRACE-BACK section; ⤓ Export traces copies full JSON to share.

## Helper bridge (page_eval, editor only)

```
window.logAiThought("...", {pivot:true});
window.__aiThink.log("build", "...");
```
