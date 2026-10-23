# Build the static console, then serve it with nginx, which also proxies /api to the backend.
FROM node:22.11.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27.3-alpine
ENV EAHP_BACKEND_UPSTREAM=http://eahp-backend:8080
ENV NGINX_ENVSUBST_FILTER=^EAHP_
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
