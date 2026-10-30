// src/fallback-llm.js — inference for Moltbook management + taglines.
//
// CUDOS-FIRST (handed off 2026-10-17): the configured OpenAI-compatible
// endpoint (default: the user's CUDOS inference URL) is the PRIMARY backend;
// root.generateText (ai-text-plugin = Perchance inference) is the fallback.
// Order is switchable via localStorage `llm_primary` ("cudos"|"perchance").
// Every call returns { text, backend } so logs always say who wrote the words.
//
// SECURITY: no secrets in this file. Keys live in the browser's
// localStorage (set via the Dashboard UI) and are passed in per call. The
// CUDOS key is sent ONLY to the configured inference endpoint — never to
// Moltbook, never logged, never uploaded anywhere else.

export const DEFAULT_ENDPOINT = "https://inference.asicloud.cudos.org/v1/chat/completions";
export const LS_KEY = "cudos_key";
export const LS_ENDPOINT = "cudos_endpoint";
export const LS_MODEL = "cudos_model";
export const LS_PRIMARY = "llm_primary"; // "cudos" (default) | "perchance"

export class LlmUnreachable extends Error {}
export class LlmApiError extends Error {}

function short(e) { return String((e && e.message) || e).slice(0, 160); }

// OpenAI-compatible chat completion. Throws LlmUnreachable on network
// failure/timeout, LlmApiError on HTTP/API problems.
export async function chatCompletions({ endpoint, apiKey, model, messages, timeoutMs = 60000, maxTokens = 500, temperature = 0.7 }) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(endpoint, {
      method: "POST",
      signal: ctrl.signal,
      headers: { "Content-Type": "application/json", "Authorization": "Bearer " + apiKey },
      body: JSON.stringify({ model, messages, max_tokens: maxTokens, temperature }),
    });
    if (!res.ok) throw new LlmApiError("HTTP " + res.status + ": " + (await res.text()).slice(0, 200));
    const j = await res.json();
    const text = j && j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
    if (typeof text !== "string" || !text.trim()) throw new LlmApiError("empty choices in response");
    return text;
  } catch (e) {
    if (e instanceof LlmApiError) throw e;
    throw new LlmUnreachable("endpoint unreachable: " + short(e));
  } finally { clearTimeout(t); }
}

function withTimeout(promise, ms, label) {
  let t;
  const timeout = new Promise((_, rej) => { t = setTimeout(() => rej(new LlmUnreachable(label + " timed out after " + ms + "ms")), ms); });
  return Promise.race([
    Promise.resolve(promise).then((v) => { clearTimeout(t); return v; }, (e) => { clearTimeout(t); throw e; }),
    timeout,
  ]);
}

export function fallbackConfigured() {
  try {
    return !!((localStorage.getItem(LS_KEY) || "").trim() && (localStorage.getItem(LS_MODEL) || "").trim());
  } catch (e) { return false; }
}

// Which backend goes first: "cudos" (default) or "perchance".
export function primaryBackend() {
  try {
    return ((localStorage.getItem(LS_PRIMARY) || "cudos").trim() === "perchance") ? "perchance" : "cudos";
  } catch (e) { return "cudos"; }
}
export function setPrimaryBackend(which) {
  try { localStorage.setItem(LS_PRIMARY, which === "perchance" ? "perchance" : "cudos"); } catch (e) {}
}

async function viaPerchance(prompt, system, ms) {
  if (typeof root.generateText !== "function") throw new LlmUnreachable("ai-text-plugin not loaded (unsaved?)");
  const full = system ? system + "\n\n" + prompt : prompt;
  const out = await withTimeout(root.generateText(full), ms, "perchance inference");
  const text = typeof out === "string" ? out : (out && (out.text || out.output || out.result)) || "";
  if (typeof text === "string" && text.trim()) return text;
  throw new LlmUnreachable("primary returned empty");
}

async function viaCudos(prompt, system, ms, maxTokens) {
  const endpoint = ((localStorage.getItem(LS_ENDPOINT) || "").trim() || DEFAULT_ENDPOINT);
  const apiKey = (localStorage.getItem(LS_KEY) || "").trim();
  const model = (localStorage.getItem(LS_MODEL) || "").trim();
  if (!apiKey || !model) throw new LlmUnreachable("CUDOS not configured — save key + model in Dashboard");
  const messages = [];
  if (system) messages.push({ role: "system", content: system });
  messages.push({ role: "user", content: prompt });
  return chatCompletions({ endpoint, apiKey, model, messages, timeoutMs: ms, maxTokens });
}

// infer(): CUDOS first (when configured), Perchance fallback — or the
// reverse if the Dashboard order is flipped. Returns { text, backend }.
// Throws only if every attempted backend fails.
export async function infer({ prompt, system = "", primaryTimeoutMs = 45000, fallbackTimeoutMs = 60000, maxTokens = 500 } = {}) {
  const first = primaryBackend();
  const errs = [];
  if (first === "cudos") {
    try { return { text: await viaCudos(prompt, system, fallbackTimeoutMs, maxTokens), backend: "cudos" }; }
    catch (e) { errs.push("cudos: " + short(e)); }
    const text = await viaPerchance(prompt, system, primaryTimeoutMs).catch((e) => { errs.push("perchance: " + short(e)); throw new Error(errs.join(" | ")); });
    return { text, backend: "perchance" };
  }
  try { return { text: await viaPerchance(prompt, system, primaryTimeoutMs), backend: "perchance" }; }
  catch (e) { errs.push("perchance: " + short(e)); }
  const text = await viaCudos(prompt, system, fallbackTimeoutMs, maxTokens).catch((e) => { errs.push("cudos: " + short(e)); throw new Error(errs.join(" | ")); });
  return { text, backend: "cudos" };
}

// testFallback(): minimal probe — asks the endpoint to reply "ok". Used by
// the Dashboard Test button. Returns { ok, endpoint, model, ms } or throws.
export async function testFallback() {
  const endpoint = ((localStorage.getItem(LS_ENDPOINT) || "").trim() || DEFAULT_ENDPOINT);
  const apiKey = (localStorage.getItem(LS_KEY) || "").trim();
  const model = (localStorage.getItem(LS_MODEL) || "").trim();
  if (!apiKey) throw new Error("no CUDOS key saved");
  if (!model) throw new Error("no model saved");
  const t0 = Date.now();
  const text = await chatCompletions({
    endpoint, apiKey, model,
    messages: [{ role: "user", content: "Reply with exactly: ok" }],
    timeoutMs: 45000, maxTokens: 20, temperature: 0,
  });
  return { ok: true, endpoint, model, ms: Date.now() - t0, reply: text.trim().slice(0, 40) };
}
