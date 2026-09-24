"""Identity & credential lifecycle — the only writer of users and tokens.

Layering (architecture doc §1.4): api / mcp / relay → domain → core.
Everything here takes a SQLAlchemy `Session` and returns ORM objects or
plain values: no FastAPI, no request objects, no async, no globals.

Passwords are bcrypt-hashed; API tokens are stored as SHA-256 digests of a
random secret (02-architecture.md §3). A plaintext token exists exactly
once — in the return value of `mint_token`, on its way to the screen that
created it.
"""
from __future__ import annotations

import json
import math
import uuid
from datetime import datetime, timedelta, timezone

import bcrypt
from sqlalchemy import func, or_, select

from app.core.security import (
    SCOPES,
    TOKEN_KINDS,
    generate_api_token,
    hash_api_token,
    token_display_prefix,
)
from app.models import ApiToken, SafetyConfig, User
from app.models.api_token import DEFAULT_SCOPES
from app.models.base import utcnow_iso

MIN_USERNAME, MAX_USERNAME = 3, 32
MIN_PASSWORD = 8
MAX_TOKEN_NAME = 64

SAFETY_KEYS: tuple[str, ...] = (
    "governor_enabled", "heat_rate", "cool_rate",
    "cooldown_threshold", "cooldown_exit", "cooldown_duration",
)
NUMERIC_SAFETY_KEYS: tuple[str, ...] = (
    "heat_rate", "cool_rate", "cooldown_threshold", "cooldown_exit", "cooldown_duration",
)


class IdentityError(ValueError):
    """A user-facing problem. The message is safe to print as-is."""


# ════════════════════════════════════════════════════════════════════════
# Users
# ════════════════════════════════════════════════════════════════════════

def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(user: User, password: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), user.password_hash.encode("utf-8"))
    except (ValueError, TypeError):
        return False


def _validate_username(username: str) -> None:
    if not MIN_USERNAME <= len(username) <= MAX_USERNAME:
        raise IdentityError(f"Username must be {MIN_USERNAME}-{MAX_USERNAME} characters")


def _validate_password(password: str) -> None:
    if len(password or "") < MIN_PASSWORD:
        raise IdentityError(f"Password must be at least {MIN_PASSWORD} characters")


def get_user(session, *, username: str | None = None, user_id: str | None = None) -> User | None:
    if user_id:
        return session.get(User, user_id)
    if username:
        return session.scalar(select(User).where(User.username == username.strip().lower()))
    raise IdentityError("get_user() needs a username or a user_id")


def require_user(session, *, username: str | None = None, user_id: str | None = None) -> User:
    user = get_user(session, username=username, user_id=user_id)
    if user is None:
        raise IdentityError(f"No such user: {username or user_id}")
    return user


def list_users(session) -> list[User]:
    return list(session.scalars(select(User).order_by(User.created_at)))


def create_user(
    session,
    username: str,
    password: str,
    *,
    is_admin: bool | None = None,
) -> User:
    """Create an account. `is_admin=None` makes the first account the owner.

    The auto-owner rule mirrors scripts/migrate_legacy.py: on a self-hosted
    single-user instance the earliest account is the one that manages the
    instance.
    """
    username = (username or "").strip().lower()
    _validate_username(username)
    _validate_password(password)
    if get_user(session, username=username) is not None:
        raise IdentityError("Username already taken")
    if is_admin is None:
        existing = session.scalar(select(func.count()).select_from(User)) or 0
        is_admin = existing == 0

    user = User(
        id=str(uuid.uuid4()),
        username=username,
        password_hash=hash_password(password),
        is_admin=1 if is_admin else 0,
        is_active=1,
        created_at=utcnow_iso(),
    )
    session.add(user)
    session.flush()
    return user


def authenticate(session, username: str, password: str) -> User | None:
    """Check credentials. Inactive accounts never authenticate."""
    user = get_user(session, username=username or "")
    if user is None or not user.is_active:
        return None
    if not verify_password(user, password):
        return None
    return user


def set_password(
    session,
    password: str,
    *,
    username: str | None = None,
    user_id: str | None = None,
) -> User:
    """Owner-driven password reset (no e-mail flow by design — see §4)."""
    _validate_password(password)
    user = require_user(session, username=username, user_id=user_id)
    user.password_hash = hash_password(password)
    session.flush()
    return user


def set_active(
    session,
    *,
    is_active: bool,
    username: str | None = None,
    user_id: str | None = None,
) -> User:
    user = require_user(session, username=username, user_id=user_id)
    user.is_active = 1 if is_active else 0
    session.flush()
    return user


# ════════════════════════════════════════════════════════════════════════
# API tokens
# ════════════════════════════════════════════════════════════════════════

def mint_token(
    session,
    user: User,
    *,
    name: str,
    kind: str,
    scopes: list[str] | tuple[str, ...] | None = None,
    expires_at: str | None = None,
    expires_in_days: int | None = None,
) -> tuple[str, ApiToken]:
    """Create a named credential. Returns (plaintext, row) — plaintext once.

    kind separates the credential families (D1/D3): a phone credential can
    never be used as an agent credential and vice versa.
    """
    if kind not in TOKEN_KINDS:
        raise IdentityError(
            f"Unknown token kind {kind!r}; expected one of {', '.join(TOKEN_KINDS)}"
        )
    name = (name or "").strip()
    if not name:
        raise IdentityError("Token needs a name (e.g. claude-desktop, phone-relay)")
    if len(name) > MAX_TOKEN_NAME:
        raise IdentityError(f"Token name must be at most {MAX_TOKEN_NAME} characters")

    scope_list = list(scopes) if scopes is not None else list(DEFAULT_SCOPES)
    unknown = [s for s in scope_list if s not in SCOPES]
    if unknown:
        raise IdentityError(
            f"Unknown scope(s): {', '.join(unknown)}; allowed: {', '.join(SCOPES)}"
        )

    if expires_in_days is not None:
        if expires_in_days <= 0:
            raise IdentityError("expires_in_days must be positive (omit for no expiry)")
        expires_at = (datetime.now(timezone.utc) + timedelta(days=expires_in_days)).isoformat()

    plaintext = generate_api_token(kind)
    row = ApiToken(
        id=str(uuid.uuid4()),
        user_id=user.id,
        name=name,
        kind=kind,
        token_hash=hash_api_token(plaintext),
        prefix=token_display_prefix(plaintext),
        scopes=json.dumps(scope_list),
        expires_at=expires_at,
        created_at=utcnow_iso(),
    )
    session.add(row)
    session.flush()
    return plaintext, row


def list_tokens(session, user: User) -> list[ApiToken]:
    return list(session.scalars(
        select(ApiToken).where(ApiToken.user_id == user.id).order_by(ApiToken.created_at)
    ))


def find_token(session, user: User, identifier: str) -> ApiToken:
    """Look a token up by full id or by its 12-character display prefix."""
    identifier = (identifier or "").strip()
    if not identifier:
        raise IdentityError("Token id or prefix required")
    matches = list(session.scalars(
        select(ApiToken).where(
            ApiToken.user_id == user.id,
            or_(ApiToken.id == identifier, ApiToken.prefix == identifier),
        )
    ))
    if not matches:
        raise IdentityError(f"No token matching {identifier!r} for user {user.username}")
    if len(matches) > 1:
        raise IdentityError(
            f"Prefix {identifier!r} matches {len(matches)} tokens — use the full id"
        )
    return matches[0]


def revoke_token(session, user: User, identifier: str) -> tuple[ApiToken, bool]:
    """Revoke a token. Returns (row, changed) so callers can stay idempotent."""
    row = find_token(session, user, identifier)
    if row.revoked_at is not None:
        return row, False
    row.revoked_at = utcnow_iso()
    session.flush()
    return row, True


def token_state(row: ApiToken) -> str:
    """One-word status for listings: active / revoked / expired."""
    if row.revoked_at is not None:
        return "revoked"
    if row.expires_at is not None and row.expires_at <= utcnow_iso():
        return "expired"
    return "active"


# ════════════════════════════════════════════════════════════════════════
# Safety config (per-user governor overrides; NULL column = follow default)
# ════════════════════════════════════════════════════════════════════════

def get_safety_overrides(session, user_id: str) -> dict:
    """Overrides only — a NULL column means "use the server default"."""
    row = session.get(SafetyConfig, user_id)
    if row is None:
        return {}
    result: dict = {"governor_enabled": bool(row.governor_enabled)}
    for key in NUMERIC_SAFETY_KEYS:
        value = getattr(row, key)
        if value is not None:
            result[key] = value
    return result


def set_safety_overrides(session, user_id: str, overrides: dict) -> dict:
    """Upsert overrides. Unknown keys are ignored, non-numbers are rejected.

    `governor_enabled` is stored as an int on purpose: kotlinx-serialization
    on the Android side rejects a String "true"/"false" that round-trips
    through SQLite, so the boolean is normalised before it reaches the row.
    """
    filtered: dict = {}
    for key, value in (overrides or {}).items():
        if key not in SAFETY_KEYS:
            continue
        if key == "governor_enabled":
            filtered[key] = int(bool(value))
            continue
        if value is None:
            filtered[key] = None  # explicit NULL = fall back to the default
            continue
        try:
            number = float(value)
        except (TypeError, ValueError):
            raise IdentityError(f"{key} must be a number, got {value!r}") from None
        if not math.isfinite(number):
            raise IdentityError(f"{key} must be a finite number, got {value!r}")
        filtered[key] = number

    row = session.get(SafetyConfig, user_id)
    if row is None:
        if session.get(User, user_id) is None:
            raise IdentityError(f"No such user: {user_id}")
        row = SafetyConfig(user_id=user_id)
        session.add(row)
    for key, value in filtered.items():
        setattr(row, key, value)
    row.updated_at = utcnow_iso()
    session.flush()
    return get_safety_overrides(session, user_id)
