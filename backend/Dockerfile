# StrategyForge backend runtime image.
# Build the jar first: ./gradlew bootJar  (see docs/RUNBOOK.md)
# Base image pinned by digest (NFR-011): eclipse-temurin 21.0.12.1 JRE on Alpine.
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555

RUN addgroup -S strategyforge && adduser -S -G strategyforge -H -s /sbin/nologin strategyforge \
 && mkdir -p /opt/strategyforge /var/lib/strategyforge/backups \
 && chown -R strategyforge:strategyforge /var/lib/strategyforge

COPY --chown=root:root build/libs/strategyforge-backend.jar /opt/strategyforge/app.jar

USER strategyforge
WORKDIR /opt/strategyforge
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8"
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/opt/strategyforge/app.jar"]
