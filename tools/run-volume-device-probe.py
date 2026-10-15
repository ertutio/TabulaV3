import json
import os
from pathlib import Path
import re
import subprocess

out = Path("device-results")
out.mkdir(exist_ok=True)

def adb(*args, check=True):
    p = subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and p.returncode:
        raise RuntimeError(p.stdout)
    return p.stdout

adb("wait-for-device")
if adb("shell", "getprop ro.kernel.qemu").strip() != "1":
    raise RuntimeError("This workflow only uses newly-created emulators")
adb("install", "device-apks/app-debug.apk")
adb("install", "device-apks/app-debug-androidTest.apk")
adb("logcat", "-c")
log = adb("shell", "am", "instrument", "-w", "-r",
          "-e", "class", "com.tabula.v3.data.repository.VolumeDeletionDeviceTest",
          "-e", "allowSyntheticMediaProbe", "true",
          "com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner", check=False)
(out / "instrumentation.txt").write_text(log)
(out / "logcat.txt").write_text(adb("logcat", "-d"))
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
