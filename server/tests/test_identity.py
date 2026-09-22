"""Identity domain + token resolution against a real (temporary) database.

Covers the contract of 02-architecture.md §4/§5 at the data layer: accounts,
token minting/hashing/revocation/expiry, the resolver's rejection paths, and
per-user safety overrides.
"""
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from app.config import settings
from app.core.security import (
    SCOPES,
    issue_session_token,
    lookup_api_token,
    resolve_principal,
)
from app.db import session_scope, upgrade_to_head
from app.domain.identity import (
    IdentityError,
    authenticate,
    create_user,
    find_token,
    get_safety_overrides,
    get_user,
    list_tokens,
    mint_token,
    revoke_token,
    set_active,
    set_password,
    set_safety_overrides,
    token_state,
)

PASSWORD = "correct horse battery"


@pytest.fixture()
def db_path(tmp_path, monkeypatch):
    """A migrated database, and settings pointed at it."""
    path = str(tmp_path / "identity.db")
    upgrade_to_head(path)
    monkeypatch.setattr(settings, "DB_PATH", path)
    return path


@pytest.fixture()
def session(db_path):
    with session_scope(db_path) as s:
        yield s


@pytest.fixture()
def user(session):
    return create_user(session, "Morphie", PASSWORD)


def mint(session, user, kind="agent", name="claude-desktop", **kwargs):
    return mint_token(session, user, name=name, kind=kind, **kwargs)


# ════════════════════════════════════════════════════════════════════════
# Accounts
# ════════════════════════════════════════════════════════════════════════

def test_first_account_becomes_owner(session):
    first = create_user(session, "morphie", PASSWORD)
    second = create_user(session, "friend", PASSWORD)
    assert first.is_admin == 1
    assert second.is_admin == 0


def test_username_is_normalised_and_unique(session):
    create_user(session, "  Morphie  ", PASSWORD)
    assert get_user(session, username="MORPHIE") is not None
    with pytest.raises(IdentityError, match="already taken"):
        create_user(session, "morphie", PASSWORD)


@pytest.mark.parametrize("username", ["ab", "", "x" * 33])
def test_username_bounds(session, username):
    with pytest.raises(IdentityError, match="Username must be"):
        create_user(session, username, PASSWORD)


def test_password_bounds(session):
    with pytest.raises(IdentityError, match="at least 8"):
        create_user(session, "morphie", "short")


def test_password_is_hashed_not_stored(session, user):
    assert user.password_hash != PASSWORD
    assert user.password_hash.startswith("$2")


def test_authenticate(session, user):
    assert authenticate(session, "morphie", PASSWORD).id == user.id
    assert authenticate(session, "MORPHIE", PASSWORD).id == user.id
    assert authenticate(session, "morphie", "wrong password") is None
    assert authenticate(session, "nobody", PASSWORD) is None


def test_inactive_account_cannot_authenticate(session, user):
    set_active(session, is_active=False, username="morphie")
    assert authenticate(session, "morphie", PASSWORD) is None


def test_set_password_replaces_the_old_one(session, user):
    set_password(session, "another long secret", username="morphie")
    assert authenticate(session, "morphie", PASSWORD) is None
    assert authenticate(session, "morphie", "another long secret") is not None
    with pytest.raises(IdentityError, match="at least 8"):
        set_password(session, "tiny", username="morphie")


def test_unknown_user_operations_raise(session):
    with pytest.raises(IdentityError, match="No such user"):
        set_password(session, PASSWORD, username="ghost")


# ════════════════════════════════════════════════════════════════════════
# Tokens
# ════════════════════════════════════════════════════════════════════════

def test_mint_stores_hash_and_prefix_only(session, user, db_path):
    plaintext, row = mint(session, user)
    assert re.fullmatch(r"aiu_agent_[A-Za-z0-9_-]{32}", plaintext)
    assert row.prefix == plaintext[:12]
    assert row.token_hash != plaintext
    assert len(row.token_hash) == 64
    assert row.scope_list == list(SCOPES)
    assert row.expires_at is None and row.revoked_at is None and row.last_used_at is None
    # The plaintext must not be recoverable from the database file at all.
    session.commit()
    assert plaintext.encode() not in Path(db_path).read_bytes()


@pytest.mark.parametrize("kind", ["agent", "phone", "human"])
def test_each_kind_is_its_own_family(session, user, kind):
    plaintext, row = mint(session, user, kind=kind, name=f"{kind}-credential")
    assert plaintext.startswith(f"aiu_{kind}_")
    assert row.kind == kind
    principal = lookup_api_token(plaintext, session=session)
    assert principal.kind == kind
    assert not principal.allows(("other-kind",))


def test_scopes_are_validated(session, user):
    _, row = mint(session, user, scopes=["status"])
    assert row.scope_list == ["status"]
    with pytest.raises(IdentityError, match="Unknown scope"):
        mint(session, user, scopes=["status", "godmode"])


def test_kind_and_name_are_validated(session, user):
    with pytest.raises(IdentityError, match="Unknown token kind"):
        mint(session, user, kind="alien")
    with pytest.raises(IdentityError, match="needs a name"):
        mint(session, user, name="   ")
    with pytest.raises(IdentityError, match="at most 64"):
        mint(session, user, name="x" * 65)


def test_expiry(session, user):
    _, row = mint(session, user, expires_in_days=1)
    assert row.expires_at > datetime.now(timezone.utc).isoformat()
    assert token_state(row) == "active"
    expired, expired_row = mint(session, user, name="old")
    expired_row.expires_at = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    session.flush()
    assert token_state(expired_row) == "expired"
    assert lookup_api_token(expired, session=session) is None
    with pytest.raises(IdentityError, match="must be positive"):
        mint(session, user, expires_in_days=0)


def test_list_and_find(session, user):
    _, first = mint(session, user, name="one")
    _, second = mint(session, user, name="two", kind="phone")
    assert [t.name for t in list_tokens(session, user)] == ["one", "two"]
    assert find_token(session, user, first.id).name == "one"
    assert find_token(session, user, second.prefix).name == "two"
    with pytest.raises(IdentityError, match="No token matching"):
        find_token(session, user, "aiu_nope_nope")
    with pytest.raises(IdentityError, match="id or prefix required"):
        find_token(session, user, "  ")


def test_ambiguous_prefix_refused(session, user, monkeypatch):
    from app.domain import identity as identity_module

    monkeypatch.setattr(identity_module, "token_display_prefix", lambda _t: "aiu_agent_xx")
    mint(session, user, name="one")
    mint(session, user, name="two")
    with pytest.raises(IdentityError, match="matches 2 tokens"):
        find_token(session, user, "aiu_agent_xx")


def test_other_users_token_is_not_reachable(session, user):
    _, row = mint(session, user)
    other = create_user(session, "friend", PASSWORD)
    with pytest.raises(IdentityError, match="No token matching"):
        find_token(session, other, row.id)


def test_revoke_is_idempotent_and_kills_the_credential(session, user):
    plaintext, row = mint(session, user, kind="phone")
    assert lookup_api_token(plaintext, session=session) is not None

    revoked, changed = revoke_token(session, user, row.id)
    assert changed and revoked.revoked_at
    assert token_state(revoked) == "revoked"
    assert lookup_api_token(plaintext, session=session) is None

    _, changed_again = revoke_token(session, user, row.id)
    assert changed_again is False


# ════════════════════════════════════════════════════════════════════════
# The resolver
# ════════════════════════════════════════════════════════════════════════

def test_lookup_rejects_unknown_and_malformed(session, user):
    mint(session, user)
    assert lookup_api_token("aiu_agent_" + "z" * 32, session=session) is None
    assert lookup_api_token("not-a-token", session=session) is None
    assert lookup_api_token(None, session=session) is None


def test_lookup_rejects_token_of_inactive_owner(session, user):
    plaintext, _ = mint(session, user)
    set_active(session, is_active=False, username="morphie")
    assert lookup_api_token(plaintext, session=session) is None


def test_lookup_records_last_used(session, user):
    plaintext, row = mint(session, user)
    assert row.last_used_at is None
    lookup_api_token(plaintext, session=session)
    assert row.last_used_at is not None
    stamp = row.last_used_at
    plaintext2, row2 = mint(session, user, name="quiet")
    lookup_api_token(plaintext2, session=session, touch=False)
    assert row2.last_used_at is None
    assert stamp  # the first touch is untouched by the second call


def test_resolve_principal_reads_both_credential_families(db_path, session, user):
    plaintext, _ = mint(session, user, kind="agent")
    session.commit()  # the resolver opens its own connection: publish the row first
    from_token = resolve_principal(f"Bearer {plaintext}")
    assert from_token.kind == "agent" and from_token.via == "api_token"
    assert from_token.user_id == user.id

    from_session = resolve_principal(f"Bearer {issue_session_token(user.id, user.username)}")
    assert from_session.is_session and from_session.user_id == user.id

    assert resolve_principal(None) is None
    assert resolve_principal("") is None
    assert resolve_principal("Bearer not-a-credential") is None
    assert resolve_principal(f"Basic {plaintext}") is None


def test_plaintext_is_never_the_hash(db_path, session, user):
    """Regression guard: a token must not be usable as its own lookup key."""
    plaintext, row = mint(session, user)
    from app.core.security import hash_api_token

    assert row.token_hash == hash_api_token(plaintext)
    assert lookup_api_token(row.token_hash, session=session) is None


# ════════════════════════════════════════════════════════════════════════
# Safety overrides
# ════════════════════════════════════════════════════════════════════════

def test_safety_overrides_default_to_nothing_set(session, user):
    """No row yet = no overrides at all; server defaults fill the gaps.

    Once a row exists, `governor_enabled` is always reported (it is NOT NULL
    on the row), while the numeric knobs only appear if explicitly set.
    """
    assert get_safety_overrides(session, user.id) == {}
    assert get_safety_overrides(session, "nobody") == {}
    set_safety_overrides(session, user.id, {"heat_rate": 1.0})
    assert get_safety_overrides(session, user.id) == {
        "governor_enabled": True, "heat_rate": 1.0,
    }


def test_safety_overrides_roundtrip(session, user):
    stored = set_safety_overrides(session, user.id, {
        "governor_enabled": False, "heat_rate": 2.5, "cooldown_exit": "40",
        "nonsense": "ignored",
    })
    assert stored == {"governor_enabled": False, "heat_rate": 2.5, "cooldown_exit": 40.0}
    assert get_safety_overrides(session, user.id) == stored


def test_safety_overrides_reject_garbage(session, user):
    with pytest.raises(IdentityError, match="must be a number"):
        set_safety_overrides(session, user.id, {"heat_rate": "hot"})
    with pytest.raises(IdentityError, match="finite"):
        set_safety_overrides(session, user.id, {"heat_rate": float("inf")})
    with pytest.raises(IdentityError, match="No such user"):
        set_safety_overrides(session, "ghost", {"heat_rate": 1.0})


def test_safety_override_can_be_cleared_back_to_default(session, user):
    set_safety_overrides(session, user.id, {"heat_rate": 9.0})
    cleared = set_safety_overrides(session, user.id, {"heat_rate": None})
    assert "heat_rate" not in cleared
