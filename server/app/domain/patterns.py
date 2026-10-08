"""User-scoped ORM library. Metadata operations never depend on a transport."""
from __future__ import annotations
import json
import math
import re
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from sqlalchemy import select
from sqlalchemy.orm import Session
from app.db import get_engine
from app.models.library import LibraryPattern, LibraryImport
from app.schemas.patterns import (
    BUILTIN_ID, BUILTIN_NAME, PatternStep, StoredPattern, MetadataPatch,
    MAX_STEPS, MAX_REPEAT, MAX_TOTAL_MS, MIN_STEP_MS,
)


def builtin_pattern():
    return StoredPattern(id=BUILTIN_ID, name=BUILTIN_NAME, builtin=True,
        description="起伏之间，认识你的第一段节奏", steps=[PatternStep(duration_ms=30000, vibrate=.2)])


class PatternStore:
    def __init__(self, root: str, db_path: str):
        self._engine = get_engine(db_path)
        LibraryPattern.__table__.create(self._engine, checkfirst=True)
        LibraryImport.__table__.create(self._engine, checkfirst=True)
        self.import_legacy(Path(root))

    @contextmanager
    def _session(self):
        with Session(self._engine, expire_on_commit=False) as session:
            with session.begin():
                yield session

    def import_legacy(self, root: Path):
        """Each file is all-or-nothing and marked once, so deletes never resurrect."""
        if not root.is_dir():
            return
        for source in sorted(root.glob("*.json")):
            uid = source.stem
            with self._session() as session:
                if session.get(LibraryImport, uid):
                    continue
                try:
                    raw = json.loads(source.read_text(encoding="utf-8"))
                    if not isinstance(raw, list):
                        raise ValueError("Expected an array")
                    parsed = [StoredPattern.model_validate(item) for item in raw]
                    ids = [p.id for p in parsed]
                    names = [p.name.strip().lower() for p in parsed]
                    if len(ids) != len(set(ids)) or len(names) != len(set(names)):
                        raise ValueError("Duplicate identifiers or names")
                    if any(p.id == BUILTIN_ID or p.name.strip().lower() == BUILTIN_NAME.lower() for p in parsed):
                        raise ValueError("Reserved built-in identifier")
                except (ValueError, OSError) as exc:
                    raise ValueError("Cannot import legacy waveform library; repair the input before startup") from exc
                for p in parsed:
                    if not session.get(LibraryPattern, (uid, p.id)):
                        session.add(self._row(uid, p))
                session.add(LibraryImport(user_id=uid))

    @staticmethod
    def _row(uid, p):
        return LibraryPattern(user_id=uid, id=p.id, definition=p.model_dump_json(),
            description=p.description, is_liked=p.is_liked, is_favorite=p.is_favorite, updated_at=p.updated_at)

    @staticmethod
    def _pattern(row):
        # The built-in instruction definition cannot be overridden in a database row.
        p = builtin_pattern() if row.id == BUILTIN_ID else StoredPattern.model_validate_json(row.definition)
        return p.model_copy(update={"description": row.description, "is_liked": row.is_liked,
            "is_favorite": row.is_favorite, "updated_at": row.updated_at})

    def _load(self, user_id: str):
        with self._session() as session:
            rows = session.scalars(select(LibraryPattern).where(
                LibraryPattern.user_id == user_id, LibraryPattern.deleted_at.is_(None), LibraryPattern.id != BUILTIN_ID)).all()
            return [self._pattern(row) for row in rows]

    def list(self, user_id: str):
        """Legacy CRUD view, excluding the virtual example."""
        return [p.model_dump() for p in self._load(user_id)]

    def library(self, user_id: str):
        return [self.get(user_id, BUILTIN_ID), *sorted(self._load(user_id), key=lambda p: (-p.created_at, p.id))]

    def get_by_id(self, user_id: str, pattern_id: str):
        with self._session() as session:
            row = session.get(LibraryPattern, (user_id, pattern_id))
            if pattern_id == BUILTIN_ID:
                return self._pattern(row) if row else builtin_pattern()
            return self._pattern(row) if row and row.deleted_at is None else None

    def get(self, user_id: str, name_or_id: str):
        key = name_or_id.strip().lower()
        if key in (BUILTIN_ID, BUILTIN_NAME.lower()):
            return self.get_by_id(user_id, BUILTIN_ID)
        return next((p for p in self._load(user_id) if p.id == key or p.name.lower() == key), None)

    def create(self, user_id, name, steps, repeat=1, intensity_scale=1., description="", device="yingti"):
        clean = name.strip()
        if not clean or len(clean) > 64:
            raise ValueError("name must contain 1-64 characters")
        if len(description.strip()) > 120:
            raise ValueError("description must be at most 120 characters")
        if clean.lower() == BUILTIN_NAME.lower():
            raise ValueError("The built-in example name is reserved")
        if not math.isfinite(intensity_scale):
            raise ValueError("intensity_scale must be finite")
        intensity_scale = max(0., min(1., intensity_scale))
        now = time.time()
        p = StoredPattern(id=uuid.uuid4().hex[:12], name=clean, description=description.strip(),
            device=device, repeat=repeat, intensity_scale=intensity_scale, steps=steps, created_at=now, updated_at=now)
        with self._session() as session:
            rows = session.scalars(select(LibraryPattern).where(LibraryPattern.user_id == user_id, LibraryPattern.deleted_at.is_(None))).all()
            if any(self._pattern(r).name.lower() == clean.lower() for r in rows):
                raise ValueError(f"a pattern named '{clean}' already exists")
            session.add(self._row(user_id, p))
        return p

    def update_metadata(self, user_id, pattern_id, updates: dict):
        patch = MetadataPatch.model_validate(updates)
        with self._session() as session:
            row = session.get(LibraryPattern, (user_id, pattern_id))
            if row is None and pattern_id == BUILTIN_ID:
                row = self._row(user_id, builtin_pattern())
                session.add(row)
            if row is None or row.deleted_at is not None:
                return None
            for key, value in patch.model_dump(exclude_unset=True).items():
                setattr(row, key, value.strip() if key == "description" else value)
            row.updated_at = time.time()
            return self._pattern(row)

    def delete_id(self, user_id, pattern_id):
        if pattern_id == BUILTIN_ID:
            raise ValueError("The built-in example cannot be deleted")
        with self._session() as session:
            row = session.get(LibraryPattern, (user_id, pattern_id))
            if row is None or row.deleted_at is not None:
                return False
            row.deleted_at = time.time()
            return True

    def delete(self, user_id, name_or_id):
        p = self.get(user_id, name_or_id)
        # Legacy MCP deletion returns false for the immutable example.
        return bool(p and not p.builtin and self.delete_id(user_id, p.id))

    def restore(self, user_id, pattern_id):
        with self._session() as session:
            row = session.get(LibraryPattern, (user_id, pattern_id))
            if row is None or row.deleted_at is None:
                return None
            if time.time() - row.deleted_at > 6:
                raise ValueError("The undo window has expired")
            p = self._pattern(row)
            active = session.scalars(select(LibraryPattern).where(LibraryPattern.user_id == user_id, LibraryPattern.deleted_at.is_(None))).all()
            if any(self._pattern(r).name.lower() == p.name.lower() for r in active):
                raise ValueError("A waveform with this name now exists")
            row.deleted_at = None
            return p
