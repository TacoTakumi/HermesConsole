"""Tests for the device_tools plugin, against a fake plugin context and a real aiohttp server.

Run from the repo root: python3 -m pytest hermes-plugin/device_tools/test_device_tools.py -q
"""

import asyncio
import importlib.util
import sys
import threading
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
        self.unloads = []

    def register_tool(self, name, toolset, schema, handler, description="", **kwargs):
        self.tools[name] = {"toolset": toolset, "schema": schema, "handler": handler, "description": description}
        self.registered.append(name)
        return FakeHandle(self, name)

    def register_platform_handler(self, platform, factory):
        self.factories.append((platform, factory))

    def on_unload(self, callback):
        self.unloads.append(callback)

    def unload(self):
        # What the plugin manager does on unload, in its order: dispose the registrations it
        # tracked when the unload began, then run the unload callbacks. A registration made after
        # that snapshot is the plugin's own to dispose.
        for name in list(self.tools):
            FakeHandle(self, name).dispose()
        for callback in reversed(self.unloads):
            callback()


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


def broker_of(ctx):
    return sys.modules[device_tools.STATE_MODULE].broker


async def in_worker(handler, args):
    # Hermes runs tool handlers on a worker thread, never on the gateway loop.
    loop = asyncio.get_running_loop()
    started = loop.time()
    result = await loop.run_in_executor(None, handler, args)
    return result, loop.time() - started


def test_not_connected_call_returns_an_error_at_once():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            await (await hello(client)).close()
            await settle()
            result, elapsed = await in_worker(ctx.tools["launch_app"]["handler"], {"name": "x"})
            assert "not connected" in result
            assert elapsed < 1.0
        finally:
            await client.close()

    run(scenario())


def test_call_goes_down_the_socket_and_returns_the_answer():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            ids = []
            for answer in ("Launched X", "Launched Y"):
                pending = asyncio.ensure_future(in_worker(ctx.tools["launch_app"]["handler"], {"name": "x"}))
                frame = await ws.receive_json(timeout=2)
                assert frame["type"] == "call"
                assert frame["tool"] == "launch_app"
                assert frame["args"] == {"name": "x"}
                assert isinstance(frame["id"], str) and frame["id"]
                ids.append(frame["id"])
                await ws.send_json({"type": "result", "id": frame["id"], "result": answer})
                result, _ = await pending
                assert result == answer
            assert ids[0] != ids[1]
            assert broker_of(ctx)._pending == {}
            await ws.close()
        finally:
            await client.close()

    run(scenario())


def test_call_answered_with_an_error_returns_that_error():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            pending = asyncio.ensure_future(in_worker(ctx.tools["list_apps"]["handler"], {}))
            frame = await ws.receive_json(timeout=2)
            await ws.send_json({"type": "result", "id": frame["id"], "error": "no package manager"})
            result, _ = await pending
            assert "no package manager" in result
            assert "error" in result
            await ws.close()
        finally:
            await client.close()

    run(scenario())


def test_timeout_when_the_console_never_answers():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            result, elapsed = await in_worker(ctx.tools["launch_app"]["handler"], {"name": "x"})
            assert "did not answer" in result
            assert 9.0 <= elapsed <= 11.0
            assert broker_of(ctx)._pending == {}
            await ws.close()
        finally:
            await client.close()

    run(scenario())


def test_disconnect_before_the_answer_returns_an_error_at_once():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            pending = asyncio.ensure_future(in_worker(ctx.tools["launch_app"]["handler"], {"name": "x"}))
            await ws.receive_json(timeout=2)
            await ws.close()
            result, elapsed = await pending
            assert "disconnected" in result
            assert elapsed < 2.0
            assert broker_of(ctx)._pending == {}
        finally:
            await client.close()

    run(scenario())


def fresh_module():
    """The plugin as Hermes loads it on a reload: the same file, executed again as a new module."""
    path = Path(device_tools.__file__)
    spec = importlib.util.spec_from_file_location("device_tools_reloaded", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_reload_serves_the_new_broker_and_registers_the_tools_again():
    async def scenario():
        ctx1 = FakeCtx()
        client = await start(ctx1)
        try:
            ws = await hello(client)

            # Reload, in Hermes' order: unload the old load, then load the file again as a new
            # module with a new ctx. The route stays (the router is frozen).
            ctx1.unload()
            ctx2 = FakeCtx()
            fresh_module().register(ctx2)

            msg = await ws.receive(timeout=2)
            assert msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED)

            ws2 = await hello(client)
            assert set(ctx2.tools) == {"list_apps", "launch_app"}
            pending = asyncio.ensure_future(in_worker(ctx2.tools["list_apps"]["handler"], {}))
            frame = await ws2.receive_json(timeout=2)
            await ws2.send_json({"type": "result", "id": frame["id"], "result": "Calculator"})
            result, _ = await pending
            assert result == "Calculator"
            await ws2.close()
        finally:
            await client.close()

    run(scenario())


def test_unload_without_a_new_load_refuses_the_console_and_registers_nothing():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)

            # The plugin is disabled: unloaded, and not loaded again.
            ctx.unload()

            msg = await ws.receive(timeout=2)
            assert msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED)
            with pytest.raises(aiohttp.WSServerHandshakeError) as err:
                await client.ws_connect(device_tools.ROUTE, headers=AUTH)
            assert err.value.status == 503
            assert ctx.tools == {}
            assert ctx.registered == ["launch_app", "list_apps"]
        finally:
            await client.close()

    run(scenario())


def test_hello_that_arrives_after_unload_is_closed_and_registers_nothing():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            # Connected while the plugin was live, but its hello lands after the unload.
            ws = await client.ws_connect(device_tools.ROUTE, headers=AUTH)
            await settle()
            ctx.unload()
            await ws.send_json({"type": "hello", "tools": TOOLS})

            msg = await ws.receive(timeout=2)
            assert msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED)
            assert ctx.tools == {}
            assert ctx.registered == []
        finally:
            await client.close()

    run(scenario())


def test_unload_on_another_thread_during_registration_leaves_nothing_registered():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            broker = broker_of(ctx)
            apply = broker._apply

            def apply_while_unloading(tools):
                # Hermes runs a reload's unload on its own thread: here it starts just as the hello
                # is applied, and gets as far as it can before the registration goes on.
                unloader = threading.Thread(target=ctx.unload)
                unloader.start()
                unloader.join(timeout=0.5)
                apply(tools)

            broker._apply = apply_while_unloading
            ws = await client.ws_connect(device_tools.ROUTE, headers=AUTH)
            await ws.send_json({"type": "hello", "tools": TOOLS})

            msg = await ws.receive(timeout=3)
            while msg.type == aiohttp.WSMsgType.TEXT:
                msg = await ws.receive(timeout=3)
            assert msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED)
            assert ctx.tools == {}
        finally:
            await client.close()

    run(scenario())


def test_call_result_with_a_non_string_id_is_ignored_and_the_socket_stays_open():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            pending = asyncio.ensure_future(in_worker(ctx.tools["launch_app"]["handler"], {"name": "x"}))
            frame = await ws.receive_json(timeout=2)
            await ws.send_json({"type": "result", "id": ["not", "a", "string"], "result": "junk"})
            await ws.send_json({"type": "result", "id": frame["id"], "result": "Launched X"})
            result, _ = await pending
            assert result == "Launched X"
            assert not ws.closed
            await ws.close()
        finally:
            await client.close()

    run(scenario())


def test_call_without_an_args_dict_sends_empty_args_not_the_context():
    async def scenario():
        ctx = FakeCtx()
        client = await start(ctx)
        try:
            ws = await hello(client)
            handler = ctx.tools["list_apps"]["handler"]
            loop = asyncio.get_running_loop()
            pending = loop.run_in_executor(
                None, lambda: handler(task_id="t1", session_id="s1", user_task="open the calculator"))
            frame = await ws.receive_json(timeout=2)
            assert frame["args"] == {}
            await ws.send_json({"type": "result", "id": frame["id"], "result": "Calculator"})
            assert await pending == "Calculator"
            await ws.close()
        finally:
            await client.close()

    run(scenario())
