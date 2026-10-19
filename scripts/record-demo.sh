#!/usr/bin/env bash
# Records a demo at the wall: the tablet screen, the tablet's playback and the tablet's mic, then
# mixes them into one demo.mp4.
#
# Two scrcpy instances, because one carries a single audio source:
#   - screen.mkv: video plus the playback, with --audio-dup so the tablet keeps playing its
#     replies out loud. The default "output" source would mute the tablet and break the demo.
#     Playback capture skips USAGE_ASSISTANT, so turn on the console's "recordable audio"
#     setting first, or its speech and cues are not in this track.
#   - mic.mka: the tablet mic (the "mic" source), which carries the speaker's voice.
#
# The console holds the mic as VOICE_COMMUNICATION. A second capture usually runs beside it, but
# once in four tests Android silenced the console's capture for the whole take, and a silenced
# console does not hear its wake word. After each take the script reads Android's silenced
# history and warns when the console was silenced during the take.
#
# The tablet mic also hears the tablet speaker, about 0.3 s after the playback track (measured
# 284 and 340 ms on two takes). The mix finds that lag by cross-correlation, moves the mic earlier
# by it so its copy of the speaker lines up with the playback, then turns the mic down while the
# playback has sound. The reply is heard once, from the clean track. A take with no console sound
# falls back to the wall-clock start offset.
#
# Usage:
#   scripts/record-demo.sh record                record until Enter, then mix
#   scripts/record-demo.sh mix DIR [OFFSET]      mix DIR again; OFFSET (seconds, may be negative)
#                                                is how far to delay the mic against the screen,
#                                                in place of the measured lag
#
# Environment:
#   ANDROID_SERIAL  the tablet, e.g. 192.0.2.10:5555 (required when more than one device is up)
#   SCRCPY          scrcpy binary (default: scrcpy on PATH; needs 2.4 or later for --audio-dup)
#   ADB             adb binary scrcpy uses (default: adb on PATH). Point it at the adb that
#                   holds the wireless connection: a second adb version restarts the server
#                   and drops the tablet.
#   MIC_GAIN_DB     gain on the tablet mic in the mix (default 12). Android has no mic gain
#                   setting, and speech from across the room records low. Also for `mix`.
#
# Each take lands in build/demo/<timestamp>/: screen.mkv, mic.mka, offset, demo.mp4.
# The lag measurement needs python3 with numpy.
set -euo pipefail
cd "$(dirname "$0")/.."

SCRCPY="${SCRCPY:-scrcpy}"
MIC_GAIN_DB="${MIC_GAIN_DB:-12}"
ADB="${ADB:-$(command -v adb || true)}"
export ADB
PKG=com.hermesconsole

# Offset of the mic against the screen recording, from the wall-clock start of each.
# scrcpy logs "Recording started" once the first packets are in, so that line marks each
# recording's time zero.
stamp_lines() {
  while IFS= read -r line; do printf '%s %s\n' "$(date +%s.%N)" "$line"; done
}

adb_t() { "$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }

# Android's record of captures it silenced for the console's uid since device time $1
# ("MM-DD HH:MM:SS"), one line per event.
console_silenced_since() {
  local since="$1" uid
  uid=$(adb_t shell cmd package list packages -U "$PKG" | sed -n "s/^package:$PKG uid://p" | tr -d '\r')
  adb_t shell dumpsys media.audio_flinger | sed -n '/Recording Silenced History/,/^$/p' |
    grep "silenced on: .*uid($uid)" | awk -v since="$since" '$1 " " $2 >= since' || true
}

# The lag of the speaker's copy in the mic behind the playback track, in seconds, by
# cross-correlation over the whole take. Prints nothing when the match is weak (no console sound).
measure_lag() {
  python3 -I - "$1/screen.mkv" "$1/mic.mka" 2>/dev/null <<'EOF' || true
import subprocess, sys
import numpy as np

SR = 8000

def pcm(path):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-map", "0:a", "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
        check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32)

play, mic = pcm(sys.argv[1]), pcm(sys.argv[2])
n = 1 << int(np.ceil(np.log2(len(play) + len(mic))))
corr = np.fft.irfft(np.fft.rfft(mic, n) * np.conj(np.fft.rfft(play, n)), n)
# Search -0.2 s to +1 s: the speaker path only ever makes the mic later.
lags = np.r_[np.arange(n - SR // 5, n), np.arange(0, SR)]
best = lags[np.argmax(corr[lags])]
lag = best - n if best > n // 2 else best
score = corr[best] / (np.linalg.norm(play) * np.linalg.norm(mic) + 1e-9)
if score >= 0.1:
    print(f"{lag / SR:.3f}")
EOF
}

mix() {
  local dir="$1" offset="${2:-}" source="given" lag mic_filter
  if [ -z "$offset" ]; then
    lag=$(measure_lag "$dir")
    if [ -n "$lag" ]; then
      offset=$(awk -v l="$lag" 'BEGIN { printf "%.3f", -l }')
      source="measured lag ${lag}s"
    else
      offset=$(cat "$dir/offset")
      source="wall clock; no lag found"
    fi
  fi
  # Delay the mic when it started late, trim its head when it started early.
  if awk -v o="$offset" 'BEGIN { exit !(o >= 0) }'; then
    mic_filter="adelay=delays=$(awk -v o="$offset" 'BEGIN { printf "%d", o * 1000 }'):all=1"
  else
    mic_filter="atrim=start=$(awk -v o="$offset" 'BEGIN { printf "%.3f", -o }'),asetpts=PTS-STARTPTS"
  fi
  # Two passes. scrcpy sends video frames only when the screen changes, and with a long gap in
  # the copied video ffmpeg 6.1 cut the mixed audio at the last frame when both ran in one pass.
  # The playback is split: one copy goes to the mix, one keys the compressor that ducks the mic.
  # The console's speech peaks near full scale, so a limiter keeps the sum from clipping.
  ffmpeg -v error -y -i "$dir/screen.mkv" -i "$dir/mic.mka" \
    -filter_complex "[0:a]asplit=2[play][key];[1:a]${mic_filter},volume=${MIC_GAIN_DB}dB[m];[m][key]sidechaincompress=threshold=0.003:ratio=20:attack=5:release=400[duck];[play][duck]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.9:level=disabled[a]" \
    -map "[a]" -c:a aac -b:a 192k "$dir/mix.m4a"
  # Re-encode the video at a constant 30 fps with a keyframe every 2 s. scrcpy's stream has one
  # keyframe at the start and no frames while the screen is still, and many players then show
  # only the first image. tpad holds the last frame until the audio ends.
  ffmpeg -v error -y -i "$dir/screen.mkv" -i "$dir/mix.m4a" -map 0:v -map 1:a \
    -vf "tpad=stop_mode=clone:stop=-1,fps=30" -shortest \
    -c:v libx264 -preset veryfast -crf 20 -g 60 -pix_fmt yuv420p -c:a copy \
    -movflags +faststart "$dir/demo.mp4"
  echo "mixed: $dir/demo.mp4 (mic offset ${offset}s from ${source}, mic gain ${MIC_GAIN_DB} dB)"
}

# Start one scrcpy recording in the background, logging with timestamps. Sets started_pid.
# Not called through $(...): the log's process substitution holds that pipe open.
start_scrcpy() {
  local log="$1"; shift
  # Line-buffered, or scrcpy holds its log (and the "Recording started" line) until it exits.
  # --no-audio-playback: scrcpy would also play the tablet audio on this host.
  stdbuf -oL -eL "$SCRCPY" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} --no-window --no-audio-playback "$@" \
    </dev/null > >(stamp_lines > "$log") 2>&1 &
  started_pid=$!
}

record() {
  local dir
  dir="build/demo/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dir"

  local since screen_pid mic_pid
  since=$(adb_t shell date +'%m-%d\ %H:%M:%S' | tr -d '\r')

  start_scrcpy "$dir/screen.log" --audio-dup --record="$dir/screen.mkv"
  screen_pid=$started_pid
  start_scrcpy "$dir/mic.log" --no-video --audio-source=mic --record="$dir/mic.mka"
  mic_pid=$started_pid

  # SIGTERM, not SIGINT: a background job in a script starts with SIGINT ignored, and scrcpy
  # keeps it that way. scrcpy closes its file cleanly on SIGTERM.
  stop() {
    trap - INT TERM
    kill -TERM "$screen_pid" "$mic_pid" 2>/dev/null || true
    while kill -0 "$screen_pid" 2>/dev/null || kill -0 "$mic_pid" 2>/dev/null; do sleep 0.1; done
  }
  trap 'stop; exit 130' INT TERM

  # Confirm both are live before anyone starts talking.
  local i log
  for log in screen mic; do
    for i in $(seq 100); do
      grep -q "Recording started" "$dir/$log.log" 2>/dev/null && break
      sleep 0.1
    done
    if ! grep -q "Recording started" "$dir/$log.log"; then
      echo "scrcpy ($log) did not start recording" >&2
      cat "$dir/$log.log" >&2
      stop
      exit 1
    fi
  done

  local screen_t0 mic_t0
  screen_t0=$(grep -m1 "Recording started" "$dir/screen.log" | cut -d' ' -f1)
  mic_t0=$(grep -m1 "Recording started" "$dir/mic.log" | cut -d' ' -f1)
  awk -v m="$mic_t0" -v s="$screen_t0" 'BEGIN { printf "%.3f\n", m - s }' > "$dir/offset"

  echo "RECORDING to $dir - press Enter to stop"
  read -r _ || true
  stop
  trap - INT TERM

  local silenced
  silenced=$(console_silenced_since "$since")
  if [ -n "$silenced" ]; then
    echo "WARNING: Android silenced the console's mic during this take; the wake word may have failed." >&2
    echo "$silenced" >&2
  fi
  mix "$dir"
}

case "${1:-}" in
  record) record ;;
  mix)    mix "${2:?usage: $0 mix DIR [OFFSET]}" "${3:-}" ;;
  *)      echo "usage: $0 record | mix DIR [OFFSET]" >&2; exit 2 ;;
esac
