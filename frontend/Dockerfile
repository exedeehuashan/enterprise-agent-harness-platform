# Build the static console, then serve it with nginx, which also proxies /api to the backend.
FROM node:22.11.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27.3-alpine
ENV EAHP_BACKEND_UPSTREAM=http://eahp-backend:8080
# An identity provider's origin for single sign-on (P46), allowed for the console's fetches; empty when SSO is off.
ENV EAHP_CSP_CONNECT_EXTRA=""
ENV NGINX_ENVSUBST_FILTER=^EAHP_
COPY nginx.conf /etc/nginx/templates/default.conf.template
# Rendered to /etc/nginx/conf.d/security-headers.inc (not *.conf, so it is only included where nginx.conf says).
COPY security-headers.conf /etc/nginx/templates/security-headers.inc.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
