"""C5/T08 任务 G：测量工具自身的正反夹具。

既有 test_report.py 覆盖缺失测量/坏样本/meminfo 口径；本文件补：
- 完整成功 fixture 通过（正例：两轮独立会话产出 valid 报告）；
- 迭代错配、环境不兼容、APK 不一致、版本集合不一致、损坏 trace 的明确失败；
- 缺少证据不输出通过（read_session/make_report 任一校验失败即异常，valid=True
  仅在全部校验通过后写盘）。

只验证测量工具本身：不产出性能数字、不恢复 PR6 的历史测量任务、不构成
任何冻结基线。运行：python -m unittest benchmark.scripts.tests.test_report_fixtures
（或直接 python -m unittest discover benchmark/scripts/tests）。
"""

import importlib.util
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
            self.assertIn("candidate", baseline["comparison"])


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

    def test_corruptTraceAndBrokenDataAreRejected(self):
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


if __name__ == "__main__":
    unittest.main()
