"""CLI contract: `python -m app.cli` end to end against a temporary database.

The CLI is the supported way to run a self-hosted instance (§8), so it gets
the same treatment as the HTTP surface: real files, real failures, asserted
exit codes.
"""
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest

from app.cli import main
from app.config import settings
from app.core.security import resolve_principal
from app.db import session_scope
from app.domain.identity import get_user, list_tokens

PASSWORD = "correct horse battery"
REPO_SERVER = Path(__file__).resolve().parent.parent


@pytest.fixture()
def db(tmp_path, monkeypatch):
    """A per-test database file, also pointed to by settings.DB_PATH.

    The CLI takes `--db` explicitly, but the credential resolver reads
    SB_DB_PATH, so the two have to agree for the revocation checks.
    """
    path = str(tmp_path / "cli.db")
    monkeypatch.setattr(settings, "DB_PATH", path)
    return path


def run(capsys, *argv):
    code = main(list(argv))
    captured = capsys.readouterr()
    return code, captured.out, captured.err


def make_user(capsys, db, username="morphie", extra=()):
    return run(capsys, "create-user", "--username", username,
               "--password", PASSWORD, "--db", db, *extra)


def test_create_user_first_account_is_owner(capsys, db):
    code, out, _ = make_user(capsys, db)
    assert code == 0
    assert "morphie" in out and "owner" in out
    assert PASSWORD not in out

    code, out, _ = make_user(capsys, db, username="friend", extra=("--admin",))
    assert code == 0 and "owner" in out

    with session_scope(db) as session:
        assert get_user(session, username="morphie").is_admin == 1
        assert get_user(session, username="friend").is_admin == 1


def test_create_user_rejects_bad_input(capsys, db):
    code, _, err = run(capsys, "create-user", "--username", "morphie",
                       "--password", "short", "--db", db)
    assert code == 1 and "at least 8" in err

    make_user(capsys, db)
    code, _, err = make_user(capsys, db)
    assert code == 1 and "already taken" in err


def test_password_can_come_from_stdin(capsys, db, monkeypatch):
    import io

    monkeypatch.setattr(sys, "stdin", io.StringIO(PASSWORD + "\n"))
    code, out, _ = run(capsys, "create-user", "--username", "morphie",
                       "--password-stdin", "--db", db)
    assert code == 0
    assert PASSWORD not in out


def test_minting_a_token_prints_it_exactly_once(capsys, db):
    make_user(capsys, db)
    code, out, _ = run(capsys, "create-token", "--username", "morphie",
                       "--kind", "agent", "--name", "claude-desktop", "--db", db)
    assert code == 0
    assert "shown once" in out
    token = re.search(r"aiu_agent_[A-Za-z0-9_-]{32}", out).group(0)

    # the database stores the hash, never the plaintext
    with session_scope(db) as session:
        rows = list_tokens(session, get_user(session, username="morphie"))
        assert rows[0].prefix == token[:12]
    assert token.encode() not in Path(db).read_bytes()

    # and a listing never repeats it
    code, listed, _ = run(capsys, "list-tokens", "--username", "morphie", "--db", db)
    assert code == 0
    assert token[:12] in listed and token not in listed


def test_token_kinds_and_scopes_are_validated(capsys, db):
    make_user(capsys, db)
    code, _, err = run(capsys, "create-token", "--username", "morphie",
                       "--kind", "phone", "--name", "relay",
                       "--scopes", "status,godmode", "--db", db)
    assert code == 1 and "Unknown scope" in err

    code, out, _ = run(capsys, "create-token", "--username", "morphie",
                       "--kind", "phone", "--name", "relay",
                       "--scopes", "status", "--expires-days", "30", "--db", db)
    assert code == 0 and "aiu_phone_" in out


def test_token_for_unknown_user_fails(capsys, db):
    make_user(capsys, db)
    code, _, err = run(capsys, "create-token", "--username", "ghost",
                       "--kind", "agent", "--name", "x", "--db", db)
    assert code == 1 and "No such user" in err


def test_revoke_by_prefix_kills_the_credential(capsys, db):
    make_user(capsys, db)
    _, out, _ = run(capsys, "create-token", "--username", "morphie",
                    "--kind", "agent", "--name", "claude-desktop", "--db", db)
    token = re.search(r"aiu_agent_[A-Za-z0-9_-]{32}", out).group(0)
    assert resolve_principal(f"Bearer {token}") is not None

    code, out, _ = run(capsys, "revoke-token", "--username", "morphie",
                       "--token", token[:12], "--db", db)
    assert code == 0 and "Revoked" in out
    assert resolve_principal(f"Bearer {token}") is None

    code, out, _ = run(capsys, "revoke-token", "--username", "morphie",
                       "--token", token[:12], "--db", db)
    assert code == 0 and "already revoked" in out


def test_reset_password_and_listing(capsys, db):
    make_user(capsys, db)
    code, out, _ = run(capsys, "reset-password", "--username", "morphie",
                       "--password", "a much longer secret", "--db", db)
    assert code == 0 and "Password updated" in out

    code, out, _ = run(capsys, "list-tokens", "--username", "morphie", "--db", db)
    assert code == 0 and "No tokens" in out


# ── doctor ──────────────────────────────────────────────────────────────

def test_doctor_reports_a_missing_database(capsys, db):
    code, out, err = run(capsys, "doctor", "--db", db)
    assert code == 1
    assert "not found" in out
    assert "failed" in err


def test_doctor_passes_on_a_healthy_instance(capsys, db):
    make_user(capsys, db)
    run(capsys, "create-token", "--username", "morphie",
        "--kind", "agent", "--name", "claude-desktop", "--db", db)
    run(capsys, "create-token", "--username", "morphie",
        "--kind", "phone", "--name", "relay", "--db", db)

    code, out, _ = run(capsys, "doctor", "--db", db)
    assert code == 0
    assert "[PASS] config" in out
    assert "[PASS] database" in out
    assert "[PASS] owner account" in out
    assert "agent×1" in out and "phone×1" in out
    assert "All checks passed" in out


def test_doctor_warns_about_the_legacy_static_token(capsys, db):
    make_user(capsys, db)
    code, out, _ = run(capsys, "doctor", "--db", db)
    assert code == 0
    assert "SB_STATIC_BEARER_TOKEN" in out  # conftest sets it: WARN, not FAIL


def test_doctor_fails_without_a_secret_key(capsys, db, monkeypatch):
    make_user(capsys, db)
    monkeypatch.setattr(settings, "SECRET_KEY", "")
    code, out, err = run(capsys, "doctor", "--db", db)
    assert code == 1 and "[FAIL] config" in out and "SB_SECRET_KEY" in out


def test_doctor_fails_on_an_unmigrated_token_less_instance(capsys, db):
    """A database created but never migrated must be reported, not guessed at."""
    make_user(capsys, db)
    with session_scope(db) as session:
        session.execute(__import__("sqlalchemy").text("DELETE FROM alembic_version"))
    code, out, _ = run(capsys, "doctor", "--db", db)
    assert code == 1 and "[FAIL] migration" in out


# ── pre-M1 databases ────────────────────────────────────────────────────

@pytest.fixture()
def legacy_db(tmp_path):
    path = tmp_path / "legacy.db"
    conn = sqlite3.connect(path)
    conn.execute(
        "CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,"
        " password_hash TEXT NOT NULL, created_at TEXT NOT NULL, is_active INTEGER DEFAULT 1)")
    conn.commit()
    conn.close()
    return str(path)


def test_writing_commands_refuse_a_pre_m1_database(capsys, legacy_db):
    code, _, err = run(capsys, "create-user", "--username", "morphie",
                       "--password", PASSWORD, "--db", legacy_db)
    assert code == 1
    assert "pre-M1 schema" in err and "scripts.migrate_legacy" in err


def test_doctor_reports_a_pre_m1_database(capsys, legacy_db):
    code, out, err = run(capsys, "doctor", "--db", legacy_db)
    assert code == 1
    assert "[FAIL] database" in out and "scripts.migrate_legacy" in out


# ── module entry point ──────────────────────────────────────────────────

def test_module_entry_point_runs(capsys, db):
    make_user(capsys, db)
    result = subprocess.run(
        [sys.executable, "-m", "app.cli", "doctor", "--db", db],
        cwd=REPO_SERVER, capture_output=True, text=True, timeout=120,
        env={"PATH": "/usr/bin:/bin", "PYTHONPATH": str(REPO_SERVER),
             "SB_SECRET_KEY": "cli-subprocess-test-key-0123456789"},
    )
    assert result.returncode == 0, result.stderr
    assert "AI-intoU doctor" in result.stdout
