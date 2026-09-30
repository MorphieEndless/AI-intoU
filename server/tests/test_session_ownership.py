"""Regression coverage for reconnect ownership and non-blocking replacement."""
import asyncio

from app.infra.session_registry import SessionRegistry


class Socket:
    def __init__(self, on_close=None):
        self.on_close = on_close

    async def close(self, code=1000, reason=""):
        if self.on_close:
            await self.on_close()


def test_replaced_socket_cannot_remove_or_mutate_successor():
    async def scenario():
        registry = SessionRegistry()
        old = await registry.register("user", Socket())
        new = await registry.register("user", Socket())
        assert not await registry.unregister("user", old)
        heartbeat = new.last_heartbeat
        await registry.update_heartbeat("user", old)
        await registry.update_devices("user", [{"name": "stale"}], old)
        assert new.last_heartbeat == heartbeat
        assert new.devices == []
        assert await registry.get_session("user") is new
        assert await registry.unregister("user", new)
        assert not await registry.unregister("user", new)
    asyncio.run(scenario())


def test_close_can_reenter_registry_without_deadlock():
    async def scenario():
        registry = SessionRegistry()
        old = None
        async def cleanup():
            assert not await registry.unregister("user", old)
            assert await registry.get_session("user") is not old
        old = await registry.register("user", Socket(cleanup))
        new = await asyncio.wait_for(registry.register("user", Socket()), 0.5)
        assert await registry.get_session("user") is new
    asyncio.run(scenario())


def test_stale_deadman_does_not_remove_successor_governor(monkeypatch):
    from server.safety import DeadManSwitch
    import server.safety as safety
    async def scenario():
        registry = SessionRegistry()
        monkeypatch.setattr(safety, "registry", registry)
        old = await registry.register("user", Socket())
        new = await registry.register("user", Socket())
        removed = []
        monkeypatch.setattr(safety.governor, "remove_user", removed.append)
        await DeadManSwitch()._emergency_stop("user", old)
        assert removed == []
        assert await registry.get_session("user") is new
    asyncio.run(scenario())
