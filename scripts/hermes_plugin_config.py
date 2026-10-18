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
    """What plugins.enabled holds, or None when the file has no plugins.enabled key.

    A dict: "key" (index of the "  enabled:" line), "kind" ("block", "flow", "empty" or "unknown"),
    "names" (the plugin names listed), "items" (indexes of block items), "indent" (a block item's
    indent) and "comment" (a trailing comment on the key line, or "").
    """
    try:
        start = next(i for i, l in enumerate(lines) if re.match(r"^plugins:\s*$", l))
    except StopIteration:
        return None
    end = next((j for j in range(start + 1, len(lines)) if re.match(r"^\S", lines[j])), len(lines))
    try:
        key = next(i for i in range(start + 1, end) if re.match(r"^  enabled:", lines[i]))
    except StopIteration:
        return None
    m = re.match(r"^  enabled:\s*(.*?)\s*(#.*)?$", lines[key].rstrip("\n"))
    value, comment = m.group(1), m.group(2) or ""
    found = {"key": key, "names": [], "items": [], "indent": "    ", "comment": comment}
    if value == "":
        found["kind"] = "block"
        for i in range(key + 1, end):
            item = re.match(r"^(\s{2,})-\s*(.*?)\s*(#.*)?$", lines[i].rstrip("\n"))
            if item:
                if not found["items"]:
                    found["indent"] = item.group(1)
                found["items"].append(i)
                found["names"].append(item.group(2).strip("'\""))
            elif lines[i].strip() and not lines[i].lstrip().startswith("#"):
                break
        if not found["items"]:
            found["kind"] = "empty"
    elif value in ("null", "~", "[]"):
        found["kind"] = "empty"
    elif re.match(r"^\[.*\]$", value):
        found["kind"] = "flow"
        found["names"] = [n.strip().strip("'\"") for n in value[1:-1].split(",") if n.strip()]
    else:
        found["kind"] = "unknown"
    return found


def check(path, plugins):
    found = enabled_list(path.read_text().splitlines())
    names = found["names"] if found is not None else []
    rc = 0
    for name in plugins:
        if name in names:
            print("CHECK: config.yaml: %s is in plugins.enabled" % name)
        else:
            print("CHECK: config.yaml: %s NOT in plugins.enabled - run deploy" % name, file=sys.stderr)
            rc = 1
    return rc


def enable(path, plugins):
    lines = path.read_text().splitlines(keepends=True)
    found = enabled_list(lines)
    if found is None or found["kind"] == "unknown":
        sys.exit("config: plugins.enabled is missing or holds a value this script cannot edit - "
                 "add %s to it by hand" % ", ".join(plugins))
    missing = [n for n in plugins if n not in found["names"]]
    if not missing:
        print("config: %s already enabled" % ", ".join(plugins))
        return 0
    backup = path.with_name("config.yaml.bak." + time.strftime("%Y%m%d_%H%M%S"))
    shutil.copy2(path, backup)
    key, comment = found["key"], ("  " + found["comment"]) if found["comment"] else ""
    if found["kind"] == "flow":
        lines[key] = "  enabled: [%s]%s\n" % (", ".join(found["names"] + missing), comment)
    else:
        if found["kind"] == "empty":
            lines[key] = "  enabled:%s\n" % comment
        at = (found["items"][-1] if found["items"] else key) + 1
        # A last line without its newline would swallow the first inserted item.
        if not lines[at - 1].endswith("\n"):
            lines[at - 1] += "\n"
        lines[at:at] = ["%s- %s\n" % (found["indent"], n) for n in missing]
    path.write_text("".join(lines))
    print("config: enabled %s (backup %s)" % (", ".join(missing), backup.name))
    return 0


def main(argv):
    mode, home, plugins = argv[0], argv[1], argv[2:]
    path = Path.home() / home / "config.yaml"
    return {"check": check, "enable": enable}[mode](path, plugins)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
