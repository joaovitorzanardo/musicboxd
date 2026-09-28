# syntax=docker/dockerfile:1
#
# Built with the repo root as build context (see deploy/docker-compose.yml),
# so paths below are rooted at the repo, not at web/.

# --- Build stage ---
FROM node:24-alpine AS build
WORKDIR /app

COPY web/package.json web/package-lock.json* ./
RUN npm ci

COPY web/ ./
RUN npm run build

# --- Serve stage: nginx serves the built static assets and proxies /api/ ---
FROM nginx:1.27-alpine AS serve

COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
