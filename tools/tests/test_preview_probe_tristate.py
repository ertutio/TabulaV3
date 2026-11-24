#!/usr/bin/env python3
"""探针（tools/run-liquid-glass-preview-probe.py）的三态收敛必须能离线验证。

## 为什么要有这个测试

run 45380937872 / 45384760411 的 API 33 暴露了两个连锁错误：

1. 探针跑完 **17 项全部 OK** 之后模拟器掉线，异常分支只写了
   `error-summary.json`，`api33-summary.json` 根本没生成 →
   汇总脚本读不到，判 `probe(no-summary)`，17 项通过证据全部作废；
2. 异常时 `raise` 让进程退出码变成 **1**，而 1 的契约是"有断言失败
   （实现问题）"——环境抖动被报成了实现缺陷。

这两条都不需要模拟器就能钉住：直接 import 模块，驱动 `write_summary` /
`classify_exit`，看落盘内容与退出码。
"""

import importlib.util
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / "tools" / "run-liquid-glass-preview-probe.py"


def load_probe(tmp: Path):
    """把探针当模块加载；OUT 指向临时目录，避免污染 device-results。"""
    spec = importlib.util.spec_from_file_location("lg_probe", PROBE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.OUT = tmp
    return module


def _has_pillow() -> bool:
    try:
        import PIL  # noqa: F401
    except ModuleNotFoundError:
        return False
    return True


@unittest.skipIf(shutil.which("python3") is None and shutil.which("python") is None,
                 "需要 Python 解释器")
@unittest.skipUnless(
    _has_pillow(),
    "探针模块顶层 import PIL；本环境未安装 Pillow 时跳过（不是实现问题）。"
    "CI 的 verify job 会先 pip install Pillow==10.4.0。",
)
class ProbeTriStateTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.probe = load_probe(self.tmp)
        # 探针里的 wait_for_device 默认等 30s；单测不需要真的等。
        os.environ["DEVICE_WAIT_SECONDS"] = "0"

    def tearDown(self):
        self._tmp.cleanup()

    def test_all_pass_writes_api_summary_with_success_true(self):
        self.probe.record("some check", True, "detail")
        code = self.probe.classify_exit(assertion_failed=False, env_error=None)
        self.assertEqual(self.probe.EXIT_PASS, code)

        self.probe.write_summary(33, success=(code == self.probe.EXIT_PASS))
        data = json.loads((self.tmp / "api33-summary.json").read_text(encoding="utf-8"))
        self.assertIs(True, data["success"])
        self.assertEqual(1, len(data["checks"]))
        self.assertNotIn("environment_unavailable", data)

    def test_assertion_failure_is_one_and_outranks_env(self):
        # 断言失败优先：真失败不能被"模拟器抖动"盖过去
        code = self.probe.classify_exit(assertion_failed=True, env_error="adb lost")
        self.assertEqual(self.probe.EXIT_ASSERTION_FAILED, code)

    def test_environment_interruption_is_two_not_one(self):
        # 关键回归：环境中断曾经因为 raise 变成退出码 1（= 实现问题）
        code = self.probe.classify_exit(assertion_failed=False, env_error="adb lost")
        self.assertEqual(self.probe.EXIT_ENVIRONMENT_INTERRUPTED, code)
        self.assertNotEqual(self.probe.EXIT_ASSERTION_FAILED, code)

    def test_environment_interruption_still_writes_api_summary(self):
        """回归：17 项已通过的证据不能因为掉线就丢。"""
        for i in range(17):
            self.probe.record(f"check {i}", True, "")
        self.probe.write_summary(33, success=False, env_error="adb: device not found")

        path = self.tmp / "api33-summary.json"
        self.assertTrue(path.is_file(), "环境中断也必须写 api<N>-summary.json")
        data = json.loads(path.read_text(encoding="utf-8"))
        self.assertIs(False, data["success"])
        self.assertEqual(17, len(data["checks"]))
        self.assertTrue(all(c["ok"] for c in data["checks"]))
        self.assertIn("device not found", data["environment_unavailable"])

        # 同时留 error-summary.json：workflow 的 Flag 步骤按这个文件名判断
        self.assertTrue((self.tmp / "error-summary.json").is_file())

    def test_unknown_api_falls_back_to_error_summary(self):
        self.probe.write_summary(None, success=False, env_error="getprop failed")
        self.assertTrue((self.tmp / "error-summary.json").is_file())
        self.assertFalse((self.tmp / "apiNone-summary.json").exists())

    def test_transient_adb_errors_are_recognised(self):
        # adb 抖动识别表：这些是"等一下再试"，不是"设备真的没了"
        for text in (
            "adb: device offline",
            "error: device 'emulator-5554' not found",
            "error: closed",
            "protocol fault (couldn't read status)",
        ):
            self.assertTrue(
                any(token in text for token in self.probe.TRANSIENT_ADB),
                f"应识别为瞬时故障：{text}",
            )


if __name__ == "__main__":
    unittest.main()
