"""Legacy alias of `app.config` — kept only until the legacy package is gone.

Configuration now lives in `app/config.py`: one source, validated at
startup by pydantic-settings. This module re-exports the same names with
the same values so the legacy `server/*` modules keep working while they
are migrated out (M3/M4). New code imports `app.config` directly.

Nothing here reads the environment itself — a second reader of `SB_*`
would be a second source of truth (architecture doc §1.2).

Two names differ on purpose:
  * `HEARTBEAT_INTERVAL_S` / `HEARTBEAT_TIMEOUT_S` are the legacy spellings
    of `settings.HEARTBEAT_INTERVAL` / `settings.HEARTBEAT_TIMEOUT`;
  * `CORS_ORIGINS` is the legacy list, not the raw comma-separated string.
"""
from __future__ import annotations

from app.config import (  # noqa: F401 — re-exported for legacy callers
    DEFAULT_DB_PATH,
    DEFAULT_PATTERNS_DIR,
    settings,
    validate,
)

# ── Server ──────────────────────────────────────────────────────────────
HOST = settings.HOST
PORT = settings.PORT
SECRET_KEY = settings.SECRET_KEY
CORS_ORIGINS = settings.cors_origin_list()

# ── Auth ────────────────────────────────────────────────────────────────
SESSION_TOKEN_TTL_HOURS = settings.SESSION_TOKEN_TTL_HOURS
REGISTRATION_OPEN = settings.REGISTRATION_OPEN

# ── Rate limiting ───────────────────────────────────────────────────────
RATE_LIMIT_AUTH = settings.RATE_LIMIT_AUTH
RATE_LIMIT_COMMANDS = settings.RATE_LIMIT_COMMANDS
RATE_LIMIT_GLOBAL = settings.RATE_LIMIT_GLOBAL
MAX_WS_PER_IP = settings.MAX_WS_PER_IP
BAN_THRESHOLD = settings.BAN_THRESHOLD
BAN_DURATION_MINUTES = settings.BAN_DURATION_MINUTES

# ── Safety ──────────────────────────────────────────────────────────────
HEARTBEAT_INTERVAL_S = settings.HEARTBEAT_INTERVAL
HEARTBEAT_TIMEOUT_S = settings.HEARTBEAT_TIMEOUT

# ── Governor ────────────────────────────────────────────────────────────
GOVERNOR_ENABLED = settings.GOVERNOR_ENABLED
GOVERNOR_HEAT_RATE = settings.GOVERNOR_HEAT_RATE
GOVERNOR_COOL_RATE = settings.GOVERNOR_COOL_RATE
GOVERNOR_COOLDOWN_THRESHOLD = settings.GOVERNOR_COOLDOWN_THRESHOLD
GOVERNOR_COOLDOWN_EXIT = settings.GOVERNOR_COOLDOWN_EXIT
GOVERNOR_COOLDOWN_DURATION = settings.GOVERNOR_COOLDOWN_DURATION

# ── Pattern library ─────────────────────────────────────────────────────
PATTERNS_DIR = settings.PATTERNS_DIR

# ── Database ────────────────────────────────────────────────────────────
DB_PATH = settings.DB_PATH
