package com.agent.memory;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record stored in long-term (SQLite) memory.
 * Captures a single phase execution: summary, findings, token usage and cost.
 */
public record LongTermMemoryEntry(
        String id,
        String projectId,
        String repoPath,
        String phase,
        String summary,
        String keyFindings,
        String decisions,
        Map<String, String> metadata,
        long inputTokens,
        long outputTokens,
        double costUsd,
        Instant createdAt
) {
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id, projectId, repoPath, phase, summary, keyFindings, decisions;
        private Map<String, String> metadata = Map.of();
        private long inputTokens, outputTokens;
        private double costUsd;
        private Instant createdAt = Instant.now();

        public Builder id(String id) { this.id = id; return this; }
        public Builder projectId(String pid) { this.projectId = pid; return this; }
        public Builder repoPath(String rp) { this.repoPath = rp; return this; }
        public Builder phase(String p) { this.phase = p; return this; }
        public Builder summary(String s) { this.summary = s; return this; }
        public Builder keyFindings(String kf) { this.keyFindings = kf; return this; }
        public Builder decisions(String d) { this.decisions = d; return this; }
        public Builder metadata(Map<String, String> m) { this.metadata = m; return this; }
        public Builder inputTokens(long t) { this.inputTokens = t; return this; }
        public Builder outputTokens(long t) { this.outputTokens = t; return this; }
        public Builder costUsd(double c) { this.costUsd = c; return this; }
        public Builder createdAt(Instant i) { this.createdAt = i; return this; }

        public LongTermMemoryEntry build() {
            return new LongTermMemoryEntry(id, projectId, repoPath, phase, summary,
                    keyFindings, decisions, metadata, inputTokens, outputTokens, costUsd, createdAt);
        }
    }
}
