#!/usr/bin/env bash
# The module boundaries, enforced (T-31: REQ-29, REQ-30, REQ-31, REQ-11).
#
# D-09 in executable form: voiceclient and hermesclient are pure Kotlin/JVM and lift into
# any consumer unchanged; audio and wakeword are Android but UI-agnostic; the app module
# composes the others and owns no reusable logic; all Hermes conversation goes through
# hermesclient's POST /v1/runs + SSE surface and nothing else. Each clause below is a
# scan or a build fact - the script exits 0 only when every one holds.
#
# Scope notes, deliberate:
#   - REQ-31's scans cover app/src/main. app/src/androidTest holds device-measurement
#     rigs (the T-04 AEC gate, wake-word range, self-barge proofs) that MUST drive raw
#     AudioRecord/AudioTrack to measure what the room hears; test code ships on no
#     runtime classpath and is not reusable logic.
#   - lcars -> audio is a designed edge, not a leak (REQ-36): the cue player's sink
#     factory seam, so UI sound rides the same echo-cancelled output path as TTS.
#     Inbound edges to audio are therefore app and lcars; to wakeword, app alone.
set -uo pipefail
cd "$(dirname "$0")/.."

fails=0
pass() { printf '  ok  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; fails=$((fails + 1)); }

# The pattern must appear nowhere under the given paths; any hit is printed as evidence.
forbid() {
  local label="$1" pattern="$2"; shift 2
  if rg -n --no-heading -e "$pattern" "$@"; then fail "$label"; else pass "$label"; fi
}

# The pattern must appear in the given file.
require() {
  local label="$1" pattern="$2" file="$3"
  if rg -q -e "$pattern" "$file"; then pass "$label"; else fail "$label"; fi
}

echo "== REQ-29: voiceclient and hermesclient are pure Kotlin/JVM =="
for m in voiceclient hermesclient; do
  require "$m builds with the Kotlin/JVM plugin" \
    'alias\(libs\.plugins\.kotlin\.jvm\)' "$m/build.gradle.kts"
  forbid "$m declares no Android plugin" \
    'plugins\.android|com\.android\.|kotlin\.android' "$m/build.gradle.kts"
  forbid "$m imports no android.*/androidx.*" \
    '^\s*import (android|androidx)\.' "$m/src"
done

echo "== REQ-30: audio and wakeword are Android but UI-agnostic =="
forbid "audio/wakeword have no Compose import" \
  'androidx\.compose|@Composable' audio/src wakeword/src
forbid "audio/wakeword build files reference no Compose artifact" \
  'libs\.compose|androidx\.compose|plugins\.(kotlin\.)?compose' \
  audio/build.gradle.kts wakeword/build.gradle.kts
forbid "audio/wakeword depend on neither :app nor :lcars" \
  'project\(":(app|lcars)"\)' audio/build.gradle.kts wakeword/build.gradle.kts

echo "== REQ-30: dependency edges point from app toward the shared modules, never back =="
forbid "no module depends on :app" \
  'project\(":app"\)' lcars/build.gradle.kts audio/build.gradle.kts wakeword/build.gradle.kts \
  voiceclient/build.gradle.kts hermesclient/build.gradle.kts
forbid "only :app consumes :wakeword" \
  'project\(":wakeword"\)' lcars/build.gradle.kts audio/build.gradle.kts \
  voiceclient/build.gradle.kts hermesclient/build.gradle.kts
forbid "only :app and :lcars (the cue seam, REQ-36) consume :audio" \
  'project\(":audio"\)' wakeword/build.gradle.kts voiceclient/build.gradle.kts \
  hermesclient/build.gradle.kts

echo "== REQ-31: the app module composes; it owns no reusable logic =="
forbid "app touches no raw AudioRecord/AudioTrack (the audio module's job)" \
  'android\.media\.(AudioRecord|AudioTrack|\*)' app/src/main
forbid "app parses no wire protocol (the clients' job)" \
  'WebSocket|EventSource|text/event-stream|decodeFromString|parseToJsonElement' app/src/main
forbid "app holds no protocol frame literal" \
  '"(audio_start|tts_stream|tts_cancel|turn_start|turn_started|message\.delta|tool\.started|tool\.completed|approval\.request|run\.(completed|failed|cancelled))"' \
  app/src/main
forbid "app defines no LCARS palette color" \
  'Color\(0x' app/src/main
forbid "app defines no LCARS shape" \
  'GenericShape|(object|class)\s+\w+\s*:\s*Shape\b' app/src/main

echo "== REQ-11: all Hermes traffic through hermesclient's /v1/runs surface =="
forbid "no source anywhere calls a forbidden conversation endpoint" \
  '"[^"]*(v1/chat/completions|v1/responses|/chat/stream)' \
  app/src lcars/src audio/src wakeword/src voiceclient/src hermesclient/src
forbid "Hermes endpoint literals live only in hermesclient" \
  '"[^"]*(v1/runs|api/sessions)' \
  app/src/main lcars/src/main audio/src/main wakeword/src/main voiceclient/src/main

echo "== Build facts: classpaths and the plain-JVM test run =="
for m in voiceclient hermesclient; do
  report="$(./gradlew -q ":$m:dependencies")" || { fail "$m dependency report"; report=""; }
  if printf '%s\n' "$report" | rg -qi 'androidx|com\.android|robolectric'; then
    fail "$m classpaths carry no Android SDK, androidx or Robolectric artifact"
  else
    pass "$m classpaths carry no Android SDK, androidx or Robolectric artifact"
  fi
done

app_report="$(./gradlew -q :app:dependencies)" || { fail ":app dependency report"; app_report=""; }
if printf '%s\n' "$app_report" | rg -qi 'koog|langchain'; then
  fail "no Koog or equivalent agent framework on the app classpath"
else
  pass "no Koog or equivalent agent framework on the app classpath"
fi

if ./gradlew :voiceclient:test :hermesclient:test >/dev/null 2>&1; then
  pass "voiceclient and hermesclient test suites run on a plain JVM"
else
  fail "voiceclient and hermesclient test suites run on a plain JVM"
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "module boundaries hold"
else
  echo "$fails boundary check(s) failed"
  exit 1
fi
