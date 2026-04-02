FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY stock/pom.xml stock/pom.xml

RUN mvn -B -DskipTests -f stock/pom.xml dependency:go-offline

COPY stock/src stock/src

RUN mvn -B -DskipTests -f stock/pom.xml clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/stock/target/stock-0.0.1-SNAPSHOT.jar /app/stock-service.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "stock-service.jar"]
