#!/usr/bin/env python3
"""`tools/run-emulator-evidence.sh` 的退出码语义必须在**没有模拟器**时也能验证。

## 为什么要有这个测试

run 45368399281 的三个模拟器 job 全红，根因既不是实现也不是断言，而是
`script:` 里写了 `set -o pipefail`——android-emulator-runner 用 `/usr/bin/sh`
（dash）逐行执行，第一行就 `Illegal option -o pipefail` 退出 2，探针和设备
测试一行都没跑。

这类错误只有在 CI 上才暴露，代价是 3×5 分钟模拟器 + 一轮等待。把脚本本身
的语义搬到这里：用假的探针命令（`true` / `false` / `exit 2`）驱动真实脚本，
在本地和 CI 的 verify job 里秒级验证退出码契约。

## 钉住的规则

| 探针 | 设备测试 | 期望 |
| --- | --- | --- |
| 0 | 通过 / API<33 跳过 | 0 |
| 1 | 任意 | 1（断言失败优先） |
| 2 | 任意 | 2（环境中断） |
| 0 | 环境中断 | 2（不能算通过） |
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "run-emulator-evidence.sh"

BASH = shutil.which("bash")


def run(probe_exit: int, api_level: int, tmp: Path) -> tuple[int, str, dict]:
    env = dict(os.environ)
    env.update(
        {
            "API_LEVEL": str(api_level),
            "OUT_DIR": str(tmp),
            # 假探针：不碰模拟器，只给出指定退出码
            "PROBE_CMD": f"bash -c 'exit {probe_exit}'",
        }
    )
    # 用 bytes 收输出：Windows 上 bash 子进程的 stdout 偶尔拿不到文本流
    # （None），再拼字符串就 TypeError。解码容错比让测试环境决定成败更稳。
    proc = subprocess.run(
        [BASH, str(SCRIPT)],
        cwd=str(ROOT),
        env=env,
        capture_output=True,
    )
    out = (proc.stdout or b"") + (proc.stderr or b"")
    viewer_file = tmp / "viewer-exit.txt"
    files = {
        "viewer_exit": (
            viewer_file.read_text(encoding="utf-8", errors="replace")
            if viewer_file.is_file()
            else ""
        )
    }
    return proc.returncode, out.decode("utf-8", errors="replace"), files


@unittest.skipIf(BASH is None, "需要 bash 才能验证取证脚本")
class EmulatorEvidenceExitSemanticsTest(unittest.TestCase):
    def test_pass_requires_probe_pass_and_no_environment_breakage(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out, files = run(0, 31, Path(tmp))
            self.assertEqual(0, code, out)
            self.assertIn("result=pass", out)
            # API<33 没有采样层：写 na，既不是通过也不是未完成
            self.assertIn("viewer exit=na", files["viewer_exit"])

    def test_probe_assertion_failure_exits_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out, _ = run(1, 31, Path(tmp))
            self.assertEqual(1, code, out)
            self.assertIn("result=assertion_failed", out)

    def test_probe_environment_interruption_exits_two(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out, _ = run(2, 31, Path(tmp))
            self.assertEqual(2, code, out)
            self.assertIn("result=environment_interrupted", out)

    def test_assertion_failure_outranks_environment_interruption(self):
        """探针断言失败 + 设备测试跑不了 → 仍然是 1，真失败不被环境掩盖。"""
        with tempfile.TemporaryDirectory() as tmp:
            code, out, files = run(1, 33, Path(tmp))
            self.assertEqual(1, code, out)
            self.assertIn("result=assertion_failed", out)
            self.assertIn("viewer exit=2", files["viewer_exit"])

    def test_api33_without_device_is_incomplete_not_passed(self):
        """没有模拟器（本地跑）时必须报未完成，不能因为探针通过就绿。"""
        with tempfile.TemporaryDirectory() as tmp:
            code, out, files = run(0, 33, Path(tmp))
            self.assertEqual(2, code, out)
            self.assertIn("result=environment_interrupted", out)
            self.assertIn("viewer exit=2", files["viewer_exit"])


if __name__ == "__main__":
    unittest.main()
