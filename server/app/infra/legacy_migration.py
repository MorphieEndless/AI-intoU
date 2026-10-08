"""Bring any database to the current schema — including pre-M1 legacy ones.

Legacy sources:
  - SQLite tables `users` / `safety_config` (raw sqlite3 era)
  - OAuth tables (removed per D4 — preserved as legacy_* backup only)
  - Patterns stored as JSON files (copied into the M1 `patterns` staging
    table; the live waveform library has its own one-time import)

The migration is all-or-nothing:

  1. take a consistent copy of the live file (SQLite backup API) and keep a
     timestamped `.pre-multiuser-*.bak` next to it;
  2. on the *copy*: rename legacy tables to legacy_*, `alembic upgrade head`,
     copy rows, verify counts;
  3. write the migrated copy back into the original file with one backup
     call — a single transaction on the destination.

A failure at any step leaves the original untouched (the work copy is
discarded), so the next start simply tries again instead of finding a
half-renamed database. Writing back through SQLite, not `os.replace`,
matters: connections that are already open on the file (the pattern store
opens one at import) see the new content instead of an unlinked inode.
"""
from __future__ import annotations

import json
import logging
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import select

from app.db import session_scope, upgrade_to_head
from app.models import Pattern, SafetyConfig, User

log = logging.getLogger("ai_intou.migration")

LEGACY_TABLES = ("users", "safety_config",
                 "oauth_clients", "oauth_codes", "oauth_refresh_tokens")


def _iso_from_epoch(ts) -> str:
    try:
        return datetime.fromtimestamp(float(ts), tz=timezone.utc).isoformat()
    except (TypeError, ValueError, OSError):
        return datetime.now(timezone.utc).isoformat()


def _table_names(conn: sqlite3.Connection) -> set[str]:
    return {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}


def _table_exists(conn: sqlite3.Connection, name: str) -> bool:
    return name in _table_names(conn)


def needs_legacy_migration(db_path: str | Path) -> bool:
    """A legacy file has the raw `users` table and no Alembic bookkeeping."""
    path = Path(db_path)
    if not path.exists():
        return False
    conn = sqlite3.connect(str(path))
    try:
        names = _table_names(conn)
    finally:
        conn.close()
    return "users" in names and "alembic_version" not in names


# ── row copies (operate on the work copy) ──────────────────────────────────

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
    copied = 0
    with session_scope(str(db_path)) as s:
        known = set(s.scalars(select(User.id)))
        for (uid, enabled, heat, cool, threshold, exit_, duration, updated) in rows:
            if uid not in known:
                continue  # override for an account that no longer exists
            s.add(SafetyConfig(
                user_id=uid,
                governor_enabled=enabled if enabled is not None else 1,
                heat_rate=heat, cool_rate=cool,
                cooldown_threshold=threshold, cooldown_exit=exit_,
                cooldown_duration=duration,
                updated_at=updated or datetime.now(timezone.utc).isoformat(),
            ))
            copied += 1
    return copied


def _copy_patterns(patterns_dir: Path, db_path, dry_run) -> int:
    """Staging copy only. Duplicate ids are skipped, never fatal."""
    if not patterns_dir.is_dir():
        return 0
    count = 0
    seen: set[str] = set()
    for file in sorted(patterns_dir.glob("*.json")):
        user_id = file.stem
        try:
            entries = json.loads(file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        if not isinstance(entries, list):
            continue
        fresh = [p for p in entries if isinstance(p, dict) and p.get("id") and p["id"] not in seen]
        seen.update(p["id"] for p in fresh)
        if dry_run:
            count += len(fresh)
            continue
        with session_scope(str(db_path)) as s:
            for p in fresh:
                s.add(Pattern(
                    id=p["id"], user_id=user_id, name=p.get("name") or p["id"],
                    definition=json.dumps(p, ensure_ascii=False),
                    created_at=_iso_from_epoch(p.get("created_at")),
                    updated_at=_iso_from_epoch(p.get("updated_at")),
                ))
                count += 1
    return count


# ── the migration ─────────────────────────────────────────────────────────

def _empty_summary() -> dict:
    return {"users": 0, "safety_config": 0, "patterns": 0, "renamed": [], "skipped": False}


def migrate(db_path: Path, patterns_dir: Path, dry_run: bool = False) -> dict:
    """Run the full migration. Returns a summary dict."""
    db_path, patterns_dir = Path(db_path), Path(patterns_dir)
    summary = _empty_summary()

    if not db_path.exists():
        if not dry_run:
            upgrade_to_head(str(db_path))  # fresh install: nothing to migrate
        return summary

    live = sqlite3.connect(str(db_path))
    try:
        if _table_exists(live, "legacy_users"):
            summary["skipped"] = True
            return summary

        if dry_run:
            summary["renamed"] = [t for t in LEGACY_TABLES if _table_exists(live, t)]
            summary["users"] = _copy_users(live, db_path, True, src="users")
            summary["safety_config"] = _copy_safety_config(live, db_path, True, src="safety_config")
            summary["patterns"] = _copy_patterns(patterns_dir, db_path, True)
            return summary

        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup_path = db_path.with_name(f"{db_path.name}.pre-multiuser-{stamp}.bak")
        work_path = db_path.with_name(f"{db_path.name}.migrating")
        work_path.unlink(missing_ok=True)

        with sqlite3.connect(str(backup_path)) as backup:
            live.backup(backup)
        work = sqlite3.connect(str(work_path))
        try:
            live.backup(work)
            legacy_users = (work.execute("SELECT COUNT(*) FROM users").fetchone()[0]
                            if _table_exists(work, "users") else 0)
            for table in LEGACY_TABLES:
                if _table_exists(work, table):
                    work.execute(f'ALTER TABLE "{table}" RENAME TO "legacy_{table}"')
                    summary["renamed"].append(table)
            work.commit()

            upgrade_to_head(str(work_path))
            summary["users"] = _copy_users(work, work_path, False)
            summary["safety_config"] = _copy_safety_config(work, work_path, False)
            summary["patterns"] = _copy_patterns(patterns_dir, work_path, False)

            migrated_users = work.execute("SELECT COUNT(*) FROM users").fetchone()[0]
            if migrated_users != legacy_users:
                raise RuntimeError(
                    f"user count mismatch after migration ({migrated_users} != {legacy_users})"
                )
            # The single write to the live file.
            work.backup(live)
        finally:
            work.close()
            work_path.unlink(missing_ok=True)
        summary["backup"] = str(backup_path)
    finally:
        live.close()
    return summary


def prepare_database(db_path: str, patterns_dir: str) -> dict | None:
    """Startup hook: migrate a legacy file if needed, then upgrade to head.

    Returns the migration summary when a legacy migration ran, else None.
    """
    summary = None
    if needs_legacy_migration(db_path):
        log.warning("Legacy database schema detected — migrating to the account/token schema")
        summary = migrate(Path(db_path), Path(patterns_dir))
        log.warning(
            "Legacy migration done: users=%s safety_config=%s backup=%s",
            summary["users"], summary["safety_config"], summary.get("backup"),
        )
    upgrade_to_head(db_path)
    return summary
