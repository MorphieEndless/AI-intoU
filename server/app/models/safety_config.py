"""safety_config — per-user governor overrides. NULL column = follow default."""
from __future__ import annotations

from sqlalchemy import Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from .base import Base, utcnow_iso


class SafetyConfig(Base):
    __tablename__ = "safety_config"

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id"), primary_key=True,
    )
    governor_enabled: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    heat_rate: Mapped[float | None] = mapped_column(Float, nullable=True)
    cool_rate: Mapped[float | None] = mapped_column(Float, nullable=True)
    cooldown_threshold: Mapped[float | None] = mapped_column(Float, nullable=True)
    cooldown_exit: Mapped[float | None] = mapped_column(Float, nullable=True)
    cooldown_duration: Mapped[float | None] = mapped_column(Float, nullable=True)
    updated_at: Mapped[str] = mapped_column(
        String, nullable=False, default=utcnow_iso, onupdate=utcnow_iso,
    )
