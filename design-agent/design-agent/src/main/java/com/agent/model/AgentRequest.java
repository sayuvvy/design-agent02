package com.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRequest(

        String sessionId,
        AgentPhase phase,

        /**
         * Absolute path to the locally checked-out codebase.
         * Mutually exclusive with repoUrl — provide one or the other.
         */
        String repoPath,

        /**
         * GitHub repository URL for hosted deployments (e.g. Render).
         * When provided, the agent reads code via the GitHub MCP server
         * instead of the local filesystem.
         * Example: "https://github.com/owner/myapp"
         */
        String repoUrl,

        /**
         * GitHub Personal Access Token — required for private repos when repoUrl is set.
         * Leave null for public repos.
         */
        String githubToken,

        /**
         * Jira Cloud project key — optional.
         * When provided and MCP is enabled, the agent fetches issues from Jira.
         * When null or MCP is disabled, the agent uses the `issues` field instead.
         */
        String jiraProjectKey,

        /**
         * Optional Jira sprint ID to scope the fetch.
         */
        String jiraSprintId,

        /**
         * Optional specific Jira issue keys to focus on.
         */
        List<String> jiraIssueKeys,

        /**
         * User-provided issue details — used when Jira is not configured.
         *
         * Accepts either plain text or structured JSON. Examples:
         *
         * Plain text:
         *   "Epic: Add pagination to user list.
         *    Story: As a user I want to see 20 results per page.
         *    Bug: NPE in UserService.findById when id is null."
         *
         * Structured JSON string:
         *   "{
         *     "epics": [{"key": "EP-1", "summary": "Add pagination"}],
         *     "stories": [{"key": "ST-1", "summary": "20 results per page", "epic": "EP-1"}],
         *     "bugs": [{"key": "BUG-1", "summary": "NPE in UserService", "priority": "HIGH"}]
         *   }"
         */
        String issues,

        /**
         * Output directory for the generated design document.
         * Defaults to {repoPath}/docs/design/
         */
        String outputDir,

        /**
         * Extra context injected into every system prompt.
         * Use for team conventions, architectural principles, constraints.
         */
        String context,

        /**
         * Codebase complexity classification (SIMPLE, MEDIUM, COMPLEX).
         * Automatically detected or can be provided by client.
         * Used to adapt analysis strategy, token budgets, and file selection.
         */
        CodebaseComplexity complexity
) {
    /** True when a GitHub URL is provided — use GitHub MCP for code reading. */
    public boolean hasRepoUrl() {
        return repoUrl != null && !repoUrl.isBlank();
    }

    /** True when a local path is provided — use filesystem tools for code reading. */
    public boolean hasLocalRepo() {
        return repoPath != null && !repoPath.isBlank();
    }

    /**
     * Returns the effective repo identifier for cache keys and memory lookups.
     * Prefers repoUrl when set, falls back to repoPath.
     */
    public String resolvedRepoKey() {
        if (hasRepoUrl()) return repoUrl;
        return repoPath;
    }

    /** True when Jira MCP should be used for fetching issues. */
    public boolean hasJiraConfig() {
        return jiraProjectKey != null && !jiraProjectKey.isBlank();
    }

    /** True when user has provided issue details directly. */
    public boolean hasUserIssues() {
        return issues != null && !issues.isBlank();
    }

    /** Resolve the effective output directory. */
    public String resolvedOutputDir() {
        if (outputDir != null && !outputDir.isBlank()) return outputDir;
        if (hasLocalRepo()) return repoPath + "/docs/design";
        return "/tmp/design";
    }

    /** Display name for the project — Jira key if available, else generic label. */
    public String projectLabel() {
        return hasJiraConfig() ? jiraProjectKey : "Project";
    }
}
