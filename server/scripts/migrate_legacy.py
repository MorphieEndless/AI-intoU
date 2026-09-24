"""Migrate legacy (pre-M1) data into the new SQLAlchemy schema.

Legacy sources:
  - SQLite tables `users` / `safety_config` (raw sqlite3 era)
  - OAuth tables (deprecated per D4 — preserved as legacy_* backup only)
  - Patterns stored as JSON files (one <user_id>.json per user)

Strategy:
  1. Rename legacy tables to legacy_* (kept as in-DB backup).
  2. `alembic upgrade head` creates the new schema.
  3. Copy rows into the new tables.
  4. Report a summary. Re-running is safe: it detects legacy_* tables
     and refuses to migrate twice.

Usage:
    python -m scripts.migrate_legacy [--db PATH] [--patterns-dir PATH] [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER_DIR))

from app.db import session_scope, upgrade_to_head  # noqa: E402
from app.models import Pattern, SafetyConfig, User  # noqa: E402

LEGACY_TABLES = ("users", "safety_config",
                 "oauth_clients", "oauth_codes", "oauth_refresh_tokens")


def _iso_from_epoch(ts) -> str:
    try:
        return datetime.fromtimestamp(float(ts), tz=timezone.utc).isoformat()
    except (TypeError, ValueError, OSError):
        return datetime.now(timezone.utc).isoformat()


def _table_exists(conn: sqlite3.Connection, name: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone()
    return row is not None


def _rename_legacy_tables(conn: sqlite3.Connection) -> list[str]:
    renamed = []
    for table in LEGACY_TABLES:
        if _table_exists(conn, table):
            conn.execute(f'ALTER TABLE "{table}" RENAME TO "legacy_{table}"')
            renamed.append(table)
    conn.commit()
    return renamed


def _create_schema(db_path: Path) -> None:
    """Single implementation of "bring the schema to head" (app.db)."""
    upgrade_to_head(str(db_path))


def _copy_users(conn, db_path, dry_run, src="legacy_users") -> int:
    if not _table_exists(conn, src):
        return 0
    rows = conn.execute(
        "SELECT id, username, password_hash, created_at, is_active "
        f"FROM {src} ORDER BY created_at ASC"
    ).fetchall()
    if dry_run:
        return len(rows)
    with session_scope(str(db_path)) as s:
        for i, (uid, username, pw_hash, created_at, is_active) in enumerate(rows):
            s.add(User(
                id=uid, username=username, password_hash=pw_hash,
                # The earliest account becomes the owner (self-hosted default).
                is_admin=1 if i == 0 else 0,
                is_active=is_active if is_active is not None else 1,
                created_at=created_at or datetime.now(timezone.utc).isoformat(),
            ))
    return len(rows)


def _copy_safety_config(conn, db_path, dry_run, src="legacy_safety_config") -> int:
    if not _table_exists(conn, src):
        return 0
    rows = conn.execute(
        "SELECT user_id, governor_enabled, heat_rate, cool_rate, "
        "cooldown_threshold, cooldown_exit, cooldown_duration, updated_at "
        f"FROM {src}"
    ).fetchall()
    if dry_run:
        return len(rows)
    with session_scope(str(db_path)) as s:
        for (uid, enabled, heat, cool, threshold, exit_, duration, updated) in rows:
            s.add(SafetyConfig(
                user_id=uid,
                governor_enabled=enabled if enabled is not None else 1,
                heat_rate=heat, cool_rate=cool,
                cooldown_threshold=threshold, cooldown_exit=exit_,
                cooldown_duration=duration,
                updated_at=updated or datetime.now(timezone.utc).isoformat(),
            ))
    return len(rows)


def _copy_patterns(patterns_dir, db_path, dry_run) -> int:
    if not patterns_dir.is_dir():
        return 0
    count = 0
    for file in sorted(patterns_dir.glob("*.json")):
        user_id = file.stem
        try:
            entries = json.loads(file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        if dry_run:
            count += len(entries)
            continue
        with session_scope(str(db_path)) as s:
            for p in entries:
                s.add(Pattern(
                    id=p["id"], user_id=user_id, name=p["name"],
                    definition=json.dumps(p, ensure_ascii=False),
                    created_at=_iso_from_epoch(p.get("created_at")),
                    updated_at=_iso_from_epoch(p.get("updated_at")),
                ))
                count += 1
    return count


def migrate(db_path: Path, patterns_dir: Path, dry_run: bool = False) -> dict:
    """Run the full migration. Returns a summary dict."""
    summary = {"users": 0, "safety_config": 0, "patterns": 0,
               "renamed": [], "skipped": False}

    if not db_path.exists():
        # Fresh install: just create the schema, nothing to migrate.
        if not dry_run:
            _create_schema(db_path)
        return summary

    conn = sqlite3.connect(str(db_path))
    try:
        if _table_exists(conn, "legacy_users"):
            summary["skipped"] = True
            return summary
        if not dry_run:
            summary["renamed"] = _rename_legacy_tables(conn)
            _create_schema(db_path)
        else:
            summary["renamed"] = [t for t in LEGACY_TABLES
                                  if _table_exists(conn, t)]
        # In dry-run nothing was renamed yet: read from the live table names.
        suffix = "" if dry_run else "legacy_"
        summary["users"] = _copy_users(conn, db_path, dry_run, src=f"{suffix}users")
        summary["safety_config"] = _copy_safety_config(
            conn, db_path, dry_run, src=f"{suffix}safety_config")
        summary["patterns"] = _copy_patterns(patterns_dir, db_path, dry_run)
    finally:
        conn.close()
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Migrate legacy data to the M1 schema")
    parser.add_argument("--db", default=str(SERVER_DIR / "signal_bridge.db"))
    parser.add_argument("--patterns-dir",
                        default=str(SERVER_DIR / "server" / "data" / "patterns"))
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would migrate, change nothing")
    args = parser.parse_args()

    summary = migrate(Path(args.db), Path(args.patterns_dir), dry_run=args.dry_run)
    prefix = "[dry-run] " if args.dry_run else ""
    if summary["skipped"]:
        print(f"{prefix}legacy_* tables already exist — already migrated, nothing to do")
        return
    print(f"{prefix}renamed legacy tables: {summary['renamed'] or 'none'}")
    print(f"{prefix}users: {summary['users']}, "
          f"safety_config: {summary['safety_config']}, "
          f"patterns: {summary['patterns']}")


if __name__ == "__main__":
    main()
