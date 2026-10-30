"""Verify signing payload, scope classification, and HMAC integrity."""
import hashlib
import hmac
import json
import unittest
from sign_bridge_command import envelope, required_scope


class BridgeSignerTest(unittest.TestCase):
    def test_scope_mapping(self):
        samples = [
            ({"op": "ping"}, "bridge:read"),
            ({"op": "mesh", "action": "send"}, "bridge:write"),
            ({"op": "moltbook", "action": "operate"}, "moltbook:write"),
            ({"request": {"model": "moltbook-team"}}, "moltbook:write"),
            ({"request": {"model": "local-llm"}}, "inference:generate"),
        ]
        for call, expected in samples:
            with self.subTest(call=call):
                self.assertEqual(required_scope(call), expected)

    def test_mixed_op_request_rejected(self):
        with self.assertRaises(ValueError):
            required_scope({"op": "ping", "request": {}})

    def test_valid_signature_covers_session_challenge_scope_and_payload(self):
        token = "test-only-secret"
        cmd = {"op": "mesh", "action": "send", "value": 3}
        result = envelope(token, "challenge", "android-session", cmd, "req-123", 123456)
        signed = "\n".join(("malty-bridge-command-v1", result["challenge"], result["sessionId"],
                            result["requestId"], str(result["issuedAt"]), result["scope"], result["payloadJson"]))
        self.assertEqual(result["signature"], hmac.new(token.encode(), signed.encode(), hashlib.sha256).hexdigest())
        self.assertEqual(json.loads(result["payloadJson"]), cmd)

    def test_missing_token_rejected(self):
        with self.assertRaises(ValueError):
            envelope("", "challenge", "session", {"op": "ping"}, "req", 123)


if __name__ == "__main__":
    unittest.main()
