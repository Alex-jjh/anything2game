#!/usr/bin/env python3
"""Real APK acceptance for games, restoration, profile and text sizing.

Use an already installed debug APK on a dedicated emulator. This script never
clears app data. It changes only the test app's locale, screen rotation/font
size, and the localhost API reverse rule, restoring system settings afterward.
The PDF picker and generation scenarios are recorded separately in the linked
acceptance document. --baseline accepts the old APK's file-hash inventory.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

from adb_ui import DeviceUi


def app_json(ui, path):
    return json.loads(ui.binary("exec-out", "run-as", ui.package, "cat", path))


def ledger(ui, profile):
    directory = "files/profiles/sample" if profile == "sample" else "files"
    return app_json(ui, directory + "/learning.json")


def checkpoint(ui, profile, game):
    return next(row for row in ledger(ui, profile).get("checkpoints", []) if row["gameId"] == game)


def home(ui):
    for _ in range(8):
        if any(ui.matches(node, "home_navigation") for node in ui.tree()):
            return
        ui.back()
    raise AssertionError("Back did not return to the home tabs")


def verify_upgrade(ui, inventory):
    ui.find("home_SPACES", scroll=False)
    preserved = 0
    for row in json.loads(inventory.read_text()):
        path = row["path"]
        if "/mastery/" not in path and path != "shared_prefs/avatar@sample.xml":
            continue
        actual = ui.binary("exec-out", "run-as", ui.package, "cat", path)
        assert hashlib.sha256(actual).hexdigest() == row["sha256"], f"Legacy bytes changed: {path}"
        preserved += 1
    assert preserved >= 5, "Expected the four old mastery files and character preference"
    ui.passed("Old PRACTICE opens Spaces; raw legacy mastery and character bytes are preserved")


def reopen_game(ui, game_tag):
    if any(ui.matches(node, "game_board") for node in ui.tree()):
        return
    home(ui)
    ui.tap("tab_PLAYGROUND")
    ui.tap(game_tag)
    ui.find("game_board", scroll=False)


def fresh_game(ui, game_tag):
    home(ui)
    ui.tap("tab_PLAYGROUND")
    ui.tap(game_tag)
    ui.find("game_board", scroll=False)
    tree = ui.tree()
    if any(node.get("text") == "迁移挑战完成" for node in tree):
        ui.tap("重新挑战")
    ui.start()


def partition(ui, profile):
    fresh_game(ui, "game_partition")
    submitted = 0
    checked_restore = False
    for _ in range(12):
        ui.start()
        tree = ui.tree()
        cuts = sorted((node for node in tree if re.fullmatch(r"cut_\d+", node.get("resource-id", ""))
                       and node.get("enabled") == "true" and ui.visible(node)),
                      key=lambda node: int(node.get("resource-id")[4:]))
        if not cuts:
            result = ui.find("game_verdict")
            if result.get("text") == "迁移挑战完成":
                break
            assert result.get("text") == "本关完成", result.get("text")
            ui.tap("game_advance")
            continue
        assert ui.find("split_submit").get("enabled") == "false", "A new round needs a player choice"
        # Choose the middle of the visible legal cuts as a learner strategy;
        # game rules and hidden targets are never reimplemented in this script.
        selected = cuts[len(cuts) // 2].get("resource-id")
        ui.start()
        ui.tap(selected)
        if not checked_restore:
            ui.shell("wm", "user-rotation", "lock", 1)
            assert ui.find("split_submit").get("enabled") == "true"
            ui.shell("wm", "user-rotation", "lock", 0)
            ui.passed("An explicit cut survives rotation before submission")
        ui.tap("split_submit")
        submitted += 1
        if not checked_restore:
            saved = checkpoint(ui, profile, "locker-search-zh")
            ui.shell("input", "keyevent", "KEYCODE_HOME")
            ui.launch()
            reopen_game(ui, "game_partition")
            assert checkpoint(ui, profile, "locker-search-zh") == saved
            ui.launch(restart=True)
            reopen_game(ui, "game_partition")
            assert checkpoint(ui, profile, "locker-search-zh") == saved
            ui.passed("Submitted game checkpoint survives background and process restart")
            ui.start()
            ui.screenshot("02-partition-zh")
            checked_restore = True
    else:
        raise AssertionError("Partition game did not finish within its bounded stages")
    assert submitted > 0 and checked_restore
    ui.screenshot("03-partition-complete-zh")
    ui.passed("Partition practice and changed-condition transfer complete through real controls")


def allocation(ui, profile):
    fresh_game(ui, "game_allocation")
    ui.tap("allocation_submit")
    assert ui.find("game_verdict").get("text", "").startswith("未达到目标")
    ui.tap("重试本关")
    ui.tap("查看提示")
    ui.tap("place_FIRST")
    before_undo = checkpoint(ui, profile, "workshop-zh")
    ui.tap("收回上枚筹码")
    assert checkpoint(ui, profile, "workshop-zh") != before_undo
    for _ in range(3):
        ui.tap("place_FIRST")
    for _ in range(3):
        ui.tap("place_SECOND")
    ui.start()
    ui.screenshot("04-allocation-zh")
    original_font = ui.shell("settings", "get", "system", "font_scale")
    ui.shell("settings", "put", "system", "font_scale", "1.5")
    ui.find("allocation_submit", enabled=True)
    ui.screenshot("04b-allocation-large-text-zh")
    ui.shell("settings", "put", "system", "font_scale", original_font)
    ui.passed("The native allocation controls remain reachable with large text")
    ui.tap("allocation_submit")
    assert ui.find("game_verdict").get("text") == "本关完成"
    ui.tap("game_advance")
    ui.start()
    for _ in range(3):
        ui.tap("place_FIRST")
    ui.tap("place_SECOND")
    ui.tap("allocation_submit")
    assert ui.find("game_verdict").get("text") == "迁移挑战完成"
    records = ledger(ui, profile).get("events", [])
    completed = [row for row in records if row.get("activity", {}).get("contentId") == "workshop-zh"
                 and row.get("outcome") == "HELPED"]
    assert completed, "A helped completion must be recorded honestly"
    snapshot = ledger(ui, profile)
    home(ui)
    reopen_game(ui, "game_allocation")
    assert ledger(ui, profile) == snapshot, "Reopening completion must not award it twice"
    ui.passed("Allocation loss, retry, hint, undo, transfer and idempotent helped completion")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", choices=("sample", "guest"), default="sample")
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    ui = DeviceUi(args.adb, args.serial, args.output)
    ui.require_emulator()
    font = ui.shell("settings", "get", "system", "font_scale")
    ui.shell("cmd", "locale", "set-app-locales", ui.package, "--user", 0, "--locales", "zh-CN")
    try:
        ui.launch()
        welcome = "以林同学继续" if args.profile == "sample" else "以访客开始"
        if any(node.get("text") == welcome for node in ui.tree()):
            ui.tap(welcome)
        if args.baseline:
            verify_upgrade(ui, args.baseline)
        home(ui)
        ui.tap("tab_SPACES")
        ui.find("home_SPACES", scroll=False)
        ui.screenshot("00-spaces-upgrade-zh")
        ui.tap("tab_PLAYGROUND")
        ui.start()
        ui.find("concept_input")
        ui.screenshot("01-playground-zh")
        # Removing the local API route proves installed gameplay has no server dependency.
        ui.call("reverse", "--remove", "tcp:18080")
        partition(ui, args.profile)
        allocation(ui, args.profile)
        ui.passed("Installed native games work with the local API disconnected")
        home(ui)
        ui.tap("tab_PROFILE")
        ui.find("home_PROFILE", scroll=False)
        ui.screenshot("05-me-zh")
        ui.shell("settings", "put", "system", "font_scale", "1.5")
        ui.find("home_PROFILE", scroll=False)
        ui.screenshot("06-me-large-text-zh")
        ui.passed("Profile remains reachable with large text")
        ui.shell("settings", "put", "system", "font_scale", font)
        ui.shell("cmd", "locale", "set-app-locales", ui.package, "--user", 0, "--locales", "en")
        ui.launch(restart=True)
        home(ui)
        ui.tap("tab_PLAYGROUND")
        ui.find("concept_input")
        ui.screenshot("07-playground-en")
        ui.passed("English Playground and Chinese navigation both render")
    except BaseException:
        try:
            ui.screenshot("failure")
        except Exception:
            pass
        raise
    finally:
        ui.shell("wm", "user-rotation", "lock", 0)
        ui.shell("settings", "put", "system", "font_scale", font)
        ui.call("reverse", "tcp:18080", "tcp:18080")
        ui.shell("cmd", "locale", "set-app-locales", ui.package, "--user", 0, "--locales", "zh-CN")
    ui.finish()


if __name__ == "__main__":
    main()
