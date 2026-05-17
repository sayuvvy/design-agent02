package com.agent.service;

import com.agent.model.CodebaseComplexity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes a codebase to determine its complexity level (SIMPLE, MEDIUM, COMPLEX).
 *
 * Uses heuristics:
 * - Count of Java source files
 * - Estimated lines of code (sampled from files)
 * - Number of configuration files
 */
@Service
public class CodebaseComplexityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CodebaseComplexityAnalyzer.class);

    /**
     * Assess the complexity of a codebase.
     *
     * @param repoPath Path to the repository
     * @return CodebaseComplexity: SIMPLE, MEDIUM, or COMPLEX
     */
    public CodebaseComplexity assessComplexity(String repoPath) {
        try {
            Path path = Paths.get(repoPath);

            if (!Files.isDirectory(path)) {
                log.warn("Path is not a directory: {}", repoPath);
                return CodebaseComplexity.MEDIUM; // Default to MEDIUM if can't assess
            }

            // Count Java files
            long javaFileCount = countJavaFiles(path);

            // Estimate lines of code
            long estimatedLines = estimateLinesOfCode(path);

            // Determine complexity
            CodebaseComplexity complexity = classifyComplexity(javaFileCount, estimatedLines);

            log.info("Codebase complexity: {} (javaFiles={}, estimatedLOC={})",
                    complexity.label(), javaFileCount, estimatedLines);

            return complexity;

        } catch (Exception e) {
            log.error("Error assessing codebase complexity: {}", e.getMessage());
            return CodebaseComplexity.MEDIUM; // Safe default
        }
    }

    /**
     * Count Java files in the repository.
     */
    private long countJavaFiles(Path repoPath) throws IOException {
        return Files.walk(repoPath)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("\\target\\")) // Skip compiled files
                .filter(p -> !p.toString().contains("/target/"))
                .count();
    }

    /**
     * Estimate total lines of code by sampling.
     * - Reads first 5 Java files encountered
     * - Counts their lines
     * - Extrapolates to estimate total
     *
     * This is a heuristic to avoid reading all files.
     */
    private long estimateLinesOfCode(Path repoPath) throws IOException {
        AtomicLong totalSampledLines = new AtomicLong(0);
        AtomicLong filesRead = new AtomicLong(0);

        Files.walk(repoPath)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("\\target\\"))
                .filter(p -> !p.toString().contains("/target/"))
                .limit(5) // Sample only 5 files
                .forEach(p -> {
                    try {
                        long lines = Files.lines(p).count();
                        totalSampledLines.addAndGet(lines);
                        filesRead.incrementAndGet();
                    } catch (IOException e) {
                        log.debug("Could not read file: {}", p);
                    }
                });

        if (filesRead.get() == 0) {
            return 0;
        }

        // Extrapolate: average lines per file × total file count
        long averageLines = totalSampledLines.get() / filesRead.get();
        long totalJavaFiles = countJavaFiles(repoPath);

        return averageLines * totalJavaFiles;
    }

    /**
     * Classify complexity based on file count and lines of code.
     */
    private CodebaseComplexity classifyComplexity(long javaFileCount, long estimatedLines) {
        // SIMPLE: ≤5 files or <2K lines
        if (javaFileCount <= 5 && estimatedLines < 2000) {
            return CodebaseComplexity.SIMPLE;
        }

        // COMPLEX: >25 files or >10K lines
        if (javaFileCount > 25 || estimatedLines > 10000) {
            return CodebaseComplexity.COMPLEX;
        }

        // MEDIUM: everything else (6-25 files, 2K-10K lines)
        return CodebaseComplexity.MEDIUM;
    }
}
