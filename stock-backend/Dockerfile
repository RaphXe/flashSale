FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY src ./src

RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/target/stock-backend-0.0.1-SNAPSHOT.jar /app/stock-backend.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "stock-backend.jar"]
