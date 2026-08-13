FROM eclipse-temurin:25-jdk AS build
RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace/backend
COPY backend/.mvn/ .mvn/
COPY --chmod=755 backend/mvnw backend/pom.xml ./
RUN ./mvnw -B -DskipTests dependency:go-offline
COPY backend/src/ src/
COPY contracts/fixtures/ /workspace/contracts/fixtures/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app \
    && mkdir /data \
    && chown app /data
WORKDIR /app
COPY --from=build /workspace/backend/target/scenery-foundry-backend-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
