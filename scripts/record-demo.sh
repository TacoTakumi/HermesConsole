#!/usr/bin/env bash
# Records a demo at the wall: the tablet screen and its audio through scrcpy, and the room
# (the speaker's voice) through a mic on this host. Then mixes the two into one demo.mp4.
#
# The tablet's own mic cannot carry the speaker's voice: the console holds it as
# VOICE_COMMUNICATION, a privacy-sensitive source, so a second capture there reads silence and
# can disturb the wake-word path. A mic on this host has neither problem.
#
# scrcpy records with --audio-dup (playback capture, Android 13+), so the tablet keeps playing
# its replies out loud. The default "output" source would mute the tablet and break the demo.
# Playback capture skips USAGE_ASSISTANT, so the console's spoken replies and cues are not in
# the tablet track: the room mic carries them. The tablet track keeps other apps' media sound.
#
# Usage:
#   scripts/record-demo.sh mics                  list the PulseAudio sources (pick MIC from these)
#   scripts/record-demo.sh record                record until Enter, then mix
#   scripts/record-demo.sh mix DIR [OFFSET]      mix DIR again; OFFSET (seconds, may be negative)
#                                                is how far to delay the mic against the screen
#
# Environment:
#   ANDROID_SERIAL  the tablet, e.g. 192.0.2.10:5555 (required when more than one device is up)
#   MIC             PulseAudio source name, e.g. alsa_input.usb-Vendor_Mic-00.analog-stereo
#   SCRCPY          scrcpy binary (default: scrcpy on PATH; needs 2.4 or later for --audio-dup)
#   ADB             adb binary scrcpy uses (default: adb on PATH). Point it at the adb that
#                   holds the wireless connection: a second adb version restarts the server
#                   and drops the tablet.
#
# Each take lands in build/demo/<timestamp>/: screen.mkv, mic.wav, offset, demo.mp4.
set -euo pipefail
cd "$(dirname "$0")/.."

SCRCPY="${SCRCPY:-scrcpy}"
ADB="${ADB:-$(command -v adb || true)}"
export ADB

# Offset of the mic against the screen recording, from the wall-clock start of each.
# scrcpy logs "Recording started" once the first packets are in, so that line marks the
# screen's time zero. ffmpeg's time zero is the moment it opens the source.
stamp_lines() {
  while IFS= read -r line; do printf '%s %s\n' "$(date +%s.%N)" "$line"; done
}

mix() {
  local dir="$1" offset="${2:-$(cat "$1/offset")}" mic_filter
  # Delay the mic when it started late, trim its head when it started early.
  if awk -v o="$offset" 'BEGIN { exit !(o >= 0) }'; then
    mic_filter="adelay=delays=$(awk -v o="$offset" 'BEGIN { printf "%d", o * 1000 }'):all=1"
  else
    mic_filter="atrim=start=$(awk -v o="$offset" 'BEGIN { printf "%.3f", -o }'),asetpts=PTS-STARTPTS"
  fi
  # Two passes. scrcpy sends video frames only when the screen changes, and with a long gap in
  # the copied video ffmpeg 6.1 cut the mixed audio at the last frame when both ran in one pass.
  ffmpeg -v error -y -i "$dir/screen.mkv" -i "$dir/mic.wav" \
    -filter_complex "[1:a]${mic_filter}[m];[0:a][m]amix=inputs=2:duration=first:normalize=0[a]" \
    -map "[a]" -c:a aac -b:a 192k "$dir/mix.m4a"
  # Re-encode the video at a constant 30 fps with a keyframe every 2 s. scrcpy's stream has one
  # keyframe at the start and no frames while the screen is still, and many players then show
  # only the first image. tpad holds the last frame until the audio ends.
  ffmpeg -v error -y -i "$dir/screen.mkv" -i "$dir/mix.m4a" -map 0:v -map 1:a \
    -vf "tpad=stop_mode=clone:stop=-1,fps=30" -shortest \
    -c:v libx264 -preset veryfast -crf 20 -g 60 -pix_fmt yuv420p -c:a copy \
    -movflags +faststart "$dir/demo.mp4"
  echo "mixed: $dir/demo.mp4 (mic offset ${offset}s)"
}

record() {
  : "${MIC:?set MIC to a PulseAudio source; scripts/record-demo.sh mics lists them}"
  local dir
  dir="build/demo/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dir"

  local mic_t0 screen_t0 mic_pid scrcpy_pid
  mic_t0=$(date +%s.%N)
  ffmpeg -nostdin -v error -f pulse -i "$MIC" "$dir/mic.wav" &
  mic_pid=$!

  # Line-buffered, or scrcpy holds its log (and the "Recording started" line) until it exits.
  # --no-audio-playback: scrcpy would also play the tablet audio on this host, into the room mic.
  stdbuf -oL -eL "$SCRCPY" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} --no-window --audio-dup --no-audio-playback \
    --record="$dir/screen.mkv" </dev/null > >(stamp_lines > "$dir/scrcpy.log") 2>&1 &
  scrcpy_pid=$!

  # SIGTERM, not SIGINT: a background job in a script starts with SIGINT ignored, and scrcpy
  # keeps it that way. Both close their files cleanly on SIGTERM.
  stop() {
    trap - INT TERM
    kill -TERM "$scrcpy_pid" "$mic_pid" 2>/dev/null || true
    wait "$scrcpy_pid" "$mic_pid" 2>/dev/null || true
  }
  trap 'stop; exit 130' INT TERM

  # Confirm both are live before anyone starts talking.
  local i
  for i in $(seq 100); do
    grep -q "Recording started" "$dir/scrcpy.log" 2>/dev/null && break
    kill -0 "$scrcpy_pid" 2>/dev/null || { cat "$dir/scrcpy.log" >&2; stop; exit 1; }
    sleep 0.1
  done
  grep -q "Recording started" "$dir/scrcpy.log" || { echo "scrcpy did not start recording" >&2; cat "$dir/scrcpy.log" >&2; stop; exit 1; }
  kill -0 "$mic_pid" 2>/dev/null || { echo "mic capture died" >&2; stop; exit 1; }

  screen_t0=$(grep -m1 "Recording started" "$dir/scrcpy.log" | cut -d' ' -f1)
  awk -v m="$mic_t0" -v s="$screen_t0" 'BEGIN { printf "%.3f\n", m - s }' > "$dir/offset"

  echo "RECORDING to $dir - press Enter to stop"
  read -r _ || true
  stop
  trap - INT TERM
  mix "$dir"
}

case "${1:-}" in
  mics)   pactl list short sources | awk '$2 !~ /\.monitor$/ { print $2 }' ;;
  record) record ;;
  mix)    mix "${2:?usage: $0 mix DIR [OFFSET]}" "${3:-}" ;;
  *)      echo "usage: $0 mics | record | mix DIR [OFFSET]" >&2; exit 2 ;;
esac
