import argparse
import csv
import json
import math
from pathlib import Path
import re
import statistics

EXPECTED = {"a_coldStartup": 10, "b_warmStartup": 10, "c_shuffleCards": 5,
            "d_drawCards": 5, "e_viewer": 5}
STAGES = ["startup", "after12Swipes", "viewer", "afterViewerClose"]

def percentile(values, p):
    values = sorted(values)
    if not values or not all(math.isfinite(v) for v in values):
        raise ValueError("Missing or non-finite samples")
    pos = (len(values) - 1) * p
    low = int(pos)
    return values[low] + (values[min(low + 1, len(values) - 1)] - values[low]) * (pos - low)

def summarize(values):
    return {"n": len(values), "p50": percentile(values, .5), "p95": percentile(values, .95),
            "min": min(values), "max": max(values), "samples": values}

def meminfo(text):
    total = re.search(r"TOTAL PSS:\s*(\d+)", text)
    if not total:
        raise ValueError("Android meminfo does not contain Total PSS")
    result = {"totalPssMB": int(total[1]) / 1024}
    for label, key in [("Java Heap", "javaHeapMB"), ("Native Heap", "nativeHeapMB"), ("Graphics", "graphicsMB")]:
        match = re.search(r"^\s*" + label + r":\s*(\d+)", text, re.M)
        if match:
            result[key] = int(match[1]) / 1024
    return result

def read_session(folder):
    log = (folder / "instrumentation.txt").read_text()
    if not re.search(r"OK \(7 tests\)", log):
        raise ValueError("Instrumentation did not pass")
    cases = {}
    for file in folder.rglob("*benchmarkData.json"):
        payload = json.loads(file.read_text())
        for case in payload.get("benchmarks", []):
            for expected in EXPECTED:
                if case["name"].startswith(expected):
                    if expected in cases:
                        raise ValueError(f"Duplicate benchmark: {expected}")
                    cases[expected] = case
    if set(cases) != set(EXPECTED):
        raise ValueError(f"Missing benchmark cases: {set(EXPECTED) - set(cases)}")
    metrics = {}
    for name, count in EXPECTED.items():
        case = cases[name]
        for key, measurement in case.get("metrics", {}).items():
            values = measurement.get("runs", [])
            if len(values) != count:
                raise ValueError(f"{name}.{key} must have {count} iterations")
            if any(v <= 0 for v in values) and key.startswith("timeTo"):
                raise ValueError("Startup measurements must be positive")
            metrics[name + "." + key] = values
        for key, measurement in case.get("sampledMetrics", {}).items():
            runs = measurement.get("runs", [])
            if len(runs) != count or any(not run for run in runs):
                raise ValueError(f"{name}.{key}: incomplete frame iterations")
            metrics[name + "." + key + ".iterationP95"] = [percentile(run, .95) for run in runs]
            metrics[name + "." + key + ".iterationP50"] = [percentile(run, .5) for run in runs]
        if name.endswith("Startup") and not any(k.startswith(name + ".timeToInitialDisplayMs") for k in metrics):
            raise ValueError("TTID is missing")
        if name in ["c_shuffleCards", "d_drawCards", "e_viewer"] and not any(
            k == name + ".frameDurationCpuMs.iterationP95" for k in metrics
        ):
            raise ValueError(f"Frame CPU durations missing for {name}")
    memory_files = list(folder.rglob("memory.json"))
    if len(memory_files) != 1:
        raise ValueError("Expected one memory report")
    rows = json.loads(memory_files[0].read_text())["rows"]
    if len(rows) != 20:
        raise ValueError("Memory report must have 5 repetitions x 4 stages")
    for stage in STAGES:
        selected = [r for r in rows if r["stage"] == stage]
        if sorted(r["iteration"] for r in selected) != list(range(5)):
            raise ValueError("Missing or duplicated memory iterations")
        for row in selected:
            for key, value in meminfo(row["meminfo"]).items():
                metrics.setdefault("memory." + stage + "." + key, []).append(value)
    events_file = folder / "operations.jsonl"
    events = [json.loads(l) for l in events_file.read_text().splitlines() if l]
    if len([r for r in events if r["action"] == "rightSwipe"]) != 180:
        raise ValueError("Not all 180 card advances executed")
    if len([r for r in events if r["action"] == "viewerOpenClose"]) != 31:
        raise ValueError("Not all viewer cycles executed")
    if not any(r["action"] == "decodedFixtureSmoke" for r in events):
        raise ValueError("Decoded-image preflight is missing")
    return {"build": json.loads((folder / "build.json").read_text()), "metrics": metrics}

def make_report(root, output):
    output.mkdir(parents=True, exist_ok=True)
    rounds = {}
    environments = {}
    for session in ["a", "b"]:
        folder = root / ("session-" + session)
        environments[session] = json.loads((folder / "environment.json").read_text())
        rounds[session] = {p.name: read_session(p) for p in folder.iterdir()
                          if p.is_dir() and (p / "build.json").exists()}
        if "baseline" not in rounds[session]:
            raise ValueError("Both sessions require a baseline")
    required_fields = ["sdk", "fingerprint", "abi", "screenSize", "density", "animations",
                       "emulatorVersion", "systemImageProperties", "protocolSHA256", "datasetSHA256", "compilationMode", "gpu"]
    for field in required_fields:
        if environments["a"][field] != environments["b"][field]:
            raise ValueError(f"Independent sessions used incompatible {field}")
    if set(rounds["a"]) != set(rounds["b"]):
        raise ValueError("Compared versions differ between sessions")
    sources = {}
    for label in rounds["a"]:
        if rounds["a"][label]["build"] != rounds["b"][label]["build"]:
            raise ValueError("Both sessions must use the exact same APKs")
        a, b = rounds["a"][label]["metrics"], rounds["b"][label]["metrics"]
        if set(a) != set(b):
            raise ValueError("Session metric schemas differ")
        combined = {}
        for name in a:
            stats = summarize(a[name] + b[name])
            m_a, m_b = statistics.median(a[name]), statistics.median(b[name])
            stats["roundMedians"] = {"a": m_a, "b": m_b}
            stats["roundSpreadPercent"] = abs(m_a - m_b) / abs((m_a + m_b) / 2) * 100 if m_a + m_b > 0 else None
            combined[name] = stats
        sources[label] = {"build": rounds["a"][label]["build"], "metrics": combined}
    comparisons = {}
    if "candidate" in sources:
        for name in sources["baseline"]["metrics"]:
            paired = {}
            for session in ["a", "b"]:
                base = statistics.median(rounds[session]["baseline"]["metrics"][name])
                candidate = statistics.median(rounds[session]["candidate"]["metrics"][name])
                paired[session] = (candidate / base - 1) * 100 if base > 0 and candidate >= 0 and "frameOverrun" not in name else None
            comparisons[name] = {"pairedChangePercent": paired,
                "meaning": "negative means lower measured value; consistent repeated changes are needed"}
    report = {"schemaVersion": 1, "valid": True, "sources": sources, "environments": environments,
              "comparison": comparisons,
              "limits": ["emulator-only, not phone GPU/ColorOS performance",
                         "memory is post-action PSS, not a continuous peak",
                         "host variation is visible in per-round medians",
                         "no fixed small-percentage pass/fail threshold"]}
    (output / "baseline.json").write_text(json.dumps(report, indent=2))
    with (output / "metrics.csv").open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["source", "metric", "n", "p50", "p95", "min", "max", "roundAmedian", "roundBmedian", "roundSpreadPercent"])
        for label, source in sources.items():
            for name, values in source["metrics"].items():
                writer.writerow([label, name, values["n"], values["p50"], values["p95"], values["min"],
                                 values["max"], values["roundMedians"]["a"], values["roundMedians"]["b"],
                                 values["roundSpreadPercent"]])
    md = ["# Tabula 模拟器性能基线 v1", "",
          "应用源码提交：" + sources["baseline"]["build"]["sourceCommit"], "",
          "两轮独立云模拟器数据。帧耗时为每轮分位数的汇总；内存是动作后快照。",
          "数值越小通常越好，但不代表实际手机表现。两轮差异用于提示云端噪声，不是改善结论。", "",
          "| 指标 | 样本数 | 中位数 | P95 | A / B 中位数 | 两轮差异 |",
          "|---|---:|---:|---:|---|---:|"]
    keys = [("进程冷启动 TTID（ms）", "a_coldStartup.timeToInitialDisplayMs"),
            ("热启动 TTID（ms）", "b_warmStartup.timeToInitialDisplayMs"),
            ("切牌：每轮 CPU 帧耗时 P95（ms）", "c_shuffleCards.frameDurationCpuMs.iterationP95"),
            ("摸牌：每轮 CPU 帧耗时 P95（ms）", "d_drawCards.frameDurationCpuMs.iterationP95"),
            ("大图：每轮 CPU 帧耗时 P95（ms）", "e_viewer.frameDurationCpuMs.iterationP95")]
    keys += [("内存 " + stage + " Total PSS（MB）", "memory." + stage + ".totalPssMB") for stage in STAGES]
    for caption, key in keys:
        s = sources["baseline"]["metrics"][key]
        spread = s["roundSpreadPercent"]
        md.append(f"| {caption} | {s['n']} | {s['p50']:.2f} | {s['p95']:.2f} | "
                  f"{s['roundMedians']['a']:.2f} / {s['roundMedians']['b']:.2f} | {spread:.1f}% |")
    md += ["", "完整样本及环境见 baseline.json，表格见 metrics.csv；原始 JSON 和 Perfetto trace 保存在对应 Actions 产物。",
           "后续推荐用同一工作流同时测固定基线和候选提交，两个会话反向执行版本顺序。",
           "模拟器镜像、测量协议或渲染配置变化时，应建立新基线，而非直接沿用冻结数字。",
           "普通整理进度、分享接收端及真机兼容性不属于本次性能测量。"]
    (output / "README.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    make_report(args.input, args.output)
