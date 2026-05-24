package com.agent.orchestrator;

import com.agent.config.ScanLimitsConfig;
import com.agent.model.AgentRequest;
import org.springframework.stereotype.Component;

@Component
public class DesignPrompts {

    private final ScanLimitsConfig scanLimits;

    public DesignPrompts(ScanLimitsConfig scanLimits) {
        this.scanLimits = scanLimits;
    }

    // -------------------------------------------------------------------------
    // FETCH_JIRA
    // -------------------------------------------------------------------------

    public String fetchJiraSystem(AgentRequest req) {
        if (req.hasJiraConfig()) {
            // Jira MCP mode — fetch from Jira
            return """
                    You are a senior business analyst reading a Jira Cloud project.
                    Fetch and summarise all relevant items for a design exercise.

                    Rules:
                    - Fetch all Epics in project %s — capture key, summary, description, status.
                    - Fetch all Stories linked to those Epics — capture key, summary, acceptance criteria.
                    - Fetch all open Bugs — capture key, summary, priority, description.
                    - If a sprint is specified, scope to that sprint only.
                    - Do NOT modify any Jira items.
                    - Output a structured summary with sections: Epics, Stories, Bugs.
                    %s
                    """.formatted(req.jiraProjectKey(), sprintScope(req), extraContext(req));
        } else {
            // User-provided issues mode — parse and structure the input
            return """
                    You are a senior business analyst structuring user-provided requirements.
                    The user has provided issue details directly (not from Jira).

                    Rules:
                    - Parse and structure the provided issues into Epics, Stories, and Bugs.
                    - The input may be plain text or JSON — handle both formats.
                    - If plain text, infer the type (Epic/Story/Bug) from the content.
                    - Output a structured summary with sections: Epics, Stories, Bugs.
                    - Assign short reference keys if none are provided (e.g. EP-1, ST-1, BUG-1).
                    - Do not invent requirements — only structure what was provided.
                    - CRITICAL: Preserve ALL specific URLs, API endpoints, field names, class names,
                      and technical specifications VERBATIM. Do NOT abstract or generalise them.
                      e.g. "https://fakestoreapi.com/products/{id}" must appear exactly as given.
                    %s
                    """.formatted(extraContext(req));
        }
    }

    public String fetchJiraUser(AgentRequest req) {
        if (req.hasJiraConfig()) {
            String base = "Fetch all epics, stories and bugs from Jira project: " + req.jiraProjectKey();
            if (req.jiraSprintId() != null && !req.jiraSprintId().isBlank()) {
                base += "\nScope to sprint: " + req.jiraSprintId();
            }
            if (req.jiraIssueKeys() != null && !req.jiraIssueKeys().isEmpty()) {
                base += "\nFocus on these specific issues: " + String.join(", ", req.jiraIssueKeys());
            }
            return base;
        } else {
            return """
                    Structure the following user-provided issue details into Epics, Stories and Bugs:

                    %s
                    """.formatted(req.issues());
        }
    }

    // -------------------------------------------------------------------------
    // ANALYZE
    // -------------------------------------------------------------------------

    public String analyzeSystem(AgentRequest req) {
        // Resolve limits from external config based on complexity
        ScanLimitsConfig.Tier tier = resolveTier(req);
        int maxFiles = tier.getMaxFiles();
        int maxLines = tier.getMaxLines();

        String priority = req.complexity() != null ? switch(req.complexity()) {
            case SIMPLE  -> "Read all source files — no skip rules needed.";
            case MEDIUM  -> """
                    Priority order (stop once you hit the file limit):
                    1. pom.xml or build.gradle
                    2. *Application.java  (entry point)
                    3. config/*Config.java  (job/app configuration — pick the largest ones)
                    4. processor/ or service/  (core business logic — largest files first)
                    5. tasklet/ or reader/ or writer/  (supporting steps)
                    6. listener/, mapping/, interceptor/ — only if file limit not yet reached
                    STOP — do not read test files or target/.""";
            case COMPLEX -> """
                    Priority order (stop once you hit the file limit):
                    1. pom.xml or build.gradle
                    2. *Application.java  (entry point)
                    3. The largest config files
                    4. The largest processor/service files
                    5. Other source files — only if file limit not yet reached
                    STOP — do not read test files or target/.""";
        } : "Read pom.xml, Application.java, then the largest config and service files.";

        return """
                You are a senior software architect analysing a Java/Spring Boot codebase.

                CODEBASE COMPLEXITY : %s
                MAX FILES TO READ   : %d   ← hard limit, do NOT exceed this
                MAX LINES PER FILE  : %d   ← read only the first N lines of each file

                USER REQUIREMENT TO ANALYSE FOR:
                >>> %s <<<

                MANDATORY SEQUENCE — follow exactly, no deviations:
                STEP 1 → Call GlobTool with pattern "src/main/java/**/*.java" to list source files.
                STEP 2 → Select files using the priority list below. Stop at the file limit.
                STEP 3 → Read each selected file (first %d lines only).
                STEP 4 → Write your analysis. STOP. Do not make any further tool calls.

                %s

                FORBIDDEN:
                - Reading files in target/, .git/, src/test/
                - Reading *.log, *.class, *.jar files
                - Re-reading a file you already read
                - Making tool calls after STEP 4
                - Exceeding the file limit under any circumstances

                Output sections (concise, no padding):
                  1. Project Structure
                  2. Architectural Patterns
                  3. Key Components (highlight any relevant to the USER REQUIREMENT)
                  4. Tech Debt / Observations

                Repository root: %s
                %s
                """.formatted(
                    req.complexity() != null ? req.complexity().label() : "MEDIUM",
                    maxFiles, maxLines,
                    req.issues() != null ? req.issues() : "General architecture review",
                    maxLines,
                    priority,
                    req.repoPath(),
                    extraContext(req));
    }

    public String analyzeUser(AgentRequest req) {
        int maxFiles = resolveTier(req).getMaxFiles();

        return """
                Analyse the codebase at: %s

                User focus: %s

                Reminder: read at most %d files total, then write the analysis.
                Do NOT exceed the file limit. Do NOT read test files or target/.
                """.formatted(
                    req.repoPath(),
                    req.issues() != null ? req.issues() : "General architecture review",
                    maxFiles);
    }

    // -------------------------------------------------------------------------
    // ANALYZE — GitHub MCP path (reads code via GitHub API, no local filesystem)
    // -------------------------------------------------------------------------

    public String githubAnalyzeSystem(AgentRequest req) {
        String owner = extractGithubOwnerRepo(req.repoUrl());
        String userFocus = req.issues() != null && !req.issues().isBlank()
                ? req.issues()
                : "general architecture, patterns, and tech debt";

        return """
                You are a senior software architect analysing a GitHub repository via the GitHub MCP tools.

                REPOSITORY      : %s
                ANALYSIS MODE   : GITHUB MCP (read files directly from GitHub API)

                USER REQUIREMENT TO ANALYSE FOR:
                >>> %s <<<

                MANDATORY SEQUENCE — follow exactly:
                STEP 1 → Call list_directory_tree for the repo root to understand the project layout.
                STEP 2 → Call get_file_contents for pom.xml or build.gradle to identify the tech stack.
                STEP 3 → Call get_file_contents for the main Application.java entry point.
                STEP 4 → Call get_file_contents or search_code for the core service/processor files
                         most relevant to the USER REQUIREMENT above.
                STEP 5 → Write your analysis. STOP. No more tool calls.

                TOOL USAGE:
                - list_directory_tree: owner="%s" — lists directory contents
                - get_file_contents:   owner="%s", path="src/main/java/..." — reads a file
                - search_code:         query="<term> repo:%s" — searches across the repo

                FORBIDDEN:
                - Reading test files (src/test/)
                - Reading binary files (.class, .jar, .png)
                - Making more than 8 total tool calls

                Output sections:
                  1. Project Structure
                  2. Architectural Patterns
                  3. Key Components (highlight those relevant to the USER REQUIREMENT)
                  4. Tech Debt / Observations
                %s
                """.formatted(req.repoUrl(), userFocus, owner, owner, owner, extraContext(req));
    }

    public String githubAnalyzeUser(AgentRequest req) {
        return """
                Analyse the GitHub repository: %s

                User focus: %s

                Use the GitHub MCP tools to explore the repository structure and read key files.
                """.formatted(
                req.repoUrl(),
                req.issues() != null ? req.issues() : "General architecture review");
    }

    private static String extractGithubOwnerRepo(String repoUrl) {
        if (repoUrl == null) return "unknown/repo";
        // strips trailing .git and leading https://github.com/
        return repoUrl.replaceFirst("https?://github\\.com/", "").replaceAll("\\.git$", "");
    }

    // -------------------------------------------------------------------------
    // ANALYZE — RAG path (semantic search replaces sequential file reads)
    // -------------------------------------------------------------------------

    /**
     * System prompt for the RAG-based analyze path.
     * The agent calls searchCodebase() 2-4 times instead of reading N files,
     * which eliminates sequential tool-call context growth entirely.
     */
    public String ragAnalyzeSystem(AgentRequest req) {
        String userFocus = req.issues() != null && !req.issues().isBlank()
                ? req.issues()
                : "general architecture, patterns, and tech debt";

        return """
                You are a senior software architect analysing a Java/Spring Boot codebase.

                CODEBASE COMPLEXITY : %s
                ANALYSIS MODE       : SEMANTIC SEARCH (RAG) — codebase is pre-indexed

                USER REQUIREMENT TO ANALYSE FOR:
                >>> %s <<<

                MANDATORY SEQUENCE — follow exactly, no deviations:
                STEP 1 → Call searchCodebase("application entry point configuration") to retrieve
                         Application.java, config classes, and build files.
                STEP 2 → Call searchCodebase("core business logic processor service job") to retrieve
                         the main processing and service classes.
                STEP 3 → Call searchCodebase with a query derived from the USER REQUIREMENT above
                         (e.g. if the requirement mentions an external API call, search for
                         "HTTP client RestTemplate WebClient external API integration").
                STEP 4 → If a critical architectural gap remains, make ONE more searchCodebase call.
                         Maximum 4 searchCodebase calls total.
                STEP 5 → Write your analysis using the retrieved code. STOP. No more tool calls.

                FORBIDDEN:
                - Using GlobTool, ReadFile, or FileSystemTools (codebase is already indexed)
                - Making more than 4 searchCodebase calls
                - Making any tool calls after STEP 5

                Output sections (concise, no padding):
                  1. Project Structure
                  2. Architectural Patterns
                  3. Key Components (highlight any relevant to the USER REQUIREMENT)
                  4. Tech Debt / Observations

                Repository root: %s
                %s
                """.formatted(
                    req.complexity() != null ? req.complexity().label() : "MEDIUM",
                    userFocus,
                    req.repoPath(),
                    extraContext(req));
    }

    /**
     * User prompt for the RAG-based analyze path.
     */
    public String ragAnalyzeUser(AgentRequest req) {
        return """
                Analyse the Java codebase at: %s

                User focus: %s

                Use searchCodebase() to retrieve relevant code (2-4 calls max), then write the analysis.
                Do NOT use file reading tools — the codebase is pre-indexed for semantic search.
                """.formatted(
                    req.repoPath(),
                    req.issues() != null ? req.issues() : "General architecture review");
    }

    // -------------------------------------------------------------------------
    // CROSS_REF
    // -------------------------------------------------------------------------

    public String crossRefSystem(AgentRequest req) {
        // Adaptive strategy based on codebase complexity
        String strategy = req.complexity() != null ? switch(req.complexity()) {
            case SIMPLE -> """
                    TOKEN BUDGET: GENEROUS
                    - You already have the full codebase analysis — do NOT re-read files
                    - Read additional files as needed if analysis gaps detected
                    """;
            case MEDIUM -> """
                    TOKEN BUDGET: MODERATE
                    - You already have the codebase analysis — do NOT re-read all files
                    - Use GrepTool to search for specific classes/methods referenced in requirements
                    - Read at most 5 additional files (only files not yet analysed)
                    """;
            case COMPLEX -> """
                    TOKEN BUDGET: STRICT
                    - You already have the codebase analysis — NEVER re-read all files
                    - Use GrepTool to search for ONLY specific classes/methods referenced
                    - Read at most 3 additional files (only if absolutely critical gaps)
                    """;
        } : """
                TOKEN BUDGET: MODERATE (default)
                - Read at most 5 additional files
                """;

        return """
                You are a senior software architect cross-referencing requirements with a codebase.
                The requirements summary and codebase analysis are provided explicitly in the user message.

                CODEBASE COMPLEXITY: %s

                STRICT TOKEN BUDGET RULES:
                %s
                - Keep output concise — aim for 1000 words max.

                Rules:
                - For each Epic/requirement, identify affected services/modules.
                - For each Story, identify which classes/files need to change.
                - For each Bug, identify the likely root cause location.
                - Flag gaps — capabilities required that don't exist yet.
                - Do NOT modify any files.
                - Output sections: Epic→Codebase mapping, Story→File mapping,
                  Bug→Root cause mapping, Gaps.

                Repository root: %s
                %s
                """.formatted(
                    req.complexity() != null ? req.complexity().label() : "UNKNOWN",
                    strategy,
                    req.repoPath(),
                    extraContext(req));
    }

    public String crossRefUser(AgentRequest req) {
        // Allow up to 25% of the tier's file limit as extra reads
        int maxExtra = Math.max(2, resolveTier(req).getMaxFiles() / 4);

        return """
                Cross-reference the requirements with the codebase at: %s

                PRIMARY NEW REQUIREMENT (must be explicitly mapped to code locations):
                >>> %s <<<

                The full REQUIREMENTS and CODEBASE ANALYSIS sections are appended below.
                Use GrepTool to find specific classes if needed. Read at most %d additional files.
                Do NOT read test files or target/.
                """.formatted(
                    req.repoPath(),
                    req.issues() != null ? req.issues() : "No specific issues",
                    maxExtra);
    }

    // -------------------------------------------------------------------------
    // DESIGN
    // -------------------------------------------------------------------------

    public String designSystem(AgentRequest req) {
        String mandatoryReq = (req.issues() != null && !req.issues().isBlank())
                ? "\nMANDATORY USER REQUIREMENT — you MUST address this explicitly in the document:\n"
                  + req.issues() + "\n"
                : "";

        return """
                You are a principal software architect producing a comprehensive design document.
                The requirements summary, codebase analysis, and cross-reference mapping
                are provided explicitly in the user message — do NOT ask for them.
                %s
                Rules:
                - Do NOT write or modify any files at this stage.
                - Produce a complete design document in Markdown format.
                - Address ALL requirements (epics, stories, bugs).
                - The MANDATORY USER REQUIREMENT above takes highest priority — it MUST appear
                  explicitly in the design with a dedicated section or sub-section.
                - Respect existing architecture where appropriate.

                The document must include these sections:
                - Executive Summary
                - Current State Assessment
                - Proposed Architecture
                - Component Design
                - Requirements Implementation Plan  ← address MANDATORY USER REQUIREMENT here
                - Bug Fixes
                - Migration Strategy
                - Risks and Mitigations
                - Open Questions

                Project: %s
                Repository: %s
                %s
                """.formatted(mandatoryReq, req.projectLabel(), req.repoPath(), extraContext(req));
    }

    public String designUser(AgentRequest req) {
        return """
                Produce the complete design document for: %s

                The full REQUIREMENTS, CODEBASE ANALYSIS, and CROSS-REFERENCE MAPPING sections
                are appended below — use them as supporting context.

                MANDATORY: The following user requirement MUST be explicitly addressed in the design.
                Do NOT omit, abstract, or generalise it — include all specific details (URLs, field names, etc.):
                >>> %s <<<
                """.formatted(req.projectLabel(), req.issues() != null ? req.issues() : "No specific issues");
    }

    // -------------------------------------------------------------------------
    // PUBLISH
    // -------------------------------------------------------------------------

    public String publishSystem(AgentRequest req) {
        boolean hasJira = req.hasJiraConfig();
        String jiraInstructions = hasJira ? """
                - For each Epic: post a comment on the Jira ticket summarising the design approach (max 200 words).
                - For each Bug: post a comment with the proposed fix approach (max 100 words).
                - Do NOT change ticket status — only add comments.
                """ : """
                - Jira is not configured — skip all Jira comment steps.
                - Only write the design document to disk.
                """;

        return """
                You are a senior engineer publishing a design document to disk.
                The complete design document will be provided directly in the user message.

                Rules:
                - Write the design document EXACTLY as provided to: %s/design.md
                  Create the output directory if it does not exist.
                - Do NOT summarise or shorten the document — write it in full.
                - Do NOT ask clarifying questions.
                %s
                - Output a one-line confirmation of what was written.

                Output directory: %s
                %s
                """.formatted(req.resolvedOutputDir(), jiraInstructions,
                              req.resolvedOutputDir(), extraContext(req));
    }

    /**
     * @param designContent the full design document to publish — injected explicitly
     *                      so the LLM never has to rely on "conversation history"
     */
    public String publishUser(AgentRequest req, String designContent) {
        String docBlock = (designContent != null && !designContent.isBlank())
                ? "\n\n=== DESIGN DOCUMENT TO PUBLISH ===\n" + designContent + "\n=== END OF DESIGN DOCUMENT ==="
                : "\n\n[WARNING: No design document found. Run the DESIGN phase first.]";

        if (req.hasJiraConfig()) {
            return """
                    Publish the design document to %s/design.md and post design comments
                    on all relevant Jira tickets in project %s.
                    %s
                    """.formatted(req.resolvedOutputDir(), req.jiraProjectKey(), docBlock);
        } else {
            return """
                    Write the following design document to: %s/design.md
                    %s
                    """.formatted(req.resolvedOutputDir(), docBlock);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns the ScanLimitsConfig tier for the request's complexity level. */
    private ScanLimitsConfig.Tier resolveTier(AgentRequest req) {
        if (req.complexity() == null) return scanLimits.getMedium();
        return switch (req.complexity()) {
            case SIMPLE  -> scanLimits.getSimple();
            case MEDIUM  -> scanLimits.getMedium();
            case COMPLEX -> scanLimits.getComplex();
        };
    }

    private String sprintScope(AgentRequest req) {
        if (req.jiraSprintId() != null && !req.jiraSprintId().isBlank()) {
            return "Sprint scope: " + req.jiraSprintId();
        }
        return "Scope: all open items in the project.";
    }

    private String extraContext(AgentRequest req) {
        if (req.context() == null || req.context().isBlank()) return "";
        return "\nAdditional context:\n" + req.context();
    }
}
