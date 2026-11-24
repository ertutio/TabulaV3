#!/usr/bin/env python3
"""汇总 liquid glass 取证结果，输出三态结论：通过 / 断言失败 / 环境中断。

## 为什么要有这个脚本

`verify-liquid-glass-backdrop.yml` 的模拟器 job 里有两份互相独立的证据：

1. **预览页探针**（`tools/run-liquid-glass-preview-probe.py`）写出的
   `api<N>-summary.json`，里面是 `checks[]` 与 `environment_unavailable`；
2. **真实回收站查看器设备测试**写出的 `viewer-exit.txt`（一行 `viewer exit=N`）。

之前的汇总直接内联在 workflow 的 shell 里，用 heredoc 调 python。问题是
heredoc 的结束符被 YAML 缩进带着一起缩进，bash 认不出终止行，整段
shell 会被当成 heredoc 内容吞掉——比"结果语义错误"更隐蔽。

## 三种结论（互不混淆）

| 退出码 | 含义 | PR 上怎么读 |
| --- | --- | --- |
| 0 | 两份证据都通过 | 通过 |
| 1 | 有断言失败 | **失败**，是实现问题，必须修 |
| 2 | 环境中断 / 证据缺失 | **未完成**，不是通过 |

断言失败优先于环境中断：只要有一条断言真的失败，就不能用"模拟器抖动"
把它盖过去。

用法::

    python3 tools/summarize-glass-evidence.py \
        --summary device-results/api33-summary.json \
        --viewer-exit device-results/viewer-exit.txt
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

EXIT_PASS = 0
EXIT_ASSERTION_FAILED = 1
EXIT_ENVIRONMENT_INTERRUPTED = 2

VIEWER_EXIT_RE = re.compile(r"viewer exit=(\d+|na)")


def read_probe(summary: Path | None) -> tuple[int, bool, str, list[str]]:
    """读探针 summary，返回 (失败断言数, 是否环境不可用, 明细, 失败行)。

    summary 缺失本身就是证据缺失，按环境中断处理，不按通过处理。
    """
    if summary is None or not summary.is_file():
        return 0, True, "probe(no-summary)", ["探针没有产出 summary 文件"]

    try:
        data = json.loads(summary.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return 0, True, f"probe(unreadable:{type(exc).__name__})", [f"summary 读不出来：{exc}"]

    checks = data.get("checks") or []
    failed = [c for c in checks if not c.get("ok")]
    env = bool(data.get("environment_unavailable"))
    lines = [
        f"  FAIL {c.get('step')}: {str(c.get('detail', ''))[:200]}" for c in failed
    ]
    if env:
        lines.append(f"ENV_UNAVAILABLE {data.get('environment_unavailable')}")
    detail = f"probe(checks={len(checks)},failed={len(failed)},env={int(env)})"
    return len(failed), env, detail, lines


def read_viewer(path: Path | None) -> tuple[str | None, str, str]:
    """读设备测试退出码，返回 (退出码字符串或 None, 明细, 说明)。

    `0`=通过，`1`=断言失败，`2`=环境中断，`na`=本 API 没有采样层、按设计不跑。
    文件缺失或读不出码 → None（证据缺失，按环境中断处理）。
    """
    if path is None or not path.is_file():
        return None, "viewer(not-run)", "设备测试没有写出 viewer-exit.txt"
    text = path.read_text(encoding="utf-8", errors="replace")
    found = VIEWER_EXIT_RE.findall(text)
    if not found:
        return None, "viewer(no-code)", "viewer-exit.txt 里没有退出码"
    code = found[-1]
    if code == "na":
        return "na", "viewer(n/a: API<33 无采样层)", ""
    return code, f"viewer(exit={code})", ""


def classify(assertion_failed: bool, env_error: bool) -> int:
    """断言失败优先：真失败不能被"环境抖动"掩盖。"""
    if assertion_failed:
        return EXIT_ASSERTION_FAILED
    if env_error:
        return EXIT_ENVIRONMENT_INTERRUPTED
    return EXIT_PASS


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="汇总 liquid glass 取证结果")
    ap.add_argument("--summary", type=Path, default=None, help="探针 summary json")
    ap.add_argument("--viewer-exit", type=Path, default=None, help="viewer-exit.txt")
    ap.add_argument("--api", default="", help="仅用于日志标注")
    args = ap.parse_args(argv)

    prefix = f"[API {args.api}] " if args.api else ""

    failed, probe_env, probe_detail, failed_lines = read_probe(args.summary)
    for line in failed_lines:
        print(line)

    viewer_code, viewer_detail, viewer_note = read_viewer(args.viewer_exit)
    if viewer_note:
        print(viewer_note)

    # `na` 既不贡献失败也不贡献环境中断：API<33 本来就没有采样层，
    # 把它算成"未完成"会让合法的矩阵项永远红。
    assertion_failed = failed > 0 or viewer_code == "1"
    env_error = probe_env or viewer_code is None or viewer_code == "2"
    code = classify(assertion_failed, env_error)

    print(f"{prefix}probe: {probe_detail}")
    print(f"{prefix}viewer: {viewer_detail}")
    print(f"{prefix}assertion_failed={assertion_failed} env_error={env_error} -> exit {code}")
    print(f"{prefix}detail={probe_detail}; {viewer_detail}")
    return code


if __name__ == "__main__":
    sys.exit(main())
