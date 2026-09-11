# Multi-stage Dockerfile for DayForge API
# Security best practices: non-root user, minimal base image, no build deps in runtime

# ==============================================================================
# Builder stage - install dependencies
# ==============================================================================
FROM python:3.12-slim AS builder

WORKDIR /app

# Install build dependencies (gcc for bcrypt)
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Install the pinned package manager and exact locked dependency set.
RUN pip install --no-cache-dir uv==0.12.13
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

# ==============================================================================
# Runtime stage - run application
# ==============================================================================
FROM python:3.12-slim

WORKDIR /app

# Create non-root user for security
RUN useradd --create-home --shell /bin/bash app

# Copy the locked virtual environment from the builder stage.
COPY --from=builder /app/.venv /app/.venv

# Copy application code
COPY src ./src
COPY alembic ./alembic
COPY alembic.ini ./alembic.ini

# The default app UID/GID is 1000. Compose can override the runtime IDs for NAS hosts.
RUN mkdir -p /app/data && chown -R app:app /app /home/app

# Set environment variables
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONPATH=/app \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Change to non-root user
USER app

# Expose port
EXPOSE 8000

# Apply migrations before starting one SQLite writer process.
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn src.main:app --host 0.0.0.0 --port 8000 --workers 1"]
