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


def apk_stub(tmp: Path, name: str) -> Path:
    """空文件冒充 APK：脚本只检查存在性，安装由假 adb 负责。"""
    path = tmp / name
    path.write_bytes(b"")
    return path


def fake_adb(tmp: Path) -> Path:
    """假 adb：install/get-state 一律成功，其他输出为空。

    本机没有模拟器，真 adb 每次调用都要等 1-2 秒（`no devices found`），
    这套测试会从秒级变成分钟级甚至卡住。
    """
    bindir = tmp / "bin"
    bindir.mkdir(exist_ok=True)
    adb = bindir / "adb"
    adb.write_text(
        "#!/bin/sh\n"
        'case "$1" in\n'
        "  get-state) echo device ;;\n"
        '  shell) if [ "$2" = am ] && [ "$3" = instrument ]; then printf "%s\\n" "$FAKE_VIEWER_RESULT"; fi ;;\n'
        "esac\n"
        "exit 0\n",
        encoding="utf-8",
        newline="\n",
    )
    adb.chmod(0o755)
    return bindir


def bash_path(*entries: str) -> str:
    """拼 bash 能用的 PATH（Windows 上 os.pathsep 是 `;`，bash 只认 `:`）。"""
    parts = [str(Path(e)).replace("\\", "/") for e in entries if e]
    return ":".join(parts)


def run(probe_exit: int, api_level: int, tmp: Path, viewer_result: str = "") -> tuple[int, str, dict]:
    env = dict(os.environ)
    env.update(
        {
            "API_LEVEL": str(api_level),
            "MODE": "both",
            "FAKE_VIEWER_RESULT": viewer_result,
            "VIEWER_ATTEMPTS": "1",
            "OUT_DIR": str(tmp),
            # 不等待设备：CI 上等满 30×2s 是对的，单测里是纯浪费
            # （曾经让 26 项跑成 756 秒）。
            "DEVICE_WAIT_SECONDS": "0",
            # 造出两个假 APK：否则脚本会判"本分支没有 androidTest APK"→ na，
            # 而这里要验的正是"有设备测试、但设备/断言出问题"的分支。
            "APK_APP": str(apk_stub(tmp, "app-debug.apk")),
            "APK_TEST": str(apk_stub(tmp, "app-debug-androidTest.apk")),
            # 假探针：不碰模拟器，只给出指定退出码
            "PROBE_CMD": f"bash -c 'exit {probe_exit}'",
            # 只试一次安装：这里验的是退出码语义，不是重试策略
            "INSTALL_ATTEMPTS": "1",
            # 假 adb：本机没有设备，真 adb 每次调用都要等（曾让这套测试卡住）
            "PATH": bash_path(fake_adb(tmp), os.environ.get("PATH", "").replace(";", ":")),
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

    def test_accepts_one_completed_viewer_test(self):
        for summary in ("OK (1 test)", "OK (1 tests)"):
            with self.subTest(summary=summary), tempfile.TemporaryDirectory() as tmp:
                code, out, files = run(0, 35, Path(tmp), viewer_result=summary)
                self.assertEqual(0, code, out)
                self.assertIn("viewer exit=0", files["viewer_exit"])

    def test_zero_viewer_tests_is_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out, files = run(0, 35, Path(tmp), viewer_result="OK (0 tests)")
            self.assertEqual(2, code, out)
            self.assertIn("viewer exit=2", files["viewer_exit"])

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

    def test_missing_androidtest_apk_is_na_not_interrupted(self):
        """本分支没有设备测试时写 na：不冒充通过，也不假装环境中断。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env = dict(os.environ)
            env.update(
                {
                    "API_LEVEL": "33",
                    "OUT_DIR": str(root),
                    "DEVICE_WAIT_SECONDS": "0",
                    "PROBE_CMD": "bash -c 'exit 0'",
                    "APK_APP": str(apk_stub(root, "app-debug.apk")),
                    # 故意不给 androidTest APK
                    "APK_TEST": str(root / "missing-androidTest.apk"),
                }
            )
            proc = subprocess.run(
                [BASH, str(SCRIPT)], cwd=str(ROOT), env=env, capture_output=True
            )
            out = ((proc.stdout or b"") + (proc.stderr or b"")).decode(
                "utf-8", errors="replace"
            )
            viewer = (root / "viewer-exit.txt").read_text(encoding="utf-8")
            self.assertIn("viewer exit=na", viewer)
            self.assertEqual(0, proc.returncode, out)
            self.assertIn("result=pass", out)

    def test_api33_without_device_is_incomplete_not_passed(self):
        """没有模拟器（本地跑）时必须报未完成，不能因为探针通过就绿。"""
        with tempfile.TemporaryDirectory() as tmp:
            code, out, files = run(0, 33, Path(tmp))
            self.assertEqual(2, code, out)
            self.assertIn("result=environment_interrupted", out)
            self.assertIn("viewer exit=2", files["viewer_exit"])


if __name__ == "__main__":
    unittest.main()
