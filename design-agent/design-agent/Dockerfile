# ============================================================================
# Design Agent — Dockerfile
# Target: Render free tier (512MB RAM)
#
# Build:
#   mvn clean package -DskipTests
#   docker build -t design-agent .
#
# Run locally:
#   docker run -p 8081:8081 \
#     -e ANTHROPIC_API_KEY=sk-ant-... \
#     -e OPENAI_API_KEY=sk-... \
#     -e SPRING_PROFILES_ACTIVE=prod \
#     design-agent
#
# MCP/Jira note:
#   MCP is disabled in the prod profile (MCP_ENABLED=false).
#   To enable it, add Node.js 20 via the commented block below
#   and set MCP_ENABLED=true in your env vars.
# ============================================================================

FROM eclipse-temurin:21-jre-jammy

# Node.js 20 — required for GitHub MCP server via npx
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Non-root user for security
RUN groupadd -r agent && useradd -r -g agent agent

# Persistent data directory for SQLite (mount a Render Disk here to survive redeploys)
RUN mkdir -p /data && chown agent:agent /data

WORKDIR /app

COPY target/design-agent-1.0.0-SNAPSHOT.jar app.jar
RUN chown agent:agent app.jar

USER agent

# Render sets PORT automatically; application.yml reads ${PORT:8081}
EXPOSE 8081

# JVM tuned for 512MB Render free tier:
#   -Xmx300m          heap ceiling
#   -Xss256k          smaller thread stacks
#   -XX:MaxMetaspaceSize=128m  cap metaspace
#   +UseContainerSupport       respect cgroup memory limits
ENV JAVA_OPTS="-Xmx300m -Xss256k -XX:MaxMetaspaceSize=128m -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dspring.profiles.active=prod -jar app.jar"]
