# Changelog

## [0.2.1]

### Fixed

- Two consoles on one Hermes profile no longer take the device tool socket
  from each other about once a second. The newest console keeps it: the
  device_tools plugin closes the replaced socket with code 4000, and a console
  that gets 4000 does not dial again until it starts again. Deploy the plugin
  and the app together.

## [0.2.0]

### Added

- Device tools: the agent can list the tablet's apps and open one by name
  (list_apps, launch_app). Opening an app that is already running brings it to
  the front. Needs the device_tools Hermes plugin, deployed with
  scripts/hermes-plugin.sh beside render_artifact.
- The console keeps one socket to the gateway for device tool calls, with a
  ping every 30 s and a reconnect that backs off until the gateway accepts it.
- A sample console persona, hermes-profile/SOUL.example.md, with the spoken
  output rules the console needs: no MEDIA: lines, and long or structured
  content through render_artifact.

### Changed

- Breaking: the console sends the VoiceManager credential as a per-user bearer
  token (`Authorization: Bearer vmp_...`) on the stream and on GET /info. Make
  one with `voicemanager user token add`. A VoiceManager that still expects an
  API key rejects the console.
- The settings screen calls the VoiceManager field TOKEN.
- scripts/hermes-plugin.sh deploys both plugins to one profile home, needs
  HERMES_URL, and counts a plugin as enabled only under plugins.enabled.
- The speech fixture scripts read VM_TOKEN and pass `token=` to the VoiceManager
  client.

### Fixed

- One wake word could get two replies spoken at once. A launch from adb
  followed by the Home key started a second console in the same app. Now the
  newest console takes over and the older one closes first.
- A Hermes key or VoiceManager token with a character that cannot go in an HTTP
  header no longer crashes the console. The settings screen will not save
  either one and marks the field. A stored Hermes key like that leaves the
  console unconfigured. A stored VoiceManager token like that makes the voice
  link report an auth failure.
- The client configs no longer print the key in their text form.
- The plugin config editor no longer adds a duplicate entry when
  plugins.enabled holds a comment at column 0, and refuses (asking for a hand
  edit) a plugins.enabled that is not a plain list of names, instead of writing
  invalid YAML.
- The device_tools plugin survives a plugin reload and stops serving the
  console socket once unloaded.

## [0.1.0] - 2026-10-17

First public release: a proof of concept and reference design.

- LCARS voice console for an Android wall tablet: wake word, streaming speech
  through VoiceManager, agent turns through a Hermes gateway.
- Markdown and image artifacts on the panel, tool rows with their output,
  tool approvals on the panel and by voice.
- Sessions that keep their history and their last artifacts.
- Needs the hermesconsole-gateway branch of the Hermes fork (see README) and
  VoiceManager (release pending).
- Built and tested only on a Samsung Galaxy Tab A9+ (SM-X210).
