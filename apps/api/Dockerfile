FROM maven:3.9.9-eclipse-temurin-22 AS build

WORKDIR /app

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:22-jre

WORKDIR /app

ENV TZ=Asia/Shanghai

RUN mkdir -p /app/uploads

COPY --from=build /app/target/*.jar /app/app.jar

EXPOSE 8083

CMD ["java", "-jar", "/app/app.jar"]
