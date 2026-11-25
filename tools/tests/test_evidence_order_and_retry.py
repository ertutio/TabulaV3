#!/usr/bin/env python3
"""补两条 #91 取证脚本的新语义：顺序（设备测试先跑）与"断言失败不重试"。

原测试只覆盖退出码 0/1/2 的合并规则。本轮新增了两件事，必须一起钉住，
否则下次有人把顺序改回去、或给断言失败加 `|| true`，只有等 5 分钟模拟器
才暴露：

1. **设备测试排在预览探针之前**——六步设备测试至今零次执行，就是因为排在
   4 分钟的 24 项预览检查后面，掉线总落在后半段；
2. **环境中断可重试（VIEWER_ATTEMPTS），断言失败绝不重试**——真失败重跑
   没有意义，还会把"实现问题"稀释成"环境抖动"。
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


def fake_bin(tmp: Path, instrument_script: str) -> Path:
    """伪造 adb：install/grant 一律成功，`am instrument` 按给定脚本输出。"""
    bindir = tmp / "bin"
    bindir.mkdir(exist_ok=True)
    adb = bindir / "adb"
    # newline="\n"：Windows 上文本模式会把 \n 翻成 \r\n，脚本里就成了
    # `#!/bin/sh\r` + `exit 126`，测试会以"设备不可执行"的假象失败。
    adb.write_text(
        "#!/bin/sh\n"
        'case "$1" in\n'
        "  install|shell)\n"
        "    case \"$*\" in\n"
        "      *instrument*)\n"
        f"        {instrument_script}\n"
        "        ;;\n"
        "    esac\n"
        "    exit 0\n"
        "    ;;\n"
        "esac\n"
        "exit 0\n",
        encoding="utf-8",
        newline="\n",
    )
    adb.chmod(0o755)

    # 假 `timeout`：MSYS 的 timeout.exe 是原生 Windows 程序，无法 exec 一个
    # shell 脚本（会把 `adb` 判成 126 "不可执行"）。Linux CI 上没这问题，
    # 但本地要能跑，所以这里给它一个纯 shell 的替身：丢掉时长参数再 exec。
    fake_timeout = bindir / "timeout"
    fake_timeout.write_text(
        "#!/bin/sh\nshift\nexec \"$@\"\n", encoding="utf-8", newline="\n"
    )
    fake_timeout.chmod(0o755)
    return bindir


def run_script(env_extra: dict, tmp: Path) -> tuple[int, str]:
    env = dict(os.environ)
    env.update({
        "OUT_DIR": str(tmp / "out"),
        "API_LEVEL": "33",
        "TIMEOUT_CMD": "",
        # 不等待设备就绪：CI 上等满 30×2s 是对的，单测里是纯浪费
        "DEVICE_WAIT_SECONDS": "0",
        "APK_APP": str(apk_stub(tmp, "app-debug.apk")),
        "APK_TEST": str(apk_stub(tmp, "app-debug-androidTest.apk")),
    })
    env.update(env_extra)
    proc = subprocess.run(
        [BASH, str(SCRIPT)], cwd=str(ROOT), env=env, capture_output=True
    )
    out = (proc.stdout or b"") + (proc.stderr or b"")
    return proc.returncode, out.decode("utf-8", errors="replace")


def apk_stub(tmp: Path, name: str) -> Path:
    """空文件冒充 APK：脚本只检查存在性，安装由假 adb 负责。"""
    path = tmp / name
    path.write_bytes(b"")
    return path


def bash_path(*entries: str) -> str:
    """把目录拼成 bash 能用的 PATH。

    Windows 上 `os.pathsep` 是 `;`，但 MSYS bash 只认 `:`；直接拿 os.pathsep
    拼，假 adb 永远不会被找到（表现为 exit 126 的"设备不可执行"假象）。
    路径本身用正斜杠即可，MSYS 认得 `D:/x/y`。
    """
    parts = [str(Path(e)).replace("\\", "/") for e in entries]
    return ":".join(parts)


@unittest.skipIf(BASH is None, "需要 bash")
class EvidenceOrderAndRetryTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_device_test_runs_before_the_preview_probe(self):
        bindir = fake_bin(self.tmp, 'echo "OK (1 test)"')
        code, out = run_script(
            {
                "PATH": bash_path(bindir, os.environ.get("PATH", "").replace(";", ":")),
                "PROBE_CMD": "bash -c 'echo PROBE_RAN; exit 0'",
            },
            self.tmp,
        )
        self.assertIn("PROBE_RAN", out)
        viewer_idx = out.index("真实查看器设备测试")
        probe_idx = out.index("PROBE_RAN")
        self.assertLess(
            viewer_idx,
            probe_idx,
            "设备测试必须排在预览探针之前（掉线总落在后半段）",
        )
        self.assertEqual(0, code, out)
        self.assertIn("result=pass", out)

    def test_assertion_failure_is_not_retried(self):
        # 输出 FAILURES!!! 时应立即判 1，attempt 计数停在 1
        bindir = fake_bin(self.tmp, 'echo "FAILURES!!!"; exit 1')
        code, out = run_script(
            {
                "PATH": bash_path(bindir, os.environ.get("PATH", "").replace(";", ":")),
                "PROBE_CMD": "bash -c 'exit 0'",
                "VIEWER_ATTEMPTS": "3",
            },
            self.tmp,
        )
        self.assertEqual(1, code, out)
        self.assertIn("viewer attempt 1", out)
        self.assertNotIn("viewer attempt 2", out)
        self.assertIn("result=assertion_failed", out)

    def test_environment_interruption_is_retried_up_to_the_limit(self):
        # 没有结论（空输出）时按环境中断处理，并重试到 VIEWER_ATTEMPTS
        bindir = fake_bin(self.tmp, "true")
        code, out = run_script(
            {
                "PATH": bash_path(bindir, os.environ.get("PATH", "").replace(";", ":")),
                "PROBE_CMD": "bash -c 'exit 0'",
                "VIEWER_ATTEMPTS": "2",
            },
            self.tmp,
        )
        self.assertEqual(2, code, out)
        self.assertIn("viewer attempt 1", out)
        self.assertIn("viewer attempt 2", out)
        self.assertIn("result=environment_interrupted", out)


if __name__ == "__main__":
    unittest.main()
