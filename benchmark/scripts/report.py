import argparse
import csv
import gzip
import json
import math
from pathlib import Path
import re
import statistics
import zlib

# Perfetto TracePacket 完整 schema 表（审计轮 4）：自动生成自固定版本的
# perfetto_trace.proto（perfetto 主分支 commit 40b529923598b739b2892a536a7692
# eedbed5685，2026-11-07；protoc/grpcio-tools 编译为 descriptor set 后提取，
# 生成步骤见 generate_perfetto_trace_schema.py）。覆盖 918 个消息定义——
# 消息型字段按子消息 schema 递归实际解析（嵌套损坏可识别）；标量/string/
# bytes/map(MapEntry) 按 wire type 消费；未知字段号按 wire type 跳过
# （proto 未知字段容忍）。运行时零第三方依赖；生成仅开发期需要 grpcio-tools。
import sys as _sys
from pathlib import Path as _Path
_SCRIPT_DIR = str(_Path(__file__).resolve().parent)
if _SCRIPT_DIR not in _sys.path:
    _sys.path.insert(0, _SCRIPT_DIR)
from perfetto_trace_schema import MESSAGES

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
    validate_round_traces(folder)
    return {"build": json.loads((folder / "build.json").read_text()), "metrics": metrics}


def _validate_message(payload: bytes, type_name: str, path_name: str, depth: int):
    """按 schema 递归校验一个消息载荷（审计轮 4：完整 schema 嵌套解析）。

    - type_name 在 schema 表中：逐字段走查——tag varint、field number ≥1、
      wire type ∈ {0,1,2,5}（proto3 不产生 groups，6/7 非法）；wire type=2 且
      schema 声明为消息的字段按子消息 schema 递归实际解析；标量/string/bytes
      及未知字段号按 wire type 消费（proto 未知字段容忍，不盲递归）；
    - type_name 不在表中（未知消息）：仅框架走查，不递归；
    - depth 上限 32（防病态嵌套；perfetto 实际嵌套远低于此）。
    任何框架损坏在此抛出 ValueError——valid 依赖解析成功。"""
    if depth > 32:
        # 审计轮 5：超限必须显式失败——静默 return 会把未检查的内容当作
        # 校验成功（嵌套损坏在 depth 33+ 被放行）。错误沿真实报告链传播，
        # read_session/make_report 失败，报告不得标为有效。
        raise ValueError(f"Trace nesting depth exceeds supported range (> 32): {path_name}")
    fields = MESSAGES.get(type_name)
    n = len(payload)
    pos = 0
    while pos < n:
        tag = 0
        shift = 0
        while True:
            if pos >= n:
                raise ValueError(f"Corrupt {type_name} (field tag truncated): {path_name}")
            byte = payload[pos]
            pos += 1
            tag |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7
            if shift > 63:
                raise ValueError(f"Corrupt {type_name} (field tag varint overrun): {path_name}")
        field_number = tag >> 3
        wire_type = tag & 0x07
        if field_number == 0:
            raise ValueError(f"Corrupt {type_name} (invalid field number 0): {path_name}")
        if wire_type == 0:
            value = 0
            shift = 0
            while True:
                if pos >= n:
                    raise ValueError(f"Corrupt {type_name} (varint value truncated): {path_name}")
                byte = payload[pos]
                pos += 1
                value |= (byte & 0x7F) << shift
                if not byte & 0x80:
                    break
                shift += 7
                if shift > 63:
                    raise ValueError(f"Corrupt {type_name} (varint value overrun): {path_name}")
        elif wire_type == 1:
            if pos + 8 > n:
                raise ValueError(f"Corrupt {type_name} (fixed64 payload past end): {path_name}")
            pos += 8
        elif wire_type == 2:
            length = 0
            shift = 0
            while True:
                if pos >= n:
                    raise ValueError(f"Corrupt {type_name} (field length truncated): {path_name}")
                byte = payload[pos]
                pos += 1
                length |= (byte & 0x7F) << shift
                if not byte & 0x80:
                    break
                shift += 7
                if shift > 63:
                    raise ValueError(f"Corrupt {type_name} (field length varint overrun): {path_name}")
            if pos + length > n:
                raise ValueError(f"Corrupt {type_name} (field payload past end): {path_name}")
            if fields is not None:
                sub_type = fields.get(field_number)
                if sub_type is not None:
                    # schema 声明的消息型字段：载荷按子消息 schema 实际解析
                    _validate_message(payload[pos:pos + length], sub_type, path_name, depth + 1)
            pos += length
        elif wire_type == 5:
            if pos + 4 > n:
                raise ValueError(f"Corrupt {type_name} (fixed32 payload past end): {path_name}")
            pos += 4
        else:
            raise ValueError(f"Corrupt {type_name} (unsupported wire type {wire_type}): {path_name}")


def validate_trace_file(path):
    """校验单个 .perfetto-trace 证据文件（T08/TC36-42：坏证据必须拒绝 valid）。

    接受两种形态，均做实际解析（valid 依赖解析成功）：
    - gzip 容器：先完整解压（gzip.decompress 校验 CRC 与长度，损坏/截断在此
      拒绝），再对解压载荷做完整 schema 走查；
    - 未压缩 protobuf Trace（`repeated TracePacket packet = 1`）：逐记录走查
      顶层 field-1 记录，**且每个包载荷按 MESSAGES 完整 schema 递归实际解析**
      （审计轮 4：918 个消息定义，来源固定 commit 40b529923598b739b2892a536
      a7692eedbed5685，生成步骤见 generate_perfetto_trace_schema.py）——外层
      合法但任意已知嵌套消息内损坏（如 `0a 01 ff`、`0a 03 5a 01 ff`、
      `0a 05 5a 03 22 01 ff`）不再被接受。
    未知字段号按 wire type 跳过（proto 未知字段容忍）；未知消息类型仅框架
    走查；zstd 等本工具无法实际解压验证的压缩格式明确拒绝（不凭魔数放行）；
    空文件、零包文件一律拒绝。运行时零第三方依赖（schema 表为内嵌生成数据；
    生成仅开发期需要 grpcio-tools，步骤见生成器模块文档）。"""
    data = path.read_bytes()
    if not data:
        raise ValueError(f"Empty trace file: {path.name}")
    if data[:2] == b"\x1f\x8b":
        try:
            data = gzip.decompress(data)
        except (OSError, EOFError, zlib.error) as error:
            raise ValueError(f"Corrupt gzip trace ({error}): {path.name}")
        if not data:
            raise ValueError(f"Gzip trace decompressed to empty payload: {path.name}")
    elif data[:4] == b"\x28\xb5\x2f\xfd":
        raise ValueError(f"Unsupported compressed trace format (zstd is not validated by this tool): {path.name}")
    n = len(data)
    pos = 0
    packets = 0
    while pos < n:
        if data[pos] != 0x0A:
            raise ValueError(f"Unparseable trace (expected packet field tag at offset {pos}): {path.name}")
        pos += 1
        length = 0
        shift = 0
        while True:
            if pos >= n:
                raise ValueError(f"Corrupt trace (packet length varint truncated): {path.name}")
            byte = data[pos]
            pos += 1
            length |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7
            if shift > 63:
                raise ValueError(f"Corrupt trace (packet length varint overrun): {path.name}")
        if pos + length > n:
            raise ValueError(f"Corrupt trace (packet extends past end of file): {path.name}")
        _validate_message(data[pos:pos + length], "TracePacket", path.name, depth=0)
        pos += length
        packets += 1
    if packets == 0:
        raise ValueError(f"Trace contains no packets: {path.name}")


def validate_round_traces(folder):
    """校验一轮测量的 Perfetto trace 证据（androidx.benchmark 每用例每迭代
    输出 *.perfetto-trace，文件名含用例名）。要求：轮内至少一份 trace、每个
    预期用例至少一份可归属 trace，且轮内所有 trace 均可通过
    [validate_trace_file]——缺失/空/损坏/无法解析/文件名不可归属均拒绝。

    证据能力边界（审计轮 2 逐项声明，各证明到什么程度）：
    - **文件名归属**：弱归属——证明"该文件被标注为某用例的 trace"，不证明
      其内容确属该用例或本次运行；
    - **环境一致性**：environment.json 必需字段与 build.json（APK 摘要）的
      两轮相等校验——证明两轮在**声明的**环境/APK 维度一致，不证明字段之外
      的宿主差异；
    - **trace 本次运行来源**：本工具**不能证明** trace 文件产生于本次运行
      （文件名与内容均不绑定运行标识）；来源证明需测量时内嵌运行绑定（如
      trace 内写入 session 标识），当前协议未含，登记为协议边界。"""
    trace_files = sorted(folder.rglob("*.perfetto-trace"))
    if not trace_files:
        raise ValueError("No perfetto traces captured for this round")
    for name in EXPECTED:
        if not any(name in p.name for p in trace_files):
            raise ValueError(f"Missing perfetto trace for benchmark case {name}")
    for path in trace_files:
        validate_trace_file(path)

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
