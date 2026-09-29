# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY src ./src
RUN --mount=type=cache,id=ai-search-food-maven,target=/root/.m2 \
    mvn -B -Dmaven.test.skip=true compile jar:jar spring-boot:repackage

FROM eclipse-temurin:17.0.13_11-jre-jammy

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app/data/finished-dish-reviews \
    && chown -R app:app /app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/ai-smart-recipe-backend-0.0.1-SNAPSHOT.jar /app/app.jar
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh \
    && chmod 755 /usr/local/bin/docker-entrypoint.sh

USER root
EXPOSE 7068
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:7068/actuator/health > /dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
