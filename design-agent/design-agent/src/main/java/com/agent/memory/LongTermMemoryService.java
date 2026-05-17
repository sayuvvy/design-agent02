package com.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Long-term memory persisted in SQLite (free, embedded, zero-config).
 * Stores analysis history, design decisions, and token usage logs across sessions.
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agent.memory.long-term.db-path:./data/agent-memory.db}")
    private String dbPath;

    private String jdbcUrl;

    @PostConstruct
    public void init() {
        try {
            Path parent = Path.of(dbPath).getParent();
            if (parent != null) Files.createDirectories(parent);
            jdbcUrl = "jdbc:sqlite:" + dbPath;
            createTables();
            log.info("[LONG-TERM-MEMORY] Initialized SQLite at {}", dbPath);
        } catch (Exception e) {
            log.error("[LONG-TERM-MEMORY] Failed to initialize: {}", e.getMessage(), e);
        }
    }

    private void createTables() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS memory_entries (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    repo_path TEXT,
                    phase TEXT NOT NULL,
                    summary TEXT,
                    key_findings TEXT,
                    decisions TEXT,
                    metadata_json TEXT,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    cost_usd REAL DEFAULT 0.0,
                    created_at TEXT NOT NULL
                )
            """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS token_usage_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    model TEXT,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    total_tokens INTEGER DEFAULT 0,
                    cost_usd REAL DEFAULT 0.0,
                    duration_ms INTEGER DEFAULT 0,
                    tool_calls INTEGER DEFAULT 0,
                    created_at TEXT NOT NULL
                )
            """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS user_preferences (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_project ON memory_entries(project_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_phase ON memory_entries(phase)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_token_session ON token_usage_log(session_id)");
        }
    }

    // ── Memory Entry CRUD ───────────────────────────────────────────────────

    public void saveEntry(LongTermMemoryEntry entry) {
        String sql = """
            INSERT OR REPLACE INTO memory_entries
            (id, project_id, repo_path, phase, summary, key_findings, decisions,
             metadata_json, input_tokens, output_tokens, cost_usd, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.id());
            ps.setString(2, entry.projectId());
            ps.setString(3, entry.repoPath());
            ps.setString(4, entry.phase());
            ps.setString(5, entry.summary());
            ps.setString(6, entry.keyFindings());
            ps.setString(7, entry.decisions());
            ps.setString(8, objectMapper.writeValueAsString(entry.metadata()));
            ps.setLong(9, entry.inputTokens());
            ps.setLong(10, entry.outputTokens());
            ps.setDouble(11, entry.costUsd());
            ps.setString(12, entry.createdAt().toString());
            ps.executeUpdate();
            log.info("[LONG-TERM-MEMORY] Saved entry {} for project {} phase {}",
                    entry.id(), entry.projectId(), entry.phase());
        } catch (Exception e) {
            log.error("[LONG-TERM-MEMORY] Failed to save entry: {}", e.getMessage(), e);
        }
    }

    public List<LongTermMemoryEntry> getProjectHistory(String repoPath, int limit) {
        String projectId = computeProjectId(repoPath);
        String sql = "SELECT * FROM memory_entries WHERE project_id = ? ORDER BY created_at DESC LIMIT ?";
        List<LongTermMemoryEntry> entries = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) entries.add(mapEntry(rs));
        } catch (Exception e) {
            log.error("[LONG-TERM-MEMORY] Failed to query: {}", e.getMessage(), e);
        }
        return entries;
    }

    /**
     * Build a historical context string for prompt enrichment.
     */
    public String buildHistoricalContext(String repoPath) {
        List<LongTermMemoryEntry> history = getProjectHistory(repoPath, 5);
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n--- HISTORICAL CONTEXT (from previous sessions) ---\n");
        for (LongTermMemoryEntry entry : history) {
            sb.append(String.format("\n[%s] %s phase:\n",
                    entry.createdAt().toString().substring(0, 10), entry.phase()));
            if (entry.summary() != null) sb.append("Summary: ").append(entry.summary()).append("\n");
            if (entry.keyFindings() != null) sb.append("Findings: ").append(entry.keyFindings()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Retrieve the most recent content stored for a given phase and repo.
     * Returns the {@code decisions} field (up to 3000 chars of the phase output),
     * falling back to {@code keyFindings}, then null if no entry found.
     * Used by PUBLISH as a fallback when session memory has expired.
     */
    public String getLatestPhaseContent(String repoPath, String phase) {
        return getProjectHistory(repoPath, 10).stream()
                .filter(e -> phase.equalsIgnoreCase(e.phase()))
                .findFirst()
                .map(e -> e.decisions() != null ? e.decisions()
                        : e.keyFindings() != null ? e.keyFindings() : null)
                .orElse(null);
    }

    // ── Token Usage Tracking ────────────────────────────────────────────────

    public void logTokenUsage(String sessionId, String phase, String model,
                               long inputTokens, long outputTokens, double costUsd,
                               long durationMs, int toolCalls) {
        String sql = """
            INSERT INTO token_usage_log
            (session_id, phase, model, input_tokens, output_tokens, total_tokens,
             cost_usd, duration_ms, tool_calls, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, phase);
            ps.setString(3, model);
            ps.setLong(4, inputTokens);
            ps.setLong(5, outputTokens);
            ps.setLong(6, inputTokens + outputTokens);
            ps.setDouble(7, costUsd);
            ps.setLong(8, durationMs);
            ps.setInt(9, toolCalls);
            ps.setString(10, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[TOKEN-TELEMETRY] Failed to log: {}", e.getMessage(), e);
        }
    }

    public Map<String, Object> getUsageSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {

            // Total usage
            ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) as calls,
                       COALESCE(SUM(input_tokens), 0) as total_input,
                       COALESCE(SUM(output_tokens), 0) as total_output,
                       COALESCE(SUM(total_tokens), 0) as total_tokens,
                       COALESCE(SUM(cost_usd), 0) as total_cost,
                       COALESCE(SUM(tool_calls), 0) as total_tool_calls,
                       COALESCE(AVG(duration_ms), 0) as avg_duration
                FROM token_usage_log
            """);
            if (rs.next()) {
                summary.put("totalCalls", rs.getInt("calls"));
                summary.put("totalInputTokens", rs.getLong("total_input"));
                summary.put("totalOutputTokens", rs.getLong("total_output"));
                summary.put("totalTokens", rs.getLong("total_tokens"));
                summary.put("totalCostUsd", rs.getDouble("total_cost"));
                summary.put("totalToolCalls", rs.getInt("total_tool_calls"));
                summary.put("avgDurationMs", rs.getLong("avg_duration"));
            }

            // Per-model breakdown
            rs = stmt.executeQuery("""
                SELECT model, COUNT(*) as calls,
                       SUM(input_tokens) as inp, SUM(output_tokens) as outp,
                       SUM(cost_usd) as cost
                FROM token_usage_log GROUP BY model
            """);
            List<Map<String, Object>> perModel = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("model", rs.getString("model"));
                row.put("calls", rs.getInt("calls"));
                row.put("inputTokens", rs.getLong("inp"));
                row.put("outputTokens", rs.getLong("outp"));
                row.put("costUsd", rs.getDouble("cost"));
                perModel.add(row);
            }
            summary.put("perModel", perModel);

            // Per-phase breakdown
            rs = stmt.executeQuery("""
                SELECT phase, COUNT(*) as calls,
                       SUM(total_tokens) as tokens, SUM(cost_usd) as cost,
                       AVG(duration_ms) as avg_dur
                FROM token_usage_log GROUP BY phase
            """);
            List<Map<String, Object>> perPhase = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("phase", rs.getString("phase"));
                row.put("calls", rs.getInt("calls"));
                row.put("totalTokens", rs.getLong("tokens"));
                row.put("costUsd", rs.getDouble("cost"));
                row.put("avgDurationMs", rs.getLong("avg_dur"));
                perPhase.add(row);
            }
            summary.put("perPhase", perPhase);

            // Last 24h
            rs = stmt.executeQuery("""
                SELECT COALESCE(SUM(total_tokens), 0) as tokens,
                       COALESCE(SUM(cost_usd), 0) as cost, COUNT(*) as calls
                FROM token_usage_log
                WHERE created_at > datetime('now', '-1 day')
            """);
            if (rs.next()) {
                Map<String, Object> last24h = new LinkedHashMap<>();
                last24h.put("calls", rs.getInt("calls"));
                last24h.put("totalTokens", rs.getLong("tokens"));
                last24h.put("costUsd", rs.getDouble("cost"));
                summary.put("last24h", last24h);
            }

        } catch (Exception e) {
            log.error("[TOKEN-TELEMETRY] Failed to get summary: {}", e.getMessage(), e);
            summary.put("error", e.getMessage());
        }
        return summary;
    }

    public List<Map<String, Object>> getSessionUsage(String sessionId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        String sql = "SELECT * FROM token_usage_log WHERE session_id = ? ORDER BY created_at";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("phase", rs.getString("phase"));
                row.put("model", rs.getString("model"));
                row.put("inputTokens", rs.getLong("input_tokens"));
                row.put("outputTokens", rs.getLong("output_tokens"));
                row.put("totalTokens", rs.getLong("total_tokens"));
                row.put("costUsd", rs.getDouble("cost_usd"));
                row.put("durationMs", rs.getLong("duration_ms"));
                row.put("toolCalls", rs.getInt("tool_calls"));
                row.put("createdAt", rs.getString("created_at"));
                entries.add(row);
            }
        } catch (Exception e) {
            log.error("[TOKEN-TELEMETRY] Failed to get session usage: {}", e.getMessage(), e);
        }
        return entries;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    public String computeProjectId(String repoPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(repoPath.getBytes());
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(repoPath.hashCode());
        }
    }

    private LongTermMemoryEntry mapEntry(ResultSet rs) throws Exception {
        Map<String, String> meta = Map.of();
        String metaJson = rs.getString("metadata_json");
        if (metaJson != null && !metaJson.isBlank()) {
            meta = objectMapper.readValue(metaJson, new TypeReference<>() {});
        }
        return LongTermMemoryEntry.builder()
                .id(rs.getString("id"))
                .projectId(rs.getString("project_id"))
                .repoPath(rs.getString("repo_path"))
                .phase(rs.getString("phase"))
                .summary(rs.getString("summary"))
                .keyFindings(rs.getString("key_findings"))
                .decisions(rs.getString("decisions"))
                .metadata(meta)
                .inputTokens(rs.getLong("input_tokens"))
                .outputTokens(rs.getLong("output_tokens"))
                .costUsd(rs.getDouble("cost_usd"))
                .createdAt(Instant.parse(rs.getString("created_at")))
                .build();
    }
}
