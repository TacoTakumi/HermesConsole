# webrtc-build - WebRTC APM-only arm64-v8a build

Builds the WebRTC AudioProcessing module (AEC3) as a single Android shared
library, `libwebrtc_apm.so`, from a pinned WebRTC source checkout. No
PeerConnection, no wider SDK surface: the ninja target is
exactly the `webrtc_apm` shared library over `//modules/audio_processing`.

## Requirements

- Linux dev machine, headless (no CI flow - single-machine by design).
- ~12-16 GB free disk for the checkout + build (cache dir is configurable).
- Android NDK r27 (`WEBRTC_NDK_ROOT`, default `$ANDROID_HOME/ndk/27.0.12077973`).
- `git`, `curl`, `python3`. depot_tools is installed automatically into the
  cache dir on first run.

## Usage

```sh
./webrtc-sdk/webrtc-build/build.sh                # fetch + build + stage
./webrtc-sdk/webrtc-build/build.sh --only-fetch   # just the pinned checkout
./webrtc-sdk/webrtc-build/build.sh --no-fetch     # rebuild with existing checkout
```

Environment:

- `WEBRTC_BUILD_CACHE` - checkout + build dir (default
  `webrtc-sdk/webrtc-build/.cache`; the dev machine keeps it on `/vol2` to
  protect the root disk).
- `WEBRTC_NDK_ROOT` - Android NDK root (default as above).
- `WEBRTC_JOBS` - ninja parallelism (default `nproc`).

Output: `audio/src/main/jniLibs/arm64-v8a/libwebrtc_apm.so`, staged for the
`:audio` module (arm64-v8a only).

## Pin

`PIN` records the branch and exact commit. Current pin: `refs/branch-heads/8009`
(WebRTC 144 line, cut 2026-08-14). The script fetches the pinned branch, verifies
HEAD against `WEBRTC_PIN_COMMIT`, and re-pins if the checkout has drifted.

The checkout is the standard webrtc_android size (~16 GB): the bulk of
third_party (blink, rust, grpc, protobuf, ...) ships as one chromium mirror git
repo that DEPS custom_deps cannot split, and the CIPD android_ndk/android_sdk
downloads re-sync regardless (verified 2026-08-16), so no trimming is attempted.
The build itself is tiny (APM-only closure, 410 BUILD.gn files parsed).

## How it works

1. Installs depot_tools into the cache dir.
2. `fetch --nohistory --nohooks webrtc_android`, then rewrites `.gclient` to pin
   the src solution to `WEBRTC_PIN_REF` and drop the bundled NDK/SDK downloads.
3. `gclient sync` + `runhooks` at the pinned commit.
4. Injects the overlay into the checkout: `webrtc-build/overlay/BUILD.gn` and the
   JNI sources from `webrtc-sdk/jni/` into `<cache>/src/webrtc_apm/`. The
   checkout itself is never edited by hand and stays reproducible from `PIN`.
5. `gn gen out/android-arm64` with `target_os="android"`, `target_cpu="arm64"`,
   `is_debug=false`, `android_ndk_root` -> installed NDK, `android_ndk_api_level
   = 31` (the app's minSdk).
6. `ninja -C out/android-arm64 webrtc_apm` and copies the `.so` into
   `audio/src/main/jniLibs/arm64-v8a/`.

## Verification

```sh
readelf -d out/android-arm64/libwebrtc_apm.so | grep SONAME        # libwebrtc_apm.so
nm -D out/android-arm64/libwebrtc_apm.so                          # JNI exports only
nm -D out/android-arm64/libwebrtc_apm.so | grep -i peerconnection # must be empty
readelf -Ws out/android-arm64/libwebrtc_apm.so | grep -c AudioProcessing  # APM present
```

`-fvisibility=hidden` keeps the dynamic export table to exactly the JNI surface;
APM internals appear in the full symbol table but not in the dynamic exports.

## Size notes

A full `webrtc_android` checkout is ~16 GB (documented at webrtc.org; the bulk
is the chromium third_party mirror repo - see the Pin section). The APM-only
build output is ~2-4 GB. First build (audio_processing + absl + perfetto +
rtc_base) takes 20-60 minutes on a 20-core machine; rebuilds are incremental
(the jni.cc recompile + relink only).
