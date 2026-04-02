FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY seckill/pom.xml seckill/pom.xml

RUN mvn -B -DskipTests -f seckill/pom.xml dependency:go-offline

COPY seckill/src seckill/src

RUN mvn -B -DskipTests -f seckill/pom.xml clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/seckill/target/seckill-0.0.1-SNAPSHOT.jar /app/seckill-service.jar

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "seckill-service.jar"]
