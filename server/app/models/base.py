"""Declarative base shared by all ORM models."""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


def utcnow_iso() -> str:
    """Timezone-aware UTC timestamp, ISO-8601. Used for all *_at columns."""
    return datetime.now(timezone.utc).isoformat()
