FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV STEPTRACKER_BIND_HOST=0.0.0.0
ENV SESSION_COOKIE_SECURE=true
COPY --from=build /workspace/target/classes /app/classes
COPY --from=build /workspace/target/dependency /app/lib
EXPOSE 8080
ENTRYPOINT ["java", "--add-modules", "jdk.httpserver", "-cp", "/app/classes:/app/lib/*", "StepTrackerService"]
