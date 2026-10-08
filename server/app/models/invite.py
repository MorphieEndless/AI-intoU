"""invites — one-time (or few-time) codes that let a new person register.

Registration stays closed by default (D5); the owner hands out invite codes
instead of opening the door to everyone. Like API tokens, the plaintext
code exists only in the response that created it: the database keeps a
SHA-256 digest and a short display prefix.
"""
from __future__ import annotations

from sqlalchemy import ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from .base import Base, utcnow_iso


class Invite(Base):
    __tablename__ = "invites"

    id: Mapped[str] = mapped_column(String, primary_key=True)  # uuid4
    code_hash: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    prefix: Mapped[str] = mapped_column(String, nullable=False)  # e.g. "K7QM-…"
    note: Mapped[str] = mapped_column(String, nullable=False, default="")
    created_by: Mapped[str | None] = mapped_column(
        ForeignKey("users.id"), nullable=True,
    )
    max_uses: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    used_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    expires_at: Mapped[str | None] = mapped_column(String, nullable=True)
    revoked_at: Mapped[str | None] = mapped_column(String, nullable=True)
    last_used_at: Mapped[str | None] = mapped_column(String, nullable=True)
    # Usernames that redeemed this code, comma separated — enough for an
    # owner to see who came in through which invite, without an audit table.
    used_by: Mapped[str] = mapped_column(String, nullable=False, default="")
    created_at: Mapped[str] = mapped_column(String, nullable=False, default=utcnow_iso)

    @property
    def state(self) -> str:
        """One word for listings: active / used_up / expired / revoked."""
        if self.revoked_at is not None:
            return "revoked"
        if self.used_count >= self.max_uses:
            return "used_up"
        if self.expires_at is not None and self.expires_at <= utcnow_iso():
            return "expired"
        return "active"

    @property
    def is_redeemable(self) -> bool:
        return self.state == "active"
