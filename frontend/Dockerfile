# The web app: built with Node, served by nginx as an unprivileged user.
#   docker build -t commerceflow/web:local frontend
FROM node:22.23.4-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.30.6-alpine
# The base image's Alpine packages, brought up to their patched versions at build time (Trivy:
# brotli, curl and expat had fixed HIGH and CRITICAL vulnerabilities in the published image).
RUN apk upgrade --no-cache
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY nginx/upstream.conf.template /etc/nginx/templates/upstream.conf.template
# The template above is rendered into /tmp at start, the one writable place (read-only root on
# Kubernetes). The DNS server comes from /etc/resolv.conf; the gateway address is overridable.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_OUTPUT_DIR=/tmp \
    GATEWAY_ADDRESS=api-gateway:8080
COPY --from=build /app/dist /usr/share/nginx/html
# The default site config would listen on 80, which an unprivileged user cannot.
RUN rm -f /etc/nginx/conf.d/default.conf
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
