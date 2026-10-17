#!/usr/bin/env bash
# webrtc-sdk/webrtc-build/build.sh
#
# Build the WebRTC AudioProcessing module (AEC3 APM) as an arm64-v8a Android
# shared library from a pinned WebRTC source checkout (REQ-09, REQ-10, D-02),
# headless on the dev machine.
#
#   ./build.sh                fetch (if needed) + build + stage the .so
#   ./build.sh --only-fetch   fetch/refresh the pinned checkout, no build
#   ./build.sh --no-fetch     build with the existing checkout, no fetch
#
# Environment:
#   WEBRTC_BUILD_CACHE   checkout + build dir (default: <repo>/webrtc-sdk/.cache)
#   WEBRTC_NDK_ROOT      Android NDK root (default: $ANDROID_HOME/ndk/27.0.12077973)
#   WEBRTC_JOBS          ninja -j value (default: nproc)
#
# Output: audio/src/main/jniLibs/arm64-v8a/libwebrtc_apm.so
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

CACHE_DIR="${WEBRTC_BUILD_CACHE:-$SCRIPT_DIR/.cache}"
SRC_DIR="$CACHE_DIR/src"
DEPOT_TOOLS_DIR="$CACHE_DIR/depot_tools"
OUT_DIR="$SRC_DIR/out/android-arm64"
PIN_FILE="$SCRIPT_DIR/PIN"
NDK_ROOT="${WEBRTC_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}"
JOBS="${WEBRTC_JOBS:-$(nproc)}"
SO_DEST="$REPO_ROOT/audio/src/main/jniLibs/arm64-v8a/libwebrtc_apm.so"

# shellcheck source=webrtc-build/PIN
source "$PIN_FILE"

log() { printf '\n== %s\n' "$*"; }

ensure_depot_tools() {
  if [ ! -x "$DEPOT_TOOLS_DIR/fetch" ]; then
    log "installing depot_tools into $DEPOT_TOOLS_DIR"
    mkdir -p "$CACHE_DIR"
    git clone --depth 1 \
      https://chromium.googlesource.com/chromium/tools/depot_tools.git \
      "$DEPOT_TOOLS_DIR"
  fi
  export PATH="$DEPOT_TOOLS_DIR:$PATH"
}

# Write .gclient: pin the src solution to WEBRTC_PIN_REF. The rest mirrors the
# `fetch webrtc_android` template (target_os android+unix, managed).
#
# Dep nulling is deliberately not used. The bulk of third_party (blink, rust,
# grpc, protobuf, ...) ships as ONE chromium mirror git repo - DEPS custom_deps
# cannot exclude parts of it, and the CIPD android_ndk/android_sdk downloads
# re-sync regardless (verified 2026-08-16). The full ~16 GB checkout is the
# expected, documented size.
write_gclient() {
  python3 - "$CACHE_DIR" "$WEBRTC_PIN_REF" <<'PYEOF'
import sys
cache_dir, pin_ref = sys.argv[1], sys.argv[2]
body = [
    "solutions = [",
    "  {",
    '    "name": "src",',
    '    "url": "https://webrtc.googlesource.com/src.git@%s",' % pin_ref,
    '    "deps_file": "DEPS",',
    '    "managed": True,',
    '    "custom_deps": {},',
    "  },",
    "]",
    'target_os = ["android", "unix"]',
    "",
]
path = f"{cache_dir}/.gclient"
with open(path, "w") as f:
    f.write("\n".join(body))
print(f"wrote {path}: pinned to {pin_ref}")
PYEOF
}

ensure_checkout() {
  if [ ! -d "$SRC_DIR/.git" ]; then
    log "fetching WebRTC checkout (first run, this takes a while)"
    mkdir -p "$CACHE_DIR"
    (
      cd "$CACHE_DIR"
      fetch --nohistory --nohooks webrtc_android
      write_gclient
      gclient sync --nohooks --no-history
    )
  fi

  # Restore the pristine tree before syncing: the overlay injection replaces the
  # root BUILD.gn and adds webrtc_apm/, which would make gclient refuse to sync.
  # Both are re-injected after the sync, so this is deterministic.
  (cd "$SRC_DIR" && git checkout -q -- BUILD.gn 2>/dev/null || true; rm -rf webrtc_apm)

  cd "$SRC_DIR"
  local head
  head="$(git rev-parse HEAD)"
  if [ "$head" != "$WEBRTC_PIN_COMMIT" ]; then
    log "checkout at $head, pin is $WEBRTC_PIN_COMMIT - moving to pin"
    git fetch origin "$WEBRTC_PIN_REF" --depth 1
    git checkout -q FETCH_HEAD
  fi
  cd "$CACHE_DIR"
  gclient sync --nohooks --no-history
  gclient runhooks
}

gen_gn() {
  log "gn gen $OUT_DIR"
  mkdir -p "$OUT_DIR"
  # The minimal root BUILD.gn (injected overlay) replaces the full WebRTC root,
  # so gn gen only parses the APM target's dependency closure.
  (cd "$SRC_DIR" && "$SRC_DIR/buildtools/linux64/gn" gen "$OUT_DIR" --args="
target_os = \"android\"
target_cpu = \"arm64\"
is_debug = false
is_component_build = false
rtc_include_tests = false
rtc_use_h264 = false
rtc_enable_protobuf = false
enable_rust = false
enable_rust_cxx = false
enable_chromium_prelude = false
rtc_rust = false
use_siso = false
android_ndk_api_level = 31
"
  )
}

# Inject the APM-only build overlay into the checkout: the minimal root
# BUILD.gn, our webrtc_apm target, and the JNI bridge sources (kept out of the
# checkout so it stays pristine/pinned and reproducible from PIN).
inject_overlay() {
  local apm_dir="$SRC_DIR/webrtc_apm"
  log "injecting overlay into $SRC_DIR (root BUILD.gn + $apm_dir)"
  cp "$SCRIPT_DIR/overlay/root_BUILD.gn" "$SRC_DIR/BUILD.gn"
  mkdir -p "$apm_dir"
  cp "$SCRIPT_DIR/overlay/BUILD.gn" "$apm_dir/BUILD.gn"
  cp "$SCRIPT_DIR/../jni/"*.cc "$apm_dir/"
  cp "$SCRIPT_DIR/../jni/"*.h "$apm_dir/" 2>/dev/null || true
}

build() {
  inject_overlay
  gen_gn
  log "ninja -C $OUT_DIR webrtc_apm (-j$JOBS)"
  ninja -C "$OUT_DIR" -j"$JOBS" webrtc_apm

  log "staging $OUT_DIR/libwebrtc_apm.so -> $SO_DEST"
  mkdir -p "$(dirname "$SO_DEST")"
  cp "$OUT_DIR/libwebrtc_apm.so" "$SO_DEST"

  log "built: $SO_DEST ($(du -h "$SO_DEST" | cut -f1))"
}

ONLY_FETCH=0
NO_FETCH=0
for arg in "$@"; do
  case "$arg" in
    --only-fetch) ONLY_FETCH=1 ;;
    --no-fetch) NO_FETCH=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

ensure_depot_tools
[ "$NO_FETCH" -eq 0 ] && ensure_checkout
[ "$ONLY_FETCH" -eq 1 ] && { log "checkout ready at $SRC_DIR"; exit 0; }

build
