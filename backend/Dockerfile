# syntax=docker/dockerfile:1
# Backend image. git and the Docker CLI are needed by the QA suite runner (方案 4); the Docker daemon is
# the host's, reached through a mounted socket only when the deployment opts in.
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package

FROM docker:27.3.1-cli AS docker-cli

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /src/target/agent-harness-backend-0.0.1-SNAPSHOT.jar /app/app.jar
# A new named volume mounted here inherits this ownership, so the QA work directory is writable without root.
RUN mkdir -p /var/lib/eahp-qa && chown 10001:10001 /var/lib/eahp-qa
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
