package com.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Captures the full output of the design pipeline:
 *  - the generated design document (written to disk)
 *  - the list of Jira tickets that were updated
 *  - a summary of findings per phase
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesignDocument(

        String sessionId,
        String jiraProjectKey,
        String repoPath,

        /** Absolute path to the generated design Markdown file. */
        String designDocPath,

        /** Full content of the design document. */
        String designDocContent,

        /** Summary from the FETCH_JIRA phase. */
        String jiraSummary,

        /** Summary from the ANALYZE phase. */
        String codebaseSummary,

        /** Summary from the CROSS_REF phase. */
        String crossRefSummary,

        /** Jira issue keys that were commented on / updated. */
        List<String> updatedJiraTickets,

        /** Any warnings or notes the agent produced. */
        String notes,

        Instant completedAt
) {
    public static DesignDocument of(String sessionId, String jiraProjectKey,
                                    String repoPath, String designDocPath,
                                    String designDocContent, String jiraSummary,
                                    String codebaseSummary, String crossRefSummary,
                                    List<String> updatedTickets, String notes) {
        return new DesignDocument(sessionId, jiraProjectKey, repoPath,
                designDocPath, designDocContent, jiraSummary, codebaseSummary,
                crossRefSummary, updatedTickets, notes, Instant.now());
    }
}
