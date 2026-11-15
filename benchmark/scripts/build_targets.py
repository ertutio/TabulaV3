import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path.cwd()
protocol = json.loads((ROOT / "benchmark/protocol-v1.json").read_text())
BASE = os.environ.get("BASE_COMMIT") or protocol["baselineCommit"]
CANDIDATE = os.environ.get("CANDIDATE_COMMIT", "").strip()
OUT = ROOT / "build-artifacts"
OUT.mkdir(exist_ok=True)

def run(args, cwd=ROOT):
    subprocess.run(args, cwd=cwd, check=True)

def output(args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()

def tree_digest(folder):
    h = hashlib.sha256()
    for file in sorted(folder.rglob("*")):
        if file.is_file():
            h.update(str(file.relative_to(folder)).replace("\\", "/").encode() + b"\0")
            h.update(file.read_bytes())
    return h.hexdigest()

for label, commit in [("baseline", BASE)] + ([("candidate", CANDIDATE)] if CANDIDATE else []):
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise RuntimeError("Inputs must be immutable, lowercase 40-character commit SHAs")
    if subprocess.run(["git", "cat-file", "-e", commit + "^{commit}"], capture_output=True).returncode:
        run(["git", "fetch", "origin", commit])
    source = Path(os.environ["RUNNER_TEMP"]) / ("tabula-perf-" + label)
    run(["git", "worktree", "add", "--detach", str(source), commit])
    original_main = tree_digest(source / "app/src/main")
    shutil.copytree(ROOT / "benchmark/macrobenchmark", source / "macrobenchmark")
    settings = source / "settings.gradle.kts"
    settings.write_text(settings.read_text() + '\ninclude(":macrobenchmark")\n')
    gradle = source / "app/build.gradle.kts"
    contents = gradle.read_text()
    anchor = "    compileOptions {"
    if contents.count(anchor) != 1:
        raise RuntimeError("App build structure changed: review the benchmark overlay")
    overlay = '''    // CI-only measurement variant; release settings were already configured above.
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

'''
    gradle.write_text(contents.replace(anchor, overlay + anchor))
    manifest = source / "app/src/benchmark/AndroidManifest.xml"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><profileable android:shell="true"/></application></manifest>\n')
    if tree_digest(source / "app/src/main") != original_main:
        raise RuntimeError("Production source unexpectedly changed")
    run(["bash", "./gradlew", "--no-daemon", "--stacktrace", ":app:assembleBenchmark", ":macrobenchmark:assembleBenchmark"], cwd=source)
    app = source / "app/build/outputs/apk/benchmark/app-benchmark.apk"
    test = source / "macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk"
    dest = OUT / label
    dest.mkdir()
    aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/aapt"
    info = output([str(aapt), "dump", "badging", str(app)])
    test_info = output([str(aapt), "dump", "badging", str(test)])
    if "package: name='com.tabula.v3'" not in info or "application-debuggable" in info:
        raise RuntimeError("Must measure the non-debuggable, release-derived app")
    for file, name in [(app, "app.apk"), (test, "test.apk")]:
        shutil.copyfile(file, dest / name)
    mapping = source / "app/build/outputs/mapping/benchmark/mapping.txt"
    if not mapping.is_file() or not mapping.stat().st_size:
        raise RuntimeError("R8 output is missing")
    metadata = {
        "label": label, "sourceCommit": commit, "sourceTree": output(["git", "rev-parse", commit + "^{tree}"]),
        "harnessCommit": os.environ["GITHUB_SHA"], "mainSourceSHA256": original_main,
        "protocolSHA256": hashlib.sha256((ROOT / "benchmark/protocol-v1.json").read_bytes()).hexdigest(),
        "appSHA256": hashlib.sha256(app.read_bytes()).hexdigest(), "appBytes": app.stat().st_size,
        "testSHA256": hashlib.sha256(test.read_bytes()).hexdigest(),
        "testPackage": re.search(r"package: name='([^']+)'", test_info)[1],
        "buildVariant": "benchmark (release + profileable + CI debug signing)",
        "libraryVersions": {"macrobenchmark": "1.4.1", "runner": "1.6.2", "uiautomator": "2.3.0"},
        "versionCatalogSHA256": hashlib.sha256((source / "gradle/libs.versions.toml").read_bytes()).hexdigest()
    }
    (dest / "build.json").write_text(json.dumps(metadata, indent=2))
    (dest / "badging.txt").write_text(info)
    print(json.dumps(metadata))
shutil.copyfile(ROOT / "benchmark/protocol-v1.json", OUT / "protocol.json")
run([sys.executable, "benchmark/scripts/generate_dataset.py", str(OUT / "fixtures")])
