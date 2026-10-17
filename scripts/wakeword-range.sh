#!/usr/bin/env bash
# Runs the T-33 wake-word range measurement on the connected device, and KEEPS its recording.
#
# Deliberately not `./gradlew connectedAndroidTest`: that uninstalls the APK when it finishes, and
# the uninstall wipes the app's files directory - taking the session recording down with it. The
# recording is the whole point of paying a human 90 seconds to stand in a room saying one word, so
# this drives the same test through `am instrument`, which leaves the app installed.
#
# Usage:
#   scripts/wakeword-range.sh                 # the 10-utterance range measurement (needs a human)
#   scripts/wakeword-range.sh cue             # the cue self-check (no human needed)
#
# The recording lands in build/wakeword/, where scripts/wakeword-sweep.py can replay it against any
# model or threshold without asking anyone to speak again.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.hermesconsole
RUNNER="${PKG}.test/androidx.test.runner.AndroidJUnitRunner"
CLASS=com.hermesconsole.app.WakeWordRangeTest

SUPPRESS=true
SOURCE=voicecomm
case "${1:-range}" in
  cue)    METHOD=the_cue_tone_actually_reaches_the_room ;;
  range)  METHOD=detects_the_wake_phrase_at_two_metres_from_the_wall_mount ;;
  # The same measurement with the platform noise suppressor switched off - the standing suspect for
  # eating the wake word's opening consonant before the model ever sees it.
  no-ns)  METHOD=detects_the_wake_phrase_at_two_metres_from_the_wall_mount; SUPPRESS=false ;;
  # DIAGNOSTIC: listen on the unprocessed source. Not a product mode - the console listens on the
  # echo-cancelled stream, because never-self-barge (REQ-06) depends on it. This only answers
  # whether VOICE_COMMUNICATION's preprocessing is what is destroying the wake word's onset.
  raw)    METHOD=detects_the_wake_phrase_at_two_metres_from_the_wall_mount; SOURCE=unprocessed ;;
  *)      echo "usage: $0 [range|no-ns|raw|cue]" >&2; exit 2 ;;
esac

OUT=build/wakeword
mkdir -p "$OUT"

./gradlew -q :app:installDebug :app:installDebugAndroidTest

# Wake the tablet first. A dozing device has no visible Activity, and since Android 12 that means
# AudioRecord hands the process digital silence rather than an error - the microphone reads as dead
# and every utterance scores as a miss. The harness catches it, but only after wasting a human's
# 90 seconds; cheaper to just turn the screen on.
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb logcat -c

echo "=== ${METHOD} ==="
adb shell am instrument -w -e class "${CLASS}#${METHOD}" -e noiseSuppression "$SUPPRESS" -e source "$SOURCE" "$RUNNER" 2>&1 | tee "$OUT/instrument.log"

adb logcat -d -s WakeWordRange:* > "$OUT/measurement.log" || true
grep -E "RESULT|VERDICT|CUE|RECORDING" "$OUT/measurement.log" || true

# The app is still installed, so its own files are still there - and run-as is the only way in:
# since Android 11 the shell user cannot read Android/data, so a plain `adb pull` fails.
#
# Every session is kept under its own timestamp. A recording costs a human 90 seconds of standing in
# a room saying one word; overwriting the last one to save a few megabytes is a bad trade, and
# tuning against several sessions is the only defence against fitting the detector to one of them.
STAMP="$(date +%Y%m%d-%H%M%S)"
for f in wakeword-session.pcm wakeword-session.marks; do
  ext="${f##*.}"
  dest="$OUT/session-${STAMP}.${ext}"
  if adb exec-out run-as "$PKG" cat "/storage/emulated/0/Android/data/${PKG}/files/${f}" > "$dest" 2>/dev/null &&
     [[ -s "$dest" ]]; then
    echo "kept: $dest ($(stat -c%s "$dest") bytes)"
  else
    rm -f "$dest"
  fi
done
