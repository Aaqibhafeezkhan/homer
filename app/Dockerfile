# Multi-stage Dockerfile for Homer
# Stage 1: Build the fat jar
FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS builder

WORKDIR /build

# Install bash for Gradle wrapper, zip to trim the fat jar
RUN apk add --no-cache bash zip

# Build context is the repo root (see docker-compose.yml / DOCKER.md): COPY
# sources are prefixed with app/, but the in-image WORKDIR layout stays the same.
# Copy Gradle wrapper and configuration files
COPY app/gradle ./gradle
COPY app/gradlew ./
COPY app/gradle.properties ./
COPY app/settings.gradle.kts ./
COPY app/buildSrc ./buildSrc

# Copy source code (must copy before running gradle)
COPY app/app ./app
COPY app/agentdebug ./agentdebug
COPY app/homer-runner ./homer-runner
COPY app/schemas ./schemas

# settings.gradle.kts includes :e2e (the black-box suite) and :architecture (the
# report-only rule tests), never built here; empty dirs satisfy Gradle without
# copying their sources.
RUN mkdir -p e2e architecture

# Optional Artifactory credentials as BuildKit secrets -- never ARG/ENV, which
# leak into image history/metadata; see DOCKER.md and root CLAUDE.md §12 rule 7.
# Download dependencies (cached layer)
RUN --mount=type=secret,id=artifactory_url \
    --mount=type=secret,id=artifactory_user \
    --mount=type=secret,id=artifactory_token \
    export ARTIFACTORY_URL="$(cat /run/secrets/artifactory_url 2>/dev/null || true)" && \
    export ARTIFACTORY_USER="$(cat /run/secrets/artifactory_user 2>/dev/null || true)" && \
    export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token 2>/dev/null || true)" && \
    ./gradlew dependencies --no-daemon || true

# Build fat jar
RUN --mount=type=secret,id=artifactory_url \
    --mount=type=secret,id=artifactory_user \
    --mount=type=secret,id=artifactory_token \
    export ARTIFACTORY_URL="$(cat /run/secrets/artifactory_url 2>/dev/null || true)" && \
    export ARTIFACTORY_USER="$(cat /run/secrets/artifactory_user 2>/dev/null || true)" && \
    export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token 2>/dev/null || true)" && \
    ./gradlew shadowJar --no-daemon

# The runtime image is Alpine (musl), where sqlite-jdbc loads only its Linux-Musl
# native library; drop the other platforms' copies. Only the image's jar is musl-only:
# a source build (gradlew shadowJar) keeps every platform. See ADR-0015.
RUN zip -q -d app/build/libs/homer-*-all.jar 'org/sqlite/native/*' -x 'org/sqlite/native/Linux-Musl/*'

# Stage 2: the slim server image (ADR-0094): Homer, a JRE and what the server itself runs:
# sh/bash for local commands and `when:` predicates, su-exec for the entrypoint, curl for the
# compose health check, python3 + homer_langgraph for the server-side LangGraph inspect and state
# calls. No agent toolchain; runs that need one go to a runner that has it.
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0 AS slim

ARG HOMER_VERSION=dev
ENV HOMER_VERSION=$HOMER_VERSION

RUN apk add --no-cache \
    bash \
    curl \
    su-exec \
    python3 \
    py3-pip

# Install the homer_langgraph Python runtime that backs the !<langgraph>
# command type. --break-system-packages installs into the system site-packages
# (PEP 668 refuses a system-wide install otherwise on Alpine); that location is
# importable by the unprivileged homer user at runtime. pydantic-core et al.
# ship musllinux wheels, so no Rust/gcc build toolchain is needed. py3-pip stays:
# its apk dependencies (packaging) satisfy langchain-core's, so removing it breaks the import.
COPY clients/python /tmp/homer_langgraph_src
RUN pip install --no-cache-dir --break-system-packages /tmp/homer_langgraph_src && \
    rm -rf /tmp/homer_langgraph_src

# Create homer group and user (GID/UID 1000, home at /home/homer)
RUN addgroup -g 1000 homer && \
    adduser -u 1000 -G homer -h /home/homer -s /bin/bash -D homer

# Create application directories. /app itself (and /app/config) stay root-owned:
# agents run in-container as homer, and if /app or the root-executed entrypoint
# were homer-writable a homer process could replace the entrypoint and get root
# on the next restart (CWE-732/269). Hand homer only the directories it must
# write at runtime — workspace (runs, SQLite db, logs) and agents (agent CRUD) —
# plus its home.
RUN mkdir -p /app/config /app/workspace /app/agents && \
    chown -R homer:homer /app/workspace /app/agents /home/homer

WORKDIR /app

# Copy the built jar as root: homer only needs to read it, and root ownership
# keeps a homer process from swapping in a different jar that the (root) restart
# would then execute.
COPY --from=builder /build/app/build/libs/homer-*-all.jar /app/homer.jar

# Debug build: conditionally copy agentdebug JAR
ARG HOMER_DEBUG=0
ENV HOMER_DEBUG=$HOMER_DEBUG
RUN --mount=type=bind,from=builder,source=/build/agentdebug/build/libs,target=/tmp/agentdebug-libs \
    if [ "$HOMER_DEBUG" = "1" ]; then \
      cp /tmp/agentdebug-libs/agentdebug-*-all.jar /app/agentdebug.jar; \
    fi

# agentdebug wrapper — always present, errors clearly if JAR absent
RUN printf '#!/bin/sh\nset -e\nif [ ! -f /app/agentdebug.jar ]; then\n  echo "Error: agentdebug not available. Rebuild the container with HOMER_DEBUG=1." >&2\n  exit 1\nfi\nexec su-exec homer java --enable-native-access=ALL-UNNAMED \\\n  -jar /app/agentdebug.jar \\\n  --config "${CONFIG_PATH:-config/config.json}" "$@"\n' > /usr/local/bin/agentdebug && \
    chmod +x /usr/local/bin/agentdebug

# Copy entrypoint script as root: ENTRYPOINT runs it as root, so it must not be
# writable by homer — otherwise a homer process could replace it and run
# arbitrary code as root on the next container start.
COPY --chown=root:root --chmod=755 app/entrypoint.sh /app/entrypoint.sh

EXPOSE 8080

ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV CONFIG_PATH="config/config.json"
# Place api.log beside app.log in the workspace volume (read by logback.xml).
ENV HOMER_LOG_DIR="/app/workspace"

ENTRYPOINT ["/app/entrypoint.sh"]

# Stage 3: the full server image (homer-app): the slim image plus the agent toolchain
# that local !<claude>, !<codex> and shell-command runs use.
FROM slim AS runner

RUN apk add --no-cache \
    git \
    git-lfs \
    nodejs \
    npm \
    openssh-client \
    glab \
    jq \
    unzip

# Install Claude Code CLI, Codex CLI and opencode globally (binaries accessible
# to all users; they back the !<claude>, !<codex> and !<opencode> command types).
# CLAUDE_CODE_VERSION defaults to the latest published version; pin it
# (--build-arg CLAUDE_CODE_VERSION=x.y.z) for reproducible builds and to match
# server.requireClaudeCodeVersion.
ARG CLAUDE_CODE_VERSION=latest
# The Codex and opencode CLIs are pinned (CWE-1357): an unpinned global npm
# install ships every later release of a binary that runs agent prompts with the
# workspace's secrets in its environment. Bump the pins deliberately.
ARG CODEX_VERSION=0.166.0
ARG OPENCODE_VERSION=1.19.5
# The npm cache is cleaned in the same layer: nothing reads it at run time.
RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} @openai/codex@${CODEX_VERSION} opencode-ai@${OPENCODE_VERSION} && \
    npm cache clean --force

# Configure glab for homer user (writes to /home/homer/.config/glab-cli/)
# Every glab command first checks its release host for a newer release; a build host whose route there
# stalls held this step until the CI job's 1-hour timeout. GLAB_CHECK_UPDATE=false skips the check,
# check_update false keeps it off for agents' glab calls too, and timeout bounds the step anyway.
USER homer
RUN GLAB_CHECK_UPDATE=false timeout 60 glab config set check_update false --global \
 && GLAB_CHECK_UPDATE=false timeout 60 glab config set git_protocol https --global
RUN git lfs install

# Switch back to root — entrypoint.sh starts as root to fix volume permissions,
# then drops to homer via su-exec
USER root

# Stage 4: the external runner instance (ADR-0066). Deliberately small: the
# agents it runs decide what else they need; derive from this image to add
# node/claude, python, docker CLIs and so on. util-linux provides setsid so
# killed commands take their whole process group with them (ADR-0041).
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0 AS homer-runner

ARG HOMER_VERSION=dev
ENV HOMER_VERSION=$HOMER_VERSION

RUN apk add --no-cache bash curl git tar gzip util-linux

RUN addgroup -g 1000 homer && \
    adduser -u 1000 -G homer -h /home/homer -s /bin/bash -D homer && \
    mkdir -p /app /var/homer-runner && \
    chown homer:homer /var/homer-runner

# Root-owned jar: the runs execute as homer and must not be able to swap it.
COPY --from=builder /build/homer-runner/build/libs/homer-runner-*-all.jar /app/homer-runner.jar

USER homer
WORKDIR /var/homer-runner

ENV HOMER_RUNNER_ROOT=/var/homer-runner
ENV JAVA_OPTS="-Xmx256m"

# Options come from HOMER_RUNNER_* env vars or the command line:
#   docker run -e HOMER_RUNNER_URL=... -e HOMER_RUNNER_TOKEN=... -e HOMER_RUNNER_TAGS=linux homer-runner
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS --enable-native-access=ALL-UNNAMED -jar /app/homer-runner.jar \"$@\"", "--"]
