"""Legacy → M1 schema migration contract.

Builds a fake legacy database (raw sqlite3 era: users / safety_config /
oauth_clients) plus a per-user patterns JSON file, runs the migration,
and asserts every byte lands in the new schema while the legacy tables
survive as legacy_* backups.
"""
import json
import sqlite3
import time

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from app.models import Pattern, SafetyConfig, User
from scripts.migrate_legacy import migrate


def build_legacy_db(path):
    conn = sqlite3.connect(path)
    conn.execute(
        "CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,"
        " password_hash TEXT NOT NULL, created_at TEXT NOT NULL,"
        " is_active INTEGER DEFAULT 1)")
    conn.execute(
        "CREATE TABLE safety_config (user_id TEXT PRIMARY KEY,"
        " governor_enabled INTEGER DEFAULT 1, heat_rate REAL, cool_rate REAL,"
        " cooldown_threshold REAL, cooldown_exit REAL, cooldown_duration REAL,"
        " updated_at TEXT NOT NULL)")
    conn.execute(
        "CREATE TABLE oauth_clients (client_id TEXT PRIMARY KEY,"
        " client_secret_hash TEXT, client_name TEXT NOT NULL,"
        " redirect_uris TEXT NOT NULL, created_at TEXT NOT NULL)")
    conn.execute("INSERT INTO users VALUES ('u1','morphie','hash1','2026-01-01T00:00:00+00:00',1)")
    conn.execute("INSERT INTO users VALUES ('u2','friend','hash2','2026-02-01T00:00:00+00:00',1)")
    conn.execute("INSERT INTO safety_config VALUES ('u1',0,2.5,NULL,NULL,NULL,NULL,'2026-03-01T00:00:00+00:00')")
    conn.execute("INSERT INTO oauth_clients VALUES ('c1','h','client','[]','2026-01-01T00:00:00+00:00')")
    conn.commit()
    conn.close()


def build_patterns_dir(path):
    path.mkdir()
    pattern = {
        "id": "abc123def456", "name": "tease", "description": "",
        "device": "yingti", "repeat": 2, "intensity_scale": 1.0,
        "steps": [{"duration_ms": 500, "vibrate": 0.5,
                   "constrict": 0.0, "constrict_mode": None}],
        "created_at": 1700000000.0, "updated_at": 1700000000.0,
    }
    (path / "u1.json").write_text(json.dumps([pattern]), encoding="utf-8")


@pytest.fixture()
def legacy_env(tmp_path):
    db = tmp_path / "legacy.db"
    patterns = tmp_path / "patterns"
    build_legacy_db(db)
    build_patterns_dir(patterns)
    return db, patterns


def table_names(db):
    conn = sqlite3.connect(db)
    names = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    conn.close()
    return names


def test_dry_run_changes_nothing(legacy_env):
    db, patterns = legacy_env
    summary = migrate(db, patterns, dry_run=True)
    assert summary["users"] == 2
    assert summary["safety_config"] == 1
    assert summary["patterns"] == 1
    names = table_names(db)
    assert "api_tokens" not in names      # schema not created
    assert "legacy_users" not in names    # nothing renamed


def test_full_migration(legacy_env):
    db, patterns = legacy_env
    summary = migrate(db, patterns)
    assert summary["users"] == 2 and summary["safety_config"] == 1
    assert summary["patterns"] == 1
    assert set(summary["renamed"]) == {"users", "safety_config", "oauth_clients"}

    names = table_names(db)
    # new schema present
    assert {"users", "api_tokens", "devices", "patterns",
            "safety_config", "alembic_version"} <= names
    # legacy preserved as backup
    assert {"legacy_users", "legacy_safety_config",
            "legacy_oauth_clients"} <= names

    engine = create_engine(f"sqlite:///{db}")
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    with factory() as s:
        morphie = s.get(User, "u1")
        friend = s.get(User, "u2")
        assert morphie.is_admin == 1   # earliest account becomes owner
        assert friend.is_admin == 0
        assert morphie.password_hash == "hash1"

        cfg = s.get(SafetyConfig, "u1")
        assert cfg.governor_enabled == 0 and cfg.heat_rate == 2.5

        pat = s.get(Pattern, "abc123def456")
        assert pat.name == "tease" and pat.user_id == "u1"
        definition = json.loads(pat.definition)
        assert definition["steps"][0]["duration_ms"] == 500
        assert definition["repeat"] == 2
        # epoch float was converted to ISO text
        assert pat.created_at.startswith("2023-11-14")


def test_migration_is_not_reentrant(legacy_env):
    db, patterns = legacy_env
    migrate(db, patterns)
    again = migrate(db, patterns)
    assert again["skipped"] is True


def test_fresh_install_creates_empty_schema(tmp_path):
    db = tmp_path / "fresh.db"
    summary = migrate(db, tmp_path / "no-patterns")
    assert summary == {"users": 0, "safety_config": 0, "patterns": 0,
                       "renamed": [], "skipped": False}
    assert {"users", "api_tokens", "devices", "patterns",
            "safety_config"} <= table_names(db)
