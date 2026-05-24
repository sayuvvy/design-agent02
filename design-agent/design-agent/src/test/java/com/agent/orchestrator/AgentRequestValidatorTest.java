package com.agent.orchestrator;

import com.agent.exception.AgentRequestValidator;
import com.agent.model.AgentPhase;
import com.agent.model.AgentRequest;
import com.agent.model.CodebaseComplexity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRequestValidatorTest {

    private final AgentRequestValidator validator = new AgentRequestValidator();

    @TempDir Path tempDir;

    @Test
    @DisplayName("should pass for valid FULL request with local repoPath")
    void should_pass_for_validFullRequest() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.FULL,
                tempDir.toString(), null, null,
                "MYAPP", null, null, null, null, null, CodebaseComplexity.MEDIUM);

        assertThatNoException().isThrownBy(() -> validator.validate(req));
    }

    @Test
    @DisplayName("should pass for valid FULL request with repoUrl")
    void should_pass_for_validFullRequest_withRepoUrl() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.FULL,
                null, "https://github.com/owner/myapp", null,
                "MYAPP", null, null, null, null, null, CodebaseComplexity.MEDIUM);

        assertThatNoException().isThrownBy(() -> validator.validate(req));
    }

    @Test
    @DisplayName("should pass for FETCH_JIRA with only jiraProjectKey")
    void should_pass_for_fetchJira_withJiraKeyOnly() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.FETCH_JIRA,
                null, null, null,
                "MYAPP", null, null, null, null, null, CodebaseComplexity.MEDIUM);

        assertThatNoException().isThrownBy(() -> validator.validate(req));
    }

    @Test
    @DisplayName("should throw when neither repoPath nor repoUrl provided for ANALYZE")
    void should_throw_when_repoPath_missing_for_analyze() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.ANALYZE,
                null, null, null,
                "MYAPP", null, null, null, null, null, CodebaseComplexity.MEDIUM);

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoPath");
    }

    @Test
    @DisplayName("should throw when repoPath does not exist")
    void should_throw_when_repoPath_doesNotExist() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.ANALYZE,
                "/no/such/path", null, null,
                "MYAPP", null, null, null, null, null, CodebaseComplexity.MEDIUM);

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("should throw when jiraProjectKey missing for FETCH_JIRA")
    void should_throw_when_jiraKey_missing_for_fetchJira() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.FETCH_JIRA,
                null, null, null,
                null, null, null, null, null, null, CodebaseComplexity.MEDIUM);

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jiraProjectKey");
    }

    @Test
    @DisplayName("should throw when jiraProjectKey missing for PUBLISH")
    void should_throw_when_jiraKey_missing_for_publish() {
        AgentRequest req = new AgentRequest(
                null, AgentPhase.PUBLISH,
                tempDir.toString(), null, null,
                null, null, null, null, null, null, CodebaseComplexity.MEDIUM);

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jiraProjectKey");
    }

    @Test
    @DisplayName("should throw when request is null")
    void should_throw_when_request_isNull() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }
}
