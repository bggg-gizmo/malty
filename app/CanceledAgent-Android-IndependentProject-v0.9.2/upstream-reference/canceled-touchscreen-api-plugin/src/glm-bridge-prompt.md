You are building a generic Perchance-to-API bridge server (Next.js 16 + Prisma + socket.io mini-service), like the reference at https://perchance-generator-to-api.space-z.ai/ — but NOT hardcoded to one generator. The user must be able to paste ANY perchance generator link and drive it through your server.

GOAL
- A deployed web app with OpenAI-compatible POST /v1/chat/completions, Anthropic-compatible POST /v1/messages, GET /v1/models, plus /api/models CRUD and /api/bridge/* relay endpoints.
- A browser client that embeds the user's chosen perchance generator as a child iframe and brokers postMessage traffic both ways, bridged to REST via a socket.io bridge-service.
- Multiple models behind one surface (perchance-bridge, openai-direct, anthropic-direct, openai-compatible).

PERCHANCE EMBED RULES (critical)
- NEVER embed https://perchance.org/<name> directly cross-origin. It returns X-Frame-Options: SAMEORIGIN + Cloudflare challenge and will not load.
- ALWAYS embed via https://null.perchance.org/<generator-name>. This 301-redirects to https://<uid>.perchance.org/<generator-name> which serves with NO X-Frame-Options and loads cleanly. Browsers follow the 301 transparently inside iframes.
- Accept any of these inputs and normalize to the embed URL:
  full URL (https://perchance.org/foo, https://<uid>.perchance.org/foo), bare name (foo), or null URL. Lowercase the name. Preserve any #hash.
- The framed generator only responds to controls if its author added the TouchBridge receiver snippet:
  <script>addEventListener("message",e=>{let m=e.data;if(!m||!m.__touchBridge)return;let el=m.x!==undefined?document.elementFromPoint(innerWidth*m.x/100,innerHeight*m.y/100):document.activeElement||document.body;if(!el)return;let b=m.button==="middle"?1:m.button==="right"?2:0;let px=m.x!==undefined?innerWidth*m.x/100:0,py=m.y!==undefined?innerHeight*m.y/100:0;function dn(){let i={button:b,bubbles:true,cancelable:true,clientX:px,clientY:py};try{el.dispatchEvent(new PointerEvent("pointerdown",i))}catch(x){}try{el.dispatchEvent(new MouseEvent("mousedown",i))}catch(x){}}function up(){let i={button:b,bubbles:true,cancelable:true,clientX:px,clientY:py};try{el.dispatchEvent(new PointerEvent("pointerup",i))}catch(x){}try{el.dispatchEvent(new MouseEvent("mouseup",i))}catch(x){}}if(m.kind==="mousedown")dn();else if(m.kind==="mouseup")up();else if(m.kind==="click"){dn();up();try{el.dispatchEvent(new MouseEvent("click",{button:b,bubbles:true,cancelable:true,clientX:px,clientY:py}))}catch(x){}try{el.focus()}catch(x){}if((m.count||1)>=2)for(let i=1;i<m.count;i++)try{el.dispatchEvent(new MouseEvent("dblclick",{button:b,bubbles:true}))}catch(x){}if(m.button==="right")try{el.dispatchEvent(new MouseEvent("contextmenu",{button:2,bubbles:true,cancelable:true}))}catch(x){}}else if(m.kind==="wheel")scrollBy(0,m.dy||100);else if(m.kind==="type"){let a=document.activeElement;if(a&&"value"in a){a.value=(a.value||"")+m.text;a.dispatchEvent(new Event("input",{bubbles:true}))}}else if(m.kind==="key"){(document.activeElement||document.body).dispatchEvent(new KeyboardEvent("keydown",{key:m.key,bubbles:true}))}});</script>
  Without it: look but don't touch. Document this in the UI.

ARCHITECTURE TO BUILD
1. Next.js app (App Router) with tabs: Overview, Canvas (generic generator iframe + URL input + Load button), Models, Chat Test, Manual Relay, Embed.
2. Canvas tab: text input for perchance URL/name, Load button, sandboxed iframe (allow-scripts allow-same-origin allow-forms), child-iframe ref wired to bridge client, "send directly to iframe" test button.
3. Mini-service bridge-service on port 3003 (socket.io server + plain HTTP): routes /health /sessions /events /relay /broadcast /push. Maintains sessions map (sessionId, socketId, parentOrigin, hasChild, connectedAt, lastSeen) + pending-request table (requestId -> resolve/reject, 60s timeout) + in-memory event ring buffer. Forwards payloads as WS event bridge:crossframe_out with target field.
4. Browser lib PerchanceBridge (socket.io-client): setChildIframe(iframe) idempotent, connect({onEvent,onStatus}), inbound postMessage classified by source (parent vs child via e.source === window.parent / iframe.contentWindow), emits bridge:crossframe_in with source tag, outbound bridge:crossframe_out routed by target: parent -> window.parent.postMessage, child -> childIframe.contentWindow.postMessage, both -> both, auto -> child if hasChild else parent. wsUrl via relative /?XTransformPort=3003 behind Caddy.
5. Server helpers src/lib/bridge.ts: BRIDGE_PORT=3003, BRIDGE_BASE=http://localhost:PORT, bridgeHealth/listSessions/listEvents/relay/broadcast with target passthrough. Next.js /api/bridge/* routes forward target.
6. REST: /v1/chat/completions (OpenAI shape) and /v1/messages (Anthropic shape) with bidirectional converters. When model provider is perchance-bridge, relay to bridge-service with target (default child, overridable via model.extraConfig.target JSON), await matching crossframe_in by requestId, convert back. Unknown model -> 404 JSON. /v1/models lists all enabled models in OpenAI shape.
7. Prisma sqlite: ModelConfig (id, name, provider, wireFormat, baseUrl, apiKey, defaultModel?, extraConfig?, enabled, createdAt, updatedAt) + BridgeEvent (id, direction, source, payload, sessionId?, createdAt). Seed: canvas-equivalent generic perchance-bridge model (target=child), openai-direct placeholder, anthropic-direct placeholder.
8. Embed tab docs: iframe snippet for embedding YOUR app in perchance, perchance generator snippet ([touchscreen] + receiver), REST curl examples for /v1/chat/completions and /v1/messages, routing diagram parent<->bridge<->child.
9. X-Frame-Options: ALLOWALL / frame-ancestors * so perchance CAN embed your app. allowedDevOrigins for preview domains.

CONSTRAINTS
- No hardcoded generator name anywhere except defaults. Every generator reference must flow from the URL input state.
- Hover/mousemove messages must NEVER trigger clicks. Clicks only fire full dn/up/click sequences.
- ESLint clean, no console errors, mobile-usable layout.

VERIFY LIKE THIS
- Load app, paste 2 different perchance generator URLs, confirm each iframe renders real content (non-blank pixel variance).
- /v1/models returns all seeded models. POST /v1/chat/completions with perchance-bridge model fires rest.out event with target=child and routes to session with hasChild=true. Same for /v1/messages. Unknown model -> 404.
- Manual Relay with target parent/child/both/auto delivers to correct frame. Only 1 bridge:register per iframe attach.

REFERENCE BUNDLE
- The attached glm-perchance-touchscreen-api.tar.gz is a working instance hardcoded to one demo generator. Use it as architecture reference (bridge-service, converters, PerchanceBridge, seed script, Caddyfile) but generalize the generator input as specified above.
