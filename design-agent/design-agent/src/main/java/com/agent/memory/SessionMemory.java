package com.agent.memory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-term memory for a single agent session / pipeline run.
 * Holds phase outputs, tool-call history and metadata so that
 * later phases (CROSS_REF, DESIGN, PUBLISH) can build on earlier ones
 * without re-reading the codebase.
 */
public class SessionMemory {

    private final String sessionId;
    private final Instant createdAt;
    private final Map<String, String> phaseOutputs = new ConcurrentHashMap<>();
    private final List<ToolCallRecord> toolCallHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private String codebaseFingerprint;
    private String repoPath;

    public SessionMemory(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Map<String, String> getPhaseOutputs() { return phaseOutputs; }
    public List<ToolCallRecord> getToolCallHistory() { return toolCallHistory; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getCodebaseFingerprint() { return codebaseFingerprint; }
    public void setCodebaseFingerprint(String fp) { this.codebaseFingerprint = fp; }
    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public void addPhaseOutput(String phase, String output) {
        phaseOutputs.put(phase, output);
    }

    public void addToolCall(ToolCallRecord record) {
        toolCallHistory.add(record);
    }

    /**
     * Build a context string from all previous phase outputs for prompt enrichment.
     * Each phase output is truncated to avoid token explosion.
     */
    public String buildContextFromPreviousPhases() {
        if (phaseOutputs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n--- CONTEXT FROM PREVIOUS PHASES ---\n");
        phaseOutputs.forEach((phase, output) -> {
            sb.append("\n### ").append(phase).append(" Phase Output:\n");
            if (output.length() > 2000) {
                sb.append(output, 0, 2000).append("\n...[truncated]\n");
            } else {
                sb.append(output).append("\n");
            }
        });
        return sb.toString();
    }

    public boolean isExpired(long ttlMinutes) {
        return Instant.now().isAfter(createdAt.plusSeconds(ttlMinutes * 60));
    }

    public record ToolCallRecord(
            String toolName,
            String input,
            int outputTokenEstimate,
            long durationMs,
            Instant timestamp
    ) {}
}
