FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY gateway-api/pom.xml gateway-api/pom.xml
COPY gateway-api/src gateway-api/src

RUN mvn -B -DskipTests -f gateway-api/pom.xml clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/gateway-api/target/gateway-service-0.0.1-SNAPSHOT.jar /app/gateway-service.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "gateway-service.jar"]