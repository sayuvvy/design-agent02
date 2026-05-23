# Design Agent

A Spring AI agentic service that analyses a local Java codebase together with a Jira Cloud project and produces a structured design document, then posts the design decisions back to Jira epics and bugs as comments.

---

## Pipeline

```
FETCH_JIRA  →  Pull epics, stories, bugs from Jira Cloud via MCP
ANALYZE     →  Read local codebase, map architecture and patterns
CROSS_REF   →  Link Jira items to code areas
DESIGN      →  Synthesise a new design addressing all requirements
PUBLISH     →  Write design.md to disk + post comments to Jira tickets
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 4.0 |
| AI | Spring AI 2.0 — Anthropic Claude (chat), OpenAI (embeddings) |
| Local LLM | Ollama (optional, via `ollama` profile) |
| Integration | Jira Cloud via MCP (`@modelcontextprotocol/server-jira`) |
| Memory | SQLite (long-term), in-process (short-term + semantic RAG) |
| Build | Maven 3.9, Maven Daemon (mvnd) |
| Container | Docker (`eclipse-temurin:21-jre-jammy`) |

---

## Prerequisites

- Java 21+, Maven 3.9+
- Node.js 20+ and `npx` — required for Jira MCP integration
- Anthropic API key
- OpenAI API key — for semantic memory embeddings (optional, disable with `AGENT_SEMANTIC_MEMORY=false`)
- Jira Cloud account with API token

---

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | Yes | Claude API key |
| `OPENAI_API_KEY` | For embeddings | OpenAI key for `text-embedding-3-small` |
| `JIRA_URL` | For Jira | e.g. `https://yourcompany.atlassian.net` |
| `JIRA_USERNAME` | For Jira | Your Atlassian account email |
| `JIRA_API_TOKEN` | For Jira | Create at id.atlassian.com/manage-profile/security/api-tokens |
| `AGENT_DAILY_BUDGET` | No | Daily cost cap in USD (default `5.00`) |
| `AGENT_MEMORY_DB` | No | Path to SQLite file (default `./data/agent-memory.db`) |
| `MCP_ENABLED` | No | Enable Jira MCP server (default `true`) |

---

## Profiles

| Profile | Port | Model | MCP | Use For |
|---|---|---|---|---|
| *(default)* | 8081 | claude-opus-4-7 | enabled | Base config only, override with a profile |
| `local` | 8091 | claude-haiku-4-5 | disabled | Local development, no Jira needed |
| `prod` | `$PORT` | claude-haiku-4-5 | disabled | Render / Docker deployment |
| `ollama` | — | qwen2.5-coder:14b | disabled | Free local LLM, no API key needed |
| `test` | random | claude-haiku-4-5 | disabled | Maven Surefire unit tests |

---

## Build

```bash
mvn clean package -DskipTests
```

The fat JAR is produced at `target/design-agent-1.0.0-SNAPSHOT.jar`.

---

## Run Locally

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export OPENAI_API_KEY=sk-...

java -Dmanagement.otlp.metrics.export.enabled=false \
     -jar target/design-agent-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=local
```

Health check: `http://localhost:8091/actuator/health`

### With Ollama (free, no API key)

```bash
ollama pull qwen2.5-coder:14b

java -jar target/design-agent-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=local,ollama
```

---

## Docker

### Build

```bash
mvn clean package -DskipTests
docker build -t design-agent .
```

### Run

```bash
docker run -d --name design-agent -p 8081:8081 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e OPENAI_API_KEY=sk-... \
  -v "/path/to/your/codebase:/workspace/myapp" \
  design-agent:latest
```

> The `-v` mount makes your local codebase visible inside the container.
> Pass the container-side path (e.g. `/workspace/myapp`) as `repoPath` in API requests.

Health check: `http://localhost:8081/actuator/health`

### JVM Tuning (prod profile, 512 MB container)

```
-Xmx300m -Xss256k -XX:MaxMetaspaceSize=128m -XX:+UseContainerSupport
```

---

## Deploy to Render (Free Tier)

1. Push this repo to GitHub
2. Go to **render.com** → New → Web Service
3. Connect your GitHub repo
4. Set **Root Directory** to `design-agent/design-agent`
5. Runtime: **Docker** (auto-detected from `Dockerfile`)
6. Add environment variables in the Render dashboard:

| Key | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `ANTHROPIC_API_KEY` | your key |
| `OPENAI_API_KEY` | your key |

> **Note:** Render free tier sleeps after 15 min inactivity (~30s cold start to wake).
> The filesystem is ephemeral — add a Render Disk mounted at `/data` ($1/month) to persist the SQLite memory DB across deploys.

---

## API Reference

### Full pipeline

```bash
curl -X POST http://localhost:8081/api/design/full \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/myapp",
    "jiraProjectKey": "MYAPP"
  }'
```

### Scope to a sprint

```bash
curl -X POST http://localhost:8081/api/design/full \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/myapp",
    "jiraProjectKey": "MYAPP",
    "jiraSprintId": "Sprint 42"
  }'
```

### Scope to specific issues

```bash
curl -X POST http://localhost:8081/api/design/full \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/myapp",
    "jiraProjectKey": "MYAPP",
    "jiraIssueKeys": ["MYAPP-101", "MYAPP-205"]
  }'
```

### Add architectural context

```bash
curl -X POST http://localhost:8081/api/design/full \
  -H "Content-Type: application/json" \
  -d '{
    "repoPath": "/workspace/myapp",
    "jiraProjectKey": "MYAPP",
    "context": "We follow hexagonal architecture. Prefer event-driven over synchronous calls."
  }'
```

### Step-by-step (multi-turn)

```bash
# 1 — Fetch Jira
curl -X POST http://localhost:8081/api/design/fetch-jira \
  -d '{"jiraProjectKey": "MYAPP"}'

# 2 — Analyse codebase (pass sessionId from step 1)
curl -X POST http://localhost:8081/api/design/analyze \
  -d '{
    "sessionId": "<sessionId>",
    "repoPath": "/workspace/myapp",
    "jiraProjectKey": "MYAPP"
  }'

# 3 — Cross-reference, design, publish — same pattern
```

---

## Response Shape

```json
{
  "sessionId": "3f7a1b2c-...",
  "phase": "FULL",
  "status": "SUCCESS",
  "designDocument": {
    "jiraProjectKey": "MYAPP",
    "repoPath": "/workspace/myapp",
    "designDocPath": "/workspace/myapp/docs/design/design.md",
    "jiraSummary": "...",
    "codebaseSummary": "...",
    "crossRefSummary": "...",
    "updatedJiraTickets": ["MYAPP-1", "MYAPP-5"],
    "completedAt": "2026-05-18T10:23:45Z"
  }
}
```

The design document is written to `{repoPath}/docs/design/design.md` by default.
Override with `outputDir` in the request body.

---

## Skills

Loaded from `.claude/skills/` at startup:

| Skill | Phase |
|---|---|
| `codebase-analyzer` | ANALYZE |
| `pattern-detector` | ANALYZE |
| `design-synthesizer` | DESIGN |
| `jira-publisher` | PUBLISH |

---

## Telemetry

Token usage and cost are tracked per session. View via:

```
GET http://localhost:8081/api/telemetry/history?repoPath=/workspace/myapp
```

Daily budget enforcement is configurable via `AGENT_DAILY_BUDGET` (default `$5.00` on local, `$2.00` on prod).
