# syntax=docker/dockerfile:1
# Base images pinned by digest (OPS-16); Dependabot proposes updates.

# ---- build ----
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /src
COPY . .
# Railway requires cache mount ids prefixed s/<service id>- (here the pharmacy-core-test api service);
# any other BuildKit treats the id as an ordinary cache name.
RUN --mount=type=cache,id=s/7cfd8ac3-f354-4387-8431-f16d28f68c5e-/root/.m2,target=/root/.m2 \
    mvn -B -q -pl application/pharmacy-core -am package -DskipTests -Dspotless.check.skip=true \
 && cp application/pharmacy-core/target/pharmacy-core-*-exec.jar /src/app.jar

# ---- extract layers (Spring Boot 4 jar tools) ----
FROM eclipse-temurin:25-jre-alpine@sha256:69c2a26e9894016e274c880fa542956d005b3755f84db765145185e87405bd89 AS layers
WORKDIR /layers
COPY --from=build /src/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- runtime ----
FROM eclipse-temurin:25-jre-alpine@sha256:69c2a26e9894016e274c880fa542956d005b3755f84db765145185e87405bd89
RUN addgroup -S pharmacy && adduser -S pharmacy -G pharmacy \
 && apk add --no-cache wget
WORKDIR /app
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/dependencies/ ./
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/application/ ./
USER pharmacy
# Exports and Tomcat write to the system temp directory (/tmp). No VOLUME here: Railway refuses
# images that declare one. On ECS the API runs without a read-only root filesystem for now.
# Out of memory, the JVM exits rather than limping on half-broken, so the platform restarts it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/livez || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
