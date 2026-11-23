"""Small adb UI driver shared by real-emulator acceptance scripts.

Every tap uses the current UI hierarchy. This helper never installs an APK,
clears app data, or chooses an emulator implicitly.
"""
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


class DeviceUi:
    package = "com.anything2game.app"

    def __init__(self, adb, serial, output):
        self.adb = [str(adb), "-s", serial]
        self.serial = serial
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.checks = []

    def binary(self, *parts, timeout=30):
        return subprocess.check_output(
            self.adb + list(map(str, parts)), timeout=timeout
        )

    def call(self, *parts):
        return self.binary(*parts).decode().strip()

    def shell(self, *parts):
        return self.call("shell", *parts)

    def require_emulator(self):
        assert self.serial.startswith("emulator-"), "Select a dedicated QA emulator"
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Expected an emulator"

    def tree(self, capture=None):
        raw = self.call("exec-out", "uiautomator", "dump", "/dev/tty")
        xml = raw[raw.index("<hierarchy"):raw.index("</hierarchy>") + 12]
        nodes = list(ET.fromstring(xml).iter("node"))
        if capture:
            (self.output / f"{capture}.xml").write_text(xml)
            summary = [
                " | ".join(f"{key}={node.get(key)}" for key in
                           ("text", "content-desc", "resource-id", "bounds", "enabled")
                           if node.get(key))
                for node in nodes
                if any(node.get(key) for key in ("text", "content-desc", "resource-id"))
            ]
            (self.output / f"{capture}.txt").write_text("\n".join(summary))
        return nodes

    @staticmethod
    def bounds(node):
        return tuple(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))

    @classmethod
    def visible(cls, node):
        bounds = cls.bounds(node)
        return len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]

    @staticmethod
    def matches(node, label):
        return label in (node.get("text"), node.get("content-desc"), node.get("resource-id"))

    def find(self, label, *, scroll=True, timeout=20, occurrence=0, enabled=None):
        deadline = time.monotonic() + timeout
        while True:
            tree = self.tree()
            matching = [node for node in tree if self.matches(node, label) and self.visible(node)]
            hits = [node for node in matching if enabled is None or
                    (node.get("enabled") == "true") == enabled]
            if len(hits) > occurrence:
                return hits[occurrence]
            if time.monotonic() >= deadline:
                self.tree("missing-control")
                raise AssertionError(f"Missing control: {label}")
            if scroll and not matching:
                area = next((node for node in tree
                             if node.get("scrollable") == "true" and self.visible(node)), None)
                if area is not None:
                    self._scroll_area(area)
            time.sleep(.15)

    def click(self, node, *, hold_ms=0):
        assert self.visible(node), "Cannot tap a hidden UI node"
        assert node.get("enabled") != "false", "Cannot tap a disabled UI node"
        x, y, right, bottom = self.bounds(node)
        center = ((x + right) // 2, (y + bottom) // 2)
        if hold_ms:
            self.shell("input", "swipe", *center, *center, hold_ms)
        else:
            self.shell("input", "tap", *center)

    def tap(self, label, **kwargs):
        kwargs.setdefault("enabled", True)
        self.click(self.find(label, **kwargs))

    def text(self, label, value):
        assert value.isascii(), "adb input text requires an ASCII test value"
        node = self.find(label)
        self.click(node)
        self.shell("input", "keyevent", "KEYCODE_MOVE_END")
        self.shell("input", "keyevent", *(["KEYCODE_DEL"] * (len(node.get("text", "")) + 4)))
        # Use an English keyboard for ASCII fixture text. Verify committed
        # field content instead of treating an IME composition as saved text.
        for character in value:
            if character == " ":
                self.shell("input", "keyevent", "KEYCODE_SPACE")
            else:
                self.shell("input", "text", shlex.quote(character))
            time.sleep(.06)
        actual = self.find(label, scroll=False).get("text", "")
        assert actual == value, f"Text input differs (check the keyboard language): expected {value!r}, got {actual!r}"

    def back(self):
        self.shell("input", "keyevent", "KEYCODE_BACK")

    def scroll(self, *, up=False):
        area = next((node for node in self.tree()
                     if node.get("scrollable") == "true" and self.visible(node)), None)
        if area is None:
            return False
        self._scroll_area(area, up=up)
        return True

    def start(self):
        """Return the current scroll area to its start without edge gestures."""
        previous = None
        for _ in range(6):
            tree = self.tree()
            visible = [(node.get("resource-id"), node.get("text"), node.get("bounds"))
                       for node in tree if self.visible(node)]
            if visible == previous:
                return
            previous = visible
            area = next((node for node in tree
                         if node.get("scrollable") == "true" and self.visible(node)), None)
            if area is None:
                return
            self._scroll_area(area, up=True)

    def _scroll_area(self, area, *, up=False):
        x, y, right, bottom = self.bounds(area)
        low, high = (y + 3 * bottom) // 4, (3 * y + bottom) // 4
        start, end = (high, low) if up else (low, high)
        self.shell("input", "swipe", (x + right) // 2, start, (x + right) // 2, end, 300)

    def launch(self, *, restart=False):
        if restart:
            self.shell("am", "force-stop", self.package)
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
                   "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n",
                   self.package + "/.MainActivity")

    def screenshot(self, name):
        destination = self.output / f"{name}.png"
        destination.write_bytes(self.binary("exec-out", "screencap", "-p", timeout=15))
        self.tree(name)
        return destination

    def passed(self, description):
        self.checks.append(description)
        print("PASS " + description, flush=True)

    def finish(self):
        result = {"status": "passed", "checks": self.checks, "serial": self.serial,
                  "api": self.shell("getprop", "ro.build.version.sdk")}
        (self.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
