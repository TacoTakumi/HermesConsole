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
"""

import asyncio
import json
import logging

logger = logging.getLogger(__name__)

ROUTE = "/v1/device/ws"
TOOLSET = "device_tools"

# How long a fresh socket may stay silent before its hello is overdue.
HELLO_TIMEOUT = 10.0


class DeviceBroker:
    """One console's socket, the tools it declared, and the calls waiting on it."""

    def __init__(self, ctx):
        self._ctx = ctx
        self._adapter = None
        self._ws = None
        self._handles = {}
        self._tool_set = None

    def attach(self, app, adapter):
        """The api_server platform handler: runs in connect(), before the router freezes."""
        self._adapter = adapter
        app.router.add_route("GET", ROUTE, self._handle_socket)

    def _refusal(self, request):
        # Plugin routes get no auth from the adapter, so the route applies the adapter's own Bearer
        # check, profile-scoped key and all. Without that check there is no way to know the key, so
        # the socket fails closed.
        from aiohttp import web

        check = getattr(self._adapter, "_check_auth", None)
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
            async for _msg in ws:
                pass
        finally:
            if self._ws is ws:
                self._ws = None
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
        raise NotImplementedError


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


def register(ctx):
    """Add the console's socket route to the api_server; the tools come with its hello."""
    broker = DeviceBroker(ctx)
    ctx.register_platform_handler("api_server", broker.attach)
