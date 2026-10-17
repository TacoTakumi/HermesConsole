# Hermes gateway plugins

The console's artifacts are tool calls: the model invokes `render_artifact` with a
typed payload, the `/v1/runs` stream forwards the argument dict whole on `tool.started` (see
"Gateway changes" in the main README), and the tablet renders the pane client-side. The tool
itself has to exist on the gateway so it is in the model's schema; it lives here and deploys
as a Hermes **user plugin**, not a source patch.

## Why a plugin and not a patch

Hermes loads user plugins from `~/.hermes/plugins/<name>/` on the gateway host - outside the
`hermes-agent` checkout - so an upstream `git pull` cannot revert the tool. This is the same
versioning idea as the patches, with a stronger guarantee: nothing to re-apply after a pull. The plugin is
gated by `plugins.enabled` in `~/.hermes/config.yaml` (plugins are opt-in), which is also
outside the checkout. Deployment is only needed for first installs, edits, and new hosts.

The plugin's toolset (`render_artifact`) reaches `/v1/runs` runs without any toolset config:
plugin toolsets are enabled by default on every platform whose toolset list has never been
saved by `hermes tools`, and this gateway has no saved `platform_toolsets.api_server` entry.
If `hermes tools` is ever used to save an api_server toolset list, `render_artifact` must be
kept checked (or added to the saved list) or the tool drops out of the schema.

## The contract

`render_artifact(kind, ...)` - `kind` declares the artifact type and names its payload field:

| kind       | payload field | the console renders                                |
|------------|---------------|----------------------------------------------------|
| `markdown` | `markdown`    | headings, lists, tables, code as formatted content |
| `image`    | `url`         | the image, aspect-correct                          |

The console's `ArtifactRouter` reads `kind` off `tool.started` args and routes to the applet
registered for it; unknown kinds are logged and skipped, never crashed on. The
handler on the gateway renders nothing - by the time it runs, the payload has already reached
the tablet on the stream. Its return value only tells the model the artifact is up and that
the spoken reply should reference the display in one sentence (see `docs/console-voice.md`).

## Deploying

    ./scripts/hermes-plugin.sh check     is the deployed plugin current and enabled?
    ./scripts/hermes-plugin.sh deploy    push the plugin, enable it in config.yaml, restart
    ./scripts/hermes-plugin.sh verify    prove it live: a markdown and an image run must each
                                         stream a render_artifact tool.started whose args
                                         carry kind and payload
    ./scripts/hermes-plugin.sh all       deploy, then verify

Host, service name, gateway URL and key file are overridable by environment variable; see the
header of `scripts/hermes-plugin.sh`. `deploy` edits `~/.hermes/config.yaml` on the gateway
host only to append `render_artifact` to `plugins.enabled` (a timestamped backup is written
first, matching the host's existing convention), and restarts the gateway: plugins and config
are read at process start.

`verify` requires tool arguments on the stream ("Gateway changes" in the main README) -
without them, `tool.started` carries no args and the artifact contract has nothing to ride on.
