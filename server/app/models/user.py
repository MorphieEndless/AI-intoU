"""users — human accounts. The first account on an instance is the owner."""
from __future__ import annotations

from sqlalchemy import Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .base import Base, utcnow_iso


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String, primary_key=True)  # uuid4
    username: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String, nullable=False)
    is_admin: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    is_active: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    created_at: Mapped[str] = mapped_column(String, nullable=False, default=utcnow_iso)

    tokens: Mapped[list["ApiToken"]] = relationship(  # noqa: F821
        back_populates="user", cascade="all, delete-orphan",
    )
    devices: Mapped[list["Device"]] = relationship(  # noqa: F821
        back_populates="user", cascade="all, delete-orphan",
    )
    patterns: Mapped[list["Pattern"]] = relationship(  # noqa: F821
        back_populates="user", cascade="all, delete-orphan",
    )
