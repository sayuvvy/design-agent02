package com.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized scan limits for the codebase analysis phase.
 *
 * Controls how many files and lines Claude reads per complexity tier.
 * Tune via application.yml or environment variables — no rebuild needed.
 *
 * YAML:
 *   agent.scan.simple.max-files: 10
 *   agent.scan.simple.max-lines: 500
 *   agent.scan.medium.max-files: 18
 *   agent.scan.medium.max-lines: 200
 *   agent.scan.complex.max-files: 25
 *   agent.scan.complex.max-lines: 120
 *
 * Env vars (override):
 *   AGENT_SCAN_SIMPLE_FILES, AGENT_SCAN_SIMPLE_LINES
 *   AGENT_SCAN_MEDIUM_FILES, AGENT_SCAN_MEDIUM_LINES
 *   AGENT_SCAN_COMPLEX_FILES, AGENT_SCAN_COMPLEX_LINES
 */
@Component
@ConfigurationProperties(prefix = "agent.scan")
public class ScanLimitsConfig {

    private Tier simple  = new Tier(10, 500);
    private Tier medium  = new Tier(18, 200);
    private Tier complex = new Tier(25, 120);

    public Tier getSimple()  { return simple; }
    public Tier getMedium()  { return medium; }
    public Tier getComplex() { return complex; }

    public void setSimple(Tier simple)   { this.simple = simple; }
    public void setMedium(Tier medium)   { this.medium = medium; }
    public void setComplex(Tier complex) { this.complex = complex; }

    public static class Tier {
        private int maxFiles;
        private int maxLines;

        public Tier() {}

        public Tier(int maxFiles, int maxLines) {
            this.maxFiles = maxFiles;
            this.maxLines = maxLines;
        }

        public int getMaxFiles() { return maxFiles; }
        public int getMaxLines() { return maxLines; }
        public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
        public void setMaxLines(int maxLines) { this.maxLines = maxLines; }

        @Override
        public String toString() {
            return "maxFiles=" + maxFiles + ", maxLines=" + maxLines;
        }
    }
}
