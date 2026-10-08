"""Behavior contract: phone WebSocket relay (WS /ws/phone).

Covers the auth handshake, the post-auth scan request, heartbeat
piggyback, and the device_list branch — the exact code path that the
K1 syntax error lived in.
"""
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from server.app import app

PHONE: dict = {}


@pytest.fixture()
def client(make_account, mint):
    with TestClient(app) as c:
        account = make_account()
        PHONE.update(account, token=mint(account["user_id"], "phone", "contract-phone"))
        yield c


def _auth(ws, token=None):
    ws.send_json({"type": "phone_auth", "token": PHONE["token"] if token is None else token})
    return ws.receive_json()


def test_phone_auth_ok_and_scan_requested(client):
    with client.websocket_connect("/ws/phone") as ws:
        msg = _auth(ws)
        assert msg["type"] == "auth_ok"
        assert msg["user_id"] == PHONE["user_id"]
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
