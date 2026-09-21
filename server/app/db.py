"""Database engine & session management (SQLAlchemy 2.x, SQLite).

Single rule (architecture doc §1.2): persistent state lives here and only
here. Runtime state belongs to the in-memory registries, never to files.
"""
from __future__ import annotations

import os
from contextlib import contextmanager
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

DEFAULT_DB_PATH = os.getenv(
    "SB_DB_PATH",
    str(Path(__file__).resolve().parent.parent / "signal_bridge.db"),
)


def get_engine(db_path: str | None = None) -> Engine:
    path = db_path or DEFAULT_DB_PATH
    return create_engine(
        f"sqlite:///{path}",
        connect_args={"check_same_thread": False},
    )


def get_session_factory(db_path: str | None = None) -> sessionmaker[Session]:
    return sessionmaker(
        bind=get_engine(db_path),
        autoflush=False,
        expire_on_commit=False,
    )


@contextmanager
def session_scope(db_path: str | None = None):
    """Transactional scope: commit on success, rollback on error."""
    factory = get_session_factory(db_path)
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
