# syntax=docker/dockerfile:1
# Build context is api/ only, so nothing outside this directory (secrets.env included) is visible.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -DskipTests package && cp target/api-*.jar /app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
EXPOSE 8080
# A fixed heap, so the decode memory budget (2 images at once, at most 32 MP each) is the
# same locally and on the 4 GB server, instead of 25% of whatever memory the host has.
ENTRYPOINT ["java", "-Xmx1g", "-jar", "/app/app.jar"]
