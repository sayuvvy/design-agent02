package com.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * MCP server configuration.
 *
 * SyncMcpToolCallbackProvider is auto-configured by Spring AI's
 * McpToolCallbackAutoConfiguration — we do NOT define it here to
 * avoid duplicate bean conflicts.
 *
 * This class only logs which MCP clients were registered at startup.
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    @Autowired(required = false)
    private List<McpSyncClient> mcpSyncClients;

    @EventListener(ApplicationReadyEvent.class)
    public void logMcpClients() {
        if (mcpSyncClients == null || mcpSyncClients.isEmpty()) {
            log.info("No MCP clients configured — running in local/no-MCP mode");
        } else {
            log.info("MCP clients registered: {}", mcpSyncClients.size());
            mcpSyncClients.forEach(c ->
                    log.info("  MCP client: {}", c.getServerInfo().name()));
        }
    }
}
