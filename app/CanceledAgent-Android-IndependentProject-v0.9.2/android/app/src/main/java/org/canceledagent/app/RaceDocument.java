package org.canceledagent.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic, attribution-preserving race document. Never writes text for an absent agent. */
public final class RaceDocument {
    private RaceDocument() { }

    public static final class Section {
        public final String agent;
        public final String assignment;
        public final String postId;
        public final String commentId;
        public final String text;
        public final String status;

        public Section(String agent, String assignment, String postId, String commentId,
                       String text, String status) {
            this.agent = required(agent, "agent");
            this.assignment = required(assignment, "assignment");
            this.postId = required(postId, "postId");
            this.commentId = commentId == null ? "" : commentId.trim();
            this.text = text == null ? "" : text;
            this.status = status == null ? "" : status;
            if (!this.text.isEmpty() && this.commentId.isEmpty()) {
                throw new IllegalArgumentException("An authored section must include the source comment ID");
            }
        }
    }

    private static String required(String input, String name) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException(name + " required");
        return input.trim();
    }

    public static String combine(String brief, List<Section> sections) {
        required(brief, "brief");
        if (sections == null || sections.isEmpty() || sections.size() > 5)
            throw new IllegalArgumentException("Race requires 1 to 5 sections");
        Set<String> seen = new HashSet<>();
        StringBuilder out = new StringBuilder("RACE DOCUMENT\n\nBrief: ").append(brief)
            .append("\n\nThe following text is verbatim from the cited author comments. ")
            .append("Unanswered assignments remain explicitly missing.\n");
        for (Section section : sections) {
            if (!seen.add(section.agent.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate agent " + section.agent);
            out.append("\n=== Section: ").append(section.assignment).append(" ===\n")
               .append("Author: ").append(section.agent).append("\n")
               .append("Post: ").append(section.postId).append("\n");
            if (!section.text.isEmpty()) {
                out.append("Comment: ").append(section.commentId).append("\n")
                   .append("Status: received (unverified claims retain author attribution)\n\n")
                   .append(section.text).append("\n");
            } else {
                out.append("Status: MISSING (no qualifying visible author reply)\n")
                   .append("Delivery state: ").append(section.status).append("\n");
            }
        }
        return out.toString();
    }
}
