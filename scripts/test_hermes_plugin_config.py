"""Tests for hermes_plugin_config.py, the config.yaml logic hermes-plugin.sh runs on the gateway host.

Run from the repo root: python3 -m pytest scripts/test_hermes_plugin_config.py -q
"""

import subprocess
import sys
from pathlib import Path

import pytest
import yaml

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


# Every shape of plugins.enabled a config.yaml can hold. Each case: the file, what check must report
# for device_tools before deploy, and the list the file must hold after enable (None: enable must
# refuse and leave the file alone).
SHAPES = {
    "block list": ("plugins:\n  enabled:\n    - render_artifact\n  disabled: []\n",
                   ["render_artifact", "device_tools"]),
    "indentless block list": ("plugins:\n  enabled:\n  - render_artifact\n  disabled: []\n",
                              ["render_artifact", "device_tools"]),
    "empty flow list with a comment": ("plugins:\n  enabled: []  # none yet\n", ["device_tools"]),
    "null": ("plugins:\n  enabled: null\n", ["device_tools"]),
    "tilde": ("plugins:\n  enabled: ~\n", ["device_tools"]),
    "empty value then another key": ("plugins:\n  enabled:\n  disabled: []\n", ["device_tools"]),
    "flow list": ("plugins:\n  enabled: [my-approval-plugin, render_artifact]  # kept\n",
                  ["my-approval-plugin", "render_artifact", "device_tools"]),
    "no final newline": ("plugins:\n  enabled:\n    - render_artifact", ["render_artifact", "device_tools"]),
    "unknown inline value": ("plugins:\n  enabled: some_string\n", None),
}


def enabled_after(path):
    return (yaml.safe_load(path.read_text())["plugins"] or {}).get("enabled")


@pytest.mark.parametrize("shape", sorted(SHAPES))
def test_every_enabled_shape_is_read_and_edited_into_valid_yaml(tmp_path, shape):
    text, expected = SHAPES[shape]
    path = config(tmp_path, text)

    before = run(tmp_path, "check", "render_artifact", "device_tools")
    assert before.returncode == 1
    assert "device_tools NOT in plugins.enabled" in before.stderr
    if "render_artifact" in text and expected is not None:
        assert "render_artifact is in plugins.enabled" in before.stdout

    result = run(tmp_path, "enable", "device_tools")
    if expected is None:
        assert result.returncode != 0
        assert path.read_text() == text
        return
    assert result.returncode == 0, result.stderr
    assert enabled_after(path) == expected
    if "#" in text:
        assert text.split("#", 1)[1].strip() in path.read_text()
    assert run(tmp_path, "check", *expected).returncode == 0


def test_a_column_0_comment_inside_enabled_does_not_hide_later_items(tmp_path):
    text = "plugins:\n  enabled:\n    - render_artifact\n#    - old_plugin\n    - device_tools\n  disabled: []\n"
    path = config(tmp_path, text)
    assert run(tmp_path, "check", "render_artifact", "device_tools").returncode == 0
    result = run(tmp_path, "enable", "device_tools")
    assert result.returncode == 0, result.stderr
    assert "already enabled" in result.stdout
    assert path.read_text() == text
    assert not list(path.parent.glob("config.yaml.bak.*"))


def test_a_column_0_comment_before_enabled_does_not_hide_the_key(tmp_path):
    config(tmp_path, "plugins:\n# the console's plugins\n  enabled:\n    - device_tools\n")
    assert run(tmp_path, "check", "device_tools").returncode == 0
