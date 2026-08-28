FROM maven:3.8.6-eclipse-temurin-11 AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY pom.xml .
RUN mvn -B -s .mvn/settings.xml dependency:go-offline
COPY src ./src
RUN mvn -B -s .mvn/settings.xml package -DskipTests

FROM eclipse-temurin:11-jre-alpine
WORKDIR /app
COPY --from=build /app/target/order-service.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
