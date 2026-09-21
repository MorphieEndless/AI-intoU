"""devices — a user's relay devices (phones running the app / termux).

One-to-many by design (D3): the schema supports multiple devices per user;
the v1 runtime still enforces one active phone session per user.
"""
from __future__ import annotations

from sqlalchemy import ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .base import Base, utcnow_iso


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String, primary_key=True)  # uuid4
    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id"), nullable=False, index=True,
    )
    name: Mapped[str] = mapped_column(String, nullable=False)
    platform: Mapped[str | None] = mapped_column(String, nullable=True)  # android/termux
    created_at: Mapped[str] = mapped_column(String, nullable=False, default=utcnow_iso)
    last_seen_at: Mapped[str | None] = mapped_column(String, nullable=True)

    user: Mapped["User"] = relationship(back_populates="devices")  # noqa: F821
