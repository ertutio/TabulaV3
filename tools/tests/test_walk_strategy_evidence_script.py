#!/usr/bin/env python3
"""`tools/run-walk-strategy-evidence.sh` 的退出码语义必须在**没有模拟器**时也能验证。

## 为什么要有这个测试

run 45372299838 的 device job 红在
`/usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "fi")`——
`script:` 被 dash 逐行执行，跨行 `if` 直接炸，把真实的断言失败
（`FAILURES!!! Tests run: 5, Failures: 1`）盖成了 exit 2 的语法错误。
同一轮里 `INSTRUMENT_EXIT=$?` 也恒为空（每行独立进程）。

把脚本搬进独立 bash 文件后，用假的 instrumentation 输出驱动它，
在本地和 CI 的 build job 里秒级验证：不用等 5 分钟模拟器。

## 钉住的规则

| instrumentation 输出 | 期望 |
| --- | --- |
| `OK (5 tests)` | 0 |
| `FAILURES!!!` | 1（断言失败优先） |
| 空 / 只有 `OK (3 tests)` | 2（未完成，不是通过） |
| 超时 | 2 |
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "run-walk-strategy-evidence.sh"
BASH = shutil.which("bash")


def run(emit: str, tmp: Path, expected: str = "5") -> tuple[int, str]:
    """用 `printf` 伪造 instrumentation 输出；不碰 adb。"""
    env = dict(os.environ)
    env.update(
        {
            "OUT_DIR": str(tmp),
            "EXPECTED_TESTS": expected,
            # 脚本在 INSTRUMENT_CMD 给出时不走 adb，安装那一步仍需 adb；
            # 因此这里同时把 adb 换成一个空实现。
            "INSTRUMENT_CMD": f"printf '%s\\n' {shell_quote(emit)}",
            "PATH": f"{tmp}{os.pathsep}{env.get('PATH', '')}",
        }
    )
    proc = subprocess.run(
        [BASH, str(SCRIPT)], cwd=str(ROOT), env=env, capture_output=True
    )
    out = (proc.stdout or b"") + (proc.stderr or b"")
    return proc.returncode, out.decode("utf-8", errors="replace")


def shell_quote(text: str) -> str:
    return "'" + text.replace("'", "'\\''") + "'"


@unittest.skipIf(BASH is None, "需要 bash 才能验证取证脚本")
class WalkStrategyEvidenceExitSemanticsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        # 假 adb：所有调用都成功，什么都不做
        fake = self.tmp / "adb"
        fake.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        fake.chmod(0o755)

    def tearDown(self):
        self._tmp.cleanup()

    def test_exact_ok_is_the_only_way_to_pass(self):
        code, out = run("OK (5 tests)", self.tmp)
        self.assertEqual(0, code, out)
        self.assertIn("result=pass", out)

    def test_assertion_failure_exits_one(self):
        code, out = run(
            "com.tabula.v3.X:\nFAILURES!!!\nTests run: 5,  Failures: 1", self.tmp
        )
        self.assertEqual(1, code, out)
        self.assertIn("result=assertion_failed", out)

    def test_fewer_tests_than_expected_is_incomplete_not_pass(self):
        # 只有 3 条跑过：既不是失败也不是通过，必须报未完成
        code, out = run("OK (3 tests)", self.tmp)
        self.assertEqual(2, code, out)
        self.assertIn("result=environment_interrupted", out)

    def test_empty_output_is_incomplete_not_pass(self):
        code, out = run("", self.tmp)
        self.assertEqual(2, code, out)
        self.assertIn("result=environment_interrupted", out)

    def test_assertion_failure_outranks_missing_summary(self):
        # 有 FAILURES 就必须是 1，不能被"条数不够"降级成 2
        code, out = run("FAILURES!!!\nTests run: 2,  Failures: 2", self.tmp)
        self.assertEqual(1, code, out)
        self.assertIn("result=assertion_failed", out)


if __name__ == "__main__":
    unittest.main()
