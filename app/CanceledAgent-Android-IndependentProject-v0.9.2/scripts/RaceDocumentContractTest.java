package org.canceledagent.app;

import java.util.Arrays;
import java.util.Collections;

public final class RaceDocumentContractTest {
    private static int checked = 0;
    private static void require(boolean ok, String message) { checked++; if (!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, message);
    }
    public static void main(String[] args) {
        RaceDocument.Section alpha = new RaceDocument.Section("Alpha", "Security", "post-a", "comment-a", "Exactly this line.\nNo edits.", "complete");
        RaceDocument.Section beta = new RaceDocument.Section("Beta", "Reliability", "post-b", "", "", "not-visible");
        String doc = RaceDocument.combine("Audit two services", Arrays.asList(alpha, beta));
        require(doc.contains("Brief: Audit two services"), "Brief missing");
        require(doc.contains("Author: Alpha\nPost: post-a\nComment: comment-a"), "Attribution missing");
        require(doc.contains("Exactly this line.\nNo edits."), "Verbatim text changed");
        require(!doc.contains("Exactly this line.\nNo edits.\n\nExactly this"), "Text duplicated");
        require(doc.contains("Author: Beta\nPost: post-b\nStatus: MISSING"), "Missing section was invented");
        require(doc.contains("Delivery state: not-visible"), "Delivery evidence dropped");
        rejects(() -> RaceDocument.combine("a", Arrays.asList(alpha, alpha)), "Duplicate agent allowed");
        rejects(() -> new RaceDocument.Section("Alpha", "Security", "post", "", "content", "complete"), "No source comment allowed");
        rejects(() -> RaceDocument.combine("", Collections.singletonList(beta)), "Empty brief allowed");
        rejects(() -> RaceDocument.combine("x", Collections.emptyList()), "Empty race allowed");
        rejects(() -> RaceDocument.combine("x", Arrays.asList(alpha, beta, alpha, beta, alpha, beta)), "More than 5 allowed");
        System.out.println("RaceDocument executable checks passed: " + checked);
    }
}
