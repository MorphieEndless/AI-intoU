FROM python:3.11-slim

WORKDIR /app

# Install server dependencies
COPY requirements-server.txt .
RUN pip install --no-cache-dir -r requirements-server.txt

# Copy server code.
# `server/` is the legacy FastAPI package; since M1/M2a it imports the new
# `app/` package (config, db, domain, schemas), and the data layer needs the
# Alembic environment. All of them must ship in the image, otherwise the
# container fails at import time with `ModuleNotFoundError: No module named 'app'`.
COPY server/ ./server/
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini ./
COPY scripts/ ./scripts/

# Create data directory for SQLite
RUN mkdir -p /data

ENV SB_DB_PATH=/data/signal_bridge.db
ENV SB_PATTERNS_DIR=/data/patterns
ENV SB_HOST=0.0.0.0
ENV SB_PORT=8420

EXPOSE 8420

CMD ["uvicorn", "server.app:app", "--host", "0.0.0.0", "--port", "8420"]
