"""api_tokens — named, revocable credentials for machines.

One token per AI platform per user (D2). The plaintext token is shown
exactly once at creation; we store only its SHA-256 hash and a display
prefix. kind='human' is reserved for future human remote control (D6).
"""
from __future__ import annotations

import json

from sqlalchemy import CheckConstraint, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .base import Base, utcnow_iso

TOKEN_KINDS = ("agent", "phone", "human")
DEFAULT_SCOPES = ["control", "status", "config"]


class ApiToken(Base):
    __tablename__ = "api_tokens"
    __table_args__ = (
        CheckConstraint(
            f"kind IN ({','.join(repr(k) for k in TOKEN_KINDS)})",
            name="ck_api_tokens_kind",
        ),
    )

    id: Mapped[str] = mapped_column(String, primary_key=True)  # uuid4
    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id"), nullable=False, index=True,
    )
    name: Mapped[str] = mapped_column(String, nullable=False)  # e.g. "claude-desktop"
    kind: Mapped[str] = mapped_column(String, nullable=False)
    token_hash: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    prefix: Mapped[str] = mapped_column(String, nullable=False)  # first 12 chars
    scopes: Mapped[str] = mapped_column(
        String, nullable=False, default=lambda: json.dumps(DEFAULT_SCOPES),
    )
    expires_at: Mapped[str | None] = mapped_column(String, nullable=True)
    revoked_at: Mapped[str | None] = mapped_column(String, nullable=True)
    last_used_at: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[str] = mapped_column(String, nullable=False, default=utcnow_iso)

    user: Mapped["User"] = relationship(back_populates="tokens")  # noqa: F821

    @property
    def scope_list(self) -> list[str]:
        return json.loads(self.scopes)

    @property
    def is_active(self) -> bool:
        """Revoked tokens are dead. Expiry is checked against wall clock."""
        if self.revoked_at is not None:
            return False
        if self.expires_at is not None and self.expires_at <= utcnow_iso():
            return False
        return True
