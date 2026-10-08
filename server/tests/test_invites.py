"""Invite codes (domain layer): format, redemption rules, atomicity.

Registration is closed by default (D5); an invite is the only way in.
These tests pin the rules the App's registration form relies on: codes are
forgiving to type, a use is only consumed by a *successful* registration,
and dead codes (used up / expired / revoked) never work.
"""
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from app.db import session_scope, upgrade_to_head
from app.domain.identity import IdentityError, create_user
from app.domain.invites import (
    ALPHABET,
    INVALID_CODE_MESSAGE,
    InviteError,
    create_invite,
    find_invite,
    format_code,
    list_invites,
    normalize_code,
    peek_invite,
    register_with_invite,
    revoke_invite,
)
from app.models import Invite, User

PASSWORD = "correct-horse-battery"


@pytest.fixture()
def db(tmp_path):
    path = str(tmp_path / "invites.db")
    upgrade_to_head(path)
    return path


def mint(db, **kwargs) -> tuple[str, str]:
    with session_scope(db) as s:
        code, row = create_invite(s, None, **kwargs)
        return code, row.id


def redeem(db, code, username, password=PASSWORD):
    with session_scope(db) as s:
        user = register_with_invite(s, code, username, password)
        return {"id": user.id, "username": user.username, "is_admin": user.is_admin}


def invite(db, invite_id) -> Invite:
    with session_scope(db) as s:
        return s.get(Invite, invite_id)


# ── format ──────────────────────────────────────────────────────────────

def test_code_format_and_storage(db):
    code, invite_id = mint(db, note="for alice")
    groups = code.split("-")
    assert len(groups) == 3 and all(len(g) == 4 for g in groups)
    assert all(ch in ALPHABET for ch in code.replace("-", ""))

    row = invite(db, invite_id)
    assert row.prefix == groups[0]
    assert code.replace("-", "") not in row.code_hash  # only a digest is stored
    assert len(row.code_hash) == 64
    assert row.note == "for alice" and row.max_uses == 1 and row.used_count == 0
    assert row.state == "active"


def test_input_is_forgiving():
    canonical = "0123456789AB"
    assert format_code(canonical) == "0123-4567-89AB"
    for typed in ("0123-4567-89ab", " 0123 4567 89AB ", "o123-4567-89ab", "0l23-4567-89AB",
                  "0I23456789AB"):
        assert normalize_code(typed) == canonical, typed
    for bad in ("", None, "0123-4567-89A", "0123-4567-89ABC", "0123-4567-89A!", "U123-4567-89AB"):
        assert normalize_code(bad) is None, bad


def test_a_typed_variant_redeems(db):
    code, invite_id = mint(db)
    sloppy = code.lower().replace("-", " ").replace("0", "o")
    user = redeem(db, sloppy, "alice")
    assert user["username"] == "alice"
    assert invite(db, invite_id).used_count == 1


# ── creation limits ────────────────────────────────────────────────────

@pytest.mark.parametrize("kwargs", [
    {"max_uses": 0}, {"max_uses": 51},
    {"expires_in_days": 0}, {"expires_in_days": 91},
    {"note": "x" * 81},
])
def test_creation_limits(db, kwargs):
    with pytest.raises(InviteError):
        mint(db, **kwargs)


def test_no_expiry_is_allowed(db):
    _, invite_id = mint(db, expires_in_days=None)
    assert invite(db, invite_id).expires_at is None


# ── redemption ─────────────────────────────────────────────────────────

def test_single_use_code_cannot_be_used_twice(db):
    code, invite_id = mint(db)
    redeem(db, code, "alice")
    with pytest.raises(InviteError, match=INVALID_CODE_MESSAGE):
        redeem(db, code, "bob")
    row = invite(db, invite_id)
    assert row.used_count == 1 and row.state == "used_up" and row.used_by == "alice"


def test_multi_use_code_records_every_user(db):
    code, invite_id = mint(db, max_uses=3)
    for name in ("alice", "bob", "carol"):
        redeem(db, code, name)
    with pytest.raises(InviteError):
        redeem(db, code, "dave")
    row = invite(db, invite_id)
    assert row.used_by == "alice,bob,carol" and row.last_used_at


def test_expired_code_is_rejected(db):
    code, invite_id = mint(db)
    with session_scope(db) as s:
        s.get(Invite, invite_id).expires_at = (
            datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
    assert invite(db, invite_id).state == "expired"
    with pytest.raises(InviteError):
        redeem(db, code, "alice")


def test_revoked_code_is_rejected(db):
    code, invite_id = mint(db)
    with session_scope(db) as s:
        row, changed = revoke_invite(s, invite_id)
        assert changed
        _, again = revoke_invite(s, invite_id)
        assert not again  # idempotent
    assert invite(db, invite_id).state == "revoked"
    with pytest.raises(InviteError):
        redeem(db, code, "alice")


def test_unknown_code_is_rejected(db):
    mint(db)
    with pytest.raises(InviteError, match=INVALID_CODE_MESSAGE):
        redeem(db, "ZZZZ-ZZZZ-ZZZZ", "alice")
    with pytest.raises(InviteError):
        redeem(db, "not a code", "alice")


@pytest.mark.parametrize("username,password", [
    ("taken", PASSWORD),      # duplicate username
    ("ok-name", "short"),     # weak password
    ("x", PASSWORD),          # username too short
])
def test_failed_registration_does_not_burn_a_use(db, username, password):
    with session_scope(db) as s:
        create_user(s, "taken", PASSWORD)
    code, invite_id = mint(db)
    with pytest.raises(IdentityError):
        redeem(db, code, username, password)
    row = invite(db, invite_id)
    assert row.used_count == 0 and row.used_by == "" and row.state == "active"
    with session_scope(db) as s:
        assert s.scalar(select(User).where(User.username == "ok-name")) is None
    # the code still works afterwards
    redeem(db, code, "late-comer")


def test_invited_accounts_are_never_owners(db):
    """Even on an empty instance (where create_user would auto-promote)."""
    code, _ = mint(db, max_uses=2)
    first = redeem(db, code, "alice")
    assert first["is_admin"] == 0


def test_conditional_update_guards_the_last_use(db, monkeypatch):
    """Two requests that both passed the peek must not both redeem."""
    code, invite_id = mint(db)
    with session_scope(db) as s:
        stale = peek_invite(s, code)
        assert stale is not None
    # The first request lands...
    redeem(db, code, "alice")
    # ...the second one already holds a row that looked redeemable.
    import app.domain.invites as invites

    monkeypatch.setattr(invites, "peek_invite", lambda session, raw: session.get(Invite, invite_id))
    with pytest.raises(InviteError):
        redeem(db, code, "bob")
    with session_scope(db) as s:
        assert s.scalar(select(User).where(User.username == "bob")) is None
    assert invite(db, invite_id).used_count == 1


# ── lookup ─────────────────────────────────────────────────────────────

def test_find_by_id_or_prefix(db):
    code, invite_id = mint(db)
    with session_scope(db) as s:
        assert find_invite(s, invite_id).id == invite_id
        assert find_invite(s, code[:4].lower()).id == invite_id
        with pytest.raises(InviteError):
            find_invite(s, "")
        with pytest.raises(InviteError):
            find_invite(s, "nope")
        assert [r.id for r in list_invites(s)] == [invite_id]
