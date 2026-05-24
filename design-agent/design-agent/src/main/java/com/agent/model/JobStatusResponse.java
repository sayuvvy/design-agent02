package com.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobStatusResponse(

        /** Unique job identifier — pass to GET /api/design/status/{jobId} */
        String jobId,

        /** IN_PROGRESS while running; SUCCESS or FAILED when done. */
        AgentStatus status,

        /** Null while IN_PROGRESS; populated when the pipeline completes or fails. */
        AgentResponse result
) {
    public static JobStatusResponse accepted(String jobId) {
        return new JobStatusResponse(jobId, AgentStatus.IN_PROGRESS, null);
    }

    public static JobStatusResponse done(String jobId, AgentResponse result) {
        return new JobStatusResponse(jobId, result.status(), result);
    }

    public static JobStatusResponse notFound(String jobId) {
        return new JobStatusResponse(jobId, null, null);
    }
}
