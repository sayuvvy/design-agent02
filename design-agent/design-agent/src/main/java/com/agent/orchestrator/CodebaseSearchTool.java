package com.agent.orchestrator;

import com.agent.memory.SemanticMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Spring AI tool that replaces sequential file reads with semantic code search.
 *
 * <p>Instead of the agent calling GlobTool → ReadFile × N (O(n²) context growth),
 * it calls {@link #searchCodebase(String)} 2-3 times with targeted queries and
 * gets back the most relevant code chunks from the pre-indexed vector store.
 *
 * <p>This is a plain class (not a Spring bean) — instantiated per-request in
 * {@link DesignOrchestrator} so it carries the correct {@code projectId} scope.
 *
 * <p>Token cost per query: ~50 tokens (query embed) + ~2000 tokens returned.
 * Compare to sequential: 19 tool calls × growing context = ~8,400 tokens.
 */
public class CodebaseSearchTool {

    private static final Logger log = LoggerFactory.getLogger(CodebaseSearchTool.class);

    private final SemanticMemoryService semanticMemory;
    private final String projectId;

    public CodebaseSearchTool(SemanticMemoryService semanticMemory, String projectId) {
        this.semanticMemory = semanticMemory;
        this.projectId = projectId;
    }

    /**
     * Search the pre-indexed codebase using semantic similarity.
     *
     * <p>Call this 2-4 times with different targeted queries to cover:
     * <ul>
     *   <li>Application entry point and configuration</li>
     *   <li>Core business logic (processors, services, jobs)</li>
     *   <li>Specific patterns relevant to the user's requirements</li>
     * </ul>
     *
     * @param query a natural-language or keyword description of the code you need,
     *              e.g. "batch job configuration", "database reader implementation",
     *              "error handling retry logic"
     * @return the top-K most relevant code chunks with file names and similarity scores
     */
    @Tool(description = """
            Search the pre-indexed Java codebase for classes and code relevant to a query.
            Returns the most semantically similar source files from the project.

            Use this INSTEAD of GlobTool + ReadFile — it is much faster and uses fewer tokens.
            Call it 2-4 times with different queries to cover the codebase:
              Query 1: "application entry point configuration" — gets Application.java + Config files
              Query 2: "core business logic processor service" — gets main processing classes
              Query 3: <specific to user's requirements> — gets domain-relevant code

            Examples:
              searchCodebase("batch job step configuration")
              searchCodebase("database connection reader writer")
              searchCodebase("error handling exception retry")
              searchCodebase("entity model domain object")
            """)
    public String searchCodebase(String query) {
        log.info("[RAG-TOOL] searchCodebase query='{}' project={}", query, projectId);
        String result = semanticMemory.retrieveCodebaseChunks(projectId, query);
        log.debug("[RAG-TOOL] Returned {} chars for query='{}'", result.length(), query);
        return result;
    }
}
