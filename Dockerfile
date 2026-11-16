# ReconFlow image (M11.1): one image for every role. SPRING_PROFILES_ACTIVE picks what a container
# does (prod,api / prod,worker / prod,migrate); nothing environment-specific is baked in.
#
#   docker build -t reconflow:local .
#   docker run --rm --entrypoint id reconflow:local     # uid=10001(reconflow)
#
# Needs BuildKit (Docker Desktop has it; on Colima install the docker-buildx plugin, README).
# Base images are pinned by digest: the tag says what it is, the digest makes it immutable.
# Dependabot's docker ecosystem proposes the bumps (.github/dependabot.yml).

# ---- build stage ------------------------------------------------------------------------------
FROM eclipse-temurin:25.0.5_8-jdk-alpine@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS build
WORKDIR /workspace

# Dependencies first: this layer is reused until pom.xml (or the wrapper) changes, so a code change
# doesn't re-download Maven Central. The cache mount keeps ~/.m2 between builds on one machine
# without putting it in any layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -ntp dependency:go-offline

COPY src/main/ src/main/
# Tests ran in CI (./mvnw verify, Testcontainers); running them here would need Docker-in-Docker.
# maven.test.skip also skips compiling them (src/test isn't in the build context anyway).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -ntp package -Dmaven.test.skip=true -Djacoco.skip=true \
 && cp target/*.jar app.jar

# Split the fat jar into directories that change at different rates (Boot 3.3+ "tools" jar mode).
# --launcher keeps the loader, so the result starts with JarLauncher and its classpath index.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- runtime stage ----------------------------------------------------------------------------
# JRE only, on Alpine: no compiler, no Maven, no source, and a much smaller OS than Ubuntu (fewer
# packages to patch). Busybox's wget serves the HEALTHCHECK, so no curl is installed.
FROM eclipse-temurin:25.0.5_8-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918 AS runtime

LABEL org.opencontainers.image.title="reconflow" \
      org.opencontainers.image.description="ReconFlow payment reconciliation (api, worker and migrate roles)" \
      org.opencontainers.image.source="https://github.com/kasalmanfarcy/PaymentReconciliation" \
      org.opencontainers.image.licenses="Apache-2.0"

# A fixed numeric uid/gid, no home directory, no login shell.
RUN addgroup -S -g 10001 reconflow \
 && adduser -S -D -H -u 10001 -G reconflow -s /sbin/nologin reconflow

WORKDIR /app
# Least to most frequently changing: a code-only change re-ships only the last (small) layer.
COPY --from=build /workspace/extracted/dependencies/          ./
COPY --from=build /workspace/extracted/spring-boot-loader/    ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/           ./

# Numeric, so platforms that enforce non-root (Kubernetes runAsNonRoot) can check it without
# reading /etc/passwd. The app files stay root-owned: the process can read them, not change them.
USER 10001:10001

# Heap from the container's memory limit, never a baked-in -Xmx (the right value depends on where
# it runs; override the whole variable to change it). 75% leaves room for metaspace, thread stacks,
# direct buffers and the code cache. A JVM that hit OutOfMemoryError is in an unknown state: exit
# and let the platform restart it. (No java.security.egd: since Java 9 SecureRandom no longer
# blocks on /dev/random.)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# 8080 public API; 8081 management (probes, /actuator/prometheus), never published.
EXPOSE 8080 8081

# Liveness, not readiness: a database outage must not make Docker call the container unhealthy and
# restart it (plans/phase-M11 §2.6). Follows MANAGEMENT_PORT when a platform moves management onto
# the public port. The migrate role exits before the first check.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=4 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${MANAGEMENT_PORT:-8081}/actuator/health/liveness" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
