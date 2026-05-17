package com.agent.orchestrator;

import com.agent.cache.AnalysisCacheService;
import com.agent.memory.LongTermMemoryEntry;
import com.agent.memory.LongTermMemoryService;
import com.agent.memory.SemanticMemoryService;
import com.agent.memory.SessionMemoryService;
import com.agent.model.AgentPhase;
import com.agent.model.AgentRequest;
import com.agent.model.AgentResponse;
import com.agent.model.CodebaseComplexity;
import com.agent.model.DesignDocument;
import com.agent.service.CodebaseComplexityAnalyzer;
import com.agent.service.CodebaseIndexingService;
import com.agent.telemetry.TokenUsageReport;
import com.agent.telemetry.TokenUsageTracker;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Central orchestrator for the design pipeline.
 *
 * Memory architecture (3-tier):
 *  - Short-term: per-session, shares context across phases within a pipeline run
 *  - Long-term:  per-project, persists analysis history and token usage in SQLite
 *  - Semantic:   cross-project vector store — used BOTH for phase-output retrieval AND
 *                codebase RAG indexing (eliminates O(n²) sequential file-read overhead)
 *
 * Telemetry:
 *  - Every LLM call is tracked: token counts, cost, duration, tool calls
 *  - Persisted to SQLite + exported as Micrometer metrics (Prometheus-ready)
 *  - Daily budget alerts
 *
 * Caching:
 *  - Responses cached by repo path + phase + issues hash
 *  - Invalidated when source files change
 */
@Service
public class DesignOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DesignOrchestrator.class);

    private final ChatClient chatClient;
    private final DesignPrompts prompts;
    private final DesignToolsFactory toolsFactory;
    private final AnalysisCacheService cacheService;
    private final SessionMemoryService sessionMemory;
    private final LongTermMemoryService longTermMemory;
    private final SemanticMemoryService semanticMemory;
    private final TokenUsageTracker tokenTracker;
    private final CodebaseComplexityAnalyzer complexityAnalyzer;
    private final CodebaseIndexingService indexingService;

    // Per-phase token limits (injected from AgentConfig beans)
    private final AnthropicChatOptions analyzeOptions;
    private final AnthropicChatOptions crossRefOptions;
    private final AnthropicChatOptions designOptions;
    private final AnthropicChatOptions publishOptions;
    private final AnthropicChatOptions fetchJiraOptions;

    @Value("${spring.ai.anthropic.chat.options.model:unknown}")
    private String modelName;

    private String requirementsSummary;
    private String codebaseSummary;
    private String crossRefSummary;
    private String designContent;
    private List<String> updatedTickets;

    public DesignOrchestrator(ChatClient chatClient,
                              DesignPrompts prompts,
                              DesignToolsFactory toolsFactory,
                              AnalysisCacheService cacheService,
                              SessionMemoryService sessionMemory,
                              LongTermMemoryService longTermMemory,
                              SemanticMemoryService semanticMemory,
                              TokenUsageTracker tokenTracker,
                              CodebaseComplexityAnalyzer complexityAnalyzer,
                              CodebaseIndexingService indexingService,
                              @Qualifier("analyzeOptions") AnthropicChatOptions analyzeOptions,
                              @Qualifier("crossRefOptions") AnthropicChatOptions crossRefOptions,
                              @Qualifier("designOptions") AnthropicChatOptions designOptions,
                              @Qualifier("publishOptions") AnthropicChatOptions publishOptions,
                              @Qualifier("fetchJiraOptions") AnthropicChatOptions fetchJiraOptions) {
        this.chatClient = chatClient;
        this.prompts = prompts;
        this.toolsFactory = toolsFactory;
        this.cacheService = cacheService;
        this.sessionMemory = sessionMemory;
        this.longTermMemory = longTermMemory;
        this.semanticMemory = semanticMemory;
        this.tokenTracker = tokenTracker;
        this.complexityAnalyzer = complexityAnalyzer;
        this.indexingService = indexingService;
        this.analyzeOptions = analyzeOptions;
        this.crossRefOptions = crossRefOptions;
        this.designOptions = designOptions;
        this.publishOptions = publishOptions;
        this.fetchJiraOptions = fetchJiraOptions;
    }

    @Observed(name = "design.execute", contextualName = "design-orchestrator")
    public AgentResponse execute(AgentRequest request) {
        String sessionId = resolveSessionId(request);
        AgentPhase phase = request.phase() != null ? request.phase() : AgentPhase.FULL;

        // Detect complexity if not already provided
        CodebaseComplexity complexity = request.complexity();
        if (complexity == null && request.repoPath() != null) {
            complexity = complexityAnalyzer.assessComplexity(request.repoPath());
            // Create new request with complexity detected
            request = new AgentRequest(
                request.sessionId(), request.phase(), request.repoPath(),
                request.jiraProjectKey(), request.jiraSprintId(), request.jiraIssueKeys(),
                request.issues(), request.outputDir(), request.context(), complexity
            );
        }

        log.info("Design agent session={} phase={} complexity={} jira={} userIssues={}",
                sessionId, phase, complexity != null ? complexity.label() : "UNKNOWN",
                request.hasJiraConfig(),
                request.hasUserIssues());

        try {
            return switch (phase) {
                case FETCH_JIRA -> runFetchRequirements(request, sessionId);
                case ANALYZE    -> runAnalyze(request, sessionId);
                case CROSS_REF  -> runCrossRef(request, sessionId);
                case DESIGN     -> runDesign(request, sessionId);
                case PUBLISH    -> runPublish(request, sessionId);
                case FULL       -> runFull(request, sessionId);
            };
        } catch (Exception ex) {
            log.error("Design agent failed session={} phase={}", sessionId, phase, ex);
            return AgentResponse.failed(sessionId, phase, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Phase runners
    // -------------------------------------------------------------------------

    private AgentResponse runFetchRequirements(AgentRequest request, String sessionId) {
        if (request.hasJiraConfig() && toolsFactory.isJiraAvailable()) {
            log.info("[FETCH_JIRA] session={} fetching from Jira project={}",
                    sessionId, request.jiraProjectKey());
        } else {
            log.info("[FETCH_JIRA] session={} using user-provided issues", sessionId);
        }

        sessionMemory.getOrCreateSession(sessionId,
                request.repoPath() != null ? request.repoPath() : "no-repo");

        // ── Long-term: enrich system prompt with prior requirements history ──
        String projectId = request.repoPath() != null
                ? longTermMemory.computeProjectId(request.repoPath()) : null;
        String historicalCtx = request.repoPath() != null
                ? longTermMemory.buildHistoricalContext(request.repoPath()) : "";

        // ── Semantic: retrieve prior requirement patterns for this project ──
        String semanticCtx = (projectId != null)
                ? semanticMemory.retrieveProjectKnowledge(projectId,
                    "requirements epics stories bugs " + (request.issues() != null ? request.issues() : ""))
                : "";

        long startMs = System.currentTimeMillis();
        ChatResponse chatResponse = chatClient.prompt()
                .system(prompts.fetchJiraSystem(request) + historicalCtx + semanticCtx)
                .user(prompts.fetchJiraUser(request))
                .options(fetchJiraOptions)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(toolsFactory.jiraReadTools())
                .call()
                .chatResponse();
        long durationMs = System.currentTimeMillis() - startMs;

        String output = chatResponse.getResult().getOutput().getText();
        this.requirementsSummary = output;

        TokenUsageReport report = tokenTracker.track(
                sessionId, "FETCH_JIRA", modelName, chatResponse, durationMs, 0);

        // ── Short-term: store for downstream phases in this session ──
        sessionMemory.storePhaseOutput(sessionId, "FETCH_JIRA", output);

        // ── Long-term: persist requirements so future sessions benefit ──
        if (projectId != null) {
            longTermMemory.saveEntry(LongTermMemoryEntry.builder()
                    .id(sessionId + "-fetchjira")
                    .projectId(projectId)
                    .repoPath(request.repoPath() != null ? request.repoPath() : "no-repo")
                    .phase("FETCH_JIRA")
                    .summary(truncate(output, 500))
                    .keyFindings(truncate(output, 2000))
                    .inputTokens(report.inputTokens()).outputTokens(report.outputTokens())
                    .costUsd(report.totalCostUsd())
                    .build());

            // ── Semantic: embed requirements for cross-project retrieval ──
            semanticMemory.storeAnalysisKnowledge(projectId,
                    request.repoPath() != null ? request.repoPath() : "no-repo",
                    "FETCH_JIRA", output);
        }

        return AgentResponse.success(sessionId, AgentPhase.FETCH_JIRA,
                "Requirements fetched", output);
    }

    private AgentResponse runAnalyze(AgentRequest request, String sessionId) {
        log.info("[ANALYZE] session={} repo={}", sessionId, request.repoPath());

        // Check cache first
        AgentResponse cached = cacheService.get(request.repoPath(), AgentPhase.ANALYZE, request.issues());
        if (cached != null) {
            this.codebaseSummary = cached.output();
            return cached;
        }

        // Ensure short-term memory session exists
        sessionMemory.getOrCreateSession(sessionId, request.repoPath());

        // ── RAG path: index once, search instead of sequential file reads ──────
        boolean useRag = indexingService.isAvailable() && request.repoPath() != null;
        if (useRag) {
            if (!indexingService.isIndexed(request.repoPath())) {
                log.info("[ANALYZE] RAG enabled — indexing codebase before analysis");
                indexingService.indexCodebase(request.repoPath(), request.complexity());
            } else {
                log.info("[ANALYZE] RAG enabled — codebase already indexed, using vector search");
            }
        } else {
            log.info("[ANALYZE] RAG not available — using sequential file reads (scan limits apply)");
        }

        // Enrich prompt with historical context (phase-output memories, NOT code chunks)
        String historicalCtx = longTermMemory.buildHistoricalContext(request.repoPath());
        // Semantic context scoped to THIS project's prior analysis outputs
        String projectId = longTermMemory.computeProjectId(request.repoPath());
        String semanticCtx = semanticMemory.retrieveProjectKnowledge(projectId,
                "codebase architecture analysis " + (request.issues() != null ? request.issues() : ""));

        final String systemPrompt;
        final String userPrompt;
        final Object[] tools;
        final String analysisMode;

        if (useRag) {
            CodebaseSearchTool searchTool = new CodebaseSearchTool(semanticMemory, projectId);
            systemPrompt = prompts.ragAnalyzeSystem(request) + historicalCtx + semanticCtx;
            userPrompt   = prompts.ragAnalyzeUser(request);
            tools        = toolsFactory.ragAnalyzeTools(searchTool);
            analysisMode = "RAG";
        } else {
            String previousCtx = sessionMemory.getPreviousContext(sessionId);
            systemPrompt = prompts.analyzeSystem(request) + historicalCtx + semanticCtx;
            userPrompt   = prompts.analyzeUser(request) + previousCtx;
            tools        = toolsFactory.codeReadTools();
            analysisMode = "SEQUENTIAL";
        }

        log.info("[ANALYZE] mode={} session={}", analysisMode, sessionId);

        long startMs = System.currentTimeMillis();
        ChatResponse chatResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(analyzeOptions)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(tools)
                .call()
                .chatResponse();
        long durationMs = System.currentTimeMillis() - startMs;

        String output = chatResponse.getResult().getOutput().getText();
        this.codebaseSummary = output;

        // Detect and warn if the LLM was cut off by the max-tokens limit
        warnIfTruncated(chatResponse, "ANALYZE", sessionId);

        // Track token usage
        TokenUsageReport report = tokenTracker.track(
                sessionId, "ANALYZE", modelName, chatResponse, durationMs, 0);

        // Store in short-term memory
        sessionMemory.storePhaseOutput(sessionId, "ANALYZE", output);

        // Store in long-term memory
        longTermMemory.saveEntry(LongTermMemoryEntry.builder()
                .id(sessionId + "-analyze")
                .projectId(projectId).repoPath(request.repoPath())
                .phase("ANALYZE").summary(truncate(output, 500))
                .keyFindings(truncate(output, 2000))
                .inputTokens(report.inputTokens()).outputTokens(report.outputTokens())
                .costUsd(report.totalCostUsd())
                .build());

        // Store phase output in semantic memory (distinct from codebase chunks)
        semanticMemory.storeAnalysisKnowledge(projectId, request.repoPath(), "ANALYZE", output);

        AgentResponse response = AgentResponse.success(sessionId, AgentPhase.ANALYZE,
                "Codebase analysed (" + analysisMode + ")", output);
        cacheService.put(request.repoPath(), AgentPhase.ANALYZE, request.issues(), response);
        return response;
    }

    private AgentResponse runCrossRef(AgentRequest request, String sessionId) {
        log.info("[CROSS_REF] session={}", sessionId);

        AgentResponse cached = cacheService.get(request.repoPath(), AgentPhase.CROSS_REF, request.issues());
        if (cached != null) {
            this.crossRefSummary = cached.output();
            return cached;
        }

        sessionMemory.getOrCreateSession(sessionId, request.repoPath());

        String projectId = longTermMemory.computeProjectId(request.repoPath());
        String historicalCtx = longTermMemory.buildHistoricalContext(request.repoPath());
        String semanticCtx = semanticMemory.retrieveProjectKnowledge(projectId,
                "cross reference requirements codebase " + (request.issues() != null ? request.issues() : ""));

        // ── Explicit full-content injection — NO 2000-char truncation ──────────
        // Short-term first; fall back to long-term SQLite if session expired/standalone
        String requirementsCtx = fullPhaseBlock("REQUIREMENTS",
                resolvePhaseContent(sessionId, "FETCH_JIRA", request.repoPath()));
        String analyzeCtx = fullPhaseBlock("CODEBASE ANALYSIS",
                resolvePhaseContent(sessionId, "ANALYZE", request.repoPath()));

        long startMs = System.currentTimeMillis();
        ChatResponse chatResponse = chatClient.prompt()
                .system(prompts.crossRefSystem(request) + historicalCtx + semanticCtx)
                .user(prompts.crossRefUser(request) + requirementsCtx + analyzeCtx)
                .options(crossRefOptions)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(toolsFactory.codeReadTools())
                .call()
                .chatResponse();
        long durationMs = System.currentTimeMillis() - startMs;

        String output = chatResponse.getResult().getOutput().getText();
        this.crossRefSummary = output;

        // Detect and warn if the LLM was cut off by the max-tokens limit
        warnIfTruncated(chatResponse, "CROSS_REF", sessionId);

        TokenUsageReport report = tokenTracker.track(
                sessionId, "CROSS_REF", modelName, chatResponse, durationMs, 0);

        sessionMemory.storePhaseOutput(sessionId, "CROSS_REF", output);

        longTermMemory.saveEntry(LongTermMemoryEntry.builder()
                .id(sessionId + "-crossref")
                .projectId(projectId).repoPath(request.repoPath())
                .phase("CROSS_REF").summary(truncate(output, 500))
                .keyFindings(truncate(output, 2000))
                .inputTokens(report.inputTokens()).outputTokens(report.outputTokens())
                .costUsd(report.totalCostUsd())
                .build());

        semanticMemory.storeAnalysisKnowledge(projectId, request.repoPath(), "CROSS_REF", output);

        AgentResponse response = AgentResponse.success(sessionId, AgentPhase.CROSS_REF,
                "Requirements cross-referenced with codebase", output);
        cacheService.put(request.repoPath(), AgentPhase.CROSS_REF, request.issues(), response);
        return response;
    }

    private AgentResponse runDesign(AgentRequest request, String sessionId) {
        log.info("[DESIGN] session={}", sessionId);

        AgentResponse cached = cacheService.get(request.repoPath(), AgentPhase.DESIGN, request.issues());
        if (cached != null) {
            this.designContent = cached.output();
            return cached;
        }

        sessionMemory.getOrCreateSession(sessionId, request.repoPath());

        String projectId = longTermMemory.computeProjectId(request.repoPath());
        String historicalCtx = longTermMemory.buildHistoricalContext(request.repoPath());
        String semanticCtx = semanticMemory.retrieveProjectKnowledge(projectId,
                "design architecture decisions patterns " + (request.issues() != null ? request.issues() : ""));

        // ── Explicit full-content injection — NO 2000-char truncation ──────────
        // Short-term first; fall back to long-term SQLite if session expired/standalone
        String requirementsCtx = fullPhaseBlock("REQUIREMENTS",
                resolvePhaseContent(sessionId, "FETCH_JIRA", request.repoPath()));
        String analyzeCtx     = fullPhaseBlock("CODEBASE ANALYSIS",
                resolvePhaseContent(sessionId, "ANALYZE", request.repoPath()));
        String crossRefCtx    = fullPhaseBlock("CROSS-REFERENCE MAPPING",
                resolvePhaseContent(sessionId, "CROSS_REF", request.repoPath()));

        long startMs = System.currentTimeMillis();
        ChatResponse chatResponse = chatClient.prompt()
                .system(prompts.designSystem(request) + historicalCtx + semanticCtx)
                .user(prompts.designUser(request) + requirementsCtx + analyzeCtx + crossRefCtx)
                .options(designOptions)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(toolsFactory.codeReadTools())
                .call()
                .chatResponse();
        long durationMs = System.currentTimeMillis() - startMs;

        String output = chatResponse.getResult().getOutput().getText();
        this.designContent = output;

        // Detect and warn if the LLM was cut off by the max-tokens limit
        warnIfTruncated(chatResponse, "DESIGN", sessionId);

        TokenUsageReport report = tokenTracker.track(
                sessionId, "DESIGN", modelName, chatResponse, durationMs, 0);

        sessionMemory.storePhaseOutput(sessionId, "DESIGN", output);

        longTermMemory.saveEntry(LongTermMemoryEntry.builder()
                .id(sessionId + "-design")
                .projectId(projectId).repoPath(request.repoPath())
                .phase("DESIGN").summary(truncate(output, 500))
                .keyFindings(truncate(output, 2000))
                .decisions(output)   // full design — used by PUBLISH fallback
                .inputTokens(report.inputTokens()).outputTokens(report.outputTokens())
                .costUsd(report.totalCostUsd())
                .build());

        semanticMemory.storeAnalysisKnowledge(projectId, request.repoPath(), "DESIGN", output);

        AgentResponse response = AgentResponse.success(sessionId, AgentPhase.DESIGN,
                "Design synthesized", output);
        cacheService.put(request.repoPath(), AgentPhase.DESIGN, request.issues(), response);
        return response;
    }

    private AgentResponse runPublish(AgentRequest request, String sessionId) {
        log.info("[PUBLISH] session={} outputDir={} jira={}",
                sessionId, request.resolvedOutputDir(), request.hasJiraConfig());

        // ── Resolve design content — 3-tier fallback ──────────────────────────
        // Tier 1: Full content from short-term session memory (same JVM session)
        String resolvedDesign = sessionMemory.getPhaseOutput(sessionId, "DESIGN");

        // Tier 2: Instance field — populated when FULL pipeline ran in this request
        if (resolvedDesign == null && designContent != null && !designContent.isBlank()) {
            resolvedDesign = designContent;
            log.info("[PUBLISH] Using in-memory designContent from FULL pipeline run");
        }

        // Tier 3: Long-term SQLite — for standalone /publish calls after app restart
        if ((resolvedDesign == null || resolvedDesign.isBlank()) && request.repoPath() != null) {
            resolvedDesign = longTermMemory.getLatestPhaseContent(request.repoPath(), "DESIGN");
            if (resolvedDesign != null) {
                log.info("[PUBLISH] Recovered design from long-term memory (SQLite) for repo={}",
                        request.repoPath());
            }
        }

        if (resolvedDesign == null || resolvedDesign.isBlank()) {
            log.warn("[PUBLISH] session={} — no design document found in session, instance, or long-term memory." +
                    " Run DESIGN phase first, or use FULL pipeline.", sessionId);
        } else {
            log.info("[PUBLISH] session={} — design content resolved ({} chars)", sessionId, resolvedDesign.length());
        }

        // ── Write design.md directly via Java — reliable, no tool-call dependency ──
        // (FileSystemTools.Write requires basePath configuration; absolute paths fail silently)
        String designFilePath = null;
        if (resolvedDesign != null && !resolvedDesign.isBlank()) {
            try {
                java.nio.file.Path outDir = java.nio.file.Paths.get(request.resolvedOutputDir());
                java.nio.file.Files.createDirectories(outDir);
                java.nio.file.Path designFile = outDir.resolve("design.md");
                java.nio.file.Files.writeString(designFile, resolvedDesign,
                        java.nio.charset.StandardCharsets.UTF_8);
                designFilePath = designFile.toAbsolutePath().toString();
                log.info("[PUBLISH] design.md written: {} ({} bytes)", designFilePath,
                        java.nio.file.Files.size(designFile));
            } catch (Exception e) {
                log.error("[PUBLISH] Failed to write design.md to {}: {}", request.resolvedOutputDir(), e.getMessage(), e);
            }
        }

        // ── Ask LLM for a publish summary / Jira update (no Write tools needed) ──
        long startMs = System.currentTimeMillis();
        ChatResponse chatResponse = chatClient.prompt()
                .system(prompts.publishSystem(request))
                .user(prompts.publishUser(request, resolvedDesign))
                .options(publishOptions)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(toolsFactory.jiraReadTools())   // Jira MCP only — no file Write tools
                .call()
                .chatResponse();
        long durationMs = System.currentTimeMillis() - startMs;

        String output = chatResponse.getResult().getOutput().getText();

        TokenUsageReport publishReport = tokenTracker.track(
                sessionId, "PUBLISH", modelName, chatResponse, durationMs, 0);
        sessionMemory.storePhaseOutput(sessionId, "PUBLISH", output);

        // ── Long-term: save publish log so we know what was written and when ──
        if (request.repoPath() != null) {
            String projectId = longTermMemory.computeProjectId(request.repoPath());
            longTermMemory.saveEntry(LongTermMemoryEntry.builder()
                    .id(sessionId + "-publish")
                    .projectId(projectId).repoPath(request.repoPath())
                    .phase("PUBLISH")
                    .summary("Published design.md to " + request.resolvedOutputDir())
                    .keyFindings(truncate(output, 1000))
                    .inputTokens(publishReport.inputTokens()).outputTokens(publishReport.outputTokens())
                    .costUsd(publishReport.totalCostUsd())
                    .build());
        }

        this.updatedTickets = toolsFactory.lastUpdatedTickets();

        DesignDocument doc = DesignDocument.of(
                sessionId,
                request.projectLabel(),
                request.repoPath(),
                designFilePath != null ? designFilePath : request.resolvedOutputDir() + "/design.md",
                designContent,
                requirementsSummary,
                codebaseSummary,
                crossRefSummary,
                updatedTickets,
                output
        );

        return AgentResponse.complete(sessionId, doc);
    }

    private AgentResponse runFull(AgentRequest request, String sessionId) {
        log.info("[FULL] session={} starting pipeline", sessionId);
        sessionMemory.getOrCreateSession(sessionId,
                request.repoPath() != null ? request.repoPath() : "no-repo");
        runFetchRequirements(request, sessionId);
        runAnalyze(request, sessionId);
        runCrossRef(request, sessionId);
        runDesign(request, sessionId);
        AgentResponse result = runPublish(request, sessionId);
        sessionMemory.removeSession(sessionId);
        log.info("[FULL] session={} pipeline complete", sessionId);
        return result;
    }

    private String resolveSessionId(AgentRequest request) {
        return (request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId()
                : UUID.randomUUID().toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Resolve the full output for a prior phase.
     * Tier 1: short-term session memory (current JVM run, same sessionId).
     * Tier 2: long-term SQLite (prior runs on the same repo — cross-session fallback).
     * This ensures standalone phase calls benefit from all prior work.
     */
    private String resolvePhaseContent(String sessionId, String phase, String repoPath) {
        String content = sessionMemory.getPhaseOutput(sessionId, phase);
        if (content != null && !content.isBlank()) return content;

        if (repoPath != null) {
            content = longTermMemory.getLatestPhaseContent(repoPath, phase);
            if (content != null && !content.isBlank()) {
                log.info("[MEMORY-FALLBACK] {} for session={} recovered from long-term SQLite", phase, sessionId);
                return content;
            }
        }
        return null;
    }

    /**
     * Build an explicit context block from a prior phase's full output.
     * Returns empty string when the phase output is null (phase not yet run).
     * This replaces the truncated getPreviousContext() / buildContextFromPreviousPhases()
     * which hard-cut each phase to 2000 chars.
     */
    private String fullPhaseBlock(String label, String content) {
        if (content == null || content.isBlank()) return "";
        return "\n\n=== " + label + " ===\n" + content + "\n";
    }

    /**
     * Log a WARN when the LLM stopped because it hit the max_tokens output limit.
     * Without this, truncated outputs are silently returned as "complete".
     * Fix: raise the per-phase max-tokens in application-local.yml if this fires.
     */
    private void warnIfTruncated(ChatResponse chatResponse, String phase, String sessionId) {
        try {
            var metadata = chatResponse.getResult().getMetadata();
            if (metadata != null) {
                Object reason = metadata.getFinishReason();
                if (reason != null && reason.toString().toLowerCase().contains("max_token")) {
                    log.warn("[TRUNCATION] Phase {} session={} was CUT OFF by max_tokens limit! " +
                             "Output is INCOMPLETE — raise {}-max-tokens in application.yml",
                            phase, sessionId, phase.toLowerCase().replace("_", ""));
                }
            }
        } catch (Exception e) {
            log.debug("[TRUNCATION] Could not read finish reason for phase {}: {}", phase, e.getMessage());
        }
    }
}
