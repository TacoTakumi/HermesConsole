#!/usr/bin/env bash
# Run a connected test class WITHOUT uninstalling the console afterwards.
#
# `gradlew connectedDebugAndroidTest` uninstalls both APKs when the run ends, and an
# uninstall wipes /data/data - taking the settings store with it: the backend keys the
# tests read (T-41, REQ-28) AND the keys the wall console itself dials with. AGP's
# documented escape hatch (android.injected.androidTest.leaveApksInstalledAfterTest)
# is not honored by AGP 8.9's UTP runner - verified empirically on this rig, twice.
#
# So this script owns the install lifecycle instead: build and install both APKs, then
# drive `am instrument` directly. Nothing is uninstalled, the store survives, and keys
# set once on the settings screen stay set. Use it for EVERY on-device run - any gradle
# connected run wipes the configured console as a side effect.
#
# Usage:
#   scripts/connected-test.sh CueQuietTest
#   scripts/connected-test.sh SeedSettingsTool -e vmKey ... -e hermesKey ... \
#       -e hermesHost ... -e vmHost ...
#
# Extra arguments are passed to `am instrument` verbatim (instrumentation args are
# `-e name value`). A key passed that way is written to the OS log by adbd - the
# settings screen is the leak-free path; -e keys are for scratch backends only.
set -euo pipefail
cd "$(dirname "$0")/.."

class="${1:?usage: connected-test.sh <TestClass> [-e name value ...]}"
shift
case "$class" in
  *.*) fqcn="$class" ;;
  *)   fqcn="com.hermesconsole.app.$class" ;;
esac

./gradlew :app:installDebug :app:installDebugAndroidTest

# am instrument exits 0 even when tests fail; the printed summary is the verdict.
out="$(adb shell am instrument -w -e class "$fqcn" "$@" \
  com.hermesconsole.test/androidx.test.runner.AndroidJUnitRunner)"
printf '%s\n' "$out"
case "$out" in
  *"FAILURES!!!"*|*"INSTRUMENTATION_FAILED"*|*"Process crashed"*) exit 1 ;;
  *"OK ("*) exit 0 ;;
  *) echo "connected-test.sh: no test verdict in instrument output" >&2; exit 1 ;;
esac
