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
COPY --from=build /build/target/cash-flows.jar cash-flows.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar cash-flows.jar"]
