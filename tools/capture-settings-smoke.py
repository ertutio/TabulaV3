import os
import re
import subprocess
from pathlib import Path
from time import sleep
from xml.etree import ElementTree

package = "com.tabula.v3.recyclebin.test"
output = Path("device-results")
output.mkdir(exist_ok=True)

def adb(*args, stdin=None, timeout=35):
    return subprocess.run(
        ["adb", *args], input=stdin, stdout=subprocess.PIPE, check=True, timeout=timeout
    ).stdout

def tree():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/tabula-settings.xml")
    return adb("shell", "cat", "/data/local/tmp/tabula-settings.xml")

def tap_attribute(xml, attribute, label):
    for node in ElementTree.fromstring(xml).iter("node"):
        if node.get(attribute) == label:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
            if x2 > x1 and y2 > y1:
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                return
    raise AssertionError(f"UI item missing: {label}")

def screenshot(label):
    (output / f"{label}.png").write_bytes(adb("exec-out", "screencap", "-p"))

def open_settings(theme):
    adb("shell", "am", "force-stop", package)
    adb("shell", "cmd", "uimode", "night", "yes" if theme == "dark" else "no")
    prefs = b"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="has_completed_onboarding" value="true" />
    <boolean name="has_acknowledged_disclaimer" value="true" />
    <boolean name="liquid_glass_lab_enabled" value="true" />
    <string name="theme_mode">SYSTEM</string>
</map>
"""
    adb("shell", "-T", f"run-as {package} sh -c 'mkdir -p shared_prefs; cat > shared_prefs/tabula_prefs.xml'", stdin=prefs)
    print(adb("shell", "am", "start", "-W", "-n", f"{package}/com.tabula.v3.MainActivity", timeout=50).decode(), flush=True)
    sleep(2)
    screenshot(f"{theme}-home")
    tap_attribute(tree(), "content-desc", "设置")
    settings = tree()
    (output / f"{theme}-settings.xml").write_bytes(settings)
    if not any(n.get("text") == "主题模式" for n in ElementTree.fromstring(settings).iter("node")):
        raise AssertionError("Settings screen did not open")
    screenshot(f"{theme}-settings")
    width, height = map(int, re.search(rb"(\d+)x(\d+)", adb("shell", "wm", "size")).groups())
    adb("shell", "input", "swipe", str(width//2), str(height*3//4), str(width//2), str(height//3), "450")
    screenshot(f"{theme}-settings-scrolled")
    print(f"Settings capture completed for {theme}", flush=True)

adb("install", "-r", "-t", "-g", "app/build/outputs/apk/debug/app-debug.apk", timeout=90)
adb("shell", "pm", "grant", package, "android.permission.READ_MEDIA_IMAGES")
open_settings(os.environ["TABULA_SMOKE_THEME"])
