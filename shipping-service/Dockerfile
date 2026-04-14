# syntax=docker/dockerfile:1
# Multi-arch: linux/amd64, linux/arm64
FROM debian:bookworm-slim AS builder
RUN apt-get update && apt-get install -y --no-install-recommends \
    cmake g++ make git libssl-dev pkg-config ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY CMakeLists.txt main.cpp ./
RUN mkdir -p build && cd build && cmake .. && make -j$(nproc)

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    libssl3 ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1000 appuser
WORKDIR /app
COPY --from=builder /build/build/shipping-service .
EXPOSE 8087
USER appuser
CMD ["./shipping-service"]
