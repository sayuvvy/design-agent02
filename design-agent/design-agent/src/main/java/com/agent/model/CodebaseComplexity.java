package com.agent.model;

/**
 * Enum for classifying codebase complexity based on:
 * - Number of Java source files
 * - Estimated lines of code
 * - Configuration complexity
 *
 * Used to adapt analysis strategies, token budgets, and file selection.
 */
public enum CodebaseComplexity {

    /**
     * SIMPLE: Small, straightforward codebases
     * - Typically 1-5 Java files
     * - Less than 2,000 lines of code
     * - Usually single module/package
     * - Examples: Microservice, small API, utility
     *
     * Strategy: Read ALL files, no constraints, thorough analysis
     */
    SIMPLE("Simple", "1-5 files, <2K LOC"),

    /**
     * MEDIUM: Moderate-sized codebases with multiple components
     * - Typically 6-25 Java files
     * - 2,000-10,000 lines of code
     * - Multiple packages/modules (config, service, controller)
     * - Examples: Spring Batch ETL, multi-service monolith
     *
     * Strategy: Read 15 files max, intelligent prioritization
     */
    MEDIUM("Medium", "6-25 files, 2K-10K LOC"),

    /**
     * COMPLEX: Large, highly modular codebases
     * - 25+ Java files
     * - 10,000+ lines of code
     * - Multiple layers, packages, concerns
     * - Examples: Large microservices, monoliths with plugins
     *
     * Strategy: Read 20 files max, strict cost control, focus on key components
     */
    COMPLEX("Complex", "25+ files, 10K+ LOC");

    private final String label;
    private final String description;

    CodebaseComplexity(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return label + " (" + description + ")";
    }
}
