# syntax=docker/dockerfile:1
# Multi-arch: linux/amd64, linux/arm64
FROM ruby:3.2-slim
RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY Gemfile Gemfile.lock* ./
# Odigos injects Ruby deps under /var/odigos/... including Bundler 4.x; the ruby:3.2 image ships Bundler 2.4.x.
# Align Bundler in the image with that layout or `bundle exec` hits Bundler::CorruptBundlerInstallError at runtime.
RUN gem install bundler -v 4.0.6 --no-document && \
    bundle _4.0.6_ config set --local without 'development test' && \
    bundle _4.0.6_ install --jobs 4
COPY . .
ENV PATH="/usr/local/bundle/bin:${PATH}"
EXPOSE 8083
# Rack + Puma for Rack/Sinatra OTel hooks. Do NOT use `bundle exec` at runtime: Odigos mounts gems under
# /var/odigos/... and running the `bundle` executable mixes two Bundler installs → Thor "superclass mismatch".
CMD ["ruby", "-S", "puma", "-b", "tcp://0.0.0.0:8083", "config.ru"]
