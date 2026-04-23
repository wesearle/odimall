# syntax=docker/dockerfile:1
# Multi-arch: linux/amd64, linux/arm64
FROM node:20-alpine AS base
RUN apk add --no-cache dumb-init
WORKDIR /app

# Production dependencies
FROM base AS deps
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# Browser bundle for Grafana Faro (optional at runtime via RUM_ENABLED)
FROM base AS rum-bundle
COPY package.json package-lock.json ./
RUN npm ci
COPY rum-entry.js ./
RUN mkdir -p public && npx esbuild rum-entry.js --bundle --minify --outfile=public/rum.bundle.js --platform=browser

# Runtime
FROM base AS runtime
ENV NODE_ENV=production
ENV PORT=8080
COPY --from=deps /app/node_modules ./node_modules
COPY . .
COPY --from=rum-bundle /app/public/rum.bundle.js ./public/rum.bundle.js
EXPOSE 8080
USER node
ENTRYPOINT ["dumb-init", "--"]
CMD ["node", "server.js"]
