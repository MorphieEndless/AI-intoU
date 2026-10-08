"""Behavior contract: MCP Streamable HTTP endpoint (POST /mcp).

Freezes the current protocol behavior so the refactor can't silently
break existing clients. Auth uses a minted agent token (M2b): the only
credential an AI client gets.
"""
import pytest
from fastapi.testclient import TestClient

from server.app import app

AUTH: dict = {}


@pytest.fixture()
def client(make_account, mint):
    with TestClient(app) as c:
        account = make_account()
        AUTH["Authorization"] = f"Bearer {mint(account['user_id'], 'agent', 'contract')}"
        yield c


def rpc(client, method, params=None, req_id=1, headers=None):
    return client.post(
        "/mcp",
        headers=AUTH if headers is None else headers,
        json={"jsonrpc": "2.0", "id": req_id, "method": method,
              "params": params or {}},
    )


def test_initialize_version_negotiation(client):
    r = rpc(client, "initialize", {"protocolVersion": "2025-06-18"})
    assert r.status_code == 200
    assert r.json()["result"]["protocolVersion"] == "2025-06-18"
    assert r.json()["result"]["serverInfo"]["name"]
    assert r.headers.get("mcp-session-id")


def test_initialize_unsupported_version_falls_back(client):
    r = rpc(client, "initialize", {"protocolVersion": "1999-01-01"})
    assert r.status_code == 200
    assert r.json()["result"]["protocolVersion"] == "2025-03-26"


def test_tools_list_shape(client):
    r = rpc(client, "tools/list")
    assert r.status_code == 200
    tools = r.json()["result"]["tools"]
    names = {t["name"] for t in tools}
    assert len(tools) >= 10
    assert {"vibrate", "constrict", "stop", "list_devices",
            "pulse", "wave", "escalate"} <= names
    for t in tools:
        assert t["inputSchema"]["type"] == "object"
        assert t["description"]


def test_tools_call_without_phone_graceful(client):
    r = rpc(client, "tools/call", {"name": "list_devices", "arguments": {}})
    assert r.status_code == 200
    assert "No phone connected" in str(r.json())


def test_unknown_tool_returns_jsonrpc_error(client):
    r = rpc(client, "tools/call", {"name": "nonexistent_tool", "arguments": {}})
    assert r.json()["error"]["code"] == -32601


def test_unauthenticated_initialize_rejected(client):
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1,
                                  "method": "initialize", "params": {}})
    assert r.status_code == 401


def test_notification_gets_bare_202(client):
    r = client.post("/mcp", json={"jsonrpc": "2.0",
                                  "method": "notifications/initialized"})
    assert r.status_code == 202


def test_ping(client):
    r = rpc(client, "ping")
    assert r.status_code == 200
    assert r.json()["result"] == {}
