"""Offline app-library API checks; no real phone or deployment is contacted."""
import os
import tempfile
import unittest
from pathlib import Path

_tmp = tempfile.TemporaryDirectory()
os.environ["SB_DB_PATH"] = str(Path(_tmp.name) / "test.db")
os.environ["SB_PATTERNS_DIR"] = str(Path(_tmp.name) / "patterns")
os.environ["SB_SECRET_KEY"] = "offline-pattern-route-verification-key-123456"
os.environ["SB_STATIC_BEARER_TOKEN"] = "offline-static-token-for-pattern-route-tests"

from fastapi import FastAPI
from fastapi.testclient import TestClient
from server import pattern_routes, mcp_tools
from server.auth import create_token
from server.pattern_store import PatternStore

app = FastAPI()
app.include_router(pattern_routes.router)
client = TestClient(app)

class PatternRoutesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = PatternStore(self.temp.name, db_path=str(Path(self.temp.name) / "test.db"))
        pattern_routes.pattern_store = self.store
        mcp_tools.pattern_store = self.store
        self.token = create_token("test-user", "test")
        self.headers = {"Authorization": "Bearer " + self.token}
        self.pattern = self.store.create("test-user", "样例", [{"duration_ms": 100, "vibrate": 0.5}], intensity_scale=0.4)
    def tearDown(self):
        self.temp.cleanup()
    def test_auth_required_for_all_routes(self):
        for method, url in [("get", "/patterns"), ("get", "/patterns/" + self.pattern.id), ("delete", "/patterns/" + self.pattern.id)]:
            self.assertEqual(getattr(client, method)(url).status_code, 401)
    def test_other_user_cannot_read_or_delete(self):
        headers = {"Authorization": "Bearer " + create_token("other-user", "other")}
        self.assertEqual(client.get("/patterns", headers=headers).json()["patterns"][0]["id"], "builtin-wave")
        for method in ["get", "delete"]:
            self.assertEqual(getattr(client, method)("/patterns/" + self.pattern.id, headers=headers).status_code, 404)
        self.assertIsNotNone(self.store.get("test-user", self.pattern.id))
    def test_list_detail_delete_share_mcp_store(self):
        import asyncio
        mcp_tools.current_user_id.set("test-user")
        asyncio.run(mcp_tools.create_pattern("AI新建", [{"duration_ms": 200, "constrict": 0.5}]))
        data = client.get("/patterns", headers=self.headers).json()
        self.assertEqual(data["total"], 3)
        self.assertNotIn("steps", data["patterns"][0])
        detail = client.get("/patterns/" + self.pattern.id, headers=self.headers).json()
        self.assertEqual(detail["intensity_scale"], 0.4)
        self.assertEqual(detail["steps"][0]["vibrate"], 0.5)
        self.assertEqual(client.delete("/patterns/" + self.pattern.id, headers=self.headers).status_code, 200)
        self.assertIsNone(self.store.get("test-user", self.pattern.id))
    def test_deletion_by_id_does_not_delete_id_shaped_name(self):
        other = self.store.create("test-user", self.pattern.id, [{"duration_ms": 100}])
        client.delete("/patterns/" + self.pattern.id, headers=self.headers)
        self.assertIsNotNone(self.store.get("test-user", other.id))
    def test_pagination_and_invalid_id(self):
        self.assertEqual(client.get("/patterns?offset=2&limit=1", headers=self.headers).json()["patterns"], [])
        self.assertEqual(client.get("/patterns?limit=51", headers=self.headers).status_code, 400)
        self.assertEqual(client.get("/patterns?offset=-1", headers=self.headers).status_code, 400)
        self.assertEqual(client.get("/patterns/not-an-id", headers=self.headers).status_code, 404)
    def test_static_token_uses_same_user_as_mcp(self):
        self.store.create("static-bearer-user", "static", [{"duration_ms": 100}])
        headers = {"Authorization": "Bearer " + os.environ["SB_STATIC_BEARER_TOKEN"]}
        self.assertEqual(client.get("/patterns", headers=headers).json()["total"], 2)

if __name__ == "__main__":
    unittest.main(verbosity=2)
