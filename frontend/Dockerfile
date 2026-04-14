# syntax=docker/dockerfile:1
# Multi-arch: linux/amd64, linux/arm64
FROM node:20-alpine AS base
RUN apk add --no-cache dumb-init
WORKDIR /app

# Production dependencies
FROM base AS deps
COPY package.json ./
RUN npm install --omit=dev

# Runtime
FROM base AS runtime
ENV NODE_ENV=production
ENV PORT=8080
COPY --from=deps /app/node_modules ./node_modules
COPY . .
EXPOSE 8080
USER node
ENTRYPOINT ["dumb-init", "--"]
CMD ["node", "server.js"]
