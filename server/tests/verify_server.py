"""End-to-end verification of the AI-intoU server:
boot, health, MCP protocol behaviour, invite onboarding, credential
families, safety config.

Run from anywhere:  python tests/verify_server.py
Needs the server deps plus httpx (for fastapi.testclient). No hardware,
no network — everything runs in-process against a throwaway database.

Flow
  owner account (domain layer, as `python -m app.cli create-user` would)
  → owner logs in → mints an invite → newcomer registers with it
  → newcomer mints an agent token (AI client) and a phone token (App)
  → MCP with the agent token, relay with the phone token, safety config
"""
import os
import sys
import tempfile
from pathlib import Path

os.environ["SB_SECRET_KEY"] = "test-secret-key-for-verification-only"
_TMP = tempfile.mkdtemp()
os.environ["SB_DB_PATH"] = os.path.join(_TMP, "test.db")
os.environ["SB_PATTERNS_DIR"] = os.path.join(_TMP, "patterns")
# One process, one client IP: the production default (5/minute) would make
# this script fail as soon as it grows a sixth login/register step.
os.environ["SB_RATE_LIMIT_AUTH"] = "60/minute"
for _removed in ("SB_STATIC_BEARER_TOKEN", "SB_STATIC_USER_ID", "SB_REQUIRE_MCP_AUTH"):
    os.environ.pop(_removed, None)

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

from fastapi.testclient import TestClient  # noqa: E402

from app.db import session_scope, upgrade_to_head  # noqa: E402
from app.domain.identity import create_user  # noqa: E402
from server.app import app  # noqa: E402

PASS = []
FAIL = []
OWNER_PW = "owner-password-123"
NEWCOMER_PW = "newcomer-password-456"


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  ok  " if cond else "  FAIL") + f" {name}" + (f" — {detail}" if detail and not cond else ""))


def bearer(token):
    return {"Authorization": f"Bearer {token}"}


with TestClient(app) as client:
    # ── Boot & health ────────────────────────────────────────────────
    r = client.get("/health")
    check("health endpoint", r.status_code == 200 and r.json().get("status") == "ok", r.text)

    # ── MCP protocol basics (unauthenticated) ────────────────────────
    r = client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"})
    check("notification -> 202", r.status_code == 202, f"got {r.status_code}")

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
    check("unauthenticated initialize -> 401", r.status_code == 401, f"got {r.status_code}")

    for gone in ("/.well-known/oauth-authorization-server", "/oauth/register"):
        r = client.get(gone)
        check(f"removed OAuth route {gone} -> 404", r.status_code in (404, 405), f"got {r.status_code}")

    # ── Registration is invite-only ──────────────────────────────────
    r = client.post("/auth/register", json={"username": "drifter", "password": NEWCOMER_PW})
    check("registration without invite -> 403", r.status_code == 403, f"{r.status_code} {r.text[:120]}")

    # ── Owner account (what `create-user` / setup-server.sh does) ────
    upgrade_to_head()
    with session_scope() as s:
        owner = create_user(s, "owner", OWNER_PW)
        check("first account is the owner", bool(owner.is_admin))

    r = client.post("/auth/login", json={"username": "owner", "password": OWNER_PW})
    owner_session = r.json().get("token", "") if r.status_code == 200 else ""
    check("owner login", r.status_code == 200 and r.json().get("is_admin") is True, r.text[:200])

    r = client.post("/auth/login", json={"username": "owner", "password": "wrong-password"})
    check("wrong password -> 401", r.status_code == 401, f"got {r.status_code}")

    # ── Invite → register ────────────────────────────────────────────
    r = client.post("/api/admin/invites", headers=bearer(owner_session),
                    json={"max_uses": 1, "expires_in_days": 7, "note": "verify"})
    code = r.json().get("code", "") if r.status_code == 200 else ""
    check("owner mints an invite", r.status_code == 200 and len(code) == 14, r.text[:200])

    r = client.post("/auth/register", json={"username": "newcomer", "password": NEWCOMER_PW,
                                            "invite_code": code.lower().replace("-", " ")})
    body = r.json() if r.status_code == 200 else {}
    session = body.get("token", "")
    check("register with invite (forgiving input)", r.status_code == 200 and session, r.text[:200])
    check("invited account is not an owner", body.get("is_admin") is False, str(body)[:200])

    r = client.post("/auth/register", json={"username": "second", "password": NEWCOMER_PW,
                                            "invite_code": code})
    check("single-use invite cannot be reused", r.status_code == 403, f"got {r.status_code}")

    r = client.get("/api/admin/users", headers=bearer(session))
    check("newcomer cannot use admin API", r.status_code == 403, f"got {r.status_code}")

    # ── Tokens: one per family ───────────────────────────────────────
    r = client.post("/api/tokens", headers=bearer(session), json={"name": "Claude Desktop", "kind": "agent"})
    agent = r.json().get("token", "") if r.status_code == 200 else ""
    check("mint agent token", agent.startswith("aiu_agent_"), r.text[:200])

    r = client.post("/api/tokens", headers=bearer(session),
                    json={"name": "樱趣 App · verify", "kind": "phone", "replace_existing": True})
    phone = r.json().get("token", "") if r.status_code == 200 else ""
    check("mint phone token", phone.startswith("aiu_phone_"), r.text[:200])

    r = client.get("/api/tokens", headers=bearer(agent))
    check("agent token cannot manage tokens", r.status_code == 403, f"got {r.status_code}")

    # ── Authenticated MCP: initialize with version negotiation ───────
    hdrs = bearer(agent)
    r = client.post("/mcp", headers=hdrs, json={
        "jsonrpc": "2.0", "id": 2, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18"},
    })
    body = r.json() if r.status_code == 200 else {}
    ver = body.get("result", {}).get("protocolVersion", "")
    sess = r.headers.get("mcp-session-id", "")
    check("initialize echoes supported version", r.status_code == 200 and ver == "2025-06-18", f"{r.status_code} ver={ver}")
    check("session id issued", bool(sess))

    r = client.post("/mcp", headers=hdrs, json={
        "jsonrpc": "2.0", "id": 3, "method": "initialize",
        "params": {"protocolVersion": "1999-01-01"},
    })
    ver = r.json().get("result", {}).get("protocolVersion", "") if r.status_code == 200 else ""
    check("unsupported version falls back", ver == "2025-03-26", f"ver={ver}")

    r = client.post("/mcp", headers=bearer(phone), json={"jsonrpc": "2.0", "id": 9, "method": "tools/list"})
    check("phone token cannot drive MCP", r.status_code == 401, f"got {r.status_code}")

    # ── tools/list: feature_index present, neutral terminology ───────
    r = client.post("/mcp", headers=hdrs, json={"jsonrpc": "2.0", "id": 4, "method": "tools/list"})
    tools = {t["name"]: t for t in r.json().get("result", {}).get("tools", [])} if r.status_code == 200 else {}
    vib = tools.get("vibrate", {})
    props = vib.get("inputSchema", {}).get("properties", {})
    check("tools/list returns tools", len(tools) >= 10, f"{len(tools)} tools")
    check("vibrate has feature_index", "feature_index" in props)
    check("vibrate requires device", vib.get("inputSchema", {}).get("required") == ["device"])
    esc = tools.get("escalate", {})
    check("escalate hold contract documented", "hold indefinitely" in esc.get("description", ""))
    all_text = str(tools)
    check("neutral terminology", "clitoral" not in all_text and "thrusting" not in all_text)

    # ── tools/call without phone: graceful error, not a crash ────────
    r = client.post("/mcp", headers=hdrs, json={
        "jsonrpc": "2.0", "id": 5, "method": "tools/call",
        "params": {"name": "list_devices", "arguments": {}},
    })
    txt = str(r.json()) if r.status_code == 200 else r.text
    check("tools/call no-phone graceful", r.status_code == 200 and "No phone connected" in txt, txt[:150])

    # ── Relay accepts the phone token, rejects the agent token ───────
    with client.websocket_connect("/ws/phone") as ws:
        ws.send_json({"type": "phone_auth", "token": phone})
        reply = ws.receive_json()
        check("relay accepts phone token", reply.get("type") == "auth_ok", str(reply)[:150])
    with client.websocket_connect("/ws/phone") as ws:
        ws.send_json({"type": "phone_auth", "token": agent})
        reply = ws.receive_json()
        check("relay rejects agent token", reply.get("type") == "auth_error", str(reply)[:150])

    # ── Safety config round-trip (phone token) ───────────────────────
    ph = bearer(phone)
    r = client.get("/safety/config", headers=ph)
    check("GET /safety/config", r.status_code == 200 and "governor_enabled" in r.json(), r.text[:200])
    r = client.get("/safety/config", headers=hdrs)
    check("agent token cannot read safety config", r.status_code == 403, f"got {r.status_code}")
    r = client.post("/safety/config", headers=ph, json={"governor_enabled": False, "heat_rate": 2.5})
    check("POST /safety/config", r.status_code == 200, r.text[:200])
    r = client.get("/safety/config", headers=ph)
    j = r.json() if r.status_code == 200 else {}
    check("governor_enabled returns JSON false (not 0)", j.get("governor_enabled") is False, r.text[:200])
    r = client.post("/safety/config", headers=ph, json={"governor_enabled": True})
    r = client.get("/safety/config", headers=ph)
    j = r.json() if r.status_code == 200 else {}
    check("governor re-enables (one-way ratchet fixed)", j.get("governor_enabled") is True, r.text[:200])

    # ── Owner disables the newcomer: everything stops at once ────────
    me = client.get("/api/me", headers=bearer(session)).json()
    r = client.post(f"/api/admin/users/{me['user_id']}/active",
                    headers=bearer(owner_session), json={"is_active": False})
    check("owner disables account", r.status_code == 200, r.text[:200])
    r = client.post("/mcp", headers=hdrs, json={"jsonrpc": "2.0", "id": 6, "method": "tools/list"})
    check("disabled account's agent token -> 401", r.status_code == 401, f"got {r.status_code}")
    r = client.get("/api/me", headers=bearer(session))
    check("disabled account's session -> 401", r.status_code == 401, f"got {r.status_code}")

print(f"\n{len(PASS)} passed, {len(FAIL)} failed")
sys.exit(1 if FAIL else 0)
