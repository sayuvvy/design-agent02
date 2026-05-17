package com.agent.exception;

import com.agent.model.AgentPhase;
import com.agent.model.AgentRequest;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

@Component
public class AgentRequestValidator {

    private static final Set<AgentPhase> REPO_REQUIRED = EnumSet.of(
            AgentPhase.ANALYZE, AgentPhase.CROSS_REF,
            AgentPhase.DESIGN, AgentPhase.PUBLISH, AgentPhase.FULL
    );

    public void validate(AgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must not be null");
        }

        AgentPhase phase = request.phase() != null ? request.phase() : AgentPhase.FULL;

        // Validate repoPath for phases that need code access
        if (REPO_REQUIRED.contains(phase)) {
            if (isBlank(request.repoPath())) {
                throw new IllegalArgumentException(
                        "repoPath is required for phase: " + phase);
            }
            Path root = Path.of(request.repoPath());
            if (!Files.exists(root)) {
                throw new IllegalArgumentException(
                        "repoPath does not exist: " + request.repoPath());
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException(
                        "repoPath is not a directory: " + request.repoPath());
            }
        }

        // For FETCH_JIRA: must have either jiraProjectKey (Jira MCP) or issues (user text)
        if (phase == AgentPhase.FETCH_JIRA) {
            if (!request.hasJiraConfig() && !request.hasUserIssues()) {
                throw new IllegalArgumentException(
                        "Either jiraProjectKey (for Jira MCP) or issues (user-provided text) " +
                        "must be provided for phase: " + phase);
            }
        }

        // For FULL pipeline: must have either jiraProjectKey or issues
        if (phase == AgentPhase.FULL) {
            if (!request.hasJiraConfig() && !request.hasUserIssues()) {
                throw new IllegalArgumentException(
                        "Either jiraProjectKey (for Jira MCP) or issues (user-provided text) " +
                        "must be provided to run the full pipeline.");
            }
        }

        // For PUBLISH: must have jiraProjectKey OR outputDir to publish results
        if (phase == AgentPhase.PUBLISH) {
            if (!request.hasJiraConfig() && isBlank(request.outputDir())) {
                throw new IllegalArgumentException(
                        "Either jiraProjectKey (for Jira MCP) or outputDir (for local file output) " +
                        "is required for PUBLISH phase");
            }
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
