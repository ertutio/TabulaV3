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
  合法但包内损坏（如 0a 01 ff）的普通与 gzip 形态均拒绝，valid 依赖解析成功。

只验证测量工具本身：不产出性能数字、不恢复 PR6 的历史测量任务、不构成
任何冻结基线。测试计数口径：本文件 23 项 + 既有 test_report.py 3 项 =
discover 全量 26 项。运行：python -m unittest
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
            with self.assertRaisesRegex(ValueError, "Corrupt trace packet"):
                report.read_session(folder)

    def test_corruptPacketContentInsideGzipIsRejectedThroughRealEntry(self):
        # 同一损坏内容经合法 gzip 包装：解压后仍须拒绝
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "b_warmStartup" in p.name)
            victim.write_bytes(gzip.compress(b"\x0a\x01\xff"))
            with self.assertRaisesRegex(ValueError, "Corrupt trace packet"):
                report.read_session(folder)

    def test_corruptVarintValueInsidePacketIsRejected(self):
        # 包内 varint 值截断（tag 合法但 value 越界）——顶层框架走查覆盖
        with tempfile.TemporaryDirectory() as tmp:
            root, folder = self.round(tmp)
            victim = next(p for p in folder.rglob("*.perfetto-trace") if "c_shuffleCards" in p.name)
            # field 1 (timestamp, varint) 声明继续但载荷耗尽
            victim.write_bytes(b"\x0a\x04\x08\xff\xff\xff\xff")
            with self.assertRaisesRegex(ValueError, "Corrupt trace packet"):
                report.read_session(folder)


if __name__ == "__main__":
    unittest.main()
