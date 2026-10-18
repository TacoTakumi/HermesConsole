"""The config.yaml half of hermes-plugin.sh, run on the gateway host.

    python3 hermes_plugin_config.py check  <home dir> <plugin>...
    python3 hermes_plugin_config.py enable <home dir> <plugin>...

<home dir> is the profile home relative to $HOME. check exits 1 unless every plugin is listed under
plugins.enabled. enable adds the missing ones there, after a timestamped backup. Only the enabled
list counts: a plugin named under plugins.disabled, or anywhere else in the block, is not enabled.

Surgical line edits, never a YAML round-trip: config.yaml is the operator's file and its comments
must survive. hermes-plugin.sh sends this file over ssh as `python3 - <args>`, so it must run on
the host's plain python3 with the standard library only.
"""

import re
import shutil
import sys
import time
from pathlib import Path


def enabled_list(lines):
    """(index of the "  enabled:" line, indexes of its "- name" items), or None without one."""
    try:
        start = next(i for i, l in enumerate(lines) if re.match(r"^plugins:\s*$", l))
    except StopIteration:
        return None
    end = next((j for j in range(start + 1, len(lines)) if re.match(r"^\S", lines[j])), len(lines))
    try:
        key = next(i for i in range(start + 1, end) if re.match(r"^  enabled:", lines[i]))
    except StopIteration:
        return None
    items = []
    for i in range(key + 1, end):
        if re.match(r"^\s{4,}-\s", lines[i]):
            items.append(i)
        elif lines[i].strip() and not lines[i].lstrip().startswith("#"):
            break
    return key, items


def listed(lines, items, name):
    return any(re.match(r"^\s*-\s*%s\s*$" % re.escape(name), lines[i]) for i in items)


def check(path, plugins):
    lines = path.read_text().splitlines()
    found = enabled_list(lines)
    rc = 0
    for name in plugins:
        if found is not None and listed(lines, found[1], name):
            print("CHECK: config.yaml: %s is in plugins.enabled" % name)
        else:
            print("CHECK: config.yaml: %s NOT in plugins.enabled - run deploy" % name, file=sys.stderr)
            rc = 1
    return rc


def enable(path, plugins):
    lines = path.read_text().splitlines(keepends=True)
    found = enabled_list(lines)
    if found is None:
        sys.exit("config: no plugins.enabled list - add %s to it by hand" % ", ".join(plugins))
    key, items = found
    missing = [n for n in plugins if not listed(lines, items, n)]
    if not missing:
        print("config: %s already enabled" % ", ".join(plugins))
        return 0
    backup = path.with_name("config.yaml.bak." + time.strftime("%Y%m%d_%H%M%S"))
    shutil.copy2(path, backup)
    if re.match(r"^  enabled:\s*\[\s*\]\s*$", lines[key]):
        lines[key] = "  enabled:\n"
    at = (items[-1] if items else key) + 1
    lines[at:at] = ["    - %s\n" % n for n in missing]
    path.write_text("".join(lines))
    print("config: enabled %s (backup %s)" % (", ".join(missing), backup.name))
    return 0


def main(argv):
    mode, home, plugins = argv[0], argv[1], argv[2:]
    path = Path.home() / home / "config.yaml"
    return {"check": check, "enable": enable}[mode](path, plugins)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
