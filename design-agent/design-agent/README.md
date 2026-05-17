# Design Agent — Spring AI

Analyses a local codebase + Jira Cloud project and produces a design document,
then posts design comments back to Jira epics and bugs.

---

## Pipeline

```
FETCH_JIRA  →  Pulls epics, stories, bugs from Jira Cloud via MCP
ANALYZE     →  Reads local codebase, maps architecture and patterns
CROSS_REF   →  Links Jira items to code areas
DESIGN      →  Synthesizes new design addressing all epics and bugs
PUBLISH     →  Writes design.md to disk + posts comments to Jira tickets
```

---

## Prerequisites

- Java 21+, Maven 3.9+
- Node.js + npx (for Jira MCP server)
- Jira Cloud account with API token

---

## Environment variables

```bash
export ANTHROPIC_API_KEY=sk-ant-...

# Jira Cloud
export JIRA_URL=https://yourcompany.atlassian.net
export JIRA_USERNAME=you@yourcompany.com
export JIRA_API_TOKEN=your-atlassian-api-token   # https://id.atlassian.com/manage-profile/security/api-tokens
```

---

## Run

```bash
mvn spring-boot:run
```

---

## API

### Run full pipeline

```bash
curl -X POST http://localhost:8080/api/design/run \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/my-spring-app",
    "jiraProjectKey": "MYAPP"
  }'
```

### Scope to a specific sprint

```bash
curl -X POST http://localhost:8080/api/design/run \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/my-spring-app",
    "jiraProjectKey": "MYAPP",
    "jiraSprintId": "Sprint 42"
  }'
```

### Scope to specific issues

```bash
curl -X POST http://localhost:8080/api/design/run \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/my-spring-app",
    "jiraProjectKey": "MYAPP",
    "jiraIssueKeys": ["MYAPP-101", "MYAPP-205", "MYAPP-312"]
  }'
```

### Run a single phase (multi-turn)

```bash
# Step 1 — fetch Jira only
curl -X POST http://localhost:8080/api/design/fetch-jira \
  -H "Content-Type: application/json" \
  -d '{"jiraProjectKey": "MYAPP"}'

# Step 2 — analyse code, continuing the same session
curl -X POST http://localhost:8080/api/design/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "<sessionId from step 1 response>",
    "repoPath": "/workspace/my-spring-app",
    "jiraProjectKey": "MYAPP"
  }'
```

### Add architectural context

```bash
curl -X POST http://localhost:8080/api/design/run \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/my-spring-app",
    "jiraProjectKey": "MYAPP",
    "context": "We follow hexagonal architecture. Prefer event-driven over synchronous calls. No new REST endpoints without OpenAPI spec."
  }'
```

---

## Response shape

```json
{
  "sessionId": "3f7a1b2c-...",
  "phase": "FULL",
  "status": "SUCCESS",
  "designDocument": {
    "jiraProjectKey": "MYAPP",
    "repoPath": "/workspace/my-spring-app",
    "designDocPath": "/workspace/my-spring-app/docs/design/design.md",
    "jiraSummary": "...",
    "codebaseSummary": "...",
    "crossRefSummary": "...",
    "updatedJiraTickets": ["MYAPP-1", "MYAPP-5", "MYAPP-12"],
    "completedAt": "2025-04-19T10:23:45Z"
  }
}
```

---

## Output

The design document is written to `{repoPath}/docs/design/design.md` by default.
Override with `outputDir` in the request.

---

## Skills

Skills are loaded from `.claude/skills/` at startup:

| Skill | Used in phase |
|-------|--------------|
| `codebase-analyzer` | ANALYZE |
| `pattern-detector` | ANALYZE |
| `design-synthesizer` | DESIGN |
| `jira-publisher` | PUBLISH |
