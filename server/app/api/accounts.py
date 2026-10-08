"""Accounts & credentials: register (invite), login, me, API tokens.

Endpoints
  POST   /auth/register        invite code (or open registration) → session
  POST   /auth/login           username + password → session
  GET    /api/me               who am I, am I the owner, is my phone online
  POST   /api/me/password      change my own password
  GET    /api/tokens           my tokens (never the plaintext)
  POST   /api/tokens           mint an agent / phone token (plaintext once)
  DELETE /api/tokens/{id}      revoke; a phone using it is disconnected

Every /api/* endpoint here takes a *session* credential only: an AI client
or a phone must never be able to mint or revoke credentials (§5).
"""
from __future__ import annotations

import asyncio
import logging
import re

from fastapi import APIRouter, HTTPException, Request
from pydantic import ValidationError
from sqlalchemy import select

from app.api.deps import client_ip, guard_auth_attempt, json_body, require_session
from app.config import settings
from app.core.rate_limit import ip_tracker
from app.core.security import issue_session_token
from app.db import session_scope
from app.domain.identity import (
    IdentityError,
    create_user,
    get_user,
    list_tokens,
    mint_token,
    revoke_token,
    set_password,
    token_state,
    verify_password,
)
from app.domain.invites import InviteError, register_with_invite
from app.infra.session_registry import registry
from app.models import ApiToken
from app.schemas.accounts import (
    ChangePasswordRequest,
    CreatedToken,
    CreateTokenRequest,
    LoginRequest,
    MeResponse,
    RegisterRequest,
    SessionResponse,
    TokenInfo,
    TokenList,
)

log = logging.getLogger("ai_intou.accounts")
router = APIRouter(tags=["accounts"])

MAX_ACTIVE_TOKENS = 20
USERNAME_RE = re.compile(r"[\w.\-@]{3,32}")

# Domain messages are English (they are also CLI output); people using the
# App read Chinese.
_MESSAGES = {
    "Username must be 3-32 characters": "用户名需要 3-32 个字符",
    "Password must be at least 8 characters": "密码至少需要 8 位",
    "Username already taken": "这个用户名已经被占用了，换一个吧",
    "Token needs a name (e.g. claude-desktop, phone-relay)": "请给这个接入起个名字（比如 RikkaHub）",
}


def _say(exc: IdentityError) -> str:
    text = str(exc)
    if text in _MESSAGES:
        return _MESSAGES[text]
    if text.startswith("Token name must be at most"):
        return "名字太长了"
    return text


def _parse(model, body: dict):
    try:
        return model.model_validate(body)
    except ValidationError as exc:
        first = exc.errors()[0] if exc.errors() else {}
        field = ".".join(str(p) for p in first.get("loc", ())) or "请求"
        raise HTTPException(400, f"{field} 字段不合法") from None


def _session_response(user) -> dict:
    return SessionResponse(
        user_id=user.id,
        username=user.username,
        token=issue_session_token(user.id, user.username),
        expires_in_hours=settings.SESSION_TOKEN_TTL_HOURS,
        is_admin=bool(user.is_admin),
    ).model_dump()


def token_info(row: ApiToken) -> TokenInfo:
    return TokenInfo(
        id=row.id, name=row.name, kind=row.kind, prefix=row.prefix,
        state=token_state(row), created_at=row.created_at,
        last_used_at=row.last_used_at, expires_at=row.expires_at,
        revoked_at=row.revoked_at,
    )


# ════════════════════════════════════════════════════════════════════════
# Register / login
# ════════════════════════════════════════════════════════════════════════

@router.post("/auth/register")
async def register(request: Request):
    ip = await guard_auth_attempt(request)
    req = _parse(RegisterRequest, await json_body(request))
    username = req.username.strip().lower()
    if not USERNAME_RE.fullmatch(username):
        raise HTTPException(400, "用户名需要 3-32 个字符，只能用字母、数字、中文和 . _ - @")

    def _create():
        with session_scope() as s:
            if req.invite_code.strip():
                user = register_with_invite(s, req.invite_code, username, req.password)
            elif settings.REGISTRATION_OPEN:
                user = create_user(s, username, req.password)
            else:
                raise InviteError("这个服务器需要邀请码才能注册，请向部署者要一个")
            return _session_response(user)

    try:
        result = await asyncio.to_thread(_create)
    except InviteError as exc:
        if req.invite_code.strip():
            await ip_tracker.record_failure(ip)  # guessing codes counts toward a ban
        raise HTTPException(403, str(exc)) from None
    except IdentityError as exc:
        raise HTTPException(400, _say(exc)) from None
    await ip_tracker.clear_failures(ip)
    log.info("Account registered: %s (invite=%s)", result["username"], bool(req.invite_code.strip()))
    return result


@router.post("/auth/login")
async def login(request: Request):
    ip = await guard_auth_attempt(request)
    req = _parse(LoginRequest, await json_body(request))

    def _check():
        with session_scope() as s:
            user = get_user(s, username=req.username or "-")
            if user is None or not verify_password(user, req.password):
                return None, False
            if not user.is_active:
                return None, True
            return _session_response(user), False

    result, disabled = await asyncio.to_thread(_check)
    if disabled:
        raise HTTPException(403, "这个账号已被停用，请联系部署者")
    if result is None:
        await ip_tracker.record_failure(ip)
        raise HTTPException(401, "用户名或密码错误")
    await ip_tracker.clear_failures(ip)
    return result


# ════════════════════════════════════════════════════════════════════════
# Me
# ════════════════════════════════════════════════════════════════════════

@router.get("/api/me")
async def me(request: Request):
    principal = await require_session(request)

    def _load():
        with session_scope() as s:
            return get_user(s, user_id=principal.user_id)

    user = await asyncio.to_thread(_load)
    if user is None:
        raise HTTPException(401, "账号不存在")
    online = await registry.get_session(user.id) is not None
    return MeResponse(
        user_id=user.id, username=user.username, is_admin=bool(user.is_admin),
        phone_online=online, registration_open=settings.REGISTRATION_OPEN,
    ).model_dump()


@router.post("/api/me/password")
async def change_password(request: Request):
    principal = await require_session(request)
    ip = await guard_auth_attempt(request)
    req = _parse(ChangePasswordRequest, await json_body(request))

    def _change():
        with session_scope() as s:
            user = get_user(s, user_id=principal.user_id)
            if user is None or not verify_password(user, req.old_password):
                return False
            set_password(s, req.new_password, user_id=user.id)
            return True

    try:
        ok = await asyncio.to_thread(_change)
    except IdentityError as exc:
        raise HTTPException(400, _say(exc)) from None
    if not ok:
        await ip_tracker.record_failure(ip)
        raise HTTPException(403, "原密码不正确")
    return {"ok": True}


# ════════════════════════════════════════════════════════════════════════
# API tokens
# ════════════════════════════════════════════════════════════════════════

@router.get("/api/tokens")
async def get_tokens(request: Request):
    principal = await require_session(request)

    def _list():
        with session_scope() as s:
            user = get_user(s, user_id=principal.user_id)
            rows = list_tokens(s, user) if user else []
            return [token_info(r) for r in reversed(rows)]

    return TokenList(tokens=await asyncio.to_thread(_list)).model_dump()


@router.post("/api/tokens")
async def create_token(request: Request):
    principal = await require_session(request)
    req = _parse(CreateTokenRequest, await json_body(request))

    def _mint():
        with session_scope() as s:
            user = get_user(s, user_id=principal.user_id)
            if user is None:
                raise IdentityError("账号不存在")
            replaced = 0
            name = req.name.strip()
            if req.replace_existing:
                for row in s.scalars(select(ApiToken).where(
                    ApiToken.user_id == user.id, ApiToken.kind == req.kind,
                    ApiToken.name == name, ApiToken.revoked_at.is_(None),
                )):
                    revoke_token(s, user, row.id)
                    replaced += 1
            active = sum(1 for r in list_tokens(s, user) if token_state(r) == "active")
            if active >= MAX_ACTIVE_TOKENS:
                raise IdentityError(f"有效 token 最多 {MAX_ACTIVE_TOKENS} 个，先撤销几个不用的吧")
            plaintext, row = mint_token(s, user, name=name, kind=req.kind)
            return CreatedToken(token=plaintext, info=token_info(row), replaced=replaced)

    try:
        created = await asyncio.to_thread(_mint)
    except IdentityError as exc:
        raise HTTPException(400, _say(exc)) from None
    log.info("Token minted: user=%s kind=%s name=%s", principal.username, req.kind, req.name)
    return created.model_dump()


@router.delete("/api/tokens/{token_id}")
async def delete_token(token_id: str, request: Request):
    principal = await require_session(request)

    def _revoke():
        with session_scope() as s:
            user = get_user(s, user_id=principal.user_id)
            row = s.get(ApiToken, token_id)
            if user is None or row is None or row.user_id != user.id:
                raise IdentityError("找不到这个 token")
            row, changed = revoke_token(s, user, row.id)
            return token_info(row), changed

    try:
        info, changed = await asyncio.to_thread(_revoke)
    except IdentityError as exc:
        raise HTTPException(404, _say(exc)) from None
    if info.kind == "phone":
        await registry.disconnect(principal.user_id, token_id=info.id, reason="Phone token revoked")
    return {"revoked": changed, "info": info.model_dump()}
