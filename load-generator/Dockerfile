FROM golang:1.21-alpine AS builder
WORKDIR /app
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o load-generator .

FROM alpine:3.19
RUN apk --no-cache add ca-certificates
COPY --from=builder /app/load-generator /load-generator
CMD ["/load-generator"]
