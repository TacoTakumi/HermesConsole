# HermesConsole

An LCARS-style, always-on voice console for an Android wall tablet. Say "hey
computer", ask a question, and the console streams your speech to a speech
server, sends the turn to a Hermes agent gateway and speaks the reply back
sentence by sentence while it shows the reply, tool activity and any
artifacts on the panel.

This is a proof of concept and a reference design, built as a personal side
project. It is not maintained as a product. It runs on one tablet model
against one home setup.

## What it does

- On-device wake word ("hey computer") with sherpa-onnx keyword spotting.
- Streaming speech-to-text and text-to-speech through a VoiceManager server
  over WebSockets.
- Agent turns through the Hermes gateway `/v1/runs` stream, with session
  continuity across turns.
- A live tool-activity strip, tool approvals on the panel and by voice, and
  artifacts (markdown documents and images) that the model renders through a
  `render_artifact` tool.
- An appliance mode: the app is the HOME launcher, keeps the microphone in a
  foreground service, survives doze and screen-off, and has an idle
  screensaver.
- Barge-in (interrupting a reply by speaking) is built but switched off
  (`BARGE_IN_ENABLED` in `MainActivity.kt`). The tablet's hardware echo
  canceller drops out under live playback, so the console can hear itself.
  A software echo canceller (WebRTC AEC3) is wired in behind
  `SOFTWARE_AEC_ENABLED`, also off.

## Requirements

- An arm64-v8a Android tablet, Android 12 (API 31) or later. Built and
  tested only on a Samsung Galaxy Tab A9+ (SM-X210) on One UI 8 / Android 16.
- A Hermes gateway (a checkout of `NousResearch/hermes-agent`) on the
  `hermesconsole-gateway` branch (see "Gateway changes" below), with the
  `render_artifact` user plugin from `hermes-plugin/` installed.
- A VoiceManager server for speech-to-text and text-to-speech (see "VoiceManager" below).
- To build: JDK 17 and the Android SDK (compileSdk 36).

## Build and install

    ./scripts/fetch-wakeword-assets.sh   # once per clone: sherpa-onnx AAR and KWS model
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

`audio/src/main/jniLibs/arm64-v8a/libwebrtc_apm.so` is a prebuilt WebRTC
AudioProcessing library. To rebuild it from the pinned WebRTC revision, see
`webrtc-sdk/webrtc-build/README.md`.

## Setup

1. Launch the console and open its settings screen. Set the Hermes gateway
   host, port and API key, and the VoiceManager host, port and API key. The
   default hosts are `127.0.0.1`, which on the tablet is the tablet itself,
   so both must be set.
2. For unattended use, exempt the app from battery optimization:

       adb shell dumpsys deviceidle whitelist +com.hermesconsole

3. Set the console as the HOME app when Android asks.

Point the console at its own Hermes profile, set up as in the next section.

## Console profile

Run the console on a Hermes profile of its own, served by its own gateway
instance on its own port. The console's persona and its device tools then stay
out of your other Hermes sessions.

1. Create the profile and its gateway service on the gateway host:

       hermes profile create console
       hermes --profile console gateway install

   The profile home is `/home/you/.hermes/profiles/console`. The service is
   `hermes-gateway-console.service`.

2. In the profile's `.env`, enable the api_server on a port of its own and
   set its key:

       API_SERVER_ENABLED=true
       API_SERVER_HOST=0.0.0.0
       API_SERVER_PORT=8643
       API_SERVER_KEY=<a long random key>

   This key is the console's token. Enter it, the gateway host and port 8643
   on the console's settings screen. The console uses the same key for its
   turns and for its device tool socket.

3. Write the console's persona to `SOUL.md` in the profile home. The gateway
   reads it at start. Keep replies short and spoken: the console reads them
   aloud, and anything long belongs on its display through `render_artifact`.

4. Install the two plugins in the profile with `scripts/hermes-plugin.sh`. It
   copies them to the profile's `plugins/` directory, adds them to
   `plugins.enabled` in the profile's `config.yaml`, and restarts the
   profile's gateway:

       HERMES_SSH=you@gateway \
       HERMES_HOME_DIR=.hermes/profiles/console \
       HERMES_SERVICE=hermes-gateway-console.service \
       HERMES_URL=http://gateway:8643 \
       ./scripts/hermes-plugin.sh all

   - `render_artifact` shows documents and images on the console's display.
   - `device_tools` adds the `/v1/device/ws` socket. The console connects to
     it, so the gateway never has to reach the tablet, also behind a VPN or
     with a changing address. The tools the console declares there
     (`list_apps` and `launch_app`) appear in this profile only.

   `HERMES_HOME_DIR` is relative to the gateway user's home. The script stops
   when `HERMES_HOME_DIR` or `HERMES_SERVICE` is not set, so it never installs
   the device tools in your default profile by mistake. The script reads
   the key from `HERMES_KEY_FILE` (default `~/.hermes/api_server.env`, a
   `KEY=` line) for its live check.

## Gateway changes

Upstream Hermes does not yet give the console everything it reads off the
`/v1/runs` stream. The changes it needs are kept as a branch of a fork:
`hermesconsole-gateway` at https://github.com/TacoTakumi/hermes-agent. It is
upstream `main` plus these commits:

- Tool arguments on `tool.started` and tool results on `tool.completed`
  (NousResearch/hermes-agent#131479). Without arguments the console shows no
  artifacts. Without results the tool rows show no output.
- Tool results on runs that go through the session-authority admission path,
  which is every `/v1/runs` run on a current gateway.
- The model that served a run, on `run.completed`. The console's MODEL readout
  shows it.
- Real tool durations on that same path.

To install it, in your hermes-agent checkout:

    git fetch https://github.com/TacoTakumi/hermes-agent.git hermesconsole-gateway
    git switch -c hermesconsole-gateway FETCH_HEAD

Then add this to the `config.yaml` of the profile you run `hermes update` from:

    updates:
      parked_branch_strategy: update_in_place

Without it, `hermes update` switches a branch with extra commits back to
`main`, and the gateway silently loses the console changes. With it, `hermes
update` merges upstream `main` into the branch, which keeps it current. Then
restart your gateway, and install the plugins (see Console profile).

Do not merge or pull the branch into `main`, and do not pull the fork's branch
into your copy: the fork's branch is rebased onto upstream from time to time.
To take a later fix from it, reset your copy to its new tip and restart the
gateway. This drops any changes of your own on that branch:

    git fetch https://github.com/TacoTakumi/hermes-agent.git hermesconsole-gateway
    git switch -C hermesconsole-gateway FETCH_HEAD

The branch goes away once its changes are upstream.

## VoiceManager

VoiceManager is the console's speech server: streaming speech-to-text for what
you say, and streaming text-to-speech for each reply, sentence by sentence.
Its public release is pending. It will be at
https://github.com/TacoTakumi/VoiceManager.

- The console talks to it over one WebSocket, `/ws/stream`, sending 16 kHz
  mono 16-bit audio and receiving transcripts and reply audio.
- The console's default port for it is 8000.
- It needs an API key, which you set on the console's settings screen beside
  the host and port.

## Layout

- `app` - the console shell. Composes the modules below.
- `lcars` - the LCARS Compose design system. Knows nothing of the protocols.
- `audio` - capture, playback and echo cancellation. Android, no UI.
- `wakeword` - the wake-word detector. Android, no UI.
- `voiceclient` - VoiceManager client. Plain Kotlin/JVM.
- `hermesclient` - Hermes gateway client. Plain Kotlin/JVM.
- `hermes-plugin` - the `render_artifact` and `device_tools` gateway plugins.
- `webrtc-sdk` - the JNI bridge and build for the WebRTC AEC3 library.

## Tests

    ./gradlew test

On-device tests run through `scripts/connected-test.sh <TestClass>`, not
`./gradlew connectedDebugAndroidTest`. The gradle run uninstalls the app when
it finishes, which wipes the saved settings.

## Third-party code and trademarks

- WebRTC (BSD-3-Clause, with a patent grant) and the components compiled
  into `libwebrtc_apm.so`. Their license texts are in `licenses/webrtc/`.
- The Antonio font (SIL Open Font License 1.1), in
  `lcars/src/main/assets/fonts/`, with its license file beside it.
- sherpa-onnx (Apache-2.0) and its keyword-spotting model are downloaded by
  `scripts/fetch-wakeword-assets.sh` and are not in this repository.

LCARS is a design from Star Trek. This is an unofficial fan project, not
affiliated with or endorsed by CBS Studios or Paramount.

## License

MIT. See `LICENSE`. Third-party code keeps its own license (see above).
