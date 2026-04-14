# syntax=docker/dockerfile:1
# Multi-arch: linux/amd64, linux/arm64
FROM python:3.11-slim
RUN groupadd -g 1000 appgroup && useradd -u 1000 -g appgroup -m appuser
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8082
USER appuser
CMD ["python", "app.py"]
