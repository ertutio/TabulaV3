import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path.cwd()
SESSION = os.environ["PERF_SESSION"]
BUILD = ROOT / "build-artifacts"
OUT = ROOT / "out" / ("session-" + SESSION)
OUT.mkdir(parents=True, exist_ok=True)
protocol = json.loads((BUILD / "protocol.json").read_text())
dataset = json.loads((BUILD / "fixtures/dataset.json").read_text())

def adb(*args, check=True):
    p = subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and p.returncode:
        raise RuntimeError(p.stdout)
    return p.stdout.strip()

def shell(command):
    return adb("shell", command)

adb("root")
adb("wait-for-device")
assert shell("getprop ro.kernel.qemu") == "1", "Only a newly-created emulator may be seeded"
assert shell("id -u") == "0", "Google APIs userdebug image must support adb root"
shell("wm size 1080x2400")
shell("wm density 420")
for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
    shell(f"settings put global {key} 1.0")
shell("settings put system peak_refresh_rate 60.0")
shell("settings put system min_refresh_rate 60.0")
shell("dumpsys battery set level 100")
shell("dumpsys battery set status 2")
shell("wm dismiss-keyguard")
shell("input keyevent 82")
shell("mkdir -p /sdcard/Pictures/PerfFixtureV1 /data/local/tmp/tabula-perf")
adb("push", "-a", str(BUILD / "fixtures") + "/.", "/sdcard/Pictures/PerfFixtureV1/")
for item in dataset["files"]:
    shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/PerfFixtureV1/" + item["name"])
deadline = time.monotonic() + 120
while True:
    rows = shell("content query --uri content://media/external/images/media --projection _id:_display_name")
    fixture_count = len(re.findall(r"_display_name=perf_fixture_\d+\.jpg", rows))
    total_count = len(re.findall(r"Row: \d+", rows))
    if fixture_count == dataset["count"] and total_count == dataset["count"]:
        break
    if time.monotonic() > deadline:
        raise RuntimeError(f"Fixture MediaStore validation failed: {fixture_count=}, {total_count=}\n{rows}")
    time.sleep(2)
(OUT / "mediastore.txt").write_text(rows)

def prefs(style):
    values = {
        "theme_mode": ("string", "DARK"), "sort_order": ("string", "NAME_ASC"),
        "batch_size": ("int", 30), "top_bar_mode": ("string", "INDEX"),
        "card_style_mode": ("string", "FIXED"), "swipe_style": ("string", style),
        "recommend_mode": ("string", "RANDOM_WALK"),
        "show_hdr_badges": ("boolean", "true"), "show_motion_badges": ("boolean", "true"),
        "play_motion_sound": ("boolean", "false"), "haptic_enabled": ("boolean", "false"),
        "swipe_haptics_enabled": ("boolean", "false"), "fluid_cloud_enabled": ("boolean", "false"),
        "liquid_glass_lab_enabled": ("boolean", "false"), "quick_action_enabled": ("boolean", "false"),
        "has_completed_onboarding": ("boolean", "true"), "has_acknowledged_disclaimer": ("boolean", "true"),
    }
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<map>"]
    for name, (kind, value) in values.items():
        if kind == "string":
            lines.append(f'<string name="{name}">{value}</string>')
        else:
            lines.append(f'<{kind} name="{name}" value="{value}" />')
    return "\n".join(lines + ["</map>"])

for style in ("SHUFFLE", "DRAW"):
    file = OUT / ("prefs-" + style + ".xml")
    file.write_text(prefs(style))
    adb("push", str(file), "/data/local/tmp/tabula-perf/")
adb("push", "benchmark/scripts/reset-app.sh", "/data/local/tmp/tabula-perf/reset-app.sh")
shell("chmod 755 /data/local/tmp/tabula-perf/reset-app.sh")

environment = {
    "session": SESSION, "runner": "ubuntu-24.04", "sdk": shell("getprop ro.build.version.sdk"),
    "fingerprint": shell("getprop ro.build.fingerprint"),
    "abi": shell("getprop ro.product.cpu.abi"),
    "screenSize": shell("wm size"), "density": shell("wm density"),
    "animations": {key: shell("settings get global " + key) for key in
                   ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")},
    "hostCPU": subprocess.check_output(["lscpu"], text=True),
    "emulatorVersion": subprocess.check_output(["emulator", "-version"], text=True, stderr=subprocess.STDOUT),
    "systemImageProperties": (Path(os.environ["ANDROID_HOME"]) / "system-images/android-36/google_apis/x86_64/source.properties").read_text(),
    "protocolSHA256": hashlib.sha256((BUILD / "protocol.json").read_bytes()).hexdigest(),
    "datasetSHA256": dataset["datasetSHA256"], "mediaStoreCount": total_count,
    "compilationMode": "Full", "gpu": "swiftshader_indirect",
    "display": shell("dumpsys display"), "memory": shell("cat /proc/meminfo"),
}
(OUT / "environment.json").write_text(json.dumps(environment, indent=2))
labels = ["baseline"] + (["candidate"] if (BUILD / "candidate").exists() else [])
if SESSION == "b":
    labels.reverse()
for label in labels:
    artifact = BUILD / label
    meta = json.loads((artifact / "build.json").read_text())
    for file, key in [("app.apk", "appSHA256"), ("test.apk", "testSHA256")]:
        assert hashlib.sha256((artifact / file).read_bytes()).hexdigest() == meta[key], "APK checksum mismatch"
    test_package = meta["testPackage"]
    assert re.fullmatch(r"[A-Za-z0-9_.]+", test_package), "Invalid test package"
    adb("uninstall", test_package, check=False)
    adb("uninstall", "com.tabula.v3", check=False)
    adb("install", str(artifact / "app.apk"))
    adb("install", str(artifact / "test.apk"))
    shell("pm grant com.tabula.v3 android.permission.READ_MEDIA_IMAGES")
    shell("pm grant com.tabula.v3 android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    shell("am start -W -n com.tabula.v3/.MainActivity")
    shell("am force-stop com.tabula.v3")
    remote_output = "/sdcard/Android/media/" + test_package + "/run-output"
    shell("mkdir -p " + remote_output)
    shell("touch /sdcard/Android/media/" + test_package + "/.nomedia")
    shell("chmod -R 777 /sdcard/Android/media/" + test_package)
    adb("logcat", "-c")
    log = adb("shell", "am", "instrument", "-w", "-r",
              "-e", "class", "com.tabula.v3.benchmark.TabulaPerformanceBenchmark",
              "-e", "androidx.benchmark.suppressErrors", "EMULATOR",
              "-e", "additionalTestOutputDir", remote_output,
              test_package + "/androidx.test.runner.AndroidJUnitRunner", check=False)
    dest = OUT / label
    dest.mkdir()
    (dest / "instrumentation.txt").write_text(log)
    (dest / "build.json").write_text(json.dumps(meta, indent=2))
    (dest / "logcat.txt").write_text(adb("logcat", "-d"))
    adb("pull", remote_output + "/.", str(dest), check=False)
    if not re.search(r"OK \(7 tests\)", log) or "FAILURES!!!" in log or "INSTRUMENTATION_FAILED" in log:
        raise RuntimeError("Benchmark instrumentation did not pass all 7 cases:\n" + log[-8000:])
    print(f"Measured {label} in session {SESSION}", flush=True)
