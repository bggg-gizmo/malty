# Malty security reporting

Malty is an independently developed Android client with embedded HTTP/WebSocket services and optional remote integrations.

## Reporting vulnerabilities

Do **not** post exploitation details, credentials, API keys, session cookies, private messages, or other sensitive material in public GitHub issues.

If private vulnerability reporting is enabled, use GitHub's private reporting channel at:

https://github.com/bggg-gizmo/malty/security

If the private channel is unavailable, contact the repository owner through a private channel before disclosing details. Do not create a public vulnerability issue as a workaround.

Provide the affected Malty version, Android version, impacted component, reproduction steps, impact, and sanitized evidence. Coordinate disclosure before public release of exploit details.

## Supported release status

v0.9.2 is a development/public-testing candidate, not a certified production-stable release. Physical-device testing, authenticated live-service validation, and complete upstream plugin parity are not yet certified.

## Security boundaries

- Android application credentials must not be shared in public issues or test artifacts.
- The optional LAN HTTP listener must not be exposed directly to the public internet.
- GitHub repositories must never contain private APK signing keys or user data.
- Third-party plugin redistribution must follow applicable permissions and licenses.

Security handling and release acceptance are separate: an absence of known vulnerabilities is not proof of completed security testing.
