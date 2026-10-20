FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
COPY examples examples
RUN mvn -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/sql-demo.jar /app/sql-demo.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/sql-demo.jar", "8080", "0.0.0.0"]
