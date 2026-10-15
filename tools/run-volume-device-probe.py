import json
import os
from pathlib import Path
import re
import subprocess
import time

out = Path("device-results")
out.mkdir(exist_ok=True)

def adb(*args, check=True):
    p = subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=180)
    if check and p.returncode:
        raise RuntimeError(p.stdout)
    return p.stdout

def install(apk):
    # Installation can race first-boot PackageManager initialization. Retry only
    # installation, never the assertions or deletion/reconciliation operations.
    for attempt in range(1, 4):
        p = subprocess.run(["adb", "install", "--no-streaming", "-r", apk],
                           text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        with (out / "install.txt").open("a") as f:
            f.write(f"{apk} attempt {attempt}\n{p.stdout}\n")
        if p.returncode == 0:
            return
        if attempt == 3:
            raise RuntimeError(p.stdout)
        time.sleep(5)
        adb("wait-for-device")

def wait_for_primary_storage():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        volumes = adb("shell", "sm", "list-volumes", "all", check=False)
        with (out / "storage-readiness.txt").open("a") as f:
            f.write(volumes + "\n")
        if re.search(r"emulated;0\s+mounted\b", volumes):
            return
        time.sleep(2)
    raise RuntimeError("Emulator primary storage did not mount before device tests")

def main():
    adb("wait-for-device")
    if adb("shell", "getprop ro.kernel.qemu").strip() != "1":
        raise RuntimeError("This workflow only uses newly-created emulators")
    # These Provider tests do not exercise gesture navigation. Three-button mode
    # avoids the emulator RegionSampling/SurfaceFlinger crash observed in logcat.
    adb("shell", "cmd", "overlay", "enable-exclusive", "--category",
        "com.android.internal.systemui.navbar.threebutton")
    wait_for_primary_storage()
    install("device-apks/app-debug.apk")
    install("device-apks/app-debug-androidTest.apk")
    wait_for_primary_storage()
    log = adb("shell", "am", "instrument", "-w", "-r",
              "-e", "class", "com.tabula.v3.data.repository.VolumeDeletionDeviceTest",
              "-e", "allowSyntheticMediaProbe", "true",
              "com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner", check=False)
    (out / "instrumentation.txt").write_text(log)
    observations = adb("exec-out", "run-as", "com.tabula.v3.recyclebin.test",
                       "cat", "files/volume-observations.jsonl", check=False)
    (out / "volume-observations.jsonl").write_text(observations)
    metadata = {"sdk": adb("shell", "getprop ro.build.version.sdk").strip(),
                "fingerprint": adb("shell", "getprop ro.build.fingerprint").strip(),
                "sourceCommit": os.environ["GITHUB_SHA"], "testedPackage": "com.tabula.v3.recyclebin.test",
                "scope": "owned synthetic JPEGs; real MediaStore queries and journal reconciliation"}
    (out / "device.json").write_text(json.dumps(metadata, indent=2))
    print(log, flush=True)
    if not re.search(r"OK \(3 tests\)", log) or "FAILURES!!!" in log or "INSTRUMENTATION_FAILED" in log:
        raise RuntimeError("Not all three actual-provider tests passed")
    rows = [json.loads(line) for line in observations.splitlines() if line.strip()]
    if len(rows) != 3:
        raise RuntimeError("Expected three volume observations")
    if any(str(row["sdk"]) != os.environ["API_LEVEL"] for row in rows):
        raise RuntimeError("Unexpected emulator Android version")
    print(json.dumps({"sdk": metadata["sdk"], "tests": 3, "failures": 0, "observations": rows}), flush=True)

try:
    main()
finally:
    # Keep evidence even if setup or installation fails before instrumentation.
    for name, args in [("logcat.txt", ("logcat", "-d")),
                       ("storage.txt", ("shell", "df", "-h", "/data")),
                       ("properties.txt", ("shell", "getprop"))]:
        try:
            (out / name).write_text(adb(*args, check=False))
        except (RuntimeError, subprocess.SubprocessError) as error:
            (out / name).write_text(str(error))
