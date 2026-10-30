
# Skill: Fetch the Latest Blueprint (for Forks & Review)

## When to use this skill

Use this skill when the user asks you to fetch/grab the latest Blueprint
file — e.g. "grab the latest blueprint from perchance.org/ai-helper-blueprint"
or "fetch the blueprint from that generator". Two legitimate cases:

1. **This generator is a FORK of the blueprint** (a project using the
   blueprint). Fetching the latest MASTER from the blueprint generator and
   installing it here is the REBASE CHECK / fork-update workflow.
2. **Reading another generator's copy for review** (e.g. the master owner
   inspecting a fork's local additions before deciding to promote them).

There is ONE case this skill does NOT cover: the ai-helper-blueprint
generator (the master owner) installing another generator's copy as its own
`-latest` master. That would replace the master with a fork — never do it.

## Problem

`fetch_generator` downloads a generator's `main.pjs` and `index.html`, but
NOT its `src/` files. The Blueprint file lives in `src/`, so it is invisible
to `fetch_generator`. You need a different method to retrieve it.

## THE #1 RULE: user.uploads.dev keys are immutable and never stale

A `https://user.uploads.dev/file/<key>` URL returns EXACTLY the bytes that
were uploaded under that key — always. It is the only source you can fully
trust. All "which key is current?" lookups go through a cacheable layer and
can lie. So:

- To VERIFY a file's content: always read it by its key.
- To DISCOVER the current key: see "Finding the current key" below.

## Finding the current key (trust order)

Use the FIRST method that works; lower ones are more likely to be stale:

1. **The perchance API (BEST)** — `fetch_url` the API URL:
   `https://perchance.org/api/getGeneratorsAndDependencies?generatorNames=<name>`
   The response's `lastEditTime` tells you the exact save time, and the
   generator's `code` (main.pjs) contains the quick-path URL which embeds the
   CURRENT `-latest` key — if that generator keeps it updated.
2. **Direct browser fetch of the subdomain** — in `execute_js`:
   `await fetch("https://<generatorPublicId>.perchance.org/<name>")` and parse
   the `preloaded-generator-data` element. This is a REAL browser fetch, not
   proxied, so it is not affected by the proxy cache. (If CORS blocks the
   subdomain, fall through to 3.)
3. **The `fetch_url` tool's cached page** — most likely to be stale. Only use
   if 1 and 2 failed. Re-fetch, wait, and re-fetch again; a changed result
   means you were seeing a cache.

### Parsing the srcManifest (for methods 2–3)

The generator's public page (`https://perchance.org/<name>`) contains a
`<script id="preloaded-generator-data">` element whose text content is
URL-encoded JSON. That JSON has a `srcManifest` field mapping each `src/`
filename to a hosting key.

```js
const html = await fs.readTextFile("scratch/<name>-page.html");
const m = html.match(/id="preloaded-generator-data"[^>]*>([\s\S]*?)<\/script>/);
const decoded = decodeURIComponent(m[1].trim());
const data = JSON.parse(decoded);
return data.srcManifest;
```

## Identify the latest blueprint file

In the `srcManifest`, look for these key entries:

- `perchance-ai-helper-blueprint-latest.txt` — the current top-level working
  file (always the latest version).
- `Blueprint-Archive/perchance-ai-helper-blueprint-vXX.YY.ZZ.txt` — archived
  copies of every version.

If the generator uses the old naming convention (no `-latest` file, just a
versioned file at the top level of `src/`), grab the highest-versioned
`perchance-ai-helper-blueprint-v*.txt` at the top level instead.

## Download the blueprint file

```
fetch_url "https://user.uploads.dev/file/<key>" -> scratch/blueprint-latest.txt
```

## Install it — depends on WHO this generator is

### Case A: THIS generator is a FORK (the normal case)

Follow the Blueprint's fork/version-control policy (read the "Version Control
Policy" and "FORK PROVENANCE" sections in the downloaded file for the
authoritative rules):

1. **Read the version** from the new file's header (e.g. "v2.7").
2. **Archive the existing old version**: copy the current top-level blueprint
   file into `src/Blueprint-Archive/` with its version number in the filename
   (if not already archived there).
3. **Write the new base** to `src/perchance-ai-helper-blueprint-latest.txt`
   (the fork's copy — name never changes).
4. **Update the fork provenance** at the top of that file: set
   `FORKED FROM MASTER:` to the new master version, and re-apply your LOCAL
   ADDITIONS on top of the new base (dropping any that were promoted — see
   the REBASE CHECK in the blueprint).
5. **Clean up**: only ONE blueprint file should exist at the top level of
   `src/`. Use the `patch` tool with `*** Delete File:` to remove leftovers.

### Case B: reading for review only

Download the file by its key and read it. Do NOT write it into this
generator's `src/` as the `-latest` file unless this generator is a fork and
the file is the master (Case A). The master owner never installs another
generator's copy.

## Update the quick-reference in main.pjs  (MANDATORY for forks)

The main.pjs "HOW TO READ THE BLUEPRINT" section contains a direct URL and a
srcManifest example. After installing a new base, update them so future
sessions can use the quick path:

1. Update the direct URL (`https://user.uploads.dev/file/<key>`) to the new
   key.
2. Update the srcManifest example to show the `-latest` filename, new key,
   and new size.
3. This step is NOT optional — if the quick-reference URL is stale, every
   future session that tries the quick path will fail and fall through to the
   full fetch-page-and-parse workflow unnecessarily.

## Verify — and how to prove a save actually landed

After any save, do NOT trust a page fetch. Verify in this order:

1. **API lastEditTime**: re-fetch
   `https://perchance.org/api/getGeneratorsAndDependencies?generatorNames=<name>`
   and confirm `lastEditTime` advanced to the moment you saved. If it didn't
   move, the save did not publish.
2. **Read the file by its key**: confirm the content at
   `https://user.uploads.dev/file/<key>` matches what you installed
   (compare sizes and, better, the version header line).
3. Only then check the manifest (via direct fetch if possible).

## Quick reference: the manifest keys

The `srcManifest` object looks like this (truncated):

```json
{
  "perchance-ai-helper-blueprint-latest.txt": {
    "key": "6fe3547aa774d4e69836808c26b7c06c.txt",
    "size": 39441
  },
  "Blueprint-Archive/perchance-ai-helper-blueprint-v02.00.txt": {
    "key": "7e5be7d8e5bd09630615bc8802a05aad.txt",
    "size": 37336
  },
  "Blueprint-Archive/perchance-ai-helper-blueprint-v02.01.txt": {
    "key": "6fe3547aa774d4e69836808c26b7c06c.txt",
    "size": 39441
  },
  "core-design-principles-latest.md": { "key": "...", "size": ... },
  "task-list.md": { "key": "...", "size": ... },
  ...
}
```

## Notes

- The `srcManifest` contains ALL of the generator's `src/` files, not just
  the blueprint. You can use the same technique to fetch any `src/` file
  (design docs, task lists, conversation history, etc.).
- The same `key` may appear for both the `-latest` file and the latest
  archive entry — they're the same file.
- If the `preloaded-generator-data` element is missing or `srcManifest` is
  absent, the generator may have no `src/` files, or the page structure may
  have changed. Fall back to `fetch_generator` and inspect `main.pjs` for
  alternative hosting URLs.
- **CACHE GOTCHA**: page fetches — even of the generator's direct subdomain —
  go through a proxy that can serve a STALE copy for minutes or longer after
  a save. Cache-busting query params do NOT reliably bypass it. This is why
  the trust order above and the verify section exist. Never conclude "the
  update didn't save" from a page fetch alone; check the API's `lastEditTime`
  and read the file by its immutable key.
- **FORK SAFETY**: the ai-helper-blueprint generator is the MASTER owner. If
  a session of that generator is asked to "grab the latest blueprint from
  <project>", that project holds a fork — read it for review, but NEVER
  install it as the master. Features flow into the master only via promotion,
  at the owner's command.
