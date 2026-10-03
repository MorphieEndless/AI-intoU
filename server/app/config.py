"""Central configuration — the single source of truth (architecture doc §3).

Read from environment variables / `.env` through pydantic-settings, so an
invalid deployment refuses to start instead of failing on the first
request. Every field maps to `SB_<FIELD>` in the environment, exactly as
the legacy `server.config` module did: no variable was renamed or
reinterpreted. `server.config` is now a thin alias over this module and
disappears together with the legacy package.

Two deliberate differences from the pre-M2a module, both documented in the
decision log:
  * invalid values raise at startup instead of silently falling back to a
    default (that is the point of centralised validation);
  * the dev-only default DB path is `server/signal_bridge.db`, matching
    `app/db.py`, `alembic/env.py` and `scripts/migrate_legacy.py`. Any real
    deployment sets `SB_DB_PATH` explicitly (Dockerfile, .env, compose).
"""
from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent  # server/
DEFAULT_DB_PATH = str(BASE_DIR / "signal_bridge.db")
DEFAULT_PATTERNS_DIR = str(BASE_DIR / "server" / "data" / "patterns")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="SB_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=True,
    )

    # ── Server ──────────────────────────────────────────────────────────
    HOST: str = "0.0.0.0"
    PORT: int = 8420
    SECRET_KEY: str = ""  # MUST be set in production
    CORS_ORIGINS: str = "*"  # comma-separated; see cors_origin_list()

    # ── Auth ────────────────────────────────────────────────────────────
    # Session (login) lifetime. M2 shortens this to the "short-lived
    # session credential" of 02-architecture.md §5.
    SESSION_TOKEN_TTL_HOURS: int = 24
    # Legacy knob kept until M2b removes it together with the mechanism.
    TOKEN_EXPIRY_HOURS: int = 168
    REGISTRATION_OPEN: bool = True
    REQUIRE_MCP_AUTH: bool = False
    # Deprecated static bearer token (D1: removed in M2b).
    STATIC_BEARER_TOKEN: str = ""
    STATIC_USER_ID: str = ""  # Compatibility binding until coordinated M2b migration

    # ── Rate limiting ───────────────────────────────────────────────────
    # Format: "count/period" — e.g. "5/minute", "100/hour"
    RATE_LIMIT_AUTH: str = "5/minute"
    RATE_LIMIT_COMMANDS: str = "120/minute"
    RATE_LIMIT_GLOBAL: str = "300/minute"
    MAX_WS_PER_IP: int = 3
    BAN_THRESHOLD: int = 20
    BAN_DURATION_MINUTES: int = 30

    # ── Safety ──────────────────────────────────────────────────────────
    HEARTBEAT_INTERVAL: float = 2.0
    HEARTBEAT_TIMEOUT: float = 6.0

    # ── Governor (session intensity limiter) ────────────────────────────
    GOVERNOR_ENABLED: bool = True
    GOVERNOR_HEAT_RATE: float = 3.0  # heat units/sec at intensity=1.0
    GOVERNOR_COOL_RATE: float = 2.0  # heat units/sec dissipated when idle
    GOVERNOR_COOLDOWN_THRESHOLD: float = Field(
        default=90.0, validation_alias="SB_GOVERNOR_COOLDOWN_ENTER",
    )  # heat% that triggers cooldown
    GOVERNOR_COOLDOWN_EXIT: float = 30.0  # heat% at which cooldown may end
    GOVERNOR_COOLDOWN_DURATION: float = 30.0  # minimum seconds in cooldown

    # ── Pattern library ─────────────────────────────────────────────────
    PATTERNS_DIR: str = DEFAULT_PATTERNS_DIR

    # ── Database ────────────────────────────────────────────────────────
    DB_PATH: str = DEFAULT_DB_PATH

    def cors_origin_list(self) -> list[str]:
        """CORS origins as a list — the legacy module split on commas."""
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",") if origin.strip()]


settings = Settings()


def validate(target: Settings | None = None) -> None:
    """Check that critical config is set. Call on startup."""
    s = target or settings
    if not s.SECRET_KEY:
        raise RuntimeError(
            "SB_SECRET_KEY is not set. Generate one with: "
            "python -c \"import secrets; print(secrets.token_hex(32))\""
        )
    if s.STATIC_BEARER_TOKEN and len(s.STATIC_BEARER_TOKEN) < 32:
        raise RuntimeError(
            "SB_STATIC_BEARER_TOKEN must be at least 32 characters. Generate one with: "
            "python -c \"import secrets; print(secrets.token_urlsafe(32))\""
        )
    if not 0 < s.PORT < 65536:
        raise RuntimeError(f"SB_PORT must be 1-65535, got {s.PORT}")
