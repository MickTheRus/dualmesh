#!/usr/bin/env python3
"""Capture real Compose screens with the isolated, fictional-data screenshots build."""
import argparse
import hashlib
from pathlib import Path
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
import zlib

PACKAGE = "io.dualmesh.app.screenshots"
ACTIVITY = PACKAGE + "/" + PACKAGE + ".ScreenshotActivity"
ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--output", type=Path, default=ROOT / "docs/screenshots")
parser.add_argument("--keep-app", action="store_true")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)


def adb(*command, timeout=15):
    return subprocess.run([args.adb, "-s", args.serial, *command], check=True, capture_output=True, timeout=timeout).stdout


def snapshot():
    for _ in range(3):
        try:
            adb("shell", "rm", "-f", "/sdcard/dualmesh-screenshot-ui.xml")
            result = adb("shell", "uiautomator", "dump", "/sdcard/dualmesh-screenshot-ui.xml")
            if b"dumped to" not in result:
                raise ET.ParseError("UI dump did not finish")
            return ET.fromstring(adb("exec-out", "cat", "/sdcard/dualmesh-screenshot-ui.xml"))
        except (subprocess.SubprocessError, ET.ParseError):
            time.sleep(0.3)
    raise RuntimeError("Cannot inspect the screenshot app")


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds"))))


def find(root, text, bottom=False):
    candidates = [n for n in root.iter("node") if n.get("package") == PACKAGE and n.get("text") == text]
    if not candidates:
        raise RuntimeError(f"Missing fixture control: {text}")
    return max(candidates, key=lambda n: bounds(n)[1]) if bottom else candidates[0]


def tap(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.4)


def tab(text):
    tap(find(snapshot(), text, bottom=True))
    root = snapshot()
    parents = {child: parent for parent in root.iter() for child in parent}
    node = find(root, text, bottom=True)
    while node is not None and node.get("selected") != "true":
        node = parents.get(node)
    assert node is not None, f"Tab did not activate: {text}"
    print(f"Opened {text}", flush=True)
    return root


def capture(name):
    root = snapshot()
    assert any(n.get("package") == PACKAGE for n in root.iter("node")), "Fixture app is not visible"
    # Do not save a lock screen, keyboard suggestions, notifications, or another app's UI.
    foreign_text = [n for n in root.iter("node") if n.get("text") and n.get("package") != PACKAGE]
    foreign_text = [n for n in foreign_text if not (n.get("package") == "com.android.systemui" and n.get("text") in ("10:00", "100%", "100"))]
    assert not foreign_text, "A system overlay is visible; dismiss it before capturing"
    raw = adb("exec-out", "screencap", "-p")
    assert raw[:8] == b"\x89PNG\r\n\x1a\n"
    position = 8
    while position < len(raw):
        length = struct.unpack(">I", raw[position:position + 4])[0]
        kind = raw[position + 4:position + 8]
        data = raw[position + 8:position + 8 + length]
        checksum = struct.unpack(">I", raw[position + 8 + length:position + 12 + length])[0]
        assert zlib.crc32(kind + data) & 0xffffffff == checksum, "Incomplete screenshot transfer"
        position += length + 12
        if kind == b"IEND":
            break
    assert kind == b"IEND"
    (args.output / name).write_bytes(raw)
    print(f"Captured {name}", flush=True)


def start():
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", ACTIVITY)
    for _ in range(5):
        root = snapshot()
        if any(n.get("text") == "Trail Base · Connected" for n in root.iter("node")):
            return
    raise RuntimeError("Screenshot fixtures did not initialize; make sure the phone is unlocked")


def demo(command, **values):
    arguments = ["shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", command]
    for key, value in values.items():
        arguments += ["-e", key, value]
    adb(*arguments)


def restore_setting(key, value):
    if value == "null":
        adb("shell", "settings", "delete", "global", key)
    else:
        adb("shell", "settings", "put", "global", key, value)


def back():
    adb("shell", "input", "keyevent", "4")


def restore(command):
    for attempt in range(3):
        try:
            adb("wait-for-device", timeout=5)
            adb("shell", *command)
            return
        except subprocess.SubprocessError:
            if attempt == 2:
                raise


apk = ROOT / "app/build/outputs/apk/screenshots/app-screenshots.apk"
remote = "/data/local/tmp/dualmesh-screenshots.apk"
adb("push", str(apk), remote, timeout=30)
assert hashlib.sha256(apk.read_bytes()).hexdigest() == adb("shell", "sha256sum", remote).decode().split()[0]
assert b"Success" in adb("shell", "pm", "install", "-r", remote, timeout=30)
# This package only contains the fixture build. Never clear the user's io.dualmesh.app package.
adb("shell", "pm", "clear", PACKAGE)
size = re.search(r"Override size: (\d+x\d+)", adb("shell", "wm", "size").decode())
density = re.search(r"Override density: (\d+)", adb("shell", "wm", "density").decode())
demo_allowed = adb("shell", "settings", "get", "global", "sysui_demo_allowed").decode().strip()
demo_on = adb("shell", "settings", "get", "global", "sysui_tuner_demo_on").decode().strip()
assert demo_on != "1", "Exit existing Android status-bar demo mode before capture"
try:
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    adb("shell", "cmd", "statusbar", "send-disable-flag", "notification-icons")
    demo("enter")
    demo("clock", hhmm="1000")
    demo("notifications", visible="false")
    demo("battery", level="100", plugged="false")
    demo("network", wifi="show", level="4", fully="true", mobile="hide")
    demo("status", bluetooth="hide", alarm="hide", location="hide", volume="hide", zen="hide", cast="hide", hotspot="hide", vpn="hide", nfc="hide")
    start()
    # Warm both responsive branches before documenting the settled window.
    tab("Settings")
    tap(find(snapshot(), "Focused"))
    tab("Channels")
    tab("Settings")
    tap(find(snapshot(), "Stacked"))
    tab("Channels")
    capture("phone-channels.png")
    tab("Settings")
    capture("phone-settings.png")
    tap(find(snapshot(), "Focused"))
    tab("Channels")
    find(snapshot(), "Trail Base · Connected")
    capture("phone-focused.png")
    # Show the native reaction UI without choosing an emoji or sending any traffic.
    reactions = [n for n in snapshot().iter("node") if n.get("text") == "React"]
    tap(reactions[-1])
    capture("phone-reactions.png")
    back()
    tab("Nodes")
    capture("phone-nodes.png")
    tap(find(snapshot(), "Ridge Scout"))
    capture("phone-node-details.png")
    for _ in range(5):
        root = snapshot()
        matches = [n for n in root.iter("node") if n.get("text") == "Traceroute"]
        if matches:
            break
        adb("shell", "input", "swipe", "540", "1850", "540", "650", "350")
    assert matches, "Traceroute section was not found"
    adb("shell", "input", "swipe", "540", "1750", "540", "950", "350")
    capture("phone-traceroute.png")
    back()
    tab("Map")
    time.sleep(4)  # Allow the public basemap tiles to arrive before the screenshot.
    capture("phone-map.png")
    tap(find(snapshot(), "Filters"))
    capture("phone-map-filters.png")
    back()
    tab("Connections")
    capture("phone-connections.png")
    # Exercise the actual tablet layout on the same device, restoring exact prior overrides below.
    adb("shell", "wm", "size", "1920x1200")
    adb("shell", "wm", "density", "240")
    start()
    tab("Settings")
    tab("Channels")
    capture("tablet-channels.png")
finally:
    errors = []
    cleanup = [
        lambda: restore(("wm", "density", density.group(1) if density else "reset")),
        lambda: restore(("wm", "size", size.group(1) if size else "reset")),
        lambda: adb("shell", "cmd", "statusbar", "send-disable-flag", "none"),
        lambda: demo("exit"),
        lambda: restore_setting("sysui_demo_allowed", demo_allowed),
        lambda: restore_setting("sysui_tuner_demo_on", demo_on),
        lambda: adb("shell", "am", "force-stop", PACKAGE),
    ]
    if not args.keep_app:
        cleanup.append(lambda: adb("uninstall", PACKAGE))
    cleanup += [
        lambda: adb("shell", "rm", "-f", remote, "/sdcard/dualmesh-screenshot-ui.xml"),
        lambda: adb("shell", "am", "start", "-n", "io.dualmesh.app/.MainActivity"),
    ]
    for action in cleanup:
        try:
            action()
        except (subprocess.SubprocessError, AssertionError) as error:
            errors.append(str(error))
    if errors:
        raise RuntimeError("Capture cleanup needs attention: " + "; ".join(errors))
    print("Restored display/status bar settings and returned to DualMesh", flush=True)
