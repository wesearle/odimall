# syntax=docker/dockerfile:1
# Multi-arch: linux/amd64, linux/arm64
FROM ruby:3.2-slim
RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY Gemfile Gemfile.lock* ./
RUN bundle config set --local without 'development test' && bundle install --jobs 4
COPY . .
EXPOSE 8083
CMD ["ruby", "app.rb"]
