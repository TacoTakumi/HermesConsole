#!/usr/bin/env bash
# Deploys the HermesConsole plugins (render_artifact, device_tools) to the console's Hermes
# profile and proves they took.
#
# The console's artifacts are tool calls: the model invokes render_artifact with a typed
# payload (string field kind plus the kind's payload field), the patched /v1/runs stream
# (see "Gateway changes" in README.md) forwards the args dict whole on tool.started, and the tablet renders
# the pane client-side. device_tools adds the socket the console dials in on, and registers the
# tools the console declares there. Both are Hermes USER PLUGINS under the profile home's
# plugins/ dir on the gateway host - outside the hermes-agent checkout, so a git pull cannot
# revert them. This script exists for first deploys, edits, and new hosts (T-40, REQ-15/REQ-16).
#
# Every path is under HERMES_HOME_DIR (relative to the gateway user's $HOME): the console's own
# profile home, so the device tools reach console sessions only. HERMES_SERVICE is the gateway
# unit that serves that profile, and HERMES_URL its api_server.
#
#   ./scripts/hermes-plugin.sh check    are the deployed plugins current and enabled?
#   ./scripts/hermes-plugin.sh deploy   push the plugins, enable them in config.yaml, restart
#   ./scripts/hermes-plugin.sh verify   prove it live: a markdown and an image run must each
#                                       stream a render_artifact tool.started carrying kind
#                                       and payload, and the device socket route must answer
#   ./scripts/hermes-plugin.sh all      deploy, then verify
#
# The API key is read from HERMES_KEY_FILE and never printed. Do not add set -x, and do not
# pass curl -v: both would echo the Authorization header.
set -euo pipefail
cd "$(dirname "$0")/.."

HERMES_SSH="${HERMES_SSH:?set HERMES_SSH to the gateway host ssh login, e.g. you@gateway}"
# No defaults for these two: a default would be the default profile, and device_tools there would
# hand the console's device tools to every Hermes session.
HERMES_HOME_DIR="${HERMES_HOME_DIR:?set HERMES_HOME_DIR to the console profile home, relative to the gateway user home, e.g. .hermes/profiles/console}"
HERMES_SERVICE="${HERMES_SERVICE:?set HERMES_SERVICE to the gateway unit that serves that profile, e.g. hermes-gateway-console.service}"
HERMES_URL="${HERMES_URL:-http://127.0.0.1:8642}"
HERMES_KEY_FILE="${HERMES_KEY_FILE:-$HOME/.hermes/api_server.env}"

PLUGINS=(render_artifact device_tools)
PLUGIN_FILES=(plugin.yaml __init__.py)

# A stable public image for the image-kind probe. The probe asserts the args shape, not this
# exact value: the model may legitimately rephrase everything but the payload fields.
PROBE_IMAGE_URL="https://upload.wikimedia.org/wikipedia/commons/3/3a/Cat03.jpg"

cmd_check() {
  local plugin f rc=0
  for plugin in "${PLUGINS[@]}"; do
    for f in "${PLUGIN_FILES[@]}"; do
      if ssh -o BatchMode=yes "$HERMES_SSH" "cat '$HERMES_HOME_DIR/plugins/$plugin/$f'" 2>/dev/null \
          | diff -q - "hermes-plugin/$plugin/$f" >/dev/null 2>&1; then
        echo "CHECK: $plugin/$f: deployed copy matches this repo"
      else
        echo "CHECK: $plugin/$f: missing or differs on $HERMES_SSH - run deploy" >&2
        rc=1
      fi
    done
  done
  # The heredoc is quoted and expanded locally; the remote shell never parses the python.
  ssh -o BatchMode=yes "$HERMES_SSH" python3 - "$HERMES_HOME_DIR" "${PLUGINS[@]}" <<'PY' || rc=1
import re, sys
from pathlib import Path
home, plugins = sys.argv[1], sys.argv[2:]
lines = (Path.home() / home / "config.yaml").read_text().splitlines()
try:
    start = next(i for i, l in enumerate(lines) if re.match(r"^plugins:\s*$", l))
except StopIteration:
    start = len(lines)
end = next((j for j in range(start + 1, len(lines)) if re.match(r"^\S", lines[j])), len(lines))
rc = 0
for name in plugins:
    if any(re.match(r"^\s*-\s*%s\s*$" % re.escape(name), l) for l in lines[start:end]):
        print("CHECK: config.yaml: %s is in plugins.enabled" % name)
    else:
        print("CHECK: config.yaml: %s NOT in plugins.enabled - run deploy" % name, file=sys.stderr)
        rc = 1
sys.exit(rc)
PY
  return "$rc"
}

cmd_deploy() {
  local plugin f
  for plugin in "${PLUGINS[@]}"; do
    ssh -o BatchMode=yes "$HERMES_SSH" \
      "mkdir -p '$HERMES_HOME_DIR/plugins/$plugin' && rm -rf '$HERMES_HOME_DIR/plugins/$plugin/__pycache__'"
    for f in "${PLUGIN_FILES[@]}"; do
      scp -q "hermes-plugin/$plugin/$f" "$HERMES_SSH:$HERMES_HOME_DIR/plugins/$plugin/$f"
      echo "deploy: $plugin/$f pushed"
    done
  done

  # Plugins are opt-in: append each to plugins.enabled if it is not already there. Surgical line
  # insert, never a YAML round-trip - config.yaml is the operator's file and its comments must
  # survive. Timestamped backup first, matching the host's own convention.
  ssh -o BatchMode=yes "$HERMES_SSH" python3 - "$HERMES_HOME_DIR" "${PLUGINS[@]}" <<'PY'
import re, shutil, sys, time
from pathlib import Path
home, plugins = sys.argv[1], sys.argv[2:]
p = Path.home() / home / "config.yaml"
lines = p.read_text().splitlines(keepends=True)
try:
    start = next(i for i, l in enumerate(lines) if re.match(r"^plugins:\s*$", l))
except StopIteration:
    sys.exit("config: no top-level plugins: block - add %s to plugins.enabled by hand" % ", ".join(plugins))
end = next((j for j in range(start + 1, len(lines)) if re.match(r"^\S", lines[j])), len(lines))
missing = [n for n in plugins
           if not any(re.match(r"^\s*-\s*%s\s*$" % re.escape(n), l) for l in lines[start:end])]
if not missing:
    print("config: %s already enabled" % ", ".join(plugins))
    sys.exit(0)
try:
    en = next(i for i in range(start + 1, end) if re.match(r"^  enabled:\s*$", lines[i]))
except StopIteration:
    sys.exit("config: no enabled: list under plugins: - add %s by hand" % ", ".join(missing))
backup = p.with_name("config.yaml.bak." + time.strftime("%Y%m%d_%H%M%S"))
shutil.copy2(p, backup)
for name in reversed(missing):
    lines.insert(en + 1, "    - %s\n" % name)
p.write_text("".join(lines))
print("config: enabled %s (backup %s)" % (", ".join(missing), backup.name))
PY

  # Plugins and config are both read at process start, so a restart is required, not a reload.
  ssh -o BatchMode=yes "$HERMES_SSH" "systemctl --user restart '$HERMES_SERVICE'"
  echo "deploy: gateway restarted"
}

read_key() {
  [ -f "$HERMES_KEY_FILE" ] || { echo "missing key file: $HERMES_KEY_FILE" >&2; exit 1; }
  set -a; . "$HERMES_KEY_FILE"; set +a
  HKEY="${KEY:-${API_SERVER_KEY:-}}"
  [ -n "$HKEY" ] || { echo "no KEY in $HERMES_KEY_FILE" >&2; exit 1; }
}

wait_gateway() {
  # The adapter binds a moment after the unit goes active; absorb that rather than sleeping.
  curl -s -o /dev/null --retry 15 --retry-connrefused --retry-delay 1 --max-time 45 \
    -H "Authorization: Bearer $HKEY" "$HERMES_URL/v1/models"
}

# POST /v1/runs with the given instruction as input, echo the run_id.
start_run() {
  python3 -c 'import json, sys; print(json.dumps({"input": sys.argv[1]}))' "$1" \
    | curl -s -H "Authorization: Bearer $HKEY" -H 'Content-Type: application/json' \
        -d @- --max-time 20 "$HERMES_URL/v1/runs" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin).get("run_id", ""))'
}

# Probe one kind: dispatch a run instructed to call render_artifact, drain its stream, and
# assert a tool.started for render_artifact arrived whose args carry the kind and a non-empty
# payload field. This is the acceptance's wire clause, checked at the same surface the tablet
# reads (T-40).
probe_kind() {
  local kind="$1" field="$2" instruction="$3" rid
  rid=$(start_run "$instruction")
  [ -n "$rid" ] || { echo "verify($kind): no run_id returned" >&2; exit 1; }
  echo "verify($kind): run_id=$rid"

  curl -sN -H "Authorization: Bearer $HKEY" --max-time 180 "$HERMES_URL/v1/runs/$rid/events" \
    | grep '^data: ' | sed 's/^data: //' | python3 -c '
import sys, json
kind, field = sys.argv[1], sys.argv[2]
ok = False
for line in sys.stdin:
    try:
        e = json.loads(line)
    except Exception:
        continue
    ev = e.get("event", "")
    if ev == "tool.started" and e.get("tool") == "render_artifact":
        a = e.get("args") or {}
        shown = dict(a)
        if isinstance(shown.get(field), str) and len(shown[field]) > 120:
            shown[field] = shown[field][:120] + "..."
        print("  tool.started render_artifact args =", json.dumps(shown, ensure_ascii=False))
        payload = a.get(field)
        if a.get("kind") == kind and isinstance(payload, str) and payload.strip():
            ok = True
    elif ev in ("run.completed", "run.failed", "run.cancelled"):
        print("  terminal event:", ev)
        break
label = "ASSERT tool.started args carry kind=%s and non-empty %s" % (kind, field)
print("  %-58s: %s" % (label, "PASS" if ok else "FAIL"))
sys.exit(0 if ok else 1)
' "$kind" "$field"
}

# The device socket route answers only when device_tools loaded: no key is a 401 from the
# plugin's own check, and the right key on a plain GET (no WebSocket upgrade) is a 400. A 404
# means the plugin did not load.
probe_device_route() {
  local anon keyed
  anon=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$HERMES_URL/v1/device/ws")
  keyed=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -H "Authorization: Bearer $HKEY" "$HERMES_URL/v1/device/ws")
  echo "verify(device): no key -> $anon, key without upgrade -> $keyed"
  if [ "$anon" = 401 ] && [ "$keyed" = 400 ]; then
    echo "  ASSERT device socket route is live and keyed                : PASS"
  else
    echo "  ASSERT device socket route is live and keyed                : FAIL" >&2
    exit 1
  fi
}

cmd_verify() {
  read_key
  wait_gateway
  probe_device_route
  probe_kind markdown markdown \
    "Use the render_artifact tool exactly once, with kind set to markdown, to display a short status document containing a level-one heading, a two-item bullet list, and a two-column table. Then reply with one short sentence."
  probe_kind image url \
    "Use the render_artifact tool exactly once, with kind set to image, to display the picture at $PROBE_IMAGE_URL - pass that URL through unchanged. Then reply with one short sentence."
  echo "verify: PASS"
}

case "${1:-all}" in
  check)  cmd_check ;;
  deploy) cmd_deploy ;;
  verify) cmd_verify ;;
  all)    cmd_deploy; cmd_verify ;;
  *)      echo "usage: $0 {check|deploy|verify|all}" >&2; exit 2 ;;
esac
