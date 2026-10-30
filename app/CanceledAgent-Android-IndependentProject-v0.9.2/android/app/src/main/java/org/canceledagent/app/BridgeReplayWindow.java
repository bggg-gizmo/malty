package org.canceledagent.app;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded fail-closed replay barrier for the active authenticated Bridge connection. */
public final class BridgeReplayWindow {
    public static final int MAX_REQUESTS = 8192;
    public static final long HOLD_MS = 2L * BridgeRequestSecurity.MAX_CLOCK_SKEW_MS;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    public synchronized void reset() {seen.clear();}
    public synchronized boolean accept(String requestId, long nowMs) {
        if (requestId == null || requestId.isEmpty()) return false;
        Iterator<Map.Entry<String, Long>> iter = seen.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Long> record = iter.next();
            if (nowMs - record.getValue() > HOLD_MS) iter.remove();
        }
        if (seen.containsKey(requestId) || seen.size() >= MAX_REQUESTS) return false;
        seen.put(requestId, nowMs);
        return true;
    }
}
