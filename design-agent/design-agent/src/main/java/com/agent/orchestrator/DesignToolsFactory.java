package com.agent.orchestrator;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Assembles the correct tool set for each design pipeline phase.
 *
 * Jira MCP is optional — when not configured the agent operates on
 * user-provided issue text instead.
 */
@Primary
@Component
public class DesignToolsFactory {

    private final Optional<SyncMcpToolCallbackProvider> mcpProvider;
    private final CopyOnWriteArrayList<String> updatedTickets = new CopyOnWriteArrayList<>();

    public DesignToolsFactory(
            @Autowired(required = false)
            @org.springframework.beans.factory.annotation.Qualifier("mcpToolCallbacks")
            SyncMcpToolCallbackProvider mcpProvider) {
        this.mcpProvider = Optional.ofNullable(mcpProvider);
    }

    /** True when Jira MCP is available. */
    public boolean isJiraAvailable() {
        return mcpProvider.isPresent();
    }

    /**
     * FETCH_JIRA phase — Jira MCP tools when available.
     * Returns empty array when Jira is not configured (agent uses user-provided text).
     */
    public Object[] jiraReadTools() {
        if (mcpProvider.isPresent()) {
            return mcpProvider.get().getToolCallbacks();
        }
        // No MCP — return empty; the orchestrator will skip the Jira call
        // and use the user-provided issues text from the request instead
        return new Object[0];
    }

    /**
     * ANALYZE phase — RAG path.
     * Replaces sequential GlobTool + ReadFile calls with a single semantic search tool.
     * The agent calls {@code searchCodebase(query)} 2-4 times instead of reading N files,
     * eliminating O(n²) context re-send growth.
     *
     * @param searchTool a per-request instance scoped to the correct project vector store
     */
    public Object[] ragAnalyzeTools(CodebaseSearchTool searchTool) {
        List<Object> tools = new ArrayList<>();
        tools.add(searchTool);
        // Jira MCP still available for issue detail lookups during analysis
        mcpProvider.ifPresent(p ->
                tools.addAll(Arrays.asList(p.getToolCallbacks())));
        return tools.toArray();
    }

    /**
     * ANALYZE phase — GitHub MCP mode.
     * Uses GitHub MCP tools (get_file_contents, list_directory_tree, search_code)
     * to read code directly from a GitHub repo URL — no local filesystem needed.
     * Jira MCP tools are also included when available.
     */
    public Object[] githubCodeReadTools() {
        List<Object> tools = new ArrayList<>();
        mcpProvider.ifPresent(p ->
                tools.addAll(Arrays.asList(p.getToolCallbacks())));
        return tools.toArray();
    }

    /**
     * ANALYZE, CROSS_REF, DESIGN phases — read-only code tools.
     * Includes Jira MCP if available for issue detail lookups.
     * Used when RAG indexing is not available (semantic memory disabled).
     *
     * NOTE: FileSystemTools intentionally excluded — it includes Write capability.
     * If Claude uses Write during DESIGN, its text response becomes "file written"
     * (empty), which breaks the PUBLISH phase that depends on the text output.
     * Write access is only given in publishTools().
     */
    public Object[] codeReadTools() {
        List<Object> tools = new ArrayList<>();
        tools.add(GlobTool.builder().build());
        tools.add(GrepTool.builder().build());
        mcpProvider.ifPresent(p ->
                tools.addAll(Arrays.asList(p.getToolCallbacks())));
        return tools.toArray();
    }

    /**
     * PUBLISH phase — file write tools always; Jira MCP when available.
     * When Jira is not configured, only the design doc is written to disk.
     */
    public Object[] publishTools(String outputDir) {
        updatedTickets.clear();
        List<Object> tools = new ArrayList<>();
        tools.add(GlobTool.builder().build());
        tools.add(GrepTool.builder().build());
        tools.add(FileSystemTools.builder().build());
        mcpProvider.ifPresent(p ->
                tools.addAll(Arrays.asList(p.getToolCallbacks())));
        return tools.toArray();
    }

    public List<String> lastUpdatedTickets() {
        return List.copyOf(updatedTickets);
    }

    public void trackUpdatedTicket(String issueKey) {
        updatedTickets.add(issueKey);
    }
}
