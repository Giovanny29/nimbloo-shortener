# ---------- 1/3 · Frontend (Vite + React + TypeScript) ----------
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- 2/3 · Backend (Spring Boot) ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests

# ---------- 3/3 · Runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY --from=frontend /app/frontend/dist /app/static
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/,file:/app/static/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]