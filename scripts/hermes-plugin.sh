#!/usr/bin/env bash
# Deploys the HermesConsole render_artifact plugin to the Hermes gateway and proves it took.
#
# The console's artifacts are tool calls: the model invokes render_artifact with a typed
# payload (string field kind plus the kind's payload field), the patched /v1/runs stream
# (see "Gateway changes" in README.md) forwards the args dict whole on tool.started, and the tablet renders
# the pane client-side. The tool is a Hermes USER PLUGIN at ~/.hermes/plugins/render_artifact
# on the gateway host - outside the hermes-agent checkout, so a git pull cannot revert it.
# This script exists for first deploys, edits, and new hosts (T-40, REQ-15/REQ-16).
#
#   ./scripts/hermes-plugin.sh check    is the deployed plugin current and enabled?
#   ./scripts/hermes-plugin.sh deploy   push the plugin, enable it in config.yaml, restart
#   ./scripts/hermes-plugin.sh verify   prove it live: a markdown and an image run must each
#                                       stream a render_artifact tool.started carrying kind
#                                       and payload
#   ./scripts/hermes-plugin.sh all      deploy, then verify
#
# The API key is read from HERMES_KEY_FILE and never printed. Do not add set -x, and do not
# pass curl -v: both would echo the Authorization header.
set -euo pipefail
cd "$(dirname "$0")/.."

HERMES_SSH="${HERMES_SSH:?set HERMES_SSH to the gateway host ssh login, e.g. you@gateway}"
HERMES_SERVICE="${HERMES_SERVICE:-hermes-gateway.service}"
HERMES_URL="${HERMES_URL:-http://127.0.0.1:8642}"
HERMES_KEY_FILE="${HERMES_KEY_FILE:-$HOME/.hermes/api_server.env}"

PLUGIN_SRC="hermes-plugin/render_artifact"
PLUGIN_DST=".hermes/plugins/render_artifact" # relative to the gateway user's $HOME
PLUGIN_FILES=(plugin.yaml __init__.py)

# A stable public image for the image-kind probe. The probe asserts the args shape, not this
# exact value: the model may legitimately rephrase everything but the payload fields.
PROBE_IMAGE_URL="https://upload.wikimedia.org/wikipedia/commons/3/3a/Cat03.jpg"

cmd_check() {
  local f rc=0
  for f in "${PLUGIN_FILES[@]}"; do
    if ssh -o BatchMode=yes "$HERMES_SSH" "cat '$PLUGIN_DST/$f'" 2>/dev/null \
        | diff -q - "$PLUGIN_SRC/$f" >/dev/null 2>&1; then
      echo "CHECK: $f: deployed copy matches this repo"
    else
      echo "CHECK: $f: missing or differs on $HERMES_SSH - run deploy" >&2
      rc=1
    fi
  done
  # The heredoc is quoted and expanded locally; the remote shell never parses the python.
  if ssh -o BatchMode=yes "$HERMES_SSH" python3 - <<'PY'
import re, sys
from pathlib import Path
lines = (Path.home() / ".hermes/config.yaml").read_text().splitlines()
try:
    start = next(i for i, l in enumerate(lines) if re.match(r"^plugins:\s*$", l))
except StopIteration:
    sys.exit(1)
end = next((j for j in range(start + 1, len(lines)) if re.match(r"^\S", lines[j])), len(lines))
sys.exit(0 if any(re.match(r"^\s*-\s*render_artifact\s*$", l) for l in lines[start:end]) else 1)
PY
  then
    echo "CHECK: config.yaml: render_artifact is in plugins.enabled"
  else
    echo "CHECK: config.yaml: render_artifact NOT in plugins.enabled - run deploy" >&2
    rc=1
  fi
  return "$rc"
}

cmd_deploy() {
  local f
  ssh -o BatchMode=yes "$HERMES_SSH" "mkdir -p '$PLUGIN_DST' && rm -rf '$PLUGIN_DST/__pycache__'"
  for f in "${PLUGIN_FILES[@]}"; do
    scp -q "$PLUGIN_SRC/$f" "$HERMES_SSH:$PLUGIN_DST/$f"
    echo "deploy: $f pushed"
  done

  # Plugins are opt-in: append render_artifact to plugins.enabled if it is not already there.
  # Surgical line insert, never a YAML round-trip - config.yaml is the operator's file and its
  # comments must survive. Timestamped backup first, matching the host's own convention.
  ssh -o BatchMode=yes "$HERMES_SSH" python3 - <<'PY'
import re, shutil, sys, time
from pathlib import Path
p = Path.home() / ".hermes/config.yaml"
lines = p.read_text().splitlines(keepends=True)
try:
    start = next(i for i, l in enumerate(lines) if re.match(r"^plugins:\s*$", l))
except StopIteration:
    sys.exit("config: no top-level plugins: block - add render_artifact to plugins.enabled by hand")
end = next((j for j in range(start + 1, len(lines)) if re.match(r"^\S", lines[j])), len(lines))
if any(re.match(r"^\s*-\s*render_artifact\s*$", l) for l in lines[start:end]):
    print("config: render_artifact already enabled")
    sys.exit(0)
try:
    en = next(i for i in range(start + 1, end) if re.match(r"^  enabled:\s*$", lines[i]))
except StopIteration:
    sys.exit("config: no enabled: list under plugins: - add render_artifact by hand")
backup = p.with_name("config.yaml.bak." + time.strftime("%Y%m%d_%H%M%S"))
shutil.copy2(p, backup)
lines.insert(en + 1, "    - render_artifact\n")
p.write_text("".join(lines))
print("config: enabled render_artifact (backup %s)" % backup.name)
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

cmd_verify() {
  read_key
  wait_gateway
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
