FROM openjdk:21-jre-slim

WORKDIR /app

COPY target/stock-backend-0.0.1-SNAPSHOT.jar /app/stock-backend.jar

EXPOSE 8081

ENTRYPOINT["java", "-jar", "stock-backend.jar"]