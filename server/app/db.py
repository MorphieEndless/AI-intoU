"""Database engine & session management (SQLAlchemy 2.x, SQLite).

Single rule (architecture doc §1.2): persistent state lives here and only
here. Runtime state belongs to the in-memory registries, never to files.

The database path is resolved at call time from `app.config.settings`, so
tests and the CLI can point at different files without re-importing.
"""
from __future__ import annotations

from contextlib import contextmanager

from alembic import command
from alembic.config import Config
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, inspect
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import BASE_DIR, DEFAULT_DB_PATH, settings

SERVER_DIR = BASE_DIR
ALEMBIC_INI = SERVER_DIR / "alembic.ini"
ALEMBIC_DIR = SERVER_DIR / "alembic"


def resolve_db_path(db_path: str | None = None) -> str:
    """Explicit argument > SB_DB_PATH > dev default."""
    return str(db_path or settings.DB_PATH)


def get_engine(db_path: str | None = None) -> Engine:
    return create_engine(
        f"sqlite:///{resolve_db_path(db_path)}",
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


# ════════════════════════════════════════════════════════════════════════
# Alembic helpers — used by the CLI, the migration script and app startup
# so there is exactly one implementation of "bring the schema up to date".
# ════════════════════════════════════════════════════════════════════════

def alembic_config(db_path: str | None = None) -> Config:
    cfg = Config(str(ALEMBIC_INI))
    cfg.set_main_option("script_location", str(ALEMBIC_DIR))
    # The URL is passed as a main option rather than a CLI argument:
    # Alembic 1.20 no longer accepts `command.upgrade(..., x_arg=...)`.
    cfg.set_main_option("sqlalchemy.url", f"sqlite:///{resolve_db_path(db_path)}")
    return cfg


def upgrade_to_head(db_path: str | None = None) -> None:
    """Create or migrate the schema to the latest revision. Idempotent."""
    command.upgrade(alembic_config(db_path), "head")


def head_revision() -> str | None:
    return ScriptDirectory.from_config(alembic_config()).get_current_head()


def current_revision(db_path: str | None = None) -> str | None:
    """Revision recorded in the database, or None for an empty DB."""
    engine = get_engine(db_path)
    with engine.connect() as conn:
        if "alembic_version" not in inspect(conn).get_table_names():
            return None
        return MigrationContext.configure(conn).get_current_revision()


def is_migrated(db_path: str | None = None) -> bool:
    """True only when the DB is at the current head — never assumes."""
    try:
        return current_revision(db_path) == head_revision()
    except Exception:  # noqa: BLE001 — an unreadable DB is simply not migrated
        return False


__all__ = [
    "ALEMBIC_DIR",
    "ALEMBIC_INI",
    "DEFAULT_DB_PATH",
    "SERVER_DIR",
    "alembic_config",
    "current_revision",
    "get_engine",
    "get_session_factory",
    "head_revision",
    "is_migrated",
    "resolve_db_path",
    "session_scope",
    "upgrade_to_head",
]
