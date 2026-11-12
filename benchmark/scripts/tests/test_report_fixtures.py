"""C5/T08 任务 G（+审计轮 1/2）：测量工具自身的正反夹具。

既有 test_report.py 覆盖缺失测量/坏样本/meminfo 口径；本文件补：
- 完整成功 fixture 通过（正例：两轮独立会话产出 valid 报告）；
- 迭代错配、环境不兼容、APK 不一致、版本集合不一致的明确失败；
- 缺少证据不输出通过（read_session/make_report 任一校验失败即异常，valid=True
  仅在全部校验通过后写盘）；
- trace 证据真实校验（审计轮 1）：真实 perfetto 工具样本（tracebox v59.1 产出、
  trace_processor 解析干净）正例；缺失/单用例缺失/空/截断/无法解析/文件名
  不可归属负例；gzip 完整解压校验（损坏/截断拒绝）；zstd 明确拒绝；
- 包内容实际解析（审计轮 2）：每个包载荷顶层 protobuf 框架走查——外层框架
  合法但包内损坏（如 0a 01 ff）的普通与 gzip 形态均拒绝，valid 依赖解析成功；
- 嵌套消息 schema 校验（审计轮 3）：TracePacket 的 82 个 schema 声明消息型
  字段（自动生成自 perfetto_trace.proto，含 field 11=TrackEvent）载荷递归
  一层实际解析——嵌套损坏（0a 03 5a 01 ff）普通与 gzip 均拒；string/bytes
  按协议不递归（防误拒测试覆盖）；已知嵌套消息的更深层字段需各消息自己的
  schema，登记为能力边界。校验为内嵌 schema 表（无外部解析工具依赖），
  解析失败即异常传播 → 报告不得标为有效。

- 嵌套消息 schema 校验（审计轮 4）：完整 schema 递归——schema 表由 protoc
  （grpcio-tools 编译固定 commit 40b52992 的 perfetto_trace.proto 为
  descriptor set）提取，覆盖 918 个消息定义；消息型字段按子消息 schema 逐层
  实际解析（两层嵌套损坏 0a 05 5a 03 22 01 ff 普通/gzip 均拒）；string/bytes
  按协议不递归（防误拒测试覆盖）；未知字段号按 wire type 跳过（proto 未知
  字段容忍）；解析失败即异常传播 → 报告不得标为有效（无外部运行时工具依赖，
  生成期仅开发机需要 grpcio-tools）。

- 深度超限（审计轮 5）：损坏载荷位于 depth 33（>32 支持上限）普通/gzip 均
  以 "nesting depth exceeds supported range" 显式拒绝（修复前超限静默跳过、
  未检查内容被当作校验成功）；边界内（depth 32）损坏拒绝、合法嵌套通过。

- 未夹具化门补钉（审计轮 6，链审查 E1 清单 F1–F3）：viewerOpenClose 少计、
  rightSwipe 超量（精确计数非下限）、decodedFixtureSmoke 缺失、memory 行数
  不足、memory 阶段内迭代重复、benchmark case 重复——六个此前无夹具钉住的
  read_session 拒绝门各补一负例，防止门被放宽或移除而无回归信号。

只验证测量工具本身：不产出性能数字、不恢复 PR6 的历史测量任务、不构成
任何冻结基线。测试计数口径（实跑）：本文件 38 项 + 既有 test_report.py 3 项 =
discover 全量 41 项，零失败零跳过。运行：python -m unittest
benchmark.scripts.tests.test_report_fixtures（或 discover benchmark/scripts/tests）。
"""

import importlib.util
import gzip
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "report", Path(__file__).parents[1] / "report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

EXPECTED = dict(report.EXPECTED)
STAGES = report.STAGES

# 真实成功样本：perfetto v59.1 tracebox（linux-amd64，traced 内嵌）以
# track_event 配置实际产出，并经 trace_processor v59.1 干净解析（零
# error/data-loss 统计）。977 字节、18 个 TracePacket。
REAL_PERFETTO_TRACE = (Path(__file__).parent / "fixtures" /
                       "real_perfetto_trace.perfetto-trace").read_bytes()

ENVIRONMENT = {
    "sdk": 36, "fingerprint": "emulator_fingerprint", "abi": "arm64-v8a",
    "screenSize": "1080x2400", "density": 440, "animations": "off",
    "emulatorVersion": "37.2.12.0", "systemImageProperties": {"tag": "google_apis"},
    "protocolSHA256": "p" * 64, "datasetSHA256": "d" * 64,
    "compilationMode": "speed", "gpu": "swiftshader_indirect",
}

BUILD = {"sourceCommit": "0" * 40, "apkSHA256": "a" * 64}


def benchmark_data(seed):
    """每个预期 case 的指标按 seed 取值：轮次间可区分，值合法。"""
    benchmarks = []
    for name, count in EXPECTED.items():
        case = {"name": name + "_CompilationNoneBaseline", "metrics": {}, "sampledMetrics": {}}
        case["metrics"] = {
            "timeToInitialDisplayMs": {"runs": [10.0 + seed + i * 0.5 for i in range(count)]},
            "frameDurationCpuMs": {"runs": [5.0 + seed for _ in range(count)]},
        }
        case["sampledMetrics"] = {
            "frameDurationCpuMs": {"runs": [[1.0 + seed + j * 0.1 for j in range(10)] for _ in range(count)]},
        }
        benchmarks.append(case)
    return {"benchmarks": benchmarks}


def meminfo_text(seed):
    return (f" Java Heap: {1024 + seed} 8000\n Native Heap: {2048 + seed} 9000\n"
            f" Graphics: {512 + seed} 4000\n TOTAL PSS: {4096 + seed} TOTAL RSS: 30000")


# 最小合法未压缩 Perfetto trace：顶层 `repeated TracePacket packet = 1`
# 两条记录（tag 0x0A + varint 长度 + 载荷）。包载荷为合法 protobuf 顶层框架：
# 包1 = field1(varint)=1、包2 = field2(varint)=7（审计轮 2 更正——原夹具载荷
# `08 01 0c` 的尾字节 0c 是非法 wiretype，旧校验器不查包内故未暴露）。
VALID_TRACE_BODY = b"\x0a\x02\x08\x01" + b"\x0a\x02\x10\x07"

TRACE_CLASS_PREFIX = "com.tabula.v3.benchmark.TabulaPerformanceBenchmark."


def write_traces(folder, case_names=None):
    """androidx.benchmark 形态：每用例一份 trace，文件名 = <类>.<用例>_<迭代>.perfetto-trace。"""
    for name in (case_names if case_names is not None else EXPECTED):
        (folder / f"{TRACE_CLASS_PREFIX}{name}_0.perfetto-trace").write_bytes(VALID_TRACE_BODY)


def write_round(folder, seed):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "build.json").write_text(json.dumps(BUILD))
    (folder / "instrumentation.txt").write_text("OK (7 tests)")
    (folder / "benchmarkData.json").write_text(json.dumps(benchmark_data(seed)))
    rows = []
    for stage in STAGES:
        for iteration in range(5):
            rows.append({"stage": stage, "iteration": iteration,
                         "meminfo": meminfo_text(seed + iteration)})
    (folder / "memory.json").write_text(json.dumps({"rows": rows}))
    events = [{"action": "rightSwipe", "n": n} for n in range(180)]
    events += [{"action": "viewerOpenClose", "n": n} for n in range(31)]
    events.append({"action": "decodedFixtureSmoke"})
    (folder / "operations.jsonl").write_text("\n".join(json.dumps(e) for e in events))
    write_traces(folder)


def write_valid_input(root):
    for session in ["a", "b"]:
        base = root / f"session-{session}"
        base.mkdir(parents=True, exist_ok=True)
        (base / "environment.json").write_text(json.dumps(ENVIRONMENT))
        write_round(base / "baseline", seed=1 if session == "a" else 2)
    return root


class ValidFixtureTest(unittest.TestCase):
    def test_completeValidSessionsProduceValidBaselineReport(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            report.make_report(write_valid_input(Path(tmp)), output)
            baseline = json.loads((output / "baseline.json").read_text())
            self.assertTrue(baseline["valid"])
            self.assertIn("baseline", baseline["sources"])
            keys = baseline["sources"]["baseline"]["metrics"]
            for expected_key in ["a_coldStartup.timeToInitialDisplayMs",
                                 "c_shuffleCards.frameDurationCpuMs.iterationP95",
                                 "memory.viewer.totalPssMB"]:
                self.assertIn(expected_key, keys)
            self.assertIn("comparison", baseline)
            self.assertEqual(1, baseline["schemaVersion"])
            csv_text = (output / "metrics.csv").read_text()
            self.assertIn("a_coldStartup.timeToInitialDisplayMs", csv_text)

    def test_candidateAndBaselineRoundsProduceComparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            write_round(root / "session-a" / "candidate", seed=5)
            write_round(root / "session-b" / "candidate", seed=6)
            output = Path(tmp) / "out"
            report.make_report(root, output)
            baseline = json.loads((output / "baseline.json").read_text())
            # comparison 按指标名键控（baseline/candidate 成对逐会话计算）
            self.assertIn(
                "a_coldStartup.timeToInitialDisplayMs",
                baseline["comparison"],
            )
            paired = baseline["comparison"]["a_coldStartup.timeToInitialDisplayMs"][
                "pairedChangePercent"
            ]
            self.assertEqual({"a", "b"}, set(paired.keys()))


class NegativeFixtureTest(unittest.TestCase):
    def test_iterationCountMismatchIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            payload = json.loads((folder / "benchmarkData.json").read_text())
            # 迭代错配：startup 指标只有 9 次（应为 10）
            payload["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"] = \
                payload["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"][:9]
            (folder / "benchmarkData.json").write_text(json.dumps(payload))
            with self.assertRaisesRegex(ValueError, "iterations"):
                report.read_session(folder)

    def test_incompatibleEnvironmentIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            env_b = dict(ENVIRONMENT, gpu="host")
            (root / "session-b" / "environment.json").write_text(json.dumps(env_b))
            with self.assertRaisesRegex(ValueError, "incompatible"):
                report.make_report(root, Path(tmp) / "out")

    def test_apkMismatchBetweenSessionsIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            build_b = dict(BUILD, apkSHA256="b" * 64)
            (root / "session-b" / "baseline" / "build.json").write_text(json.dumps(build_b))
            with self.assertRaisesRegex(ValueError, "same APKs"):
                report.make_report(root, Path(tmp) / "out")

    def test_comparedVersionSetsMustMatchAcrossSessions(self):
        # 不同来源/不同运行的轮次不能被拼成同一次有效基线：仅 A 会话有
        # candidate 轮（版本集合不一致）必须明确失败
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            write_round(root / "session-a" / "candidate", seed=5)
            with self.assertRaisesRegex(ValueError, "Compared versions differ"):
                report.make_report(root, Path(tmp) / "out")

    def test_corruptInstrumentationLogAndBrokenJsonAreRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            (folder / "instrumentation.txt").write_text("FAILED (0 tests)")
            with self.assertRaisesRegex(ValueError, "Instrumentation did not pass"):
                report.read_session(folder)

        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            (folder / "benchmarkData.json").write_text("{not-json")
            with self.assertRaises(json.JSONDecodeError):
                report.read_session(folder)

    def test_missingEvidenceFailsInsteadOfPassing(self):
        # 缺少操作证据（滑动/查看循环/解码冒烟）不能产出 valid 报告
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            events = [{"action": "rightSwipe", "n": n} for n in range(10)]  # 应为 180
            (folder / "operations.jsonl").write_text("\n".join(json.dumps(e) for e in events))
            with self.assertRaisesRegex(ValueError, "180 card advances"):
                report.read_session(folder)

    # —— 事件门精确计数负例（审计轮 6）：现有夹具只钉"部分滑动"，以下钉住
    #    另外三个未夹具化的 read_session 门，防止门被放宽为 >= 或被移除 ——
    def test_undercountViewerCyclesIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            events = ([{"action": "rightSwipe", "n": n} for n in range(180)]
                      + [{"action": "viewerOpenClose", "n": n} for n in range(30)]  # 应为 31
                      + [{"action": "decodedFixtureSmoke"}])
            (folder / "operations.jsonl").write_text("\n".join(json.dumps(e) for e in events))
            with self.assertRaisesRegex(ValueError, "viewer cycles"):
                report.read_session(folder)

    def test_overcountSwipesIsRejected(self):
        # 超量事件同样拒绝：门是精确计数，不是下限（防 179/181 都被放行）
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            events = ([{"action": "rightSwipe", "n": n} for n in range(181)]  # 应为 180
                      + [{"action": "viewerOpenClose", "n": n} for n in range(31)]
                      + [{"action": "decodedFixtureSmoke"}])
            (folder / "operations.jsonl").write_text("\n".join(json.dumps(e) for e in events))
            with self.assertRaisesRegex(ValueError, "180 card advances"):
                report.read_session(folder)

    def test_missingDecodedFixtureSmokeIsRejected(self):
        # 解码冒烟预检缺失：没有真实解码像素证据的轮次不能产出 valid 报告
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            events = ([{"action": "rightSwipe", "n": n} for n in range(180)]
                      + [{"action": "viewerOpenClose", "n": n} for n in range(31)])
            (folder / "operations.jsonl").write_text("\n".join(json.dumps(e) for e in events))
            with self.assertRaisesRegex(ValueError, "preflight"):
                report.read_session(folder)

    # —— memory 行门负例（审计轮 6）：rows 总数与阶段内迭代唯一性 ——
    def test_shortMemoryRowsAreRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            payload = json.loads((folder / "memory.json").read_text())
            payload["rows"] = payload["rows"][:19]  # 应为 20（5 迭代 x 4 阶段）
            (folder / "memory.json").write_text(json.dumps(payload))
            with self.assertRaisesRegex(ValueError, "5 repetitions x 4 stages"):
                report.read_session(folder)

    def test_duplicateMemoryIterationIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            payload = json.loads((folder / "memory.json").read_text())
            # 仅改写迭代号：startup 阶段出现两个 0、缺失 1——总数仍为 20，
            # 先行的 rows==20 门通过，命中阶段内迭代唯一性门
            for row in payload["rows"]:
                if row["stage"] == "startup" and row["iteration"] == 1:
                    row["iteration"] = 0
            (folder / "memory.json").write_text(json.dumps(payload))
            (folder / "memory.json").write_text(json.dumps(payload))
            with self.assertRaisesRegex(ValueError, "Missing or duplicated memory iterations"):
                report.read_session(folder)

    # —— 重复 benchmark case 门负例（审计轮 6）——
    def test_duplicateBenchmarkCaseIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = write_valid_input(Path(tmp))
            folder = root / "session-a" / "baseline"
            payload = json.loads((folder / "benchmarkData.json").read_text())
            payload["benchmarks"].append(json.loads(json.dumps(payload["benchmarks"][0])))
            (folder / "benchmarkData.json").write_text(json.dumps(payload))
            with self.assertRaisesRegex(ValueError, "Duplicate benchmark"):
                report.read_session(folder)


class TraceFixtureTest(unittest.TestCase):
    """T08/TC36-42 trace 证据校验：坏 JSON 测试不能替代 trace 校验——
    生产报告入口必须对真实 trace 文件做存在/空/损坏/解析/归属校验。"""

    def round(self, tmp):
        root = write_valid_input(Path(tmp))
        return root, root / "session-a" / "baseline"

    def test_validTracesFromEveryExpectedCasePassThroughRealEntry(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, _ = self.round(tmp)
            output = Path(tmp) / "out"
            report.make_report(root, output)
            baseline = json.loads((output / "baseline.json").read_text())
            self.assertTrue(baseline["valid"])

    def test_roundWithoutTracesIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            for p in folder.rglob("*.perfetto-trace"):
                p.unlink()
            with self.assertRaisesRegex(ValueError, "No perfetto traces"):
                report.read_session(folder)

    def test_traceMissingForOneExpectedCaseIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "c_shuffleCards" in p.name)
            victim.unlink()
            with self.assertRaisesRegex(ValueError, "Missing perfetto trace for benchmark case c_shuffleCards"):
                report.read_session(folder)

    def test_emptyTraceFileIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "d_drawCards" in p.name)
            victim.write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "Empty trace file"):
                report.read_session(folder)

    def test_truncatedTraceIsRejected(self):
        # 长度 varint 声称超出文件末尾——真实截断 trace 的典型形态
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            victim.write_bytes(b"\x0a\x40\x01\x02")
            with self.assertRaisesRegex(ValueError, "Corrupt trace"):
                report.read_session(folder)

    def test_unparseableTraceContentIsRejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "b_warmStartup" in p.name)
            victim.write_text("this is not a perfetto trace")
            with self.assertRaisesRegex(ValueError, "Unparseable trace"):
                report.read_session(folder)

    def test_tracesFromUnrelatedSourcesCannotCoverExpectedCases(self):
        # 文件名不可归属：全部 trace 来自无关用例名（文件名归属之外的同名文件
        # 不得充当本轮预期用例的 trace 证据；轮次来源证明由 build/env 校验承担）
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            for p in folder.rglob("*.perfetto-trace"):
                p.unlink()
            write_traces(folder, case_names=["someOtherBenchmark"])
            with self.assertRaisesRegex(ValueError, "Missing perfetto trace for benchmark case a_coldStartup"):
                report.read_session(folder)

    def test_realPerfettoToolTraceIsAcceptedThroughRealEntry(self):
        # 真实成功样本：perfetto v59.1 tracebox（linux-amd64）+ track_event 配置
        # 实际产出、并经 trace_processor v59.1 干净解析（零 error/data-loss 统计）
        # 的 977 字节 trace（fixtures/real_perfetto_trace.perfetto-trace，18 个
        # TracePacket）——经真实 read_session 入口接受。
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            victim.write_bytes(REAL_PERFETTO_TRACE)
            output = Path(tmp) / "out"
            report.make_report(root, output)
            self.assertTrue(json.loads((output / "baseline.json").read_text())["valid"])

    def test_gzipCompressedRealTraceIsValidatedAfterDecompression(self):
        # gzip 容器：完整解压（CRC 校验）+ 解压载荷 protobuf 走查——有效压缩
        # trace 通过真实入口
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            victim.write_bytes(gzip.compress(REAL_PERFETTO_TRACE))
            output = Path(tmp) / "out"
            report.make_report(root, output)
            self.assertTrue(json.loads((output / "baseline.json").read_text())["valid"])

    def test_corruptGzipTraceIsRejected(self):
        # 损坏压缩内容：gzip 流中部字节翻转——解压失败必须拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            compressed = bytearray(gzip.compress(REAL_PERFETTO_TRACE))
            mid = len(compressed) // 2
            compressed[mid] ^= 0xFF
            victim.write_bytes(bytes(compressed))
            with self.assertRaisesRegex(ValueError, "Corrupt gzip trace"):
                report.read_session(folder)

    def test_truncatedGzipTraceIsRejected(self):
        # 截断压缩内容：gzip 流尾部截断——解压失败必须拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "d_drawCards" in p.name)
            victim.write_bytes(gzip.compress(REAL_PERFETTO_TRACE)[:-6])
            with self.assertRaisesRegex(ValueError, "Corrupt gzip trace"):
                report.read_session(folder)

    def test_zstdTraceIsExplicitlyRejectedAsUnsupported(self):
        # 本工具无法实际解压验证的压缩格式（zstd）明确拒绝，不凭魔数放行
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "e_viewer" in p.name)
            victim.write_bytes(b"\x28\xb5\x2f\xfd" + b"\x00" * 24)
            with self.assertRaisesRegex(ValueError, "Unsupported compressed trace format"):
                report.read_session(folder)

    def test_corruptPacketContentIsRejectedThroughRealEntry(self):
        # 审计轮：外层框架合法（tag 0x0A len 1）但包内 protobuf 损坏（0xff）——
        # 包载荷必须实际解析，valid 依赖解析成功
        corrupt = b"\x0a\x01\xff"
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            victim.write_bytes(corrupt)
            with self.assertRaisesRegex(ValueError, "Corrupt"):
                report.read_session(folder)

    def test_corruptPacketContentInsideGzipIsRejectedThroughRealEntry(self):
        # 同一损坏内容经合法 gzip 包装：解压后仍须拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "b_warmStartup" in p.name)
            victim.write_bytes(gzip.compress(b"\x0a\x01\xff"))
            with self.assertRaisesRegex(ValueError, "Corrupt"):
                report.read_session(folder)

    def test_corruptVarintValueInsidePacketIsRejected(self):
        # 包内 varint 值截断（tag 合法但 value 越界）——顶层框架走查覆盖
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "c_shuffleCards" in p.name)
            # field 1 (timestamp, varint) 声明继续但载荷耗尽
            victim.write_bytes(b"\x0a\x04\x08\xff\xff\xff\xff")
            with self.assertRaisesRegex(ValueError, "Corrupt"):
                report.read_session(folder)

    @staticmethod
    def depth_chain_packet(ff_depth: int, innermost: bytes = b"\xff") -> bytes:
        """构造 TracePacket → TrackEvent(11) → DebugAnnotation(4) →
        DebugAnnotation[11] 自递归 (ff_depth-2) 层的 trace，给定载荷恰位于
        递归深度 ff_depth（TracePacket=0、TrackEvent=1、DebugAnnotation=2）。"""
        def varint(n):
            out = bytearray()
            while True:
                b = n & 0x7F
                n >>= 7
                out.append(b | (0x80 if n else 0))
                if not n:
                    return bytes(out)
        def wrap11(body):
            return b"\x5a" + varint(len(body)) + body
        def wrap4(body):
            return b"\x22" + varint(len(body)) + body
        body = innermost
        for _ in range(ff_depth - 2):
            body = wrap11(body)
        body = wrap4(body)
        body = wrap11(body)
        return b"\x0a" + varint(len(body)) + body

    def test_overLimitNestedCorruptionIsRejectedThroughRealEntry(self):
        # 审计轮 5：损坏载荷位于 depth 33（>32 支持上限）——超限必须显式失败
        # （"nesting depth exceeds supported range"），不得静默跳过把未检查
        # 内容当作校验成功（修复前普通/gzip 均 ACCEPTED，留证提交历史）
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            victim.write_bytes(self.depth_chain_packet(33))
            with self.assertRaisesRegex(ValueError, "nesting depth exceeds supported range"):
                report.read_session(folder)

    def test_overLimitNestedCorruptionInsideGzipIsRejectedThroughRealEntry(self):
        # 同一超限损坏经合法 gzip 包装：解压后仍须以超限失败拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "b_warmStartup" in p.name)
            victim.write_bytes(gzip.compress(self.depth_chain_packet(33)))
            with self.assertRaisesRegex(ValueError, "nesting depth exceeds supported range"):
                report.read_session(folder)

    def test_withinLimitNestedCorruptionIsRejectedThroughRealEntry(self):
        # 边界内：损坏载荷位于 depth 32（≤32 上限）——框架走查拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "c_shuffleCards" in p.name)
            victim.write_bytes(self.depth_chain_packet(32))
            with self.assertRaisesRegex(ValueError, "Corrupt DebugAnnotation"):
                report.read_session(folder)

    def test_withinLimitLegalNestingIsAcceptedThroughRealEntry(self):
        # 边界内合法正例：depth 32 的合法嵌套（最内层为合法空消息）必须通过
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "d_drawCards" in p.name)
            victim.write_bytes(self.depth_chain_packet(32, innermost=b""))
            output = Path(tmp) / "out"
            report.make_report(root, output)
            self.assertTrue(json.loads((output / "baseline.json").read_text())["valid"])

    def test_corruptNestedMessageIsRejectedThroughRealEntry(self):
        # 审计轮 3：0a 03 5a 01 ff = TracePacket，field 11（TrackEvent，schema
        # 声明的消息型字段）载荷 ff 损坏——schema 驱动递归必须识别
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            victim.write_bytes(b"\x0a\x03\x5a\x01\xff")
            with self.assertRaisesRegex(ValueError, "Corrupt"):
                report.read_session(folder)

    def test_corruptNestedMessageInsideGzipIsRejectedThroughRealEntry(self):
        # 同一嵌套损坏经合法 gzip 包装：解压后仍须拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "b_warmStartup" in p.name)
            victim.write_bytes(gzip.compress(b"\x0a\x03\x5a\x01\xff"))
            with self.assertRaisesRegex(ValueError, "Corrupt"):
                report.read_session(folder)

    def test_corruptTwoLevelNestedMessageIsRejectedThroughRealEntry(self):
        # 审计轮 4：0a 05 5a 03 22 01 ff = TracePacket → TrackEvent(11) →
        # DebugAnnotation(4) 最内层载荷 ff——完整 schema 递归必须识别
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "a_coldStartup" in p.name)
            victim.write_bytes(b"\x0a\x05\x5a\x03\x22\x01\xff")
            with self.assertRaisesRegex(ValueError, "Corrupt DebugAnnotation"):
                report.read_session(folder)

    def test_corruptTwoLevelNestedMessageInsideGzipIsRejectedThroughRealEntry(self):
        # 同一两层嵌套损坏经合法 gzip 包装：解压后仍须拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "b_warmStartup" in p.name)
            victim.write_bytes(gzip.compress(b"\x0a\x05\x5a\x03\x22\x01\xff"))
            with self.assertRaisesRegex(ValueError, "Corrupt DebugAnnotation"):
                report.read_session(folder)

    def test_stringFieldPayloadIsNotRecursedAsMessage(self):
        # 防误拒：消息型字段之外的 length-delimited 载荷（按协议可能是字符串/
        # 任意字节）不得被当作消息递归——含任意文本的合法 trace 必须通过。
        # field 36 在 schema 中为 string 型（不在消息表）。
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "e_viewer" in p.name)
            victim.write_bytes(b"\x0a\x0e\xa2\x02\x0bhello world")
            report.read_session(folder)  # 不抛出即通过


if __name__ == "__main__":
    unittest.main()
