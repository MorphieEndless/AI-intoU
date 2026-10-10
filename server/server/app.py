"""
AI-intoU — main server application (legacy package entry point).

Single FastAPI app that serves three roles:
  1. Accounts & credentials (invite registration, login, API tokens, admin)
     — routers in `app.api`
  2. MCP endpoint (Streamable HTTP — tool calls from AI clients)
  3. WebSocket relay hub (persistent phone connections)

Every credential goes through the single resolver
`app.core.security.resolve_principal` (architecture doc §5):
  · POST /mcp      agent token (or a session credential)
  · WS /ws/phone   phone token only
  · REST /api/*    session credential (see app.api)
There is no static token, no sole-phone fallback and no OAuth (M2b).
"""
from __future__ import annotations
import asyncio
import json
import logging
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api import accounts_router, admin_router
from app.api.deps import require
from app.config import removed_settings_present
from app.core.security import describe_credential, extract_bearer, resolve_credential, resolve_principal
from app.domain.identity import IdentityError
from app.infra.legacy_migration import prepare_database
from . import config
from .auth import ip_tracker, rate_limiter, get_safety_config, set_safety_config
from .mcp_tools import TOOLS, HANDLERS, current_user_id
from .pattern_routes import router as pattern_router
from .relay_hub import check_ws_ip_limit, release_ws_ip_slot, get_ip_from_headers
from .session_registry import registry
from .governor import governor
from .safety import dead_man_switch

# ── Logging ─────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("signal_bridge")


# ── Lifespan ────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    config.validate()
    # Legacy databases are migrated (with a backup) before anything serves.
    await asyncio.to_thread(prepare_database, config.settings.DB_PATH, config.settings.PATTERNS_DIR)
    for name in removed_settings_present():
        log.warning(f"{name} is set but no longer used (removed in the account/token migration)")
    await dead_man_switch.start()
    log.info(f"AI-intoU started on {config.HOST}:{config.PORT}")
    log.info(f"Registration {'OPEN' if config.REGISTRATION_OPEN else 'invite-only'}")
    yield
    await dead_man_switch.stop()
    log.info("AI-intoU shutting down")


app = FastAPI(
    title="AI-intoU",
    version="1.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=config.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(accounts_router)
app.include_router(admin_router)
app.include_router(pattern_router)


def _get_ip(request: Request) -> str:
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


# ════════════════════════════════════════════════════════════════════════
# MCP Endpoint — Streamable HTTP (JSON-RPC over POST + GET)
#
# Implements the MCP Streamable HTTP transport spec:
#   - POST: JSON-RPC requests from client
#   - GET: SSE stream for server-to-client notifications (kept open)
#   - Mcp-Session-Id header for session tracking
#   - Authless mode for claude.ai connector, Bearer token for Claude Desktop
# ════════════════════════════════════════════════════════════════════════

MCP_AUTH_HELP = (
    "需要 AI 接入 token：在樱趣 App「设置 → AI 接入」里生成，"
    "填到请求头 Authorization: Bearer aiu_agent_…"
)


async def _resolve_mcp_user(request: Request) -> tuple[dict | None, str]:
    """Agent token (or a person's session). Returns (user, rejection message)."""
    header = request.headers.get("Authorization", "")
    principal = await asyncio.to_thread(resolve_principal, header)
    if principal is None:
        return None, MCP_AUTH_HELP
    if not principal.allows(("agent",), allow_session=True):
        return None, "这是手机中继用的 token，不能给 AI 用。" + MCP_AUTH_HELP
    return {"user_id": principal.user_id, "username": principal.username,
            "label": principal.label}, ""


@app.post("/mcp")
async def mcp_endpoint(request: Request):
    """
    MCP Streamable HTTP endpoint (POST).

    Accepts JSON-RPC requests, routes tool calls to the authenticated
    user's phone via the session registry.
    """
    ip = _get_ip(request)

    if await ip_tracker.is_banned(ip):
        return JSONResponse({"error": "Temporarily banned"}, status_code=429)

    if not await rate_limiter.check(f"global:{ip}", config.RATE_LIMIT_GLOBAL):
        return JSONResponse({"error": "Rate limit exceeded"}, status_code=429)

    # Parse JSON-RPC first (we need to check if it's an initialize request)
    try:
        body = await request.json()
    except Exception:
        return _jsonrpc_error(None, -32700, "Parse error: invalid JSON")

    method = body.get("method", "")
    params = body.get("params", {})
    req_id = body.get("id")
    # Notifications (no id) — spec requires a bare 202 ACK, not a JSON-RPC error.
    if req_id is None:
        return Response(status_code=202)

    # Resolve user — every request carries its own credential
    user, rejection = await _resolve_mcp_user(request)
    if not user:
        await ip_tracker.record_failure(ip)
        return JSONResponse(
            {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": rejection}},
            status_code=401,
        )

    # Rate limit per user for commands
    if not await rate_limiter.check(
        f"cmd:{user['user_id']}", config.RATE_LIMIT_COMMANDS
    ):
        return JSONResponse(
            {"jsonrpc": "2.0", "error": {"code": -32000, "message": "Command rate limit exceeded"}},
            status_code=429,
        )

    # Set user context for tool handlers
    current_user_id.set(user["user_id"])

    # ── Route by method ─────────────────────────────────────────────

    if method == "initialize":
        # The session id satisfies the transport spec; it is never used as a
        # credential (the Bearer header is checked on every request).
        session_id = str(uuid.uuid4())
        log.info(f"MCP session created: {session_id[:8]}... for {user['username']} via {user['label']}")
        client_ver = (params or {}).get("protocolVersion", "2025-03-26")
        supported = {"2024-11-05", "2025-03-26", "2025-06-18"}

        result = {
            "protocolVersion": client_ver if client_ver in supported else "2025-03-26",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "AI-intoU", "version": "1.1.0"},
        }
        response = JSONResponse({"jsonrpc": "2.0", "id": req_id, "result": result})
        response.headers["Mcp-Session-Id"] = session_id
        return response

    elif method == "tools/list":
        log.info(f"tools/list hit — user={user['user_id']} ua={request.headers.get('user-agent','?')}")
        return _jsonrpc_result(req_id, {"tools": TOOLS})

    elif method == "tools/call":
        tool_name = params.get("name", "")
        tool_args = params.get("arguments", {})

        handler = HANDLERS.get(tool_name)
        if not handler:
            return _jsonrpc_error(req_id, -32601, f"Unknown tool: {tool_name}")

        try:
            result_text = await handler(**tool_args)
            return _jsonrpc_result(req_id, {
                "content": [{"type": "text", "text": result_text}],
            })
        except Exception as e:
            log.error(f"Tool {tool_name} error: {e}")
            return _jsonrpc_result(req_id, {
                "content": [{"type": "text", "text": f"Error: {e}"}],
                "isError": True,
            })

    elif method == "ping":
        return _jsonrpc_result(req_id, {})

    elif method == "resources/list":
        return _jsonrpc_result(req_id, {"resources": []})

    elif method == "prompts/list":
        return _jsonrpc_result(req_id, {"prompts": []})

    elif method.startswith("notifications/"):
        # MCP notifications (e.g. notifications/initialized) are fire-and-forget.
        # Return empty success — no error, no noise.
        return _jsonrpc_result(req_id, {})

    else:
        return _jsonrpc_error(req_id, -32601, f"Unknown method: {method}")


@app.get("/mcp")
async def mcp_sse_endpoint(request: Request):
    """
    MCP Streamable HTTP endpoint (GET).

    Opens an SSE stream for server-to-client notifications.
    We don't currently use server-initiated notifications,
    so this just stays open to satisfy the spec.
    """
    from starlette.responses import StreamingResponse

    async def event_stream():
        # Send a keep-alive comment, then hold the connection open
        yield ": connected\n\n"
        try:
            while True:
                await asyncio.sleep(30)
                yield ": keepalive\n\n"
        except asyncio.CancelledError:
            pass

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
        },
    )


def _jsonrpc_result(req_id, result):
    return JSONResponse({"jsonrpc": "2.0", "id": req_id, "result": result})


def _jsonrpc_error(req_id, code, message):
    return JSONResponse(
        {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}
    )


# ════════════════════════════════════════════════════════════════════════
# WebSocket Relay — Phone connections
# ════════════════════════════════════════════════════════════════════════

@app.websocket("/ws/phone")
async def websocket_phone(websocket: WebSocket):
    """
    WebSocket endpoint for phone relay clients.
    The phone connects here, authenticates with its JWT,
    and maintains a persistent connection for receiving device commands.
    """
    await websocket.accept()
    await _handle_phone_ws(websocket)


async def _handle_phone_ws(ws: WebSocket):
    """
    Full phone WebSocket lifecycle: auth → register → message loop → cleanup.
    """
    from .models import CommandAck

    ip = get_ip_from_headers(
        ws.client.host if ws.client else None,
        dict(ws.headers) if ws.headers else None,
    )

    # IP-level rate limiting
    rejection = await check_ws_ip_limit(ip)
    if rejection:
        await ws.close(4003, rejection)
        return

    user_id = None
    session = None
    try:
        # Wait for auth message
        raw = await asyncio.wait_for(ws.receive_text(), timeout=10.0)
        msg = json.loads(raw)

        if msg.get("type") != "phone_auth" or "token" not in msg:
            await ws.send_json({"type": "auth_error", "message": "First message must be phone_auth"})
            await ws.close(4001, "First message must be phone_auth")
            await ip_tracker.record_failure(ip)
            return

        credential = str(msg.get("token") or "")
        principal = await asyncio.to_thread(resolve_credential, credential)
        if principal is None or principal.kind != "phone":
            family = describe_credential(credential)
            if principal is not None and principal.kind == "agent":
                message = "这是 AI 接入 token，手机需要 aiu_phone_ 开头的手机 token"
            elif principal is not None:
                message = "App 版本过旧：请升级樱趣 App 后重新登录"
            else:
                message = ("凭证无效或已被撤销。旧版 Bearer Token 已停用："
                           "请升级 App 后用账号登录，或填写 aiu_phone_ 开头的手机 token")
            log.warning(f"[PHONE] Rejecting phone auth ({family}) from {ip}")
            await ws.send_json({"type": "auth_error", "message": message})
            await ws.close(4001, "Invalid token")
            await ip_tracker.record_failure(ip)
            return

        user = {"user_id": principal.user_id, "username": principal.username}
        user_id = user["user_id"]
        await ip_tracker.clear_failures(ip)
        await ws.send_json({
            "type": "auth_ok",
            "user_id": user_id,
            "message": "Connected to AI-intoU relay",
        })
        log.info(f"Phone connected: user={user['username']} ip={ip}")

        # Create a wrapper that looks like a websockets ServerConnection
        wrapper = _FastAPIWSWrapper(ws)
        session = await registry.register(user_id, wrapper)
        session.token_id = principal.token_id

        # Load per-user governor config from database
        effective_config = _effective_safety_config(user_id)
        governor.apply_user_config(user_id, effective_config)

        # Request device list (phone also sends proactively, but this is a backup)
        log.info(f"Requesting device scan from phone: user={user_id}")
        await ws.send_json({"type": "scan"})

        # Message loop
        while True:
            try:
                raw = await ws.receive_text()
                msg = json.loads(raw)
                msg_type = msg.get("type")

                if msg_type == "heartbeat_pong":
                    await registry.update_heartbeat(user_id, session)
                elif msg_type == "command_ack":
                    ack = CommandAck(
                        success=msg.get("success", True),
                        message=msg.get("message", ""),
                        request_id=msg.get("request_id"),
                        data=msg.get("data"),
                    )
                    if ack.request_id:
                        session.resolve_ack(ack.request_id, ack)
                elif msg_type == "phone_emergency_stop" and await registry.is_current(user_id, session):
                    # Phone-initiated emergency stop (volume keys, etc.)
                    # Tell the governor so heat stops accumulating
                    governor.record_stop(user_id)
                    log.warning(f"Phone emergency stop: user={user_id}")
                elif msg_type == "device_list":
                    devices = msg.get("devices", [])
                    await registry.update_devices(user_id, devices, session)
                    log.info(f"Devices updated: user={user_id}, count={len(devices)}")

            except WebSocketDisconnect:
                break
            except json.JSONDecodeError:
                continue

    except asyncio.TimeoutError:
        await ws.close(4001, "Auth timeout")
    except WebSocketDisconnect:
        pass
    except Exception as e:
        log.error(f"Phone WS error: {e}")
    finally:
        if session and await registry.unregister(user_id, session):
            governor.remove_user(user_id)
            log.info(f"Phone disconnected: user={user_id}")
        await release_ws_ip_slot(ip)


class _FastAPIWSWrapper:
    """
    Minimal wrapper to make a FastAPI WebSocket look enough like a
    websockets ServerConnection for the session registry and safety module.
    """
    def __init__(self, ws: WebSocket):
        self._ws = ws

    async def send(self, data: str):
        await self._ws.send_text(data)

    async def close(self, code: int = 1000, reason: str = ""):
        await self._ws.close(code, reason)

    @property
    def transport(self):
        return self  # duck typing for _get_ip fallback

    def get_extra_info(self, key):
        if key == "peername" and self._ws.client:
            return (self._ws.client.host, self._ws.client.port)
        return None


# ════════════════════════════════════════════════════════════════════════
# Safety Config (per-user governor settings)
# ════════════════════════════════════════════════════════════════════════

def _effective_safety_config(user_id: str) -> dict:
    """Merge per-user overrides with server defaults."""
    defaults = {
        "governor_enabled": config.GOVERNOR_ENABLED,
        "heat_rate": config.GOVERNOR_HEAT_RATE,
        "cool_rate": config.GOVERNOR_COOL_RATE,
        "cooldown_threshold": config.GOVERNOR_COOLDOWN_THRESHOLD,
        "cooldown_exit": config.GOVERNOR_COOLDOWN_EXIT,
        "cooldown_duration": config.GOVERNOR_COOLDOWN_DURATION,
    }
    overrides = get_safety_config(user_id)
    merged = {**defaults, **overrides}
    return merged


async def _safety_principal(request: Request):
    # The App reads/writes its own safety settings with its phone token or a
    # session; an AI client cannot loosen its own limits.
    return await require(request, kinds=("phone",), allow_session=True)


@app.get("/safety/config")
async def get_safety_config_endpoint(request: Request):
    """Get the effective safety config for the authenticated user."""
    principal = await _safety_principal(request)
    return await asyncio.to_thread(_effective_safety_config, principal.user_id)


@app.post("/safety/config")
async def set_safety_config_endpoint(request: Request):
    """Update per-user safety config overrides."""
    principal = await _safety_principal(request)
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({"error": "Invalid JSON"}, status_code=400)
    if not isinstance(body, dict):
        return JSONResponse({"error": "Body must be a JSON object"}, status_code=400)

    try:
        overrides = await asyncio.to_thread(set_safety_config, principal.user_id, body)
    except IdentityError as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)
    effective = await asyncio.to_thread(_effective_safety_config, principal.user_id)
    governor.apply_user_config(principal.user_id, effective)
    log.info(f"Safety config updated for user {principal.user_id}: {overrides}")
    return effective


@app.get("/safety/status")
async def safety_status(request: Request):
    """Get current governor state for the authenticated user."""
    principal = await _safety_principal(request)
    state = governor.get_state(principal.user_id)
    state["config"] = await asyncio.to_thread(_effective_safety_config, principal.user_id)
    return state


# ════════════════════════════════════════════════════════════════════════
# Health & Status
# ════════════════════════════════════════════════════════════════════════

@app.get("/health")
async def health():
    return {
        "status": "ok",
        "active_phones": registry.active_count,
        "banned_ips": ip_tracker.banned_count,
    }


# ════════════════════════════════════════════════════════════════════════
# Init module
# ════════════════════════════════════════════════════════════════════════

@app.get("/")
async def root():
    raise HTTPException(status_code=404, detail="Not Found")
