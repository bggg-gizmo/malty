package org.canceledagent.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticates individual relay commands; no command is trusted on relay identity alone. */
public final class BridgeRequestSecurity {
    private BridgeRequestSecurity() {}
    public static final long MAX_CLOCK_SKEW_MS = 120000L;
    public static String sign(String secret, String challenge, String sessionId, String requestId,
                              long issuedAt, String scope, String payloadJson) throws Exception {
        if (secret == null || secret.isEmpty()) throw new SecurityException("Missing bridge pairing secret");
        String message = "malty-bridge-command-v1\n" + challenge + "\n" + sessionId + "\n" + requestId
                       + "\n" + issuedAt + "\n" + scope + "\n" + payloadJson;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] result = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(result.length * 2);
        for (byte b : result) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }
    public static boolean verify(String secret, String expectedChallenge, String expectedSession,
                                 String challenge, String sessionId, String requestId, long issuedAt,
                                 String scope, String payloadJson, String providedHex, long now) {
        if (secret == null || secret.isEmpty() || expectedChallenge == null || expectedChallenge.isEmpty()
            || !expectedChallenge.equals(challenge) || expectedSession == null || !expectedSession.equals(sessionId)
            || requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")
            || !("bridge:read".equals(scope) || "bridge:write".equals(scope)
                 || "inference:generate".equals(scope) || "moltbook:write".equals(scope))
            || payloadJson == null || payloadJson.isEmpty() || payloadJson.length() > 262144
            || providedHex == null || !providedHex.matches("[A-Fa-f0-9]{64}")
            || issuedAt <= 0 || issuedAt < now - MAX_CLOCK_SKEW_MS || issuedAt > now + MAX_CLOCK_SKEW_MS)
            return false;
        try {
            String expected = sign(secret, challenge, sessionId, requestId, issuedAt, scope, payloadJson);
            byte[] a = new byte[32], b = new byte[32];
            for (int i = 0; i < 32; i++) {
                a[i] = (byte) Integer.parseInt(expected.substring(i * 2, i * 2 + 2), 16);
                b[i] = (byte) Integer.parseInt(providedHex.substring(i * 2, i * 2 + 2), 16);
            }
            return MessageDigest.isEqual(a, b);
        } catch (Exception ex) {
            return false;
        }
    }
}
