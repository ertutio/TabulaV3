#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 intent_samples 产物：数量、可解码性、尺寸、体积、金标准一致性。

检查项
------
1. 目录里 *.png 数量 == 50
2. 每个 PNG 能被 Pillow 打开并 verify()，尺寸为 720×1280
3. 每张 < 120 KB；总体积 < 4 MB
4. golden.json：version == 1、无 BOM、sample 条目 == 50、file 唯一且文件都存在
5. 类别计数：9 类合计 50，且各类数量与约定一致
6. 枚举合法性：category / expectation / expected_signals 词表
7. 保护一致性：must_keep_protected == True 的样本 expectation 必须为 keep
8. 脱敏一致性：sensitive_tokens 非空时，其每一项都要出现在 golden_lines 的拼接文本里
9. 确定性：用同一脚本重跑后，PNG 的 sha256 与本次记录一致（由 --record 记录基线）

用法
----
    python tools/intent-samples/verify_samples.py
    python tools/intent-samples/verify_samples.py --record   # 首次记录 sha256 基线
"""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_DIR = REPO_ROOT / "app" / "src" / "test" / "resources" / "intent_samples"
GOLDEN = SAMPLE_DIR / "golden.json"
BASELINE = Path(__file__).resolve().parent / ".sha256_baseline.json"

EXPECT_FILES = 50
EXPECT_W, EXPECT_H = 720, 1280
MAX_BYTES = 120 * 1024
MAX_TOTAL = 4 * 1024 * 1024

ALLOWED_CATEGORY = {
    "expired_coupon", "stale_news", "share", "temp_code", "event",
    "old_price", "keep", "uncertain", "hard",
}
ALLOWED_EXPECT = {"recommend", "lower_priority", "uncertain", "keep"}
ALLOWED_SIGNAL = {
    "coupon", "old_price", "expired_date", "deadline", "news", "live", "share",
    "extract_code", "temp_code", "verification_code", "pickup", "event", "ended",
    "order", "invoice", "ticket", "courseware", "long_term", "link", "phone", "order_no",
}

# 需求里约定的类别配额
EXPECT_CATEGORY_COUNT = {
    "expired_coupon": 6,   # coupon-expired 4 + promo-flash 2
    "old_price": 2,
    "stale_news": 4,
    "keep": 13,            # news-article 3 + protect-* 10
    "share": 6,
    "uncertain": 1,        # share-justlink-01
    "temp_code": 6,
    "event": 6,
    "hard": 6,
}

# 需求里约定的期望分布（按清单逐条累加）
#   recommend      = coupon-expired 4 + promo-flash 2 + news-live 2 + news-flash 2
#                    + share-expired 2 + share-cloud 2 + code-* 6 + event-* 6 + hard-rotate 1 = 27
#   lower_priority = coupon-oldprice 2
#   uncertain      = share-link 2 + share-justlink 1 + hard-blurry 2 + hard-nowords 2 = 7
#   keep           = news-article 3 + protect-* 10 + hard-mixed 1 = 14
EXPECT_EXPECTATION_COUNT = {
    "recommend": 27,
    "lower_priority": 2,
    "uncertain": 7,
    "keep": 14,
}

# 需求里约定的文件名前缀 → 该前缀应有的张数
EXPECT_PREFIX_COUNT = {
    "coupon-expired": 4, "promo-flash": 2, "coupon-oldprice": 2,
    "news-live-ended": 2, "news-flash-old": 2, "news-article": 3,
    "share-expired": 2, "share-link": 2, "share-cloud": 2, "share-justlink": 1,
    "code-sms": 2, "code-pickup": 2, "code-invite": 2,
    "event-ticket": 2, "event-signup": 2, "event-vote": 2,
    "protect-order": 3, "protect-invoice": 2, "protect-ticket": 2,
    "protect-slide": 2, "protect-longterm": 1,
    "hard-blurry": 2, "hard-nowords": 2, "hard-mixed": 1, "hard-rotate": 1,
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    fails: list[str] = []
    warns: list[str] = []

    # 1. 数量
    pngs = sorted(SAMPLE_DIR.glob("*.png"))
    print(f"[1] png 文件数：{len(pngs)}（期望 {EXPECT_FILES}）")
    if len(pngs) != EXPECT_FILES:
        fails.append(f"png 数量 {len(pngs)} != {EXPECT_FILES}")

    # 2 & 3. 可解码 / 尺寸 / 单张体积
    total = 0
    sizes: Counter[tuple[int, int]] = Counter()
    biggest = ("", 0)
    for p in pngs:
        n = p.stat().st_size
        total += n
        if n > MAX_BYTES:
            fails.append(f"{p.name}: {n} 字节 > {MAX_BYTES}")
        if n > biggest[1]:
            biggest = (p.name, n)
        try:
            with Image.open(p) as im:
                im.verify()
            with Image.open(p) as im:
                w, h = im.size
                fmt = im.format
                mode = im.mode
        except Exception as exc:  # 解码失败
            fails.append(f"{p.name}: Pillow 打开失败 {exc!r}")
            continue
        sizes[(w, h)] += 1
        if (w, h) != (EXPECT_W, EXPECT_H):
            fails.append(f"{p.name}: 尺寸 {w}x{h} != {EXPECT_W}x{EXPECT_H}")
        if fmt != "PNG":
            fails.append(f"{p.name}: 格式 {fmt} != PNG")
        if mode not in ("RGB", "P", "RGBA"):
            warns.append(f"{p.name}: 色彩模式 {mode}")

    print(f"[2] 全部可被 Pillow 打开并校验通过（PNG, 720x1280）：{sum(sizes.values())} 张")
    print(f"[3] 总体积：{total} 字节 = {total/1024/1024:.3f} MB（上限 4 MB）；"
          f"最大单张：{biggest[0]} {biggest[1]/1024:.1f} KB（上限 120 KB）")
    if total > MAX_TOTAL:
        fails.append(f"总体积 {total} > {MAX_TOTAL}")

    # 4. golden.json 基本结构
    raw = GOLDEN.read_bytes()
    print(f"[4] golden.json：{len(raw)} 字节，BOM={raw[:3] == b'\\xef\\xbb\\xbf'}")
    if raw[:3] == b"\xef\xbb\xbf":
        fails.append("golden.json 带 BOM")
    doc = json.loads(raw.decode("utf-8"))
    samples = doc.get("samples", [])
    print(f"    version={doc.get('version')}  samples={len(samples)}（期望 50）")
    if doc.get("version") != 1:
        fails.append(f"version {doc.get('version')} != 1")
    if len(samples) != EXPECT_FILES:
        fails.append(f"samples 条目 {len(samples)} != {EXPECT_FILES}")
    if list(doc.keys()) != ["version", "samples"]:
        warns.append(f"顶层键顺序：{list(doc.keys())}")

    names = [s.get("file") for s in samples]
    if len(set(names)) != len(names):
        dup = [k for k, v in Counter(names).items() if v > 1]
        fails.append(f"file 重复：{dup}")
    missing = [n for n in names if not (SAMPLE_DIR / n).exists()]
    if missing:
        fails.append(f"golden 指向的文件不存在：{missing}")
    extra = sorted(set(p.name for p in pngs) - set(names))
    if extra:
        fails.append(f"目录里有 golden 未登记的 png：{extra}")

    # 5 & 6 & 7 & 8. 逐条内容校验
    cat_count: Counter[str] = Counter()
    exp_count: Counter[str] = Counter()
    prefix_count: Counter[str] = Counter()
    for s in samples:
        f = s["file"]
        cat_count[s["category"]] += 1
        exp_count[s["expectation"]] += 1
        prefix_count[f.rsplit("-", 1)[0]] += 1
        if s["category"] not in ALLOWED_CATEGORY:
            fails.append(f"{f}: category {s['category']} 不在词表")
        if s["expectation"] not in ALLOWED_EXPECT:
            fails.append(f"{f}: expectation {s['expectation']} 不在词表")
        bad_sig = [x for x in s["expected_signals"] if x not in ALLOWED_SIGNAL]
        if bad_sig:
            fails.append(f"{f}: expected_signals 非法 {bad_sig}")
        if not isinstance(s["must_keep_protected"], bool):
            fails.append(f"{f}: must_keep_protected 非 bool")
        if s["must_keep_protected"] and s["expectation"] != "keep":
            fails.append(f"{f}: must_keep_protected=True 但 expectation={s['expectation']}")
        if not isinstance(s["screenshot_saved_days_ago"], int):
            fails.append(f"{f}: screenshot_saved_days_ago 非整数")
        dl = s["deadline_date"]
        if dl and not (len(dl) == 10 and dl[4] == "-" and dl[7] == "-"):
            fails.append(f"{f}: deadline_date 格式异常 {dl!r}")
        if not isinstance(s["golden_lines"], list) or not isinstance(s["sensitive_tokens"], list):
            fails.append(f"{f}: golden_lines / sensitive_tokens 不是数组")
        joined = "\n".join(s["golden_lines"])
        for tok in s["sensitive_tokens"]:
            if tok not in joined:
                fails.append(f"{f}: sensitive_token {tok!r} 未出现在 golden_lines")
        # 临时码类必须声明脱敏片段
        if s["category"] == "temp_code" and not s["sensitive_tokens"]:
            fails.append(f"{f}: temp_code 类未声明 sensitive_tokens")

    print(f"[5] 类别计数（合计 {sum(cat_count.values())}）：")
    for cat in sorted(cat_count):
        want = EXPECT_CATEGORY_COUNT.get(cat)
        flag = "OK" if want == cat_count[cat] else f"期望 {want}"
        print(f"      {cat:<16} {cat_count[cat]:>2}  {flag}")
        if want != cat_count[cat]:
            fails.append(f"类别 {cat} 计数 {cat_count[cat]} != {want}")
    for cat, want in EXPECT_CATEGORY_COUNT.items():
        if cat not in cat_count:
            fails.append(f"类别 {cat} 缺失")

    print(f"[6] 期望分布（合计 {sum(exp_count.values())}）：")
    for exp in sorted(exp_count):
        want = EXPECT_EXPECTATION_COUNT.get(exp)
        flag = "OK" if want == exp_count[exp] else f"期望 {want}"
        print(f"      {exp:<16} {exp_count[exp]:>2}  {flag}")
        if want != exp_count[exp]:
            fails.append(f"expectation {exp} 计数 {exp_count[exp]} != {want}")

    print("[7] 文件名前缀计数：")
    for pre, want in EXPECT_PREFIX_COUNT.items():
        got = prefix_count.get(pre, 0)
        mark = "OK" if got == want else f"期望 {want} 实际 {got}"
        if got != want:
            fails.append(f"前缀 {pre} 计数 {got} != {want}")
    print("      " + "；".join(f"{k}={v}" for k, v in sorted(prefix_count.items())))

    # 9. 确定性基线
    digests = {p.name: sha256(p) for p in pngs}
    if "--record" in sys.argv:
        BASELINE.write_text(json.dumps(digests, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"[9] 已写入 sha256 基线：{BASELINE.name}（{len(digests)} 条）")
    elif BASELINE.exists():
        base = json.loads(BASELINE.read_text(encoding="utf-8"))
        diff = [k for k in digests if base.get(k) != digests[k]]
        print(f"[9] 确定性复核：{len(digests)} 张与基线比对，差异 {len(diff)} 张")
        if diff:
            fails.append(f"与基线不一致（重跑非确定性）：{diff[:5]}")
    else:
        print("[9] 无基线文件，跳过确定性复核（可用 --record 建立）")

    print("-" * 74)
    if warns:
        print(f"[warn] {len(warns)} 条：")
        for w in warns[:10]:
            print(f"    - {w}")
    if fails:
        print(f"[FAIL] {len(fails)} 项不通过：")
        for f in fails:
            print(f"    - {f}")
        return 1
    print(f"[PASS] 全部检查通过：50 张 PNG + golden.json，"
          f"总计 {total/1024/1024:.3f} MB，单张最大 {biggest[1]/1024:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
