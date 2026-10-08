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


# ── atomic, automatic migration (multi-user onboarding) ────────────────────

def snapshot(db) -> dict:
    """Every table's full contents — the 'byte-for-byte unchanged' check."""
    conn = sqlite3.connect(db)
    try:
        return {
            name: sorted(conn.execute(f'SELECT * FROM "{name}"').fetchall(), key=repr)
            for (name,) in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        }
    finally:
        conn.close()


def add_library_tables(db):
    """What production looks like: the waveform library ran on a legacy DB."""
    conn = sqlite3.connect(db)
    conn.execute(
        "CREATE TABLE library_patterns (user_id TEXT NOT NULL, id TEXT NOT NULL,"
        " definition TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',"
        " is_liked BOOLEAN NOT NULL DEFAULT 0, is_favorite BOOLEAN NOT NULL DEFAULT 0,"
        " updated_at FLOAT NOT NULL DEFAULT 0, deleted_at FLOAT,"
        " PRIMARY KEY (user_id, id))")
    conn.execute(
        "CREATE TABLE library_imports (user_id VARCHAR NOT NULL PRIMARY KEY)")
    conn.execute("INSERT INTO library_patterns VALUES ('u1','p1','{\"steps\":[]}','mine',1,0,1.5,NULL)")
    conn.execute("INSERT INTO library_patterns VALUES ('u1','p2','{\"steps\":[]}','',0,1,2.5,9.0)")
    conn.execute("INSERT INTO library_imports VALUES ('u1')")
    conn.commit()
    conn.close()


def test_needs_legacy_migration_detection(legacy_env, tmp_path):
    from app.infra.legacy_migration import needs_legacy_migration

    db, patterns = legacy_env
    assert needs_legacy_migration(db) is True
    assert needs_legacy_migration(tmp_path / "missing.db") is False
    migrate(db, patterns)
    assert needs_legacy_migration(db) is False


def test_startup_hook_migrates_and_keeps_a_backup(legacy_env):
    from app.infra.legacy_migration import prepare_database

    db, patterns = legacy_env
    before = snapshot(db)
    summary = prepare_database(str(db), str(patterns))
    assert summary is not None and summary["users"] == 2

    backups = list(db.parent.glob(f"{db.name}.pre-multiuser-*.bak"))
    assert len(backups) == 1 and str(backups[0]) == summary["backup"]
    assert snapshot(backups[0]) == before          # the backup is the original
    assert not list(db.parent.glob("*.migrating"))  # work copy cleaned up
    assert "alembic_version" in table_names(db)
    assert "invites" in table_names(db)

    # second start: nothing to do, no second backup
    assert prepare_database(str(db), str(patterns)) is None
    assert len(list(db.parent.glob(f"{db.name}.pre-multiuser-*.bak"))) == 1


def test_failure_midway_leaves_the_original_untouched(legacy_env, monkeypatch):
    import app.infra.legacy_migration as lm

    db, patterns = legacy_env
    before = snapshot(db)

    def boom(*args, **kwargs):
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(lm, "_copy_users", boom)
    with pytest.raises(RuntimeError, match="disk on fire"):
        lm.prepare_database(str(db), str(patterns))

    assert snapshot(db) == before
    assert lm.needs_legacy_migration(db) is True
    assert not list(db.parent.glob("*.migrating"))

    # the next start simply tries again and succeeds
    monkeypatch.undo()
    summary = lm.prepare_database(str(db), str(patterns))
    assert summary["users"] == 2


def test_user_count_mismatch_aborts(legacy_env, monkeypatch):
    import app.infra.legacy_migration as lm

    db, patterns = legacy_env
    before = snapshot(db)
    real = lm._copy_users

    def drop_one(conn, db_path, dry_run, src="legacy_users"):
        real(conn, db_path, dry_run, src)
        conn.execute("DELETE FROM users WHERE id = 'u2'")
        conn.commit()
        return 1

    monkeypatch.setattr(lm, "_copy_users", drop_one)
    with pytest.raises(RuntimeError, match="user count mismatch"):
        lm.migrate(db, patterns)
    assert snapshot(db) == before


def test_duplicate_pattern_ids_are_skipped_not_fatal(legacy_env):
    db, patterns = legacy_env
    dup = json.loads((patterns / "u1.json").read_text(encoding="utf-8"))
    (patterns / "u2.json").write_text(json.dumps(dup), encoding="utf-8")
    (patterns / "broken.json").write_text("{not json", encoding="utf-8")
    summary = migrate(db, patterns)
    assert summary["patterns"] == 1 and summary["users"] == 2


def test_safety_config_for_a_vanished_user_is_skipped(legacy_env):
    db, patterns = legacy_env
    conn = sqlite3.connect(db)
    conn.execute("INSERT INTO safety_config VALUES ('ghost',1,NULL,NULL,NULL,NULL,NULL,'2026-03-01')")
    conn.commit()
    conn.close()
    summary = migrate(db, patterns)
    assert summary["safety_config"] == 1


def test_waveform_library_survives_the_migration(legacy_env):
    from app.models import LibraryImport, LibraryPattern

    db, patterns = legacy_env
    add_library_tables(db)
    migrate(db, patterns)

    engine = create_engine(f"sqlite:///{db}")
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    with factory() as s:
        rows = {r.id: r for r in s.scalars(select(LibraryPattern))}
        assert set(rows) == {"p1", "p2"}
        assert rows["p1"].user_id == "u1" and rows["p1"].description == "mine"
        assert rows["p1"].is_liked and rows["p2"].is_favorite and rows["p2"].deleted_at == 9.0
        assert s.scalar(select(func.count()).select_from(LibraryImport)) == 1
        # the library's owner id still resolves to the migrated owner account
        assert s.get(User, "u1").is_admin == 1
