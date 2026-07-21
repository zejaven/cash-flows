FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
COPY frontend ./frontend
# frontend-maven-plugin builds the SPA inside mvn package
RUN mvn clean package -q -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# Statement parser runs as a python subprocess (see import.parser.* properties)
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt parse_statement.py /app/
RUN pip3 install --no-cache-dir --break-system-packages -r /app/requirements.txt
COPY --from=build /build/target/cash-flows.jar cash-flows.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar cash-flows.jar"]
