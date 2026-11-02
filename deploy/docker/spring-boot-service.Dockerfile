# One image recipe for every Spring Boot service. Build the jar first (scripts/dev-up.sh --apps
# does this), then: docker build -f deploy/docker/spring-boot-service.Dockerfile \
#   --build-arg SERVICE=identity-service .
#
# Layered: dependencies change rarely and sit in their own layers, so a code change rebuilds and
# pushes only the small application layer.

FROM eclipse-temurin:21.0.13_9-jre-noble AS extract
ARG SERVICE
WORKDIR /build
COPY services/${SERVICE}/target/${SERVICE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21.0.13_9-jre-noble
# A dedicated unprivileged user; the process never runs as root.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER 10001:10001
# Size the heap from the container's memory limit, and die cleanly on OOM so the orchestrator
# restarts the container instead of leaving a half-alive JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
