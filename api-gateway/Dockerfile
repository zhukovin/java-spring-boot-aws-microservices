# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Build context is the project root so mvnw and all module POMs are accessible.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy wrapper and all POMs first — enables Docker layer caching for deps
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY common/pom.xml           common/pom.xml
COPY order-service/pom.xml    order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml  payment-service/pom.xml
COPY api-gateway/pom.xml      api-gateway/pom.xml

RUN chmod +x mvnw
# Resolve dependencies (cached layer; invalidated only when a POM changes)
RUN ./mvnw dependency:go-offline -pl api-gateway -am -q

# Copy source and build
COPY common/src      common/src
COPY api-gateway/src api-gateway/src
RUN ./mvnw package -pl api-gateway -am -DskipTests -q

# ── Stage 2: Extract Spring Boot layers ───────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS extractor
WORKDIR /workspace
COPY --from=builder /workspace/api-gateway/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ── Stage 3: Minimal runtime image ────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Non-root user for security
RUN addgroup -S orderflow && adduser -S orderflow -G orderflow
WORKDIR /app

# Copy layers from most stable (deps) to least stable (app code)
COPY --from=extractor /workspace/dependencies/          ./
COPY --from=extractor /workspace/spring-boot-loader/    ./
COPY --from=extractor /workspace/snapshot-dependencies/ ./
COPY --from=extractor /workspace/application/           ./

USER orderflow
EXPOSE 8080

# ZGC (generational) for low pause times; container-aware heap sizing
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational \
    -XX:+UseContainerSupport \
    -Xms256m -Xmx512m \
    -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
