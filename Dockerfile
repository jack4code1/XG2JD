ARG IMAGE_REGISTRY=docker.m.daocloud.io

FROM ${IMAGE_REGISTRY}/library/node:22-alpine AS frontend-build

WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM ${IMAGE_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS backend-build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY . ./
COPY --from=frontend-build /workspace/src/main/resources/static/ ./src/main/resources/static/
RUN mvn -q -DskipTests package

FROM ${IMAGE_REGISTRY}/library/eclipse-temurin:17-jre

WORKDIR /app
COPY --from=backend-build /workspace/target/seckill-coupon-1.0.0.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
