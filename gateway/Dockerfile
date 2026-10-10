# syntax=docker/dockerfile:1
# Build context is the repository root; the root .dockerignore keeps secrets*.env out.

FROM node:24-alpine AS frontend
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.30.5-alpine
RUN rm -r /etc/nginx/conf.d/default.conf /usr/share/nginx/html/*
COPY gateway/nginx.conf /etc/nginx/nginx.conf
COPY gateway/proxy_headers.conf /etc/nginx/proxy_headers.conf
COPY --from=frontend /src/out/ /usr/share/nginx/html/
EXPOSE 8080
