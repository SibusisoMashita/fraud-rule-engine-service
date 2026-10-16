FROM eclipse-temurin:21-jdk@sha256:f9bf7e9fe2f9bc4657537c1a7e20c768f0c3bafca41598263be4714783dd62eb AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/main src/main
COPY config config
COPY api api
RUN ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre@sha256:ccbad9e7cb84c27092b12c40a4143e5733e050db9c3784d84f632dcf423fb2ef
# Remove after the pinned Temurin base includes the Ubuntu curl security fix.
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl=8.18.0-1ubuntu2.8 libcurl4t64=8.18.0-1ubuntu2.8 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/fraud-rule-engine-service-*.jar /app/service.jar
COPY --from=build /build/target/sbom/application.cdx.json /app/bom.json
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/service.jar"]
