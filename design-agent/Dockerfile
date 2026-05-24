# ============================================================================
# Design Agent — Multi-stage Dockerfile
# Render root directory: design-agent/
# Target: Render free tier (512MB RAM)
#
# Stage 1 (builder): Maven + JDK 21 compiles the fat JAR
# Stage 2 (runtime): JRE 21 + Node.js 20 runs it
# ============================================================================

# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Cache Maven dependencies separately from source for faster rebuilds
COPY design-agent/pom.xml .
RUN mvn dependency:go-offline -q

COPY design-agent/src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
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

COPY --from=builder /build/target/design-agent-1.0.0-SNAPSHOT.jar app.jar
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
