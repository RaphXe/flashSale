FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY goods/pom.xml goods/pom.xml
COPY goods/src goods/src

RUN mvn -B -DskipTests -f goods/pom.xml clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/goods/target/goods-0.0.1-SNAPSHOT.jar /app/goods-service.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "goods-service.jar"]
