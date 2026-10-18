"""Tests for the device_tools plugin, against a fake plugin context and a real aiohttp server.

Run from the repo root: python3 -m pytest hermes-plugin/device_tools/test_device_tools.py -q
"""

import asyncio
import sys
from pathlib import Path

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import device_tools  # noqa: E402

KEY = "test-key"
AUTH = {"Authorization": "Bearer " + KEY}

TOOLS = [
    {"name": "list_apps", "description": "List the apps", "parameters": {"type": "object", "properties": {}}},
    {
        "name": "launch_app",
        "description": "Launch an app",
        "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
    },
]


class FakeHandle:
    def __init__(self, ctx, name):
        self._ctx, self._name = ctx, name

    def dispose(self):
        self._ctx.tools.pop(self._name, None)
        self._ctx.disposed.append(self._name)


class FakeCtx:
    """The two plugin-context calls the plugin makes, recorded."""

    def __init__(self):
        self.tools = {}
        self.registered = []
        self.disposed = []
        self.factories = []

    def register_tool(self, name, toolset, schema, handler, description="", **kwargs):
        self.tools[name] = {"toolset": toolset, "schema": schema, "handler": handler, "description": description}
        self.registered.append(name)
        return FakeHandle(self, name)

    def register_platform_handler(self, platform, factory):
        self.factories.append((platform, factory))


class FakeAdapter:
    """The api_server adapter's auth check: None when the Bearer key matches, else a 401."""

    def _check_auth(self, request):
        if request.headers.get("Authorization", "") == "Bearer " + KEY:
            return None
        return web.json_response({"error": "bad key"}, status=401)


async def start(ctx):
    device_tools.register(ctx)
    platform, factory = ctx.factories[0]
    assert platform == "api_server"
    app = web.Application()
    factory(app, FakeAdapter())
    client = TestClient(TestServer(app))
    await client.start_server()
    return client


async def hello(client, tools=TOOLS):
    ws = await client.ws_connect(device_tools.ROUTE, headers=AUTH)
    await ws.send_json({"type": "hello", "tools": tools})
    assert await ws.receive_json(timeout=2) == {"type": "ready"}
    return ws


async def settle():
    # Let the server side of a closed socket run its cleanup.
    await asyncio.sleep(0.1)


def run(coro):
    asyncio.run(coro)


def test_auth_missing_key_is_refused():
    async def scenario():
        client = await start(FakeCtx())
        try:
            with pytest.raises(aiohttp.WSServerHandshakeError) as err:
                await client.ws_connect(device_tools.ROUTE)
            assert err.value.status == 401
        finally:
            await client.close()

    run(scenario())


def test_auth_wrong_key_is_refused():
    async def scenario():
        client = await start(FakeCtx())
        try:
            with pytest.raises(aiohttp.WSServerHandshakeError) as err:
                await client.ws_connect(device_tools.ROUTE, headers={"Authorization": "Bearer nope"})
            assert err.value.status == 401
        finally:
            await client.close()

    run(scenario())


def test_hello_gets_ready_and_registers_each_tool():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            assert set(ctx.tools) == {"list_apps", "launch_app"}
            for tool in TOOLS:
                entry = ctx.tools[tool["name"]]
                assert entry["description"] == tool["description"]
                assert entry["schema"] == {"description": tool["description"], "parameters": tool["parameters"]}
                assert entry["toolset"] == device_tools.TOOLSET
            await ws.close()
        finally:
            await client.close()

    run(scenario())


def test_hello_that_is_malformed_closes_the_socket_and_registers_nothing():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await client.ws_connect(device_tools.ROUTE, headers=AUTH)
            await ws.send_json({"type": "hello", "tools": [{"name": "", "parameters": []}]})
            msg = await ws.receive(timeout=2)
            assert msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED)
            assert ctx.tools == {}
        finally:
            await client.close()

    run(scenario())


def test_registry_keeps_the_tools_when_the_socket_closes():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            await ws.close()
            await settle()
            assert set(ctx.tools) == {"list_apps", "launch_app"}
            assert ctx.disposed == []
        finally:
            await client.close()

    run(scenario())


def test_registry_is_untouched_by_a_reconnect_with_the_same_tools():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            await (await hello(client)).close()
            await settle()
            registered = list(ctx.registered)
            # Same tools in another order: still the same set.
            ws = await hello(client, list(reversed(TOOLS)))
            assert ctx.registered == registered
            assert ctx.disposed == []
            await ws.close()
        finally:
            await client.close()

    run(scenario())


def test_registry_replaces_the_tools_when_a_hello_brings_another_set():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            await (await hello(client)).close()
            await settle()
            changed = [dict(TOOLS[1], description="Start an app")]
            ws = await hello(client, changed)
            assert set(ctx.tools) == {"launch_app"}
            assert ctx.tools["launch_app"]["description"] == "Start an app"
            assert sorted(ctx.disposed) == ["launch_app", "list_apps"]
            await ws.close()
        finally:
            await client.close()

    run(scenario())
