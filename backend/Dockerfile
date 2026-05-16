# syntax=docker/dockerfile:1.7

# Stage 1: build
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B clean package -DskipTests -Dmaven.test.skip=true \
 && mkdir -p target/extracted \
 && cp target/*.jar target/app.jar

# Stage 2: runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 10001 --user-group --home-dir /app --shell /usr/sbin/nologin --no-create-home workhub
COPY --from=builder /workspace/target/app.jar /app/app.jar
RUN chown -R workhub:workhub /app
USER workhub
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
