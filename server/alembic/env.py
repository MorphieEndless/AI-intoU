"""Alembic environment — wires migrations to app.models.Base.metadata.

URL resolution order:
  1. sqlalchemy.url main option (set by scripts/migrate_legacy.py and tests)
  2. $SB_DB_PATH
  3. server/signal_bridge.db (dev default)
"""
from __future__ import annotations

import os
import sys
from logging.config import fileConfig
from pathlib import Path

from alembic import context
from sqlalchemy import create_engine

SERVER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER_DIR))

from app.models import Base  # noqa: E402

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _db_url() -> str:
    url = config.get_main_option("sqlalchemy.url")
    if url:
        return url
    path = os.getenv("SB_DB_PATH", str(SERVER_DIR / "signal_bridge.db"))
    return f"sqlite:///{path}"


def run_migrations_offline() -> None:
    context.configure(
        url=_db_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    engine = create_engine(_db_url())
    with engine.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
