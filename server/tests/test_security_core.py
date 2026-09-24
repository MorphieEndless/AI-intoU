"""Core credential primitives: format, hashing, session JWTs, kind gates.

Pure-level tests — no database, no HTTP. Database-backed resolution lives in
test_identity.py; entry-point wiring lives in M2b's contract tests.
"""
import hashlib
import re

import jwt
import pytest

from app.config import settings
from app.core.security import (
    DISPLAY_PREFIX_LEN,
    SESSION_TOKEN_TYPE,
    SCOPES,
    TOKEN_KINDS,
    Principal,
    extract_bearer,
    generate_api_token,
    hash_api_token,
    issue_session_token,
    looks_like_api_token,
    parse_api_token,
    verify_session_token,
)


# ── api token format ────────────────────────────────────────────────────

@pytest.mark.parametrize("kind", TOKEN_KINDS)
def test_generated_token_shape(kind):
    token = generate_api_token(kind)
    assert token.startswith(f"aiu_{kind}_")
    assert re.fullmatch(r"aiu_(agent|phone|human)_[A-Za-z0-9_-]{32}", token)
    assert parse_api_token(token) == kind
    assert looks_like_api_token(token)


def test_generated_tokens_are_unique():
    assert len({generate_api_token("agent") for _ in range(50)}) == 50


def test_unknown_kind_refused():
    with pytest.raises(ValueError):
        generate_api_token("alien")


@pytest.mark.parametrize("candidate", [
    None, "",
    "aiu_agent_short",
    "aiu_alien_" + "a" * 32,
    "aiu_agent_" + "a" * 31,
    "aiu_agent_" + "a" * 33,
    "agent_" + "a" * 32,
    "aiu_agent_" + "a" * 31 + "!",
    "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.signature",
])
def test_malformed_tokens_are_not_api_tokens(candidate):
    assert parse_api_token(candidate) is None
    assert not looks_like_api_token(candidate)


def test_hash_is_sha256_hex_and_prefix_is_display_only():
    token = generate_api_token("phone")
    digest = hash_api_token(token)
    assert digest == hashlib.sha256(token.encode()).hexdigest()
    assert len(digest) == 64 and int(digest, 16) >= 0  # hex
    assert token[:DISPLAY_PREFIX_LEN].startswith("aiu_phone_")
    assert len(token[:DISPLAY_PREFIX_LEN]) == DISPLAY_PREFIX_LEN


@pytest.mark.parametrize("header,expected", [
    (f"Bearer {'x' * 40}", "x" * 40),
    (f"bearer {'x' * 40}", "x" * 40),
    ("Bearer", None),
    ("Token abc", None),
    ("", None),
    (None, None),
])
def test_extract_bearer(header, expected):
    assert extract_bearer(header) == expected


# ── session JWTs ────────────────────────────────────────────────────────

def test_session_token_roundtrip():
    token = issue_session_token("user-1", "morphie")
    principal = verify_session_token(token)
    assert principal is not None
    assert principal.user_id == "user-1"
    assert principal.username == "morphie"
    assert principal.via == "session" and principal.is_session
    assert principal.kind is None
    assert set(principal.scopes) == set(SCOPES)
    assert all(principal.has_scope(scope) for scope in SCOPES)


def test_session_token_carries_type_claim():
    token = issue_session_token("user-1", "morphie")
    payload = jwt.decode(token, settings.SECRET_KEY, algorithms=["HS256"])
    assert payload["typ"] == SESSION_TOKEN_TYPE
    assert payload["exp"] > payload["iat"]


def test_expired_session_token_rejected():
    assert verify_session_token(issue_session_token("u", "n", ttl_hours=-1)) is None


def test_token_signed_with_other_key_rejected():
    forged = jwt.encode({"sub": "u", "typ": SESSION_TOKEN_TYPE},
                        "not-the-key-but-long-enough-for-hs256", algorithm="HS256")
    assert verify_session_token(forged) is None


def test_token_without_type_claim_rejected():
    """A JWT that was not issued as a session must not be usable as one."""
    legacy = jwt.encode({"sub": "u", "username": "n"}, settings.SECRET_KEY, algorithm="HS256")
    assert verify_session_token(legacy) is None


def test_tampered_session_token_rejected():
    token = issue_session_token("user-1", "morphie")
    head, payload, signature = token.split(".")
    assert verify_session_token(".".join([head, payload, signature[:-2] + "xy"])) is None


@pytest.mark.parametrize("candidate", [None, "", "not-a-jwt", "a.b.c"])
def test_garbage_is_not_a_session(candidate):
    assert verify_session_token(candidate) is None


# ── entry-point gates ───────────────────────────────────────────────────

def test_allows_kind_and_session_gates():
    phone = Principal(user_id="u", kind="phone", scopes=SCOPES, via="api_token")
    agent = Principal(user_id="u", kind="agent", scopes=SCOPES, via="api_token")
    session = Principal(user_id="u", kind=None, scopes=SCOPES, via="session")

    # §5: the relay accepts phone credentials only, and never a session.
    assert phone.allows(("phone",))
    assert not agent.allows(("phone",))
    assert not session.allows(("phone",))

    # §5: /mcp accepts agent credentials, or a session credential.
    assert agent.allows(("agent",), allow_session=True)
    assert session.allows(("agent",), allow_session=True)
    assert not phone.allows(("agent",), allow_session=True)


def test_kind_label_and_scope_check():
    token = Principal(user_id="u", kind="human", scopes=("status",), via="api_token")
    assert token.kind_label == "human"
    assert token.has_scope("status") and not token.has_scope("control")
    assert Principal(user_id="u", kind=None, scopes=SCOPES, via="session").kind_label == "session"
