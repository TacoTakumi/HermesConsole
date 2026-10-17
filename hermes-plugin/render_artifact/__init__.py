"""render_artifact plugin for Hermes Agent - the HermesConsole artifact channel.

The wall console reads this tool's argument dict off the /v1/runs SSE stream
(tool.started args, forwarded whole by the console's 0001 gateway patch) and
renders the declared artifact in an LCARS pane while the spoken reply streams.
The handler therefore renders nothing: the display is entirely client-side,
and the tool exists so the model has a typed, schema-enforced channel for
content that belongs on a screen instead of in speech (HermesConsole
REQ-15/REQ-16). v1 kinds are exactly markdown and image; the console skips
anything else without crashing, so the enum here and the tablet's applet
registry are two ends of one contract.
"""

import json

VALID_KINDS = ("markdown", "image")

# Which args field carries each kind's payload. The console's applet for the
# kind reads the same field name off the stream, so this dict IS the payload
# half of the wire contract.
PAYLOAD_FIELD = {"markdown": "markdown", "image": "url"}


def register(ctx):
    """Register the render_artifact tool when the plugin is loaded."""

    def render_artifact(args=None, **kwargs):
        # The tool call layer passes a dict or bare keywords; accept both
        # (same convention as the agentmail plugin on this host).
        a = args if isinstance(args, dict) else {k: v for k, v in kwargs.items() if k != "task_id"}

        kind = a.get("kind")
        if kind not in VALID_KINDS:
            return json.dumps({"error": "kind must be one of %s" % (list(VALID_KINDS),)})

        field = PAYLOAD_FIELD[kind]
        payload = a.get(field)
        if not isinstance(payload, str) or not payload.strip():
            # The console has already skipped this call (it validates the same
            # fields), so the error goes to the one party who can fix it: the
            # model, which retries with the payload filled in.
            return json.dumps({"error": "kind '%s' requires a non-empty string field '%s'" % (kind, field)})

        # By the time this handler runs, the artifact has already reached the
        # console: the payload rode tool.started, not this return value.
        return json.dumps({
            "status": "displayed",
            "kind": kind,
            "note": (
                "The artifact is on the console display. Reference it in one "
                "short spoken sentence; do not read its content aloud."
            ),
        })

    ctx.register_tool(
        "render_artifact",
        "render_artifact",
        {
            "type": "object",
            "description": "Show content on the console's display screen",
            "parameters": {
                "type": "object",
                "properties": {
                    "kind": {
                        "type": "string",
                        "enum": ["markdown", "image"],
                        "description": (
                            "What the artifact is: 'markdown' for formatted text "
                            "content, 'image' for a picture."
                        ),
                    },
                    "markdown": {
                        "type": "string",
                        "description": (
                            "The full markdown source to display. Required when kind "
                            "is 'markdown'. Headings, lists, tables, fenced code and "
                            "inline formatting all render."
                        ),
                    },
                    "url": {
                        "type": "string",
                        "description": (
                            "Direct URL of the image to display. Required when kind "
                            "is 'image'."
                        ),
                    },
                },
                "required": ["kind"],
            },
        },
        render_artifact,
        description=(
            "Show content on the console's display screen. Use this for anything a "
            "listener cannot absorb by ear: tables, documents, reports, code, lists "
            "of more than a few items, or images. Then reference the display in one "
            "short spoken sentence instead of reading the content aloud."
        ),
        emoji="🖥️",
    )
