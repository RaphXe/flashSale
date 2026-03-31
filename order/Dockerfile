FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY order/pom.xml order/pom.xml
COPY order/src order/src

RUN mvn -B -DskipTests -f order/pom.xml clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/order/target/order-0.0.1-SNAPSHOT.jar /app/order-service.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "order-service.jar"]
