# Changelog

## [0.3.2]

### Added

- scripts/record-demo.sh can take the voice from a mic on the recording host:
  set MIC to its PipeWire source name. scrcpy then plays the tablet audio on
  the host, so wear headphones, and the script sets the tablet's media volume
  to 0 for the take and back after. The recording keeps the full level. The
  mic hears no reply, and the mix has no echo. Without MIC the script records the tablet
  mic as before.
- Setting "barge-in" on the config screen, off by default. On, speech over a
  reply stops it. The tablet's hardware echo canceller no longer removes the
  console's own speech, so at normal volume the console would cut itself off:
  turn it on only while the tablet speaker is quiet, for example in a demo
  take with record-demo.sh and MIC. A change applies to the next barge-in, with
  no restart. While the speaker is silent (recordable audio on, media volume
  0) the convergence guard is skipped, so the first word over a reply stops
  it; otherwise speech in the first 10 s after a start or reconnect, and in
  the first 2 s of each reply, is still held back. The README has a new
  Barge-in section.

### Fixed

- A saved change to the Hermes or VoiceManager connection settings now takes
  effect at once: the console closes and starts again on the new settings, and
  the session in progress ends. Before, the change needed an app restart. A
  console that starts with no settings now starts when they are first saved.
  A save that changes no connection setting leaves the console running.
- A host the console cannot dial, such as one with a space, no longer crashes
  the app (and, as the HOME app, again at each relaunch). The config screen
  marks such a host and does not save it; a bad host already stored leaves the
  console standing by. A console that fails to start for any other reason is
  logged and the console stands by until the settings change.

## [0.3.1]

### Changed

- The default VoiceManager port is now 8255, VoiceManager's new default. A
  console with saved settings keeps the port it has. The speech fixture
  scripts (make-aec-speech.py, make-bargein-speech.py) default to 8255 too.

## [0.3.0]

### Added

- Device tool return_home: brings the console back to the front, for "close
  the calculator" or "go back to the console". Android 14 and later let no app
  end another app, so the app it leaves keeps running in the background, and
  the tool tells the model so.
- Device tools get_volume and set_volume, for "turn the media down" or "speak
  quieter". media is the tablet's media stream, which YouTube and Spotify play
  on. voice is the console's own level for its speech, announcements and
  cues: it applies at once, also to a reply already playing, and the console
  keeps it across a restart. set_volume takes a percent or a direction (10
  points up or down) and replies with the level set. Voice does not go below
  10 percent, so the console stays audible.
- Setting "recordable audio" on the config screen, off by default. On, the
  console plays its speech, announcements and cues as media, so a screen
  recording with scrcpy --audio-dup has the console's voice. Off, the console
  plays as an assistant, which Android does not let a recording capture.
  Cues change at once. Speech changes with its next audio track, so restart
  the app after the change to be sure.
- scripts/record-demo.sh records a demo through scrcpy: the tablet screen,
  its playback and its mic, mixed into one demo.mp4. The mic hears the
  tablet speaker about 0.3 s late, so the mix measures that lag, lines the
  mic up and turns it down while the console speaks: a reply is heard once.
  MIC_GAIN_DB raises the mic (default 12 dB). The script warns when Android
  silenced the console's mic during the take.

### Changed

- Breaking: several consoles can share one Hermes profile, each with its own
  device tool socket. Each console tells the device_tools plugin which session
  it shows, and a device tool call goes only to the console in the calling
  conversation, also after a context compression or from a subagent. A call
  from a conversation that no console shows fails with "no console is in this
  conversation". Deploy the plugin and the app together: an older app does not
  say which session it shows, so every device tool call from it fails.
- The console profile keeps the device tools always loaded
  (`tools.tool_search.eager_toolsets: [device_tools]`, a new gateway setting).
  A device request no longer waits for an extra model turn first: on the wall,
  "open the calculator" reached launch_app in 0.8 s instead of 2.5 s.

### Fixed

- Two consoles on one profile no longer take the device tool socket from each
  other about once a second.

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
