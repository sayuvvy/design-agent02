package com.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test — verifies the application compiles and the test framework works.
 * Full Spring context loading is skipped because it requires a real ChatModel
 * bean (Anthropic API key or Ollama running).
 */
class CodingAgentApplicationTest {

    @Test
    @DisplayName("Application class exists and is loadable")
    void applicationClassLoads() {
        // Verify the main class is on the classpath
        CodingAgentApplication app = new CodingAgentApplication();
        org.junit.jupiter.api.Assertions.assertNotNull(app);
    }
}
