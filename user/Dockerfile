FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY user/pom.xml user/pom.xml

RUN mvn -B -DskipTests -f user/pom.xml dependency:go-offline

COPY user/src user/src

RUN mvn -B -DskipTests -f user/pom.xml clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/user/target/user-service-0.0.1-SNAPSHOT.jar /app/user-service.jar

ENV SPRING_THREADS_VIRTUAL_ENABLED=true

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "user-service.jar"]
