"""Migrate legacy (pre-M1) data into the account/token schema.

The server runs this automatically on startup when it finds a legacy
database (a timestamped `.pre-multiuser-*.bak` is written first). This
script is the manual entry point — use `--dry-run` to preview.

Usage:
    python -m scripts.migrate_legacy [--db PATH] [--patterns-dir PATH] [--dry-run]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER_DIR))

from app.config import settings  # noqa: E402
from app.infra.legacy_migration import LEGACY_TABLES, migrate  # noqa: E402,F401


def main() -> None:
    parser = argparse.ArgumentParser(description="Migrate legacy data to the account/token schema")
    parser.add_argument("--db", default=settings.DB_PATH)
    parser.add_argument("--patterns-dir", default=settings.PATTERNS_DIR)
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
    if summary.get("backup"):
        print(f"backup of the original database: {summary['backup']}")


if __name__ == "__main__":
    main()
