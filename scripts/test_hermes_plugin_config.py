"""Tests for hermes_plugin_config.py, the config.yaml logic hermes-plugin.sh runs on the gateway host.

Run from the repo root: python3 -m pytest scripts/test_hermes_plugin_config.py -q
"""

import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "hermes_plugin_config.py"
HOME_DIR = ".hermes/profiles/console"


def run(home, mode, *plugins):
    return subprocess.run(
        [sys.executable, str(SCRIPT), mode, HOME_DIR, *plugins],
        env={"HOME": str(home), "PATH": "/usr/bin:/bin"},
        capture_output=True,
        text=True,
    )


def config(home, text):
    path = home / HOME_DIR / "config.yaml"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


DISABLED_ONLY = """model: x
plugins:
  enabled:
    - render_artifact
  disabled:
    - device_tools
  entries: {}
terminal:
  backend: local
"""

ENABLED = """plugins:
  enabled:
    - render_artifact
    - device_tools
  disabled: []
"""


def test_a_plugin_only_under_disabled_is_not_reported_enabled(tmp_path):
    config(tmp_path, DISABLED_ONLY)
    result = run(tmp_path, "check", "render_artifact", "device_tools")
    assert result.returncode == 1
    assert "render_artifact is in plugins.enabled" in result.stdout
    assert "device_tools NOT in plugins.enabled" in result.stderr


def test_enable_inserts_under_enabled_and_leaves_disabled_alone(tmp_path):
    path = config(tmp_path, DISABLED_ONLY)
    result = run(tmp_path, "enable", "render_artifact", "device_tools")
    assert result.returncode == 0, result.stderr
    assert path.read_text() == DISABLED_ONLY.replace(
        "    - render_artifact\n", "    - render_artifact\n    - device_tools\n"
    )
    assert run(tmp_path, "check", "render_artifact", "device_tools").returncode == 0


def test_enabled_plugins_are_reported_and_left_unchanged(tmp_path):
    path = config(tmp_path, ENABLED)
    assert run(tmp_path, "check", "render_artifact", "device_tools").returncode == 0
    result = run(tmp_path, "enable", "render_artifact", "device_tools")
    assert result.returncode == 0
    assert "already enabled" in result.stdout
    assert path.read_text() == ENABLED
    assert not list(path.parent.glob("config.yaml.bak.*"))


def test_an_empty_inline_enabled_list_is_expanded(tmp_path):
    path = config(tmp_path, "plugins:\n  enabled: []\n  disabled: []\n")
    assert run(tmp_path, "enable", "device_tools").returncode == 0
    assert path.read_text() == "plugins:\n  enabled:\n    - device_tools\n  disabled: []\n"
