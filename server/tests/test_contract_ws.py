"""Behavior contract: phone WebSocket relay (WS /ws/phone).

Covers the auth handshake, the post-auth scan request, heartbeat
piggyback, and the device_list branch — the exact code path that the
K1 syntax error lived in.
"""
import os

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from server.app import app

STATIC = os.environ["SB_STATIC_BEARER_TOKEN"]


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


def _auth(ws, token=STATIC):
    ws.send_json({"type": "phone_auth", "token": token})
    return ws.receive_json()


def test_phone_auth_ok_and_scan_requested(client):
    with client.websocket_connect("/ws/phone") as ws:
        msg = _auth(ws)
        assert msg["type"] == "auth_ok"
        assert msg["user_id"] == "static-bearer-user"
        # server proactively requests a device scan after auth
        assert ws.receive_json()["type"] == "scan"
        # dead man's switch heartbeat arrives with governor state piggybacked
        ping = ws.receive_json()
        assert ping["type"] == "heartbeat_ping"
        assert "heat_pct" in ping


def test_phone_auth_bad_token_rejected(client):
    with client.websocket_connect("/ws/phone") as ws:
        msg = _auth(ws, token="definitely-wrong-token")
        assert msg["type"] == "auth_error"
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 4001


def test_first_message_must_be_phone_auth(client):
    with client.websocket_connect("/ws/phone") as ws:
        ws.send_json({"type": "heartbeat_pong", "timestamp": 0})
        assert ws.receive_json()["type"] == "auth_error"
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 4001


def test_device_list_then_heartbeat_survives(client):
    """Regression for K1: the device_list branch must not kill the loop."""
    with client.websocket_connect("/ws/phone") as ws:
        assert _auth(ws)["type"] == "auth_ok"
        assert ws.receive_json()["type"] == "scan"
        ws.send_json({"type": "device_list",
                      "devices": [{"short_name": "yingti"}]})
        ws.send_json({"type": "heartbeat_pong", "timestamp": 0})
        # connection still alive: the next heartbeat ping arrives
        ping = ws.receive_json()
        assert ping["type"] == "heartbeat_ping"
