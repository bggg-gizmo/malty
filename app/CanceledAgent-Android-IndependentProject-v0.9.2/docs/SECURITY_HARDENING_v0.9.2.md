# Malty v0.9.2-source security hardening — 2026-10-30

Source hardening is **not** included in the unchanged signed testing APK at downloads/v0.9.2/Malty-signed-v0.9.2.apk. That APK must not be represented as patched. A rebuilt APK requires real Android testing and the owner's existing authorized signing key for update compatibility.

## Security changes

* MLT-SEC-001: the standalone Python backend now requires configured bearer authentication on **all** non-health paths, including /v1/models, /v1/chat/completions, and /v1/messages; /ws remains protected. When AGENT_BACKEND_TOKEN is absent, non-loopback clients are refused.
* MLT-SEC-002: the Android outbound Bridge enables strict HTTPS hostname identification before the TLS handshake. A certificate trusted for localhost is rejected for 127.0.0.1 in the executable host regression.
* MLT-SEC-003: all inbound relay commands require an authenticated session/challenge, fresh timestamp, unique request ID, recomputed operation scope and HMAC-SHA256 signature over the exact payload. Forged/stale/unsigned commands fail closed; legitimate commands remain implemented but require a relay upgraded to the v1 envelope.
* MLT-SEC-004: LAN mode stays opt-in and HTTP-only, with existing bearer access control and added private/link-local IP filtering. **Plaintext on a trusted LAN remains a confidentiality risk.** Never expose port 8000 to the internet, or enable on untrusted Wi-Fi. Use an encrypted VPN or an authenticated TLS reverse proxy for untrusted transport. Not certified for hostile networks.
* MLT-SEC-005: unauthenticated Python access remains possible only from loopback (including in-process test client). For external deployment configure AGENT_BACKEND_TOKEN and an HTTPS ingress; a reverse proxy must not bypass authentication.
* MLT-SEC-006: the existing copy-token Settings button remains, but copied credentials are labeled sensitive and automatically cleared after 60 seconds if still in the accessible clipboard. Device-specific clipboard tools can still retain a copy.

All existing Android activities, local services, workflows, messages, and UI controls are retained.

## Secure outbound Bridge command contract

The Android Socket.IO client sends the existing bridge:register event with additional authVersion=1 and a new random challenge on every connection, along with sessionId. Only a trusted relay with the user-supplied local service access token may sign a command. The access token is already obtainable through the existing Settings → Copy local service access token button. Transfer it to the trusted relay out of band, securely; never commit it or put it in URLs or logs. It is a high-authority credential shared with the local HTTP service.

Relay events bridge:model_response and bridge:crossframe_out must use a data object with fields sessionId, challenge, requestId, issuedAt, scope, payloadJson, signature. The payloadJson value is a JSON **string** containing the exact object that will execute. The issuedAt value is Unix milliseconds, allowed within ±120 seconds. Request IDs must be unique within a connection, 1–128 ASCII alphanumeric, period, underscore, colon or hyphen characters. Payload strings are limited to 262144 characters. Duplicates are dropped.

Signature = lowercase hex HMAC-SHA256 using the local service access token (UTF-8) as key. The signed UTF-8 message is seven fields joined by a single newline, in order:

1. malty-bridge-command-v1
2. challenge
3. sessionId
4. requestId
5. issuedAt as decimal milliseconds
6. scope
7. original, unmodified payloadJson string

The device recomputes the required scope from the payload and rejects mismatches. Supported scopes are bridge:read for the documented read operations, bridge:write for local writes, inference:generate for normal provider calls and moltbook:write for Moltbook commands. Mixed op/request objects are rejected. The existing explicit agent-turn opt-in, account authorization, governor controls, and operation handlers continue to apply.

Reference signer (outputs an event body for the relay to emit, not a completed socket transmission):

~~~sh
MALTY_BRIDGE_TOKEN='a-token-copied-privately-from-the-device' \
python3 scripts/sign_bridge_command.py --session 'current-session-id' \
    --challenge 'current-connection-challenge' < command.json
~~~

The relay must send that JSON object as event data. Older unsigned command envelopes will connect but their remote operations will be rejected; upgrade the relay protocol before enabling Bridge. This is an intentional security compatibility gate, not deletion of any application operation.

## Host tests (no paid CI)

~~~sh
cd backend && PYTHONPATH=. python3 -m pytest -q
cd .. && PYTHONPATH=scripts python3 -m unittest scripts/test_bridge_signer.py -v
bash scripts/test_bridge_security.sh
TOOLCHAIN=/path/to/local-sdk bash scripts/build-direct.sh
~~~

The JDK test includes both correct-hostname and intentionally incorrect-hostname local certificate handshakes.

## Outstanding real-world acceptance

Physical Android device, live authenticated relay and provider interactions, duplicate-event runtime rejection, original third-party plugin behavior, untrusted-LAN TLS transport, and private history/archive credential examination remain separately pending. Never claim these are certified by host tests. Branch/tag rules and native GitHub Release publishing are not part of this security-source change.
