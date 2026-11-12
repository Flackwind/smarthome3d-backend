FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /source
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system sh3d && useradd --system --gid sh3d sh3d && mkdir /app/data && chown sh3d:sh3d /app/data
COPY --from=build /source/target/smart-home-3d-backend-0.1.0.jar /app/api.jar
USER sh3d
ENV SH3D_BIND=0.0.0.0
EXPOSE 8090
VOLUME /app/data
ENTRYPOINT ["java","-jar","/app/api.jar"]
