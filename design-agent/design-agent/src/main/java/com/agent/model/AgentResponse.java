package com.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(

        String sessionId,
        AgentPhase phase,
        AgentStatus status,

        /** Human-readable summary of what the agent did. */
        String summary,

        /** Full agent output for this phase. */
        String output,

        /** Populated after PUBLISH phase completes. */
        DesignDocument designDocument,

        /** Error message when status == FAILED. */
        String error,

        Instant completedAt
) {
    public static AgentResponse success(String sessionId, AgentPhase phase,
                                        String summary, String output) {
        return new AgentResponse(sessionId, phase, AgentStatus.SUCCESS,
                summary, output, null, null, Instant.now());
    }

    public static AgentResponse complete(String sessionId, DesignDocument doc) {
        return new AgentResponse(sessionId, AgentPhase.FULL, AgentStatus.SUCCESS,
                "Design pipeline completed", null, doc, null, Instant.now());
    }

    public static AgentResponse failed(String sessionId, AgentPhase phase, String error) {
        return new AgentResponse(sessionId, phase, AgentStatus.FAILED,
                null, null, null, error, Instant.now());
    }
}
