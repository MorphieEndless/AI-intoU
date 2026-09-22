"""Token primitives and THE single authentication resolver (§5).

Every request walks through `resolve_principal()`:

  1. `Authorization: Bearer aiu_<kind>_<secret>` → the matching `api_tokens`
     row, provided the hash matches, the token is neither revoked nor
     expired and its owner is active.
  2. a short-lived session JWT (issued by password login, Web/CLI only).
  3. neither → `None`, and the caller answers 401. There is no third path.

Static bearer tokens, the sole-phone fallback and OAuth are gone for good
(01-product.md D1/D4) — nothing here can resurrect them.

Token plaintext format: `aiu_<kind>_<32 url-safe characters>`. Only the
SHA-256 hash and a 12-character display prefix are ever persisted, so the
plaintext exists exactly once, in the response that created it.
"""
from __future__ import annotations

import hashlib
import re
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import jwt
from sqlalchemy import select

from app.config import settings
from app.db import session_scope
from app.models import ApiToken, User
from app.models.base import utcnow_iso

TOKEN_PREFIX = "aiu"
TOKEN_KINDS: tuple[str, ...] = ("agent", "phone", "human")  # human reserved (D6)
SCOPES: tuple[str, ...] = ("control", "status", "config")
DISPLAY_PREFIX_LEN = 12
SECRET_CHARS = 32
SESSION_TOKEN_TYPE = "session"
JWT_ALGORITHM = "HS256"

TOKEN_RE = re.compile(
    rf"^{TOKEN_PREFIX}_({'|'.join(TOKEN_KINDS)})_[A-Za-z0-9_-]{{{SECRET_CHARS}}}$"
)


# ════════════════════════════════════════════════════════════════════════
# Principal — what a successfully resolved request is allowed to do
# ════════════════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class Principal:
    """An authenticated caller.

    kind is the API-token class ('agent' | 'phone' | 'human'), or None for
    a human session credential. `via` tells the two apart, so an access
    layer can require an API token kind while still accepting a session
    where the design says so (§5).
    """
    user_id: str
    kind: str | None
    scopes: tuple[str, ...]
    via: str  # "api_token" | "session"
    token_id: str | None = None
    label: str = ""  # token name, or username for sessions — logs only
    username: str = ""

    @property
    def is_session(self) -> bool:
        return self.via == "session"

    @property
    def kind_label(self) -> str:
        return self.kind or "session"

    def has_scope(self, scope: str) -> bool:
        return scope in self.scopes

    def allows(self, kinds: tuple[str, ...] = (), *, allow_session: bool = False) -> bool:
        """Does this credential match the entry point it arrived at?

        kinds          — API-token kinds this entry point accepts
        allow_session  — whether a human session credential is also accepted
        """
        if self.is_session:
            return allow_session
        return self.kind in kinds

    def __str__(self) -> str:  # pragma: no cover - log readability only
        return f"{self.kind_label}({self.user_id[:8]}…)" if self.user_id else self.kind_label


# ════════════════════════════════════════════════════════════════════════
# Token primitives (pure — no DB, no request)
# ════════════════════════════════════════════════════════════════════════

def generate_api_token(kind: str) -> str:
    """Mint a plaintext token. Shown once, never stored, never logged."""
    if kind not in TOKEN_KINDS:
        raise ValueError(
            f"unknown token kind {kind!r}; expected one of {', '.join(TOKEN_KINDS)}"
        )
    return f"{TOKEN_PREFIX}_{kind}_{secrets.token_urlsafe(24)}"


def hash_api_token(plaintext: str) -> str:
    """SHA-256 hex digest — what actually goes into the database."""
    return hashlib.sha256(plaintext.encode("utf-8")).hexdigest()


def token_display_prefix(plaintext: str) -> str:
    """First 12 characters, for a human to recognise a token in a list."""
    return plaintext[:DISPLAY_PREFIX_LEN]


def parse_api_token(plaintext: str | None) -> str | None:
    """Return the kind encoded in a well-formed token, else None."""
    if not plaintext:
        return None
    match = TOKEN_RE.match(plaintext.strip())
    return match.group(1) if match else None


def looks_like_api_token(plaintext: str | None) -> bool:
    return parse_api_token(plaintext) is not None


def extract_bearer(authorization: str | None) -> str | None:
    """Pull the credential out of an `Authorization: Bearer …` header."""
    if not authorization:
        return None
    parts = authorization.split()
    if len(parts) == 2 and parts[0].lower() == "bearer":
        return parts[1].strip() or None
    return None


# ════════════════════════════════════════════════════════════════════════
# Session credentials (password login → short-lived JWT)
# ════════════════════════════════════════════════════════════════════════

def issue_session_token(
    user_id: str,
    username: str,
    *,
    ttl_hours: int | None = None,
    now: datetime | None = None,
) -> str:
    """Issue the short-lived credential a human uses from Web/CLI (§5)."""
    issued = now or datetime.now(timezone.utc)
    ttl = settings.SESSION_TOKEN_TTL_HOURS if ttl_hours is None else ttl_hours
    payload = {
        "sub": user_id,
        "username": username,
        "typ": SESSION_TOKEN_TYPE,
        "scopes": list(SCOPES),
        "iat": issued,
        "exp": issued + timedelta(hours=ttl),
    }
    return jwt.encode(payload, settings.SECRET_KEY, algorithm=JWT_ALGORITHM)


def verify_session_token(token: str | None) -> Principal | None:
    """Validate a session JWT. Anything else (including an api token) is None."""
    if not token:
        return None
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[JWT_ALGORITHM])
    except jwt.InvalidTokenError:
        return None
    # An explicit type claim keeps the two credential families from ever
    # being interchangeable, even if one is pasted where the other belongs.
    if payload.get("typ") != SESSION_TOKEN_TYPE:
        return None
    user_id = payload.get("sub")
    if not user_id:
        return None
    scopes = tuple(payload.get("scopes") or SCOPES)
    return Principal(
        user_id=user_id,
        kind=None,
        scopes=scopes,
        via="session",
        label=str(payload.get("username") or ""),
        username=str(payload.get("username") or ""),
    )


# ════════════════════════════════════════════════════════════════════════
# API tokens (DB-backed)
# ════════════════════════════════════════════════════════════════════════

def _principal_from_hash(session, digest: str, *, touch: bool) -> Principal | None:
    row = session.scalar(select(ApiToken).where(ApiToken.token_hash == digest))
    if row is None or not row.is_active:
        return None
    owner = session.get(User, row.user_id)
    if owner is None or not owner.is_active:
        return None
    if touch:
        # Committed by whoever owns the transaction (session_scope, or the
        # caller when a session was passed in).
        row.last_used_at = utcnow_iso()
    return Principal(
        user_id=row.user_id,
        kind=row.kind,
        scopes=tuple(row.scope_list),
        via="api_token",
        token_id=row.id,
        label=row.name,
        username=owner.username,
    )


def lookup_api_token(
    plaintext: str | None,
    *,
    session=None,
    touch: bool = True,
) -> Principal | None:
    """Resolve an api token. Returns None for anything not currently valid.

    The lookup is by SHA-256 digest, so the plaintext never has to be
    compared and cannot leak through timing.
    """
    if parse_api_token(plaintext) is None:
        return None
    digest = hash_api_token(str(plaintext).strip())
    if session is not None:
        return _principal_from_hash(session, digest, touch=touch)
    with session_scope() as own_session:
        return _principal_from_hash(own_session, digest, touch=touch)


def resolve_principal(
    authorization: str | None,
    *,
    session=None,
    touch: bool = True,
) -> Principal | None:
    """THE authentication resolver (§5). One implementation, every entry point.

    Returns None when the caller must be rejected — the layer above decides
    whether that is a 401, a 4001 close code or a JSON-RPC error.
    """
    credential = extract_bearer(authorization)
    if not credential:
        return None
    if parse_api_token(credential) is not None:
        return lookup_api_token(credential, session=session, touch=touch)
    return verify_session_token(credential)


def require_scope(principal: Principal, scope: str) -> bool:
    """Scope gate for command/status/config endpoints (§5)."""
    return principal.has_scope(scope)
