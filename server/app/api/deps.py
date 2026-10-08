"""Request-level helpers shared by every REST router.

All authentication goes through `app.core.security.resolve_principal` —
the single resolver (architecture doc §5). This module only adapts its
answer to HTTP: which credential families an endpoint accepts, and the
message a person sees when they used the wrong one.
"""
from __future__ import annotations

import asyncio

from fastapi import HTTPException, Request

from app.core.rate_limit import ip_tracker, rate_limiter
from app.core.security import Principal, describe_credential, extract_bearer, resolve_principal
from app.db import session_scope
from app.models import User

WRONG_KIND_HINTS = {
    "agent token": "这是 AI 接入用的 token，只能填在 AI 客户端（MCP）里",
    "phone token": "这是手机中继用的 token，只能填在樱趣 App 里",
}


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


async def resolve(request: Request) -> Principal | None:
    header = request.headers.get("authorization", "")
    return await asyncio.to_thread(resolve_principal, header)


async def require(
    request: Request,
    *,
    kinds: tuple[str, ...] = (),
    allow_session: bool = False,
    scope: str | None = None,
) -> Principal:
    """Authenticate and check the credential family matches the endpoint."""
    principal = await resolve(request)
    if principal is None:
        raise HTTPException(401, "未登录或凭证已失效，请重新登录")
    if not principal.allows(kinds, allow_session=allow_session):
        family = describe_credential(extract_bearer(request.headers.get("authorization", "")))
        hint = WRONG_KIND_HINTS.get(family)
        if allow_session and not kinds:
            hint = "这个操作需要用账号密码登录后进行"
        raise HTTPException(403, hint or "这个凭证不能用于此操作")
    if scope and not principal.has_scope(scope):
        raise HTTPException(403, f"凭证缺少 {scope} 权限")
    return principal


async def require_session(request: Request) -> Principal:
    return await require(request, allow_session=True)


async def require_admin(request: Request) -> Principal:
    principal = await require_session(request)

    def _is_admin() -> bool:
        with session_scope() as s:
            user = s.get(User, principal.user_id)
            return bool(user and user.is_active and user.is_admin)

    if not await asyncio.to_thread(_is_admin):
        raise HTTPException(403, "只有部署者（owner）可以进行这个操作")
    return principal


async def guard_auth_attempt(request: Request) -> str:
    """Ban + rate-limit gate for credential-guessing endpoints. Returns the IP."""
    from app.config import settings

    ip = client_ip(request)
    if await ip_tracker.is_banned(ip):
        raise HTTPException(429, "尝试次数过多，请稍后再试")
    if not await rate_limiter.check(f"auth:{ip}", settings.RATE_LIMIT_AUTH):
        raise HTTPException(429, "尝试次数过多，请稍后再试")
    return ip


async def json_body(request: Request) -> dict:
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(400, "请求体不是合法的 JSON") from None
    if not isinstance(body, dict):
        raise HTTPException(400, "请求体必须是 JSON 对象")
    return body
