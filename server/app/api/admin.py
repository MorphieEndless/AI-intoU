"""Owner-only administration: invite codes and accounts.

Endpoints (session credential of an active owner account)
  GET    /api/admin/invites                list invites
  POST   /api/admin/invites                mint an invite (plaintext once)
  DELETE /api/admin/invites/{id}           revoke an invite
  GET    /api/admin/users                  list accounts with online state
  POST   /api/admin/users/{id}/active      enable / disable (disable kicks the phone)
  POST   /api/admin/users/{id}/password    reset a password (no e-mail flow, §4)
"""
from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, HTTPException, Request
from pydantic import ValidationError

from app.api.accounts import _say
from app.api.deps import json_body, require_admin
from app.db import session_scope
from app.domain.identity import IdentityError, list_tokens, list_users, set_active, set_password, token_state
from app.domain.invites import InviteError, create_invite, list_invites, revoke_invite
from app.infra.session_registry import registry
from app.models import Invite, User
from app.schemas.accounts import (
    CreatedInvite,
    CreateInviteRequest,
    InviteInfo,
    InviteList,
    ResetPasswordRequest,
    SetActiveRequest,
    UserInfo,
    UserList,
)

log = logging.getLogger("ai_intou.admin")
router = APIRouter(prefix="/api/admin", tags=["admin"])


def _parse(model, body: dict):
    try:
        return model.model_validate(body)
    except ValidationError as exc:
        first = exc.errors()[0] if exc.errors() else {}
        field = ".".join(str(p) for p in first.get("loc", ())) or "请求"
        raise HTTPException(400, f"{field} 字段不合法") from None


def invite_info(row: Invite) -> InviteInfo:
    return InviteInfo(
        id=row.id, prefix=row.prefix, note=row.note, max_uses=row.max_uses,
        used_count=row.used_count, used_by=[u for u in row.used_by.split(",") if u],
        state=row.state, created_at=row.created_at, expires_at=row.expires_at,
        last_used_at=row.last_used_at,
    )


# ── invites ────────────────────────────────────────────────────────────

@router.get("/invites")
async def get_invites(request: Request):
    await require_admin(request)

    def _list():
        with session_scope() as s:
            return [invite_info(r) for r in list_invites(s)]

    return InviteList(invites=await asyncio.to_thread(_list)).model_dump()


@router.post("/invites")
async def post_invite(request: Request):
    principal = await require_admin(request)
    req = _parse(CreateInviteRequest, await json_body(request))

    def _create():
        with session_scope() as s:
            owner = s.get(User, principal.user_id)
            code, row = create_invite(
                s, owner, max_uses=req.max_uses,
                expires_in_days=req.expires_in_days, note=req.note,
            )
            return CreatedInvite(code=code, info=invite_info(row))

    try:
        created = await asyncio.to_thread(_create)
    except InviteError as exc:
        raise HTTPException(400, str(exc)) from None
    log.info("Invite minted by %s: prefix=%s uses=%s", principal.username,
             created.info.prefix, created.info.max_uses)
    return created.model_dump()


@router.delete("/invites/{invite_id}")
async def delete_invite(invite_id: str, request: Request):
    await require_admin(request)

    def _revoke():
        with session_scope() as s:
            if s.get(Invite, invite_id) is None:
                raise InviteError("找不到这个邀请码")
            row, changed = revoke_invite(s, invite_id)
            return invite_info(row), changed

    try:
        info, changed = await asyncio.to_thread(_revoke)
    except InviteError as exc:
        raise HTTPException(404, str(exc)) from None
    return {"revoked": changed, "info": info.model_dump()}


# ── users ──────────────────────────────────────────────────────────────

@router.get("/users")
async def get_users(request: Request):
    await require_admin(request)
    online = await registry.online_user_ids()

    def _list():
        with session_scope() as s:
            result = []
            for user in list_users(s):
                counts: dict[str, int] = {}
                for row in list_tokens(s, user):
                    if token_state(row) == "active":
                        counts[row.kind] = counts.get(row.kind, 0) + 1
                result.append(UserInfo(
                    id=user.id, username=user.username, is_admin=bool(user.is_admin),
                    is_active=bool(user.is_active), created_at=user.created_at,
                    phone_online=user.id in online, active_tokens=counts,
                ))
            return result

    return UserList(users=await asyncio.to_thread(_list)).model_dump()


@router.post("/users/{user_id}/active")
async def post_user_active(user_id: str, request: Request):
    principal = await require_admin(request)
    req = _parse(SetActiveRequest, await json_body(request))
    if user_id == principal.user_id and not req.is_active:
        raise HTTPException(400, "不能停用你自己的账号")

    def _set():
        with session_scope() as s:
            user = set_active(s, is_active=req.is_active, user_id=user_id)
            return user.username

    try:
        username = await asyncio.to_thread(_set)
    except IdentityError:
        raise HTTPException(404, "找不到这个账号") from None
    if not req.is_active:
        await registry.disconnect(user_id, reason="Account disabled")
    log.info("Account %s %s by %s", username, "enabled" if req.is_active else "disabled",
             principal.username)
    return {"ok": True, "is_active": req.is_active}


@router.post("/users/{user_id}/password")
async def post_user_password(user_id: str, request: Request):
    principal = await require_admin(request)
    req = _parse(ResetPasswordRequest, await json_body(request))

    def _reset():
        with session_scope() as s:
            if s.get(User, user_id) is None:
                raise LookupError
            set_password(s, req.password, user_id=user_id)

    try:
        await asyncio.to_thread(_reset)
    except LookupError:
        raise HTTPException(404, "找不到这个账号") from None
    except IdentityError as exc:
        raise HTTPException(400, _say(exc)) from None
    log.info("Password reset for %s by %s", user_id[:8], principal.username)
    return {"ok": True}
