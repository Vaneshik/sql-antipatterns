FROM maven:3.9-eclipse-temurin-21 AS build
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/analysis
ENV PATH="/opt/analysis/bin:${PATH}"
WORKDIR /build
COPY pom.xml .
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY src src
COPY examples examples
RUN mvn -B package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/analysis
ENV PATH="/opt/analysis/bin:${PATH}"
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --from=build /build/target/sql-demo.jar /app/sql-demo.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/sql-demo.jar", "8080", "0.0.0.0"]
