"""`python -m app.cli` — the operator console (architecture doc §2/§8).

Accounts and credentials are managed here: the deployment owner creates the
first account, mints one token per AI platform and per phone, lists what
exists and revokes what should stop working. No web UI is needed (M7 adds
one; the CLI stays the supported path).

Rules this module enforces:
  * a plaintext token is printed exactly once, at creation, preceded by a
    warning — it is never written to the database, a log or a file;
  * listings show the display prefix, never a full token;
  * passwords are prompted for (or read from stdin) unless explicitly
    passed, and are never echoed;
  * every command that touches data first brings the schema to head, and
    refuses to guess when a pre-M1 database still holds legacy tables.
"""
from __future__ import annotations

import argparse
import getpass
import sqlite3
import sys
from pathlib import Path

from app.config import settings
from app.config import validate as validate_config
from app.core.security import SCOPES, TOKEN_KINDS
from app.db import (
    current_revision,
    head_revision,
    is_migrated,
    resolve_db_path,
    session_scope,
    upgrade_to_head,
)
from app.domain.identity import (
    IdentityError,
    create_user,
    get_safety_overrides,
    list_tokens,
    list_users,
    mint_token,
    require_user,
    revoke_token,
    set_password,
    token_state,
)

EXIT_OK = 0
EXIT_ERROR = 1

MIGRATION_COMMAND = "python -m scripts.migrate_legacy"


# ════════════════════════════════════════════════════════════════════════
# Helpers
# ════════════════════════════════════════════════════════════════════════

def _out(message: str = "") -> None:
    print(message)


def _err(message: str) -> None:
    print(message, file=sys.stderr)


def _legacy_tables(db_path: str) -> set[str]:
    """Legacy (pre-M1) tables still sitting in the file, if any."""
    if not Path(db_path).exists():
        return set()
    try:
        conn = sqlite3.connect(db_path)
    except sqlite3.Error:
        return set()
    try:
        names = {row[0] for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )}
    except sqlite3.Error:
        return set()
    finally:
        conn.close()
    if "alembic_version" in names:
        return set()  # already on the new schema
    return {t for t in names if not t.startswith("legacy_") and t != "sqlite_sequence"}


def _prepare_db(db_path: str) -> None:
    """Bring a fresh or migrated database to head; never guess about legacy."""
    legacy = _legacy_tables(db_path)
    if legacy:
        raise IdentityError(
            "This database still uses the pre-M1 schema "
            f"(tables: {', '.join(sorted(legacy))}).\n"
            f"Migrate it first:  {MIGRATION_COMMAND} --db {db_path}"
        )
    upgrade_to_head(db_path)


def _resolve_password(args) -> str:
    if getattr(args, "password_stdin", False):
        return sys.stdin.readline().rstrip("\n")
    if getattr(args, "password", None):
        return args.password
    first = getpass.getpass("Password: ")
    if first != getpass.getpass("Repeat password: "):
        raise IdentityError("Passwords do not match")
    return first


def _print_token(plaintext: str, row, username: str) -> None:
    _out("")
    _out("=" * 68)
    _out("  TOKEN CREATED — shown once, copy it now")
    _out("=" * 68)
    _out(f"  {plaintext}")
    _out("=" * 68)
    _out(f"  user    : {username}")
    _out(f"  kind    : {row.kind}")
    _out(f"  name    : {row.name}")
    _out(f"  scopes  : {', '.join(row.scope_list)}")
    _out(f"  expires : {row.expires_at or 'never'}")
    _out("")
    _out("  The server stores only a hash of this value — it cannot be shown again.")
    _out("  Write it into the client that needs it: never into git, a PR or an issue")
    _out("  (AGENTS.md R1–R4 — credentials never enter the repository).")
    _out("")


# ════════════════════════════════════════════════════════════════════════
# Commands
# ════════════════════════════════════════════════════════════════════════

def cmd_create_user(args) -> int:
    db_path = resolve_db_path(args.db)
    _prepare_db(db_path)
    password = _resolve_password(args)
    with session_scope(db_path) as session:
        user = create_user(session, args.username, password, is_admin=args.admin or None)
        role = "owner" if user.is_admin else "user"
        _out(f"User created: {user.username} (id {user.id}, {role})")
    return EXIT_OK


def cmd_reset_password(args) -> int:
    db_path = resolve_db_path(args.db)
    _prepare_db(db_path)
    password = _resolve_password(args)
    with session_scope(db_path) as session:
        user = set_password(session, password, username=args.username)
        _out(f"Password updated for {user.username}")
    return EXIT_OK


def cmd_create_token(args) -> int:
    db_path = resolve_db_path(args.db)
    _prepare_db(db_path)
    scopes = [s.strip() for s in args.scopes.split(",")] if args.scopes else None
    with session_scope(db_path) as session:
        user = require_user(session, username=args.username)
        plaintext, row = mint_token(
            session, user,
            name=args.name, kind=args.kind, scopes=scopes,
            expires_in_days=args.expires_days,
        )
        _print_token(plaintext, row, user.username)
    return EXIT_OK


def cmd_list_tokens(args) -> int:
    db_path = resolve_db_path(args.db)
    _prepare_db(db_path)
    with session_scope(db_path) as session:
        user = require_user(session, username=args.username)
        rows = list_tokens(session, user)
        if not rows:
            _out(f"No tokens for {user.username}")
            return EXIT_OK
        _out(f"Tokens for {user.username} ({len(rows)}) — full tokens are never stored:")
        for row in rows:
            _out(f"  {row.prefix}  {row.kind:<6} {token_state(row):<8} "
                 f"{row.name:<24} last_used={row.last_used_at or '-'} "
                 f"expires={row.expires_at or 'never'}  id={row.id}")
    return EXIT_OK


def cmd_revoke_token(args) -> int:
    db_path = resolve_db_path(args.db)
    _prepare_db(db_path)
    with session_scope(db_path) as session:
        user = require_user(session, username=args.username)
        row, changed = revoke_token(session, user, args.token)
        if changed:
            _out(f"Revoked {row.kind} token {row.prefix} ({row.name})")
        else:
            _out(f"Token {row.prefix} ({row.name}) was already revoked at {row.revoked_at}")
    return EXIT_OK


def cmd_doctor(args) -> int:
    """Read-only health report (M6 extends it with WS/heartbeat checks)."""
    db_path = resolve_db_path(args.db)
    checks: list[tuple[str, str, str]] = []  # (status, label, detail)

    # ── configuration ──────────────────────────────────────────────────
    try:
        validate_config()
        checks.append(("PASS", "config", f"secret key set ({len(settings.SECRET_KEY)} chars)"))
    except RuntimeError as exc:
        checks.append(("FAIL", "config", str(exc)))

    if settings.STATIC_BEARER_TOKEN:
        checks.append((
            "WARN", "legacy static token",
            "SB_STATIC_BEARER_TOKEN is set; the mechanism is removed in M2 — see the migration guide",
        ))

    # ── database ───────────────────────────────────────────────────────
    legacy = _legacy_tables(db_path)
    migrated = False
    if legacy:
        checks.append((
            "FAIL", "database",
            f"pre-M1 schema present ({', '.join(sorted(legacy))}); run: "
            f"{MIGRATION_COMMAND} --db {db_path}",
        ))
    else:
        try:
            if not Path(db_path).exists():
                # Checked before touching the engine: connecting would create
                # an empty file and turn "missing" into "unmigrated".
                checks.append(("FAIL", "database", f"not found: {db_path}"))
            else:
                current, head = current_revision(db_path), head_revision()
                if not is_migrated(db_path):
                    checks.append((
                        "FAIL", "migration",
                        f"at {current or 'no revision'}, head is {head}; "
                        "any write command (e.g. create-user) upgrades it",
                    ))
                else:
                    migrated = True
                    checks.append(("PASS", "database", f"{db_path} (revision {current})"))
        except Exception as exc:  # noqa: BLE001 — doctor reports, never raises
            checks.append(("FAIL", "database", f"unreadable: {exc}"))

    # ── accounts & credentials ───────────────────────────────────────
    if migrated:
        with session_scope(db_path) as session:
            users = list_users(session)
            owners = [u for u in users if u.is_admin]
            checks.append((
                "PASS" if owners else "FAIL", "owner account",
                ", ".join(u.username for u in owners) or (
                    "none — create one with: python -m app.cli create-user "
                    "--username <name> --admin"
                ),
            ))
            for user in users:
                kinds: dict[str, int] = {}
                for row in list_tokens(session, user):
                    if token_state(row) == "active":
                        kinds[row.kind] = kinds.get(row.kind, 0) + 1
                summary = ", ".join(f"{k}×{v}" for k, v in sorted(kinds.items()))
                checks.append(("INFO", f"tokens: {user.username}", summary or "no active tokens"))
                overrides = get_safety_overrides(session, user.id)
                checks.append((
                    "INFO", f"safety overrides: {user.username}",
                    ", ".join(f"{k}={v}" for k, v in overrides.items()) or "server defaults",
                ))

    width = max(len(label) for _, label, _ in checks)
    _out(f"AI-intoU doctor — {db_path}")
    for status, label, detail in checks:
        _out(f"  [{status:<4}] {label:<{width}}  {detail}")
    _out("")
    failures = sum(1 for status, _, _ in checks if status == "FAIL")
    if failures:
        _err(f"{failures} check(s) failed")
        return EXIT_ERROR
    _out("All checks passed.")
    return EXIT_OK


# ════════════════════════════════════════════════════════════════════════
# Argument parsing
# ════════════════════════════════════════════════════════════════════════

def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--db", default=None,
                        help="SQLite file to operate on (default: SB_DB_PATH)")

    credentials = argparse.ArgumentParser(add_help=False)
    credentials.add_argument("--password", default=None,
                             help="password (discouraged: visible in shell history; "
                                  "omit to be prompted)")
    credentials.add_argument("--password-stdin", action="store_true",
                             help="read the password from the first stdin line")

    parser = argparse.ArgumentParser(
        prog="python -m app.cli",
        description="AI-intoU server console: accounts, tokens, diagnostics.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("create-user", parents=[common, credentials],
                       help="create an account")
    p.add_argument("--username", required=True)
    p.add_argument("--admin", action="store_true",
                   help="grant owner rights (default: the first account is owner)")
    p.set_defaults(func=cmd_create_user)

    p = sub.add_parser("reset-password", parents=[common, credentials],
                       help="set a new password for an account")
    p.add_argument("--username", required=True)
    p.set_defaults(func=cmd_reset_password)

    p = sub.add_parser("create-token", parents=[common],
                       help="mint an API token (printed once)")
    p.add_argument("--username", required=True)
    p.add_argument("--kind", required=True, choices=TOKEN_KINDS,
                   help="agent = AI client, phone = relay app, human = reserved")
    p.add_argument("--name", required=True,
                   help="human-readable label, e.g. claude-desktop")
    p.add_argument("--scopes", default=None,
                   help=f"comma-separated subset of {','.join(SCOPES)} (default: all)")
    p.add_argument("--expires-days", type=int, default=None,
                   help="expire after N days (default: never)")
    p.set_defaults(func=cmd_create_token)

    p = sub.add_parser("list-tokens", parents=[common],
                       help="list tokens (prefixes only, never a full token)")
    p.add_argument("--username", required=True)
    p.set_defaults(func=cmd_list_tokens)

    p = sub.add_parser("revoke-token", parents=[common],
                       help="revoke a token by id or display prefix")
    p.add_argument("--username", required=True)
    p.add_argument("--token", required=True, help="token id or 12-character prefix")
    p.set_defaults(func=cmd_revoke_token)

    p = sub.add_parser("doctor", parents=[common],
                       help="read-only health report (config, migration, tokens)")
    p.set_defaults(func=cmd_doctor)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except IdentityError as exc:
        _err(f"error: {exc}")
        return EXIT_ERROR
    except RuntimeError as exc:  # configuration validation
        _err(f"error: {exc}")
        return EXIT_ERROR
    except KeyboardInterrupt:  # pragma: no cover - interactive only
        _err("aborted")
        return EXIT_ERROR


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
