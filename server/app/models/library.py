"""Compatibility library tables; no parallel identity or JSON-file writes."""
from sqlalchemy import Boolean, Float, String, Text
from sqlalchemy.orm import Mapped, mapped_column
from .base import Base


class LibraryPattern(Base):
    __tablename__ = "library_patterns"
    user_id: Mapped[str] = mapped_column(String, primary_key=True)
    id: Mapped[str] = mapped_column(String, primary_key=True)
    definition: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    is_liked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_favorite: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    updated_at: Mapped[float] = mapped_column(Float, nullable=False, default=0.)
    deleted_at: Mapped[float | None] = mapped_column(Float, nullable=True)


class LibraryImport(Base):
    __tablename__ = "library_imports"
    user_id: Mapped[str] = mapped_column(String, primary_key=True)
