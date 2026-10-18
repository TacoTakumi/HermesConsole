"""device_tools plugin for Hermes Agent - tools that run on the wall console itself.

The console dials in: it opens a WebSocket to ROUTE on the api_server, authenticated with the
gateway API key it already uses for /v1/runs, and its first frame (the hello) declares its tools.
The plugin registers those tools with Hermes and sends each call down the socket; the console runs
it and answers on the same socket. The gateway never dials the console, so a console behind a VPN,
on Tailscale or on a dynamic address works the same.

Wire frames, JSON text:

    console -> {"type": "hello", "tools": [{"name", "description", "parameters"}]}
    plugin  -> {"type": "ready"}
    plugin  -> {"type": "call", "id": "<id>", "tool": "<name>", "args": {...}}
    console -> {"type": "result", "id": "<id>", "result": "<text>"}
    console -> {"type": "result", "id": "<id>", "error": "<text>"}

The tools stay registered when the socket closes. Every register or dispose bumps the registry
generation, which rebuilds cached agents and makes an open conversation process its whole history
again, so a Wi-Fi drop must not churn the tool list. A hello re-registers only when it brings a
different tool set.

A plugin reload runs this file again as a new module, but the route stays: the api_server's router
froze when it connected. So the route and every load share one state object kept in sys.modules,
and the route always serves the latest load's broker.
"""

import asyncio
import concurrent.futures
import json
import logging
import sys
import types
import uuid

logger = logging.getLogger(__name__)

ROUTE = "/v1/device/ws"
STATE_MODULE = "_hermesconsole_device_tools_state"
TOOLSET = "device_tools"

# How long a fresh socket may stay silent before its hello is overdue.
HELLO_TIMEOUT = 10.0

# How long a call waits for the console's answer. Hermes gives a tool 420 s and then abandons the
# worker, so the plugin keeps its own, short deadline: a voice turn cannot wait long.
CALL_TIMEOUT = 10.0


class DeviceBroker:
    """One console's socket, the tools it declared, and the calls waiting on it."""

    def __init__(self, ctx):
        self._ctx = ctx
        self._loop = None
        self._ws = None
        # Call id -> (socket it went down, future for its answer). Touched on the gateway loop only.
        self._pending = {}
        self._handles = {}
        self._tool_set = None

    def unload(self):
        """This load is going away: drop its tools' bookkeeping and close the console's socket.

        The plugin manager disposes the registrations itself. Closing the socket fails the calls
        waiting on it and makes the console dial again; its hello then reaches the next load's
        broker, which registers the tools through its own context.
        """
        self._handles = {}
        self._tool_set = None
        loop, ws = self._loop, self._ws
        self._ws = None
        if loop is None or ws is None or ws.closed or loop.is_closed():
            return
        try:
            asyncio.run_coroutine_threadsafe(ws.close(code=1012, message=b"plugin reloaded"), loop)
        except RuntimeError:
            pass  # the loop is shutting down; the socket goes with it

    def _refusal(self, request):
        # Plugin routes get no auth from the adapter, so the route applies the adapter's own Bearer
        # check, profile-scoped key and all. Without that check there is no way to know the key, so
        # the socket fails closed.
        from aiohttp import web

        check = getattr(_state().adapter, "_check_auth", None)
        if check is None:
            return web.json_response({"error": {"message": "device socket auth is unavailable"}}, status=401)
        return check(request)

    async def _handle_socket(self, request):
        from aiohttp import WSMsgType, web

        refused = self._refusal(request)
        if refused is not None:
            return refused

        ws = web.WebSocketResponse(heartbeat=30.0)
        await ws.prepare(request)
        self._loop = asyncio.get_running_loop()

        try:
            first = await asyncio.wait_for(ws.receive(), HELLO_TIMEOUT)
        except asyncio.TimeoutError:
            await ws.close(code=1008, message=b"no hello")
            return ws
        tools = _hello_tools(first.data if first.type == WSMsgType.TEXT else None)
        if tools is None:
            await ws.close(code=1008, message=b"bad hello")
            return ws

        self._apply(tools)
        previous, self._ws = self._ws, ws
        if previous is not None and not previous.closed:
            # One console per profile: a new socket replaces the old one.
            await previous.close(code=1000, message=b"replaced")
        await ws.send_json({"type": "ready"})
        logger.info("device_tools: console connected with tools %s", [t["name"] for t in tools])

        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    self._on_frame(ws, msg.data)
        finally:
            if self._ws is ws:
                self._ws = None
            for owner, answer in list(self._pending.values()):
                if owner is ws and not answer.done():
                    answer.set_exception(ConnectionError("socket closed"))
            logger.info("device_tools: console socket closed")
        return ws

    def _apply(self, tools):
        """Register the hello's tools, unless they are the set already registered."""
        tool_set = json.dumps(tools, sort_keys=True)
        if tool_set == self._tool_set:
            return
        for handle in self._handles.values():
            handle.dispose()
        self._handles = {}
        for tool in tools:
            name = tool["name"]
            handle = self._ctx.register_tool(
                name,
                TOOLSET,
                {"description": tool["description"], "parameters": tool["parameters"]},
                self._handler_for(name),
                description=tool["description"],
            )
            if handle is not None:
                self._handles[name] = handle
            else:
                logger.warning("device_tools: Hermes refused to register %s", name)
        self._tool_set = tool_set

    def _handler_for(self, name):
        def handler(args=None, **kwargs):
            # The tool call layer passes a dict or bare keywords; accept both.
            a = args if isinstance(args, dict) else {k: v for k, v in kwargs.items() if k != "task_id"}
            return self.call(name, a)

        return handler

    def call(self, name, args):
        """Run one tool on the console. Called on a Hermes worker thread; blocks until the answer."""
        loop, ws = self._loop, self._ws
        if loop is None or ws is None or ws.closed:
            return _error("the console is not connected")
        future = asyncio.run_coroutine_threadsafe(self._send_call(name, args), loop)
        try:
            # The coroutine enforces CALL_TIMEOUT itself; this outer bound only guards a stalled loop.
            return future.result(CALL_TIMEOUT + 5.0)
        except concurrent.futures.TimeoutError:
            future.cancel()
            return _error("the console did not answer within %d s" % CALL_TIMEOUT)

    async def _send_call(self, name, args):
        ws = self._ws
        if ws is None or ws.closed:
            return _error("the console is not connected")
        call_id = uuid.uuid4().hex
        answer = asyncio.get_running_loop().create_future()
        self._pending[call_id] = (ws, answer)
        try:
            await ws.send_json({"type": "call", "id": call_id, "tool": name, "args": args})
            return await asyncio.wait_for(answer, CALL_TIMEOUT)
        except asyncio.TimeoutError:
            return _error("the console did not answer within %d s" % CALL_TIMEOUT)
        except ConnectionError:
            return _error("the console disconnected before it answered")
        finally:
            self._pending.pop(call_id, None)

    def _on_frame(self, ws, text):
        """Settle the waiting call a result frame answers. Anything else is dropped."""
        try:
            frame = json.loads(text)
        except ValueError:
            return
        if not isinstance(frame, dict) or frame.get("type") != "result":
            return
        owner, answer = self._pending.get(frame.get("id"), (None, None))
        if owner is not ws or answer.done():
            return
        if "error" in frame:
            answer.set_result(_error(str(frame["error"])))
        else:
            result = frame.get("result", "")
            answer.set_result(result if isinstance(result, str) else json.dumps(result))


def _error(message):
    return json.dumps({"error": message})


def _hello_tools(text):
    """The tools a hello declares, sorted by name, or None when it is not a well-formed hello."""
    try:
        frame = json.loads(text) if isinstance(text, str) else None
    except ValueError:
        return None
    if not isinstance(frame, dict) or frame.get("type") != "hello" or not isinstance(frame.get("tools"), list):
        return None
    tools = []
    for tool in frame["tools"]:
        if not isinstance(tool, dict):
            return None
        name, description, parameters = tool.get("name"), tool.get("description"), tool.get("parameters")
        if not isinstance(name, str) or not name.strip() or not isinstance(description, str):
            return None
        if not isinstance(parameters, dict):
            return None
        tools.append({"name": name, "description": description, "parameters": parameters})
    if len({t["name"] for t in tools}) != len(tools):
        return None
    return sorted(tools, key=lambda t: t["name"])


def _state():
    """The state every load of this plugin shares: the live broker and the api_server adapter."""
    state = sys.modules.get(STATE_MODULE)
    if state is None:
        state = types.ModuleType(STATE_MODULE)
        state.broker = None
        state.adapter = None
        sys.modules[STATE_MODULE] = state
    return state


def _attach(app, adapter):
    """The api_server platform handler: runs in connect(), before the router freezes."""
    _state().adapter = adapter
    app.router.add_route("GET", ROUTE, _serve_socket)


async def _serve_socket(request):
    return await _state().broker._handle_socket(request)


def register(ctx):
    """Add the console's socket route to the api_server; the tools come with its hello."""
    state = _state()
    previous, state.broker = state.broker, DeviceBroker(ctx)
    if previous is not None:
        # A reload whose unload callback has not run yet: the old socket would keep the console
        # talking to a broker whose tools are gone.
        previous.unload()
    ctx.on_unload(state.broker.unload)
    ctx.register_platform_handler("api_server", _attach)
