"""REST schemas for accounts, credentials and invites (architecture §1.3)."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.domain.invites import DEFAULT_EXPIRY_DAYS, MAX_EXPIRY_DAYS, MAX_NOTE, MAX_USES_LIMIT
from app.domain.identity import MAX_TOKEN_NAME


class _Request(BaseModel):
    model_config = ConfigDict(extra="ignore", str_strip_whitespace=False)


# ── auth ────────────────────────────────────────────────────────────────

class LoginRequest(_Request):
    username: str = Field(default="", max_length=64)
    password: str = Field(default="", max_length=256)


class RegisterRequest(LoginRequest):
    invite_code: str = Field(default="", max_length=64)


class SessionResponse(BaseModel):
    """Same keys the App already reads (`token`, `username`) plus new ones."""
    user_id: str
    username: str
    token: str
    token_type: Literal["session"] = "session"
    expires_in_hours: int
    is_admin: bool


class MeResponse(BaseModel):
    user_id: str
    username: str
    is_admin: bool
    phone_online: bool
    registration_open: bool


class ChangePasswordRequest(_Request):
    old_password: str = Field(default="", max_length=256)
    new_password: str = Field(default="", max_length=256)


# ── tokens ─────────────────────────────────────────────────────────────

class CreateTokenRequest(_Request):
    name: str = Field(default="", max_length=MAX_TOKEN_NAME)
    kind: Literal["agent", "phone"]
    # Revoke the caller's other active tokens of the same kind and name —
    # the App re-pairing the same phone should not pile up credentials.
    replace_existing: bool = False


class TokenInfo(BaseModel):
    id: str
    name: str
    kind: str
    prefix: str
    state: str
    created_at: str
    last_used_at: str | None = None
    expires_at: str | None = None
    revoked_at: str | None = None


class TokenList(BaseModel):
    tokens: list[TokenInfo]


class CreatedToken(BaseModel):
    token: str  # plaintext — shown exactly once
    info: TokenInfo
    replaced: int = 0


# ── admin ──────────────────────────────────────────────────────────────

class CreateInviteRequest(_Request):
    max_uses: int = Field(default=1, ge=1, le=MAX_USES_LIMIT)
    expires_in_days: int = Field(default=DEFAULT_EXPIRY_DAYS, ge=1, le=MAX_EXPIRY_DAYS)
    note: str = Field(default="", max_length=MAX_NOTE)


class InviteInfo(BaseModel):
    id: str
    prefix: str
    note: str
    max_uses: int
    used_count: int
    used_by: list[str]
    state: str
    created_at: str
    expires_at: str | None = None
    last_used_at: str | None = None


class InviteList(BaseModel):
    invites: list[InviteInfo]


class CreatedInvite(BaseModel):
    code: str  # plaintext — shown exactly once
    info: InviteInfo


class UserInfo(BaseModel):
    id: str
    username: str
    is_admin: bool
    is_active: bool
    created_at: str
    phone_online: bool
    active_tokens: dict[str, int]


class UserList(BaseModel):
    users: list[UserInfo]


class SetActiveRequest(_Request):
    is_active: bool


class ResetPasswordRequest(_Request):
    password: str = Field(default="", max_length=256)
