"""Behavior contract: MCP Streamable HTTP endpoint (POST /mcp).

Freezes the current protocol behavior so the refactor can't silently
break existing clients. Auth uses the static Bearer token (current
single-user mode); when M2 lands the unified token system, update the
AUTH fixture to mint an agent token instead.
"""
import os

import pytest
from fastapi.testclient import TestClient

from server.app import app

STATIC = os.environ["SB_STATIC_BEARER_TOKEN"]
AUTH = {"Authorization": f"Bearer {STATIC}"}


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


def rpc(client, method, params=None, req_id=1, headers=AUTH):
    return client.post(
        "/mcp",
        headers=headers,
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
