#!/usr/bin/env python3
"""静态闸门：androidTest 的 @Test/@Before/@After 必须是块体函数。

JUnit4 要求这些方法返回 void。Kotlin 的表达式体（`fun x() = runBlocking { }`）
返回最后一句的类型，编译能过，但真跑时报

    Invalid test class '...': 1. Method cleanup() should be void

并拒绝加载**整个测试类**——所有用例都不执行，只报一个"失败"。
`assembleDebugAndroidTest` 抓不到，只有模拟器真跑才炸（run 45408968334）。

用法::

    python3 tools/check_androidtest_signatures.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ANNOTATION = re.compile(r"@(Test|Before|After|BeforeClass|AfterClass)\b")
FUN = re.compile(r"\bfun\s+\w+\s*\(")


def violations(root: Path) -> list[str]:
    found: list[str] = []
    for path in sorted(root.rglob("*.kt")):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        for index, line in enumerate(lines):
            if not ANNOTATION.search(line):
                continue
            for cursor in range(index + 1, min(index + 8, len(lines))):
                if not FUN.search(lines[cursor]):
                    continue
                signature = lines[cursor].rstrip()
                next_line = lines[cursor + 1].strip() if cursor + 1 < len(lines) else ""
                if signature.endswith("=") or next_line.startswith("="):
                    found.append(
                        f"{path}:{cursor + 1}: 注解方法写成表达式体，JUnit4 会拒绝"
                        f"加载整个类：{signature.strip()}"
                    )
                break
    return found


def main() -> int:
    root = Path("app/src/androidTest")
    if not root.is_dir():
        print(f"跳过：{root} 不存在")
        return 0
    found = violations(root)
    for item in found:
        print(item)
    if found:
        print(f"\n{len(found)} 处表达式体注解方法；改为块体函数即可。")
        return 1
    print("OK：androidTest 的注解方法都是块体函数")
    return 0


if __name__ == "__main__":
    sys.exit(main())
