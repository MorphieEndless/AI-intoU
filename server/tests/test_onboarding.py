"""Onboarding over HTTP: invite registration, sessions, token APIs, admin.

The credential families must never be interchangeable:
  session  (login)          → /api/tokens, /api/me, /api/admin/*
  agent    (aiu_agent_…)    → /mcp only
  phone    (aiu_phone_…)    → /ws/phone, /patterns, /safety/*
"""
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.config import settings
from server.app import app

PASSWORD = "correct-horse-battery"


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def login(client, username, password=PASSWORD) -> dict:
    r = client.post("/auth/login", json={"username": username, "password": password})
    assert r.status_code == 200, r.text
    return r.json()


@pytest.fixture()
def owner(client, make_account):
    account = make_account(admin=True)
    account["session"] = login(client, account["username"])["token"]
    return account


@pytest.fixture()
def member(client, make_account):
    account = make_account(admin=False)
    account["session"] = login(client, account["username"])["token"]
    return account


def new_invite(client, owner, **body) -> str:
    r = client.post("/api/admin/invites", headers=bearer(owner["session"]), json=body)
    assert r.status_code == 200, r.text
    return r.json()["code"]


def new_token(client, session, kind, name="test", **extra) -> dict:
    r = client.post("/api/tokens", headers=bearer(session),
                    json={"name": name, "kind": kind, **extra})
    assert r.status_code == 200, r.text
    return r.json()


def mcp_call(client, token, method="tools/list"):
    return client.post("/mcp", headers=bearer(token),
                       json={"jsonrpc": "2.0", "id": 1, "method": method})


def ws_auth(client, token):
    """Open the relay socket and return (ws, first reply). Caller closes."""
    ctx = client.websocket_connect("/ws/phone")
    ws = ctx.__enter__()
    ws.send_json({"type": "phone_auth", "token": token})
    return ctx, ws, ws.receive_json()


def drain_until_closed(ws) -> tuple[list[dict], int]:
    seen = []
    with pytest.raises(WebSocketDisconnect) as exc:
        for _ in range(50):
            seen.append(ws.receive_json())
    return seen, exc.value.code


def unique(prefix="n"):
    import uuid
    return f"{prefix}{uuid.uuid4().hex[:8]}"


# ════════════════════════════════════════════════════════════════════════
# Registration
# ════════════════════════════════════════════════════════════════════════

def test_register_with_invite_then_login(client, owner):
    code = new_invite(client, owner, note="qq friend")
    name = unique("Alice")
    r = client.post("/auth/register", json={"username": name, "password": PASSWORD,
                                            "invite_code": code.lower()})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["username"] == name.lower()  # normalised
    assert body["token_type"] == "session" and body["is_admin"] is False
    assert body["expires_in_hours"] == settings.SESSION_TOKEN_TTL_HOURS

    me = client.get("/api/me", headers=bearer(body["token"])).json()
    assert me["username"] == name.lower() and me["is_admin"] is False
    assert me["phone_online"] is False and me["registration_open"] is False

    again = login(client, name.lower())
    assert again["user_id"] == body["user_id"] and again["is_admin"] is False

    # the code is now used up
    r = client.post("/auth/register", json={"username": unique(), "password": PASSWORD,
                                            "invite_code": code})
    assert r.status_code == 403 and "邀请码" in r.json()["detail"]


def test_register_without_invite_is_closed_by_default(client):
    assert settings.REGISTRATION_OPEN is False
    r = client.post("/auth/register", json={"username": unique(), "password": PASSWORD})
    assert r.status_code == 403 and "邀请码" in r.json()["detail"]


def test_open_registration_allows_no_code(client, monkeypatch, owner):
    monkeypatch.setattr(settings, "REGISTRATION_OPEN", True)
    r = client.post("/auth/register", json={"username": unique(), "password": PASSWORD})
    assert r.status_code == 200 and r.json()["is_admin"] is False


def test_register_validation_messages(client, owner):
    code = new_invite(client, owner)
    r = client.post("/auth/register", json={"username": "a b", "password": PASSWORD,
                                            "invite_code": code})
    assert r.status_code == 400 and "用户名" in r.json()["detail"]
    r = client.post("/auth/register", json={"username": unique(), "password": "short",
                                            "invite_code": code})
    assert r.status_code == 400 and "密码" in r.json()["detail"]
    r = client.post("/auth/register", json={"username": owner["username"], "password": PASSWORD,
                                            "invite_code": code})
    assert r.status_code == 400 and "占用" in r.json()["detail"]
    # none of the failures consumed the single use
    r = client.post("/auth/register", json={"username": unique(), "password": PASSWORD,
                                            "invite_code": code})
    assert r.status_code == 200


def test_bad_invite_codes_count_toward_the_ip_ban(client, monkeypatch):
    monkeypatch.setattr(settings, "RATE_LIMIT_AUTH", "1000/minute")
    monkeypatch.setattr(settings, "BAN_THRESHOLD", 3)
    for _ in range(3):
        r = client.post("/auth/register", json={"username": unique(), "password": PASSWORD,
                                                "invite_code": "ZZZZ-ZZZZ-ZZZZ"})
        assert r.status_code == 403
    r = client.post("/auth/register", json={"username": unique(), "password": PASSWORD,
                                            "invite_code": "ZZZZ-ZZZZ-ZZZZ"})
    assert r.status_code == 429


def test_auth_endpoints_reject_non_object_bodies(client):
    assert client.post("/auth/login", content=b"nope").status_code == 400
    assert client.post("/auth/login", json=["a"]).status_code == 400


# ════════════════════════════════════════════════════════════════════════
# Login / me / password
# ════════════════════════════════════════════════════════════════════════

def test_login_wrong_password_and_disabled_account(client, owner, member):
    r = client.post("/auth/login", json={"username": member["username"], "password": "wrong-pass"})
    assert r.status_code == 401
    client.post(f"/api/admin/users/{member['user_id']}/active",
                headers=bearer(owner["session"]), json={"is_active": False})
    r = client.post("/auth/login", json={"username": member["username"], "password": PASSWORD})
    assert r.status_code == 403 and "停用" in r.json()["detail"]


def test_owner_login_reports_admin(client, owner):
    assert login(client, owner["username"])["is_admin"] is True


def test_change_own_password(client, member):
    h = bearer(member["session"])
    r = client.post("/api/me/password", headers=h,
                    json={"old_password": "wrong-pass", "new_password": "another-secret"})
    assert r.status_code == 403
    r = client.post("/api/me/password", headers=h,
                    json={"old_password": PASSWORD, "new_password": "short"})
    assert r.status_code == 400
    r = client.post("/api/me/password", headers=h,
                    json={"old_password": PASSWORD, "new_password": "another-secret"})
    assert r.status_code == 200
    login(client, member["username"], "another-secret")


# ════════════════════════════════════════════════════════════════════════
# Token API
# ════════════════════════════════════════════════════════════════════════

def test_mint_list_revoke_agent_token(client, member):
    created = new_token(client, member["session"], "agent", "RikkaHub")
    token = created["token"]
    assert token.startswith("aiu_agent_") and created["info"]["state"] == "active"
    assert created["info"]["prefix"] and token.startswith(created["info"]["prefix"])

    listed = client.get("/api/tokens", headers=bearer(member["session"])).json()["tokens"]
    assert [t["name"] for t in listed] == ["RikkaHub"]
    assert all("token" not in t for t in listed)  # plaintext never listed

    assert mcp_call(client, token).status_code == 200

    r = client.delete(f"/api/tokens/{created['info']['id']}", headers=bearer(member["session"]))
    assert r.status_code == 200 and r.json()["revoked"] is True
    assert mcp_call(client, token).status_code == 401


def test_token_api_requires_a_session(client, member, mint):
    agent = mint(member["user_id"], "agent")
    phone = mint(member["user_id"], "phone")
    assert client.get("/api/tokens").status_code == 401
    for cred in (agent, phone):
        r = client.get("/api/tokens", headers=bearer(cred))
        assert r.status_code == 403, cred[:12]
        r = client.post("/api/tokens", headers=bearer(cred), json={"name": "x", "kind": "agent"})
        assert r.status_code == 403
        assert client.get("/api/me", headers=bearer(cred)).status_code == 403
        assert client.get("/api/admin/users", headers=bearer(cred)).status_code == 403


def test_cannot_revoke_someone_elses_token(client, owner, member):
    created = new_token(client, owner["session"], "agent")
    r = client.delete(f"/api/tokens/{created['info']['id']}", headers=bearer(member["session"]))
    assert r.status_code == 404
    assert mcp_call(client, created["token"]).status_code == 200


def test_replace_existing_revokes_same_name_same_kind(client, member):
    s = member["session"]
    first = new_token(client, s, "phone", "樱趣 App · Pixel")
    other_name = new_token(client, s, "phone", "樱趣 App · Tablet")
    agent_same_name = new_token(client, s, "agent", "樱趣 App · Pixel")
    second = new_token(client, s, "phone", "樱趣 App · Pixel", replace_existing=True)
    assert second["replaced"] == 1

    states = {t["id"]: t["state"] for t in
              client.get("/api/tokens", headers=bearer(s)).json()["tokens"]}
    assert states[first["info"]["id"]] == "revoked"
    assert states[second["info"]["id"]] == "active"
    assert states[other_name["info"]["id"]] == "active"
    assert states[agent_same_name["info"]["id"]] == "active"


def test_token_request_validation(client, member):
    h = bearer(member["session"])
    assert client.post("/api/tokens", headers=h, json={"name": "x", "kind": "human"}).status_code == 400
    assert client.post("/api/tokens", headers=h, json={"name": "  ", "kind": "agent"}).status_code == 400
    assert client.post("/api/tokens", headers=h, json={"kind": "agent"}).status_code == 400


def test_active_token_cap(client, member, monkeypatch):
    import app.api.accounts as accounts

    monkeypatch.setattr(accounts, "MAX_ACTIVE_TOKENS", 2)
    new_token(client, member["session"], "agent", "a")
    new_token(client, member["session"], "agent", "b")
    r = client.post("/api/tokens", headers=bearer(member["session"]),
                    json={"name": "c", "kind": "agent"})
    assert r.status_code == 400 and "最多" in r.json()["detail"]
    # replacing one at the cap still works (the old one is revoked first)
    new_token(client, member["session"], "agent", "a", replace_existing=True)


# ════════════════════════════════════════════════════════════════════════
# Credential families never cross
# ════════════════════════════════════════════════════════════════════════

def test_phone_token_cannot_drive_mcp(client, member, mint):
    r = mcp_call(client, mint(member["user_id"], "phone"))
    assert r.status_code == 401
    assert "手机" in r.json()["error"]["message"]


def test_unauthenticated_mcp_explains_where_to_get_a_token(client):
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "initialize"})
    assert r.status_code == 401 and "AI 接入" in r.json()["error"]["message"]


@pytest.mark.parametrize("family", ["agent", "session", "garbage"])
def test_only_phone_tokens_open_the_relay(client, member, mint, family):
    cred = {"agent": lambda: mint(member["user_id"], "agent"),
            "session": lambda: member["session"],
            "garbage": lambda: "a-legacy-static-token-value-0123456789"}[family]()
    ctx, ws, reply = ws_auth(client, cred)
    try:
        assert reply["type"] == "auth_error"
        assert reply["message"]  # a human explanation, in Chinese
        _, code = drain_until_closed(ws)
        assert code == 4001
    finally:
        ctx.__exit__(None, None, None)


def test_agent_token_cannot_use_rest_library_or_safety(client, member, mint):
    agent = mint(member["user_id"], "agent")
    assert client.get("/patterns", headers=bearer(agent)).status_code == 403
    assert client.get("/safety/config", headers=bearer(agent)).status_code == 403
    phone = mint(member["user_id"], "phone")
    assert client.get("/patterns", headers=bearer(phone)).status_code == 200
    assert client.get("/safety/config", headers=bearer(phone)).status_code == 200
    assert client.get("/safety/config", headers=bearer(member["session"])).status_code == 200


# ════════════════════════════════════════════════════════════════════════
# Live disconnects
# ════════════════════════════════════════════════════════════════════════

def test_revoking_the_phone_token_kicks_the_phone(client, member):
    created = new_token(client, member["session"], "phone", "relay")
    ctx, ws, reply = ws_auth(client, created["token"])
    try:
        assert reply["type"] == "auth_ok"
        assert ws.receive_json()["type"] == "scan"
        r = client.delete(f"/api/tokens/{created['info']['id']}", headers=bearer(member["session"]))
        assert r.status_code == 200
        seen, code = drain_until_closed(ws)
        assert code == 4001
        stops = [m for m in seen if m.get("type") == "stop"]
        assert stops and stops[0]["emergency"] is True and stops[0]["device"] == "all"
    finally:
        ctx.__exit__(None, None, None)


def test_revoking_another_phone_token_leaves_the_connection(client, member):
    live = new_token(client, member["session"], "phone", "live")
    spare = new_token(client, member["session"], "phone", "spare")
    ctx, ws, reply = ws_auth(client, live["token"])
    try:
        assert reply["type"] == "auth_ok"
        client.delete(f"/api/tokens/{spare['info']['id']}", headers=bearer(member["session"]))
        assert client.get("/api/me", headers=bearer(member["session"])).json()["phone_online"] is True
    finally:
        ctx.__exit__(None, None, None)


# ════════════════════════════════════════════════════════════════════════
# Admin
# ════════════════════════════════════════════════════════════════════════

def test_admin_endpoints_are_owner_only(client, member):
    h = bearer(member["session"])
    for method, path in (("GET", "/api/admin/invites"), ("POST", "/api/admin/invites"),
                         ("GET", "/api/admin/users"),
                         ("POST", f"/api/admin/users/{member['user_id']}/active"),
                         ("POST", f"/api/admin/users/{member['user_id']}/password")):
        r = client.request(method, path, headers=h, json={"is_active": True, "password": "x" * 10})
        assert r.status_code == 403, path
        assert "owner" in r.json()["detail"]
    assert client.get("/api/admin/users").status_code == 401


def test_invite_lifecycle_via_admin_api(client, owner):
    h = bearer(owner["session"])
    r = client.post("/api/admin/invites", headers=h,
                    json={"max_uses": 2, "expires_in_days": 3, "note": "群友"})
    created = r.json()
    assert created["info"]["max_uses"] == 2 and created["info"]["note"] == "群友"
    invite_id = created["info"]["id"]

    name = unique()
    client.post("/auth/register", json={"username": name, "password": PASSWORD,
                                        "invite_code": created["code"]})
    listed = {i["id"]: i for i in client.get("/api/admin/invites", headers=h).json()["invites"]}
    assert listed[invite_id]["used_count"] == 1 and listed[invite_id]["used_by"] == [name]
    assert "code" not in listed[invite_id]

    r = client.delete(f"/api/admin/invites/{invite_id}", headers=h)
    assert r.status_code == 200 and r.json()["info"]["state"] == "revoked"
    r = client.post("/auth/register", json={"username": unique(), "password": PASSWORD,
                                            "invite_code": created["code"]})
    assert r.status_code == 403
    assert client.delete("/api/admin/invites/nope", headers=h).status_code == 404

    for bad in ({"max_uses": 0}, {"max_uses": 51}, {"expires_in_days": 0}, {"expires_in_days": 91}):
        assert client.post("/api/admin/invites", headers=h, json=bad).status_code == 400


def test_disabling_a_user_kills_every_credential_and_kicks_the_phone(client, owner, member, mint):
    agent = mint(member["user_id"], "agent")
    phone = new_token(client, member["session"], "phone", "relay")["token"]
    ctx, ws, reply = ws_auth(client, phone)
    try:
        assert reply["type"] == "auth_ok"
        users = {u["id"]: u for u in
                 client.get("/api/admin/users", headers=bearer(owner["session"])).json()["users"]}
        assert users[member["user_id"]]["phone_online"] is True
        assert users[member["user_id"]]["active_tokens"] == {"agent": 1, "phone": 1}

        r = client.post(f"/api/admin/users/{member['user_id']}/active",
                        headers=bearer(owner["session"]), json={"is_active": False})
        assert r.status_code == 200 and r.json()["is_active"] is False

        seen, code = drain_until_closed(ws)
        assert code == 4001 and any(m.get("type") == "stop" for m in seen)
    finally:
        ctx.__exit__(None, None, None)

    assert mcp_call(client, agent).status_code == 401
    assert client.get("/api/me", headers=bearer(member["session"])).status_code == 401
    assert client.get("/patterns", headers=bearer(phone)).status_code == 401
    ctx, ws, reply = ws_auth(client, phone)
    try:
        assert reply["type"] == "auth_error"
    finally:
        ctx.__exit__(None, None, None)

    # re-enabling restores the same credentials
    client.post(f"/api/admin/users/{member['user_id']}/active",
                headers=bearer(owner["session"]), json={"is_active": True})
    assert mcp_call(client, agent).status_code == 200


def test_owner_cannot_disable_themselves(client, owner):
    r = client.post(f"/api/admin/users/{owner['user_id']}/active",
                    headers=bearer(owner["session"]), json={"is_active": False})
    assert r.status_code == 400
    assert client.get("/api/me", headers=bearer(owner["session"])).status_code == 200


def test_admin_password_reset(client, owner, member):
    h = bearer(owner["session"])
    r = client.post(f"/api/admin/users/{member['user_id']}/password", headers=h,
                    json={"password": "short"})
    assert r.status_code == 400
    r = client.post(f"/api/admin/users/{member['user_id']}/password", headers=h,
                    json={"password": "a-brand-new-one"})
    assert r.status_code == 200
    login(client, member["username"], "a-brand-new-one")
    r = client.post("/api/admin/users/nope/password", headers=h, json={"password": "a-brand-new-one"})
    assert r.status_code == 404
    r = client.post("/api/admin/users/nope/active", headers=h, json={"is_active": False})
    assert r.status_code == 404


def test_demoted_owner_loses_admin_immediately(client, owner):
    """require_admin reads the database on every call, not the session claim."""
    from app.db import session_scope
    from app.models import User

    with session_scope() as s:
        s.get(User, owner["user_id"]).is_admin = 0
    r = client.get("/api/admin/users", headers=bearer(owner["session"]))
    assert r.status_code == 403
