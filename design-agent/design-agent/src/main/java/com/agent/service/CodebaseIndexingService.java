package com.agent.service;

import com.agent.config.ScanLimitsConfig;
import com.agent.memory.LongTermMemoryService;
import com.agent.memory.SemanticMemoryService;
import com.agent.model.CodebaseComplexity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Indexes a Java codebase into the semantic vector store so the agent can
 * retrieve relevant code via similarity search instead of reading files
 * one-by-one (which causes O(n²) context re-send growth).
 *
 * <p>Each Java source file becomes one vector document tagged with:
 * <ul>
 *   <li>category = "CODEBASE_CHUNK"</li>
 *   <li>projectId = hash of the repo path</li>
 *   <li>metadata.file = relative path within repo</li>
 * </ul>
 *
 * <p>Indexing is idempotent within a JVM session: once a project is indexed
 * it won't be re-indexed unless {@link #invalidate(String)} is called.
 * This means the indexing cost (embedding API calls) is paid once per restart,
 * not on every {@code /analyze} request.
 *
 * <p>Cost: text-embedding-3-small = $0.02/M tokens.
 * Indexing 18 files × ~1100 chars ≈ 5,000 tokens ≈ $0.0001 per repo.
 */
@Service
public class CodebaseIndexingService {

    private static final Logger log = LoggerFactory.getLogger(CodebaseIndexingService.class);
    private static final String CODEBASE_CHUNK_CATEGORY = "CODEBASE_CHUNK";

    private final SemanticMemoryService semanticMemory;
    private final LongTermMemoryService longTermMemory;
    private final ScanLimitsConfig scanLimits;

    /** projectId → timestamp of last successful indexing (in-memory, JVM-scoped). */
    private final Map<String, Instant> indexedProjects = new ConcurrentHashMap<>();

    public CodebaseIndexingService(SemanticMemoryService semanticMemory,
                                   LongTermMemoryService longTermMemory,
                                   ScanLimitsConfig scanLimits) {
        this.semanticMemory = semanticMemory;
        this.longTermMemory = longTermMemory;
        this.scanLimits = scanLimits;
    }

    /** Returns true only when the embedding model is configured and enabled. */
    public boolean isAvailable() {
        return semanticMemory.isAvailable();
    }

    /** Returns true if this repo was already indexed in the current JVM session. */
    public boolean isIndexed(String repoPath) {
        return indexedProjects.containsKey(longTermMemory.computeProjectId(repoPath));
    }

    /**
     * Index all Java source files in {@code repoPath} up to the scan limit for
     * the given complexity tier.  Skips {@code target/} and {@code src/test/}.
     *
     * <p>Safe to call multiple times — exits immediately if already indexed.
     */
    public void indexCodebase(String repoPath, CodebaseComplexity complexity) {
        if (!semanticMemory.isAvailable()) {
            log.info("[RAG-INDEX] Semantic memory not available — skipping codebase indexing");
            return;
        }

        String projectId = longTermMemory.computeProjectId(repoPath);
        if (indexedProjects.containsKey(projectId)) {
            log.info("[RAG-INDEX] Already indexed project={} — skipping", projectId);
            return;
        }

        ScanLimitsConfig.Tier tier = resolveTier(complexity);
        int maxFiles = tier.getMaxFiles();

        log.info("[RAG-INDEX] Indexing project={} repo={} maxFiles={} complexity={}",
                projectId, repoPath, maxFiles, complexity != null ? complexity.label() : "MEDIUM");

        Path root = Path.of(repoPath);
        if (!Files.isDirectory(root)) {
            log.warn("[RAG-INDEX] Repo path does not exist or is not a directory: {}", repoPath);
            return;
        }

        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try (var stream = Files.walk(root)) {
            stream
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !isExcluded(p))
                .sorted()                          // deterministic order
                .takeWhile(p -> fileCount.get() < maxFiles)
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        String relativePath = root.relativize(p).toString().replace('\\', '/');

                        semanticMemory.storeKnowledge(
                            content,
                            CODEBASE_CHUNK_CATEGORY,
                            projectId,
                            Map.of("file", relativePath, "repoPath", repoPath)
                        );
                        int n = fileCount.incrementAndGet();
                        log.debug("[RAG-INDEX] [{}/{}] Indexed {}", n, maxFiles, relativePath);
                    } catch (IOException e) {
                        errorCount.incrementAndGet();
                        log.warn("[RAG-INDEX] Failed to read {}: {}", p.getFileName(), e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.error("[RAG-INDEX] Failed to walk repo {}: {}", repoPath, e.getMessage());
            return;
        }

        indexedProjects.put(projectId, Instant.now());
        log.info("[RAG-INDEX] Done — {} files indexed, {} errors, project={}",
                fileCount.get(), errorCount.get(), projectId);
    }

    /**
     * Force re-indexing on next call (e.g. after source files change).
     */
    public void invalidate(String repoPath) {
        String projectId = longTermMemory.computeProjectId(repoPath);
        indexedProjects.remove(projectId);
        log.info("[RAG-INDEX] Invalidated index for project={}", projectId);
    }

    /** Number of projects currently indexed in this JVM session. */
    public int indexedProjectCount() {
        return indexedProjects.size();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isExcluded(Path p) {
        String path = p.toString().replace('\\', '/');
        return path.contains("/target/")
            || path.contains("/src/test/")
            || path.contains("/.git/");
    }

    private ScanLimitsConfig.Tier resolveTier(CodebaseComplexity complexity) {
        if (complexity == null) return scanLimits.getMedium();
        return switch (complexity) {
            case SIMPLE  -> scanLimits.getSimple();
            case MEDIUM  -> scanLimits.getMedium();
            case COMPLEX -> scanLimits.getComplex();
        };
    }
}
