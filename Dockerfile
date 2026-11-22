# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# Railway requires cache mount ids prefixed s/<service id>- (here the pharmacy-core-test api service);
# any other BuildKit treats the id as an ordinary cache name.
RUN --mount=type=cache,id=s/7cfd8ac3-f354-4387-8431-f16d28f68c5e-/root/.m2,target=/root/.m2 \
    mvn -B -q -pl application/pharmacy-core -am package -DskipTests -Dspotless.check.skip=true \
 && cp application/pharmacy-core/target/pharmacy-core-*-exec.jar /src/app.jar

# ---- extract layers (Spring Boot 4 jar tools) ----
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /layers
COPY --from=build /src/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S pharmacy && adduser -S pharmacy -G pharmacy \
 && apk add --no-cache wget
WORKDIR /app
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/dependencies/ ./
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=pharmacy:pharmacy /layers/extracted/application/ ./
USER pharmacy
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
