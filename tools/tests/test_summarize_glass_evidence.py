"""`tools/summarize-glass-evidence.py` 的三态结论必须能被机器区分。

## 为什么必须有这个测试

CI 之前用 `continue-on-error: true`，导致**断言失败也显示绿色**。改成三态之后，
新的失败模式是"分类错了"：把环境中断报成通过（假绿）、或把真失败报成环境抖动
（假红后被人忽略）。这两种错误都不会让 workflow 自己报错，只会让人读错结论。

因此这里直接钉住分类规则：

- 断言失败 → 退出码 1，且**优先于**环境中断；
- 环境中断 / 证据缺失 → 退出码 2，绝不等于通过；
- 两份证据都通过 → 退出码 0。
"""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "summarize_glass_evidence", ROOT / "tools" / "summarize-glass-evidence.py"
)
summarize = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summarize)


def write_summary(root: Path, checks, environment_unavailable=None) -> Path:
    path = root / "summary.json"
    path.write_text(
        json.dumps(
            {"checks": checks, "environment_unavailable": environment_unavailable},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    return path


def write_viewer(root: Path, code: int) -> Path:
    path = root / "viewer-exit.txt"
    path.write_text(f"viewer exit={code}\n", encoding="utf-8")
    return path


def run_cli(summary: Path | None, viewer: Path | None) -> int:
    argv = []
    if summary is not None:
        argv += ["--summary", str(summary)]
    if viewer is not None:
        argv += ["--viewer-exit", str(viewer)]
    return summarize.main(argv)


class GlassEvidenceClassificationTest(unittest.TestCase):
    def test_both_evidence_pass_is_the_only_way_to_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(root, [{"step": "size contract", "ok": True}])
            viewer = write_viewer(root, 0)
            self.assertEqual(0, run_cli(summary, viewer))

    def test_probe_assertion_failure_fails_the_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(
                root,
                [
                    {"step": "size contract", "ok": True},
                    {"step": "draw invalidation", "ok": False, "detail": "1->1"},
                ],
            )
            viewer = write_viewer(root, 0)
            self.assertEqual(1, run_cli(summary, viewer))

    def test_viewer_assertion_failure_fails_the_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(root, [{"step": "size contract", "ok": True}])
            viewer = write_viewer(root, 1)
            self.assertEqual(1, run_cli(summary, viewer))

    def test_probe_environment_interruption_is_incomplete_not_passed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(
                root,
                [{"step": "size contract", "ok": True}],
                environment_unavailable="adb: device 'emulator-5554' not found",
            )
            viewer = write_viewer(root, 0)
            self.assertEqual(2, run_cli(summary, viewer))

    def test_viewer_environment_interruption_is_incomplete_not_passed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(root, [{"step": "size contract", "ok": True}])
            viewer = write_viewer(root, 2)
            self.assertEqual(2, run_cli(summary, viewer))

    def test_assertion_failure_outranks_environment_interruption(self):
        """真失败不能被"模拟器抖动"盖过去——这是本条规则的全部意义。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(
                root,
                [{"step": "draw invalidation", "ok": False, "detail": "1->1"}],
                environment_unavailable="error: closed",
            )
            viewer = write_viewer(root, 2)
            self.assertEqual(1, run_cli(summary, viewer))

    def test_missing_probe_summary_is_incomplete_not_passed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            viewer = write_viewer(root, 0)
            self.assertEqual(2, run_cli(None, viewer))

    def test_missing_viewer_result_is_incomplete_not_passed(self):
        """设备测试压根没跑（之前是 `adb: command not found`）不能算通过。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(root, [{"step": "size contract", "ok": True}])
            self.assertEqual(2, run_cli(summary, None))

    def test_api_below_sampling_floor_is_not_a_failure_nor_a_pass(self):
        """API<33 没有采样层：不能算通过，也不能把合法矩阵项判成未完成。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(root, [{"step": "size contract", "ok": True}])
            viewer = root / "viewer-exit.txt"
            viewer.write_text(
                "viewer exit=na  # API 31 < 33，无采样层\n", encoding="utf-8"
            )
            self.assertEqual(0, run_cli(summary, viewer))

    def test_na_viewer_still_fails_when_probe_asserts_false(self):
        """`na` 不掩盖探针的真失败。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(
                root, [{"step": "draw invalidation", "ok": False, "detail": "1->1"}]
            )
            viewer = root / "viewer-exit.txt"
            viewer.write_text("viewer exit=na\n", encoding="utf-8")
            self.assertEqual(1, run_cli(summary, viewer))

    def test_unparsable_viewer_exit_is_incomplete_not_passed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(root, [{"step": "size contract", "ok": True}])
            viewer = root / "viewer-exit.txt"
            viewer.write_text("adb: command not found\n", encoding="utf-8")
            self.assertEqual(2, run_cli(summary, viewer))

    def test_corrupt_summary_is_incomplete_not_passed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = root / "summary.json"
            summary.write_text("{not json", encoding="utf-8")
            viewer = write_viewer(root, 0)
            self.assertEqual(2, run_cli(summary, viewer))

    def test_explicit_null_success_is_incomplete_not_pass(self):
        """增量落盘的半截 summary（success=null）绝不能被读成通过。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = root / "summary.json"
            summary.write_text(
                json.dumps(
                    {
                        "success": None,
                        "checks": [{"step": "install debug apk", "ok": True}],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            viewer = write_viewer(root, 0)
            code = run_cli(summary, viewer)
            self.assertEqual(2, code, "半截 summary 必须是未完成，不是通过")

    def test_missing_success_key_keeps_legacy_behaviour(self):
        """更早的 summary 没有 success 字段：仍按失败数判，不制造假红。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = write_summary(root, [{"step": "a", "ok": True}])
            viewer = write_viewer(root, 0)
            self.assertEqual(0, run_cli(summary, viewer))

    def test_success_false_without_failed_checks_is_incomplete(self):
        """探针自己判失败却没留失败项：证据自相矛盾，按未完成。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = root / "summary.json"
            summary.write_text(
                json.dumps(
                    {"success": False, "checks": [{"step": "a", "ok": True}]},
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            viewer = write_viewer(root, 0)
            self.assertEqual(2, run_cli(summary, viewer))


if __name__ == "__main__":
    unittest.main()
