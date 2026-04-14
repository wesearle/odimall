# syntax=docker/dockerfile:1
# Multi-arch: linux/amd64, linux/arm64
FROM golang:1.21-alpine AS builder
RUN apk add --no-cache ca-certificates
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o inventory-service .

FROM alpine:3.19
RUN apk add --no-cache ca-certificates && adduser -D -u 1000 appuser
WORKDIR /app
COPY --from=builder /build/inventory-service .
EXPOSE 8088
USER appuser
ENTRYPOINT ["./inventory-service"]
