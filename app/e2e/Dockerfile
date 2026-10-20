# Test-runner image for the black-box E2E suite. Build context = repo root
# (same as app/Dockerfile). The Gradle distribution and the :e2e dependency
# graph are warmed into their own layer — before the test sources are
# copied — so editing a test only recompiles, it never re-downloads.
FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151

RUN apk add --no-cache bash

WORKDIR /e2e

COPY app/gradle ./gradle
COPY app/gradlew app/gradle.properties app/settings.gradle.kts ./
COPY app/buildSrc ./buildSrc
COPY app/e2e/build.gradle.kts ./e2e/build.gradle.kts

# settings.gradle.kts includes :app, :agentdebug, :homer-runner and :architecture; empty dirs
# satisfy Gradle without copying (or building) the backend sources into this
# image. :e2e has no sources yet either — this run only warms the distro,
# plugins, and deps (`|| true`: a warm-up miss is caught by the real
# testClasses run below).
# Optional Artifactory credentials as BuildKit secrets; see app/Dockerfile.
RUN --mount=type=secret,id=artifactory_url \
    --mount=type=secret,id=artifactory_user \
    --mount=type=secret,id=artifactory_token \
    mkdir -p app agentdebug homer-runner architecture && \
    export ARTIFACTORY_URL="$(cat /run/secrets/artifactory_url 2>/dev/null || true)" && \
    export ARTIFACTORY_USER="$(cat /run/secrets/artifactory_user 2>/dev/null || true)" && \
    export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token 2>/dev/null || true)" && \
    (./gradlew :e2e:dependencies :e2e:testClasses --no-daemon || true)

COPY app/e2e ./e2e

RUN --mount=type=secret,id=artifactory_url \
    --mount=type=secret,id=artifactory_user \
    --mount=type=secret,id=artifactory_token \
    export ARTIFACTORY_URL="$(cat /run/secrets/artifactory_url 2>/dev/null || true)" && \
    export ARTIFACTORY_USER="$(cat /run/secrets/artifactory_user 2>/dev/null || true)" && \
    export ARTIFACTORY_TOKEN="$(cat /run/secrets/artifactory_token 2>/dev/null || true)" && \
    ./gradlew :e2e:testClasses --no-daemon

ENTRYPOINT ["./gradlew", ":e2e:test", "--no-daemon"]
