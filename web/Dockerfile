# syntax=docker/dockerfile:1
#
# Built with the repo root as build context (see deploy/docker-compose.yml),
# so paths below are rooted at the repo, not at web/.

# --- OpenAPI stage: boots the api to dump its OpenAPI contract ---
# The web build generates its TypeScript client from api/build/openapi.json,
# which is a Gradle build artifact and is not in the repo.
FROM eclipse-temurin:25-jdk AS openapi
WORKDIR /repo/api

COPY api/gradlew gradlew
COPY api/gradle gradle
COPY api/settings.gradle.kts api/build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --version

COPY api/src src
RUN ./gradlew generateOpenApiDocs --no-daemon

# --- Build stage ---
FROM node:24-alpine AS build
# Mirrors the repo layout: the client generator reads ../../../api/build/openapi.json
# relative to web/tools/openapi-ts-gen.
WORKDIR /repo/web

COPY web/package.json web/package-lock.json* ./
RUN npm ci

# The client generator is its own npm project, with its own dependencies.
COPY web/tools/openapi-ts-gen/package.json web/tools/openapi-ts-gen/package-lock.json* tools/openapi-ts-gen/
RUN npm --prefix tools/openapi-ts-gen ci

COPY --from=openapi /repo/api/build/openapi.json /repo/api/build/openapi.json

COPY web/ ./
RUN npm run build

# --- Serve stage: nginx serves the built static assets and proxies /api/ ---
FROM nginx:1.27-alpine AS serve

COPY --from=build /repo/web/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
