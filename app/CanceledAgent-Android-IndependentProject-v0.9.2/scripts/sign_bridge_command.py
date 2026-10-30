#!/usr/bin/env python3
"""Sign one Malty Bridge relay command without logging the device access token.

The signing secret comes from MALTY_BRIDGE_TOKEN in the local environment.
Payload JSON comes from stdin; stdout contains an authenticated event body.
"""
from __future__ import annotations
import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import uuid


def required_scope(call: dict) -> str:
    if "op" in call and "request" in call:
        raise ValueError("mixed op/request payload is forbidden")
    if "request" in call:
        q = call.get("request") or {}
        if not isinstance(q, dict):
            raise ValueError("request must be an object")
        return "moltbook:write" if str(q.get("model", "")).startswith("moltbook-team") else "inference:generate"
    op, action = str(call.get("op", "")), str(call.get("action", ""))
    if op in {"ping", "team-status", "team-capability", "race-list", "race-document", "roster-discover", "watch", "inbox"}:
        return "bridge:read"
    if op == "mesh" and action in {"inbox", "talk", "presence"}:
        return "bridge:read"
    if op == "character-agents" and action in {"list", "profile", "threads", "history"}:
        return "bridge:read"
    if op == "agent" and action == "status":
        return "bridge:read"
    if op == "moltbook" and action == "route":
        return "bridge:read"
    if op in {"moltbook", "team-dispatch", "race-create", "race-send", "race-collect", "reach", "reach-many"}:
        return "moltbook:write"
    return "bridge:write"


def envelope(secret: str, challenge: str, session_id: str, payload: dict, request_id: str, issued_at: int) -> dict:
    if not all((secret, challenge, session_id, request_id)):
        raise ValueError("secret, challenge, sessionId and requestId are required")
    if not isinstance(payload, dict):
        raise ValueError("payload must be an object")
    scope = required_scope(payload)
    payload_json = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    if len(payload_json) > 262144:
        raise ValueError("payload too large")
    authenticated = "\n".join(("malty-bridge-command-v1", challenge, session_id, request_id, str(issued_at), scope, payload_json))
    signature = hmac.new(secret.encode("utf-8"), authenticated.encode("utf-8"), hashlib.sha256).hexdigest()
    return {"challenge": challenge, "sessionId": session_id, "requestId": request_id,
            "issuedAt": issued_at, "scope": scope, "payloadJson": payload_json, "signature": signature}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--session", required=True, help="sessionId from the Bridge registration event")
    p.add_argument("--challenge", required=True, help="challenge from the current Bridge registration event")
    p.add_argument("--request-id", default=None)
    a = p.parse_args()
    secret = os.getenv("MALTY_BRIDGE_TOKEN", "")
    if not secret:
        p.error("MALTY_BRIDGE_TOKEN must be set in the environment")
    try:
        payload = json.load(sys.stdin)
        value = envelope(secret, a.challenge, a.session, payload, a.request_id or uuid.uuid4().hex, int(time.time() * 1000))
    except (ValueError, json.JSONDecodeError) as err:
        p.error(str(err))
    print(json.dumps(value, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
