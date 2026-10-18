"""device_tools plugin for Hermes Agent - tools that run on the wall console itself.

The console dials in: it opens a WebSocket to ROUTE on the api_server, authenticated with the
gateway API key it already uses for /v1/runs, and its first frame (the hello) declares its tools.
The plugin registers those tools with Hermes and sends each call down the socket; the console runs
it and answers on the same socket. The gateway never dials the console, so a console behind a VPN,
on Tailscale or on a dynamic address works the same.

Wire frames, JSON text:

    console -> {"type": "hello", "tools": [{"name", "description", "parameters"}]}
    plugin  -> {"type": "ready"}
    console -> {"type": "session", "id": "<session id>" | null}
    plugin  -> {"type": "call", "id": "<id>", "tool": "<name>", "args": {...}}
    console -> {"type": "result", "id": "<id>", "result": "<text>"}
    console -> {"type": "result", "id": "<id>", "error": "<text>"}

Several consoles can share the profile. Each keeps its own socket, and no hello closes another
console's socket. A console claims the session it shows with a session frame, after its ready and
on every change. A call goes to the console that claimed the calling run's session, the newest
claim first, and to no other console: with no claim the call fails.

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
import itertools
import json
import logging
import sys
import threading
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

# The tool error for a call whose session no live console claimed.
NO_CONSOLE = "no console is in this conversation"


class DeviceBroker:
    """The consoles' sockets, the tools they declared, and the calls waiting on them."""

    def __init__(self, ctx):
        self._ctx = ctx
        self._loop = None
        # Live console sockets, oldest hello first.
        self._sockets = []
        # Socket -> (session id it claimed, claim order). Touched on the gateway loop only.
        self._claims = {}
        self._claim_order = itertools.count()
        # Call id -> (socket it went down, future for its answer). Touched on the gateway loop only.
        self._pending = {}
        self._handles = {}
        self._tool_set = None
        # Hermes may unload on another thread (a reload runs off the gateway loop). This lock makes
        # "still live?", the registration and the socket handover one step that unload waits for.
        self._lock = threading.Lock()
        self._unloaded = False

    def unload(self):
        """This load is going away: drop its tools' bookkeeping and close the consoles' sockets.

        The broker disposes its own registrations too: one made just before the unload may be
        newer than what the plugin manager tracked (disposing twice is harmless). Closing a
        socket fails the calls waiting on it and makes its console dial again; its hello then
        reaches the next load's broker, which registers the tools through its own context. With
        no next load (the plugin was disabled) the route has no broker and refuses the console.
        """
        state = _state()
        if state.broker is self:
            state.broker = None
        with self._lock:
            self._unloaded = True
            handles, self._handles = self._handles, {}
            self._tool_set = None
            loop, sockets = self._loop, self._sockets
            self._sockets = []
        for handle in handles.values():
            handle.dispose()
        if loop is None or loop.is_closed():
            return
        for ws in sockets:
            if ws.closed:
                continue
            try:
                asyncio.run_coroutine_threadsafe(ws.close(code=1012, message=b"plugin reloaded"), loop)
            except RuntimeError:
                return  # the loop is shutting down; the sockets go with it

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
        with self._lock:
            live = not self._unloaded and _state().broker is self
            if live:
                self._apply(tools)
                self._sockets.append(ws)
        if not live:
            # This load was unloaded while the hello was on its way: its context must not register
            # anything. The console dials again and reaches the live load, if there is one.
            await ws.close(code=1012, message=b"plugin reloaded")
            return ws
        await ws.send_json({"type": "ready"})
        logger.info("device_tools: console connected with tools %s", [t["name"] for t in tools])

        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    self._on_frame(ws, msg.data)
        finally:
            with self._lock:
                if ws in self._sockets:
                    self._sockets.remove(ws)
            self._claims.pop(ws, None)
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
            # The keywords are the dispatcher's context (task_id, session_id, user_task), never the
            # model's arguments: those come as the args dict, or not at all.
            return self.call(name, args if isinstance(args, dict) else {}, kwargs.get("session_id"))

        return handler

    def call(self, name, args, session_id):
        """Run one tool on the console in session_id. Called on a Hermes worker thread; blocks until
        the answer."""
        loop = self._loop
        if loop is None or not session_id:
            return _error(NO_CONSOLE)
        future = asyncio.run_coroutine_threadsafe(self._send_call(name, args, session_id), loop)
        try:
            # The coroutine enforces CALL_TIMEOUT itself; this outer bound only guards a stalled loop.
            return future.result(CALL_TIMEOUT + 5.0)
        except concurrent.futures.TimeoutError:
            future.cancel()
            return _error("the console did not answer within %d s" % CALL_TIMEOUT)

    async def _send_call(self, name, args, session_id):
        ws = self._console_in(session_id)
        if ws is None:
            return _error(NO_CONSOLE)
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

    def _console_in(self, session_id):
        """The live socket with the newest claim of session_id, or None."""
        claims = [(order, ws) for ws, (claimed, order) in self._claims.items()
                  if claimed == session_id and not ws.closed]
        return max(claims, key=lambda claim: claim[0])[1] if claims else None

    def _on_frame(self, ws, text):
        """Record a session claim, or settle the waiting call a result frame answers. Anything else is
        dropped."""
        try:
            frame = json.loads(text)
        except ValueError:
            return
        if not isinstance(frame, dict):
            return
        if frame.get("type") == "session":
            session_id = frame.get("id")
            if isinstance(session_id, str) and session_id:
                self._claims[ws] = (session_id, next(self._claim_order))
            elif session_id is None:
                self._claims.pop(ws, None)
            return
        if frame.get("type") != "result":
            return
        call_id = frame.get("id")
        if not isinstance(call_id, str):
            return
        owner, answer = self._pending.get(call_id, (None, None))
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
    from aiohttp import web

    broker = _state().broker
    if broker is None:
        # The plugin was unloaded and the frozen router cannot drop the route.
        return web.json_response({"error": {"message": "device tools are not loaded"}}, status=503)
    return await broker._handle_socket(request)


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
