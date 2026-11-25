#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为 TabulaV3「按整理目的凑一批图」生成 50 张**纯合成**中文截图样图 + golden.json。

设计要点
--------
1. 全部像素由本脚本绘制，不含任何真实用户截图、真实手机号 / 订单号 / 链接 / 验证码。
   手机号一律 ``138****0000`` 形式，订单号一律 ``TB2024...``，链接一律 ``example.com``，
   验证码 / 取件码 / 提取码一律示范值，并在图上带「合成样图 · 非真实数据」水印。
2. 确定性：全局种子 + 每个文件名的 crc32 派生子种子，同一台机器上重跑字节稳定
   （PNG 输出经 Pillow 编码，跨 Pillow 版本可能有极小差异，但版面完全一致）。
3. 重跑幂等：先清空目标目录里的 ``*.png``，再全量重建并覆盖 ``golden.json``。
4. 体积：720×1280，逐张尝试 RGB → 调色板量化，命中 < 118 KB 即停。
5. 自校验：每个渲染函数返回「实际绘制的逻辑行」，主流程断言它与声明的 golden_lines 完全一致，
   避免图与金标准脱节。

用法
----
    python tools/intent-samples/generate_samples.py
"""

from __future__ import annotations

import io
import json
import random
import sys
import zlib
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Windows 控制台默认 GBK，统一成 UTF-8 方便贴日志
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:  # 非终端环境忽略
    pass

# --------------------------------------------------------------------------------------
# 路径与常量
# --------------------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = REPO_ROOT / "app" / "src" / "test" / "resources" / "intent_samples"
GOLDEN_PATH = OUT_DIR / "golden.json"

W, H = 720, 1280
M = 40                      # 版心边距
MAX_BYTES = 118 * 1024      # 单张上限（要求 < 120 KB）
GLOBAL_SEED = 20261125

INK = (26, 26, 30)
INK2 = (74, 78, 86)
SUB = (142, 146, 154)
LINE = (233, 234, 240)
WHITE = (255, 255, 255)
RED = (212, 54, 54)
ORANGE = (230, 118, 36)
BLUE = (38, 108, 214)
GREEN = (28, 146, 94)
GREY_BG = (240, 241, 245)

# --------------------------------------------------------------------------------------
# 字体（带回退）
# --------------------------------------------------------------------------------------

FONT_CANDIDATES = [
    (Path("C:/Windows/Fonts/msyh.ttc"), 0),          # 微软雅黑
    (Path("C:/Windows/Fonts/MSYH.TTC"), 0),
    (Path("C:/Windows/Fonts/msyhbd.ttc"), 0),        # 微软雅黑 Bold
    (Path("C:/Windows/Fonts/simhei.ttf"), 0),        # 黑体
    (Path("C:/Windows/Fonts/simsun.ttc"), 0),        # 宋体
    (Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"), 0),
    (Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"), 0),
    (Path("/System/Library/Fonts/PingFang.ttc"), 0),
]

_FONT_CACHE: dict[int, ImageFont.ImageFont] = {}
FONT_USED: str = ""


def F(size: int) -> ImageFont.ImageFont:
    """按字号取字体，首次调用时解析可用字体文件并缓存结果。"""
    if size in _FONT_CACHE:
        return _FONT_CACHE[size]
    global FONT_USED
    for path, index in FONT_CANDIDATES:
        if not path.exists():
            continue
        try:
            font = ImageFont.truetype(str(path), size, index=index)
        except Exception:  # 字体损坏 / 索引越界 → 继续回退
            continue
        _FONT_CACHE[size] = font
        if not FONT_USED:
            FONT_USED = f"{path.name}#{index}"
        return font

    # 最后一层回退：Pillow 内置位图字体（无中文字形，仅保证脚本不崩）
    try:
        font = ImageFont.load_default(size)
    except TypeError:  # 老版本 Pillow 不支持 size
        font = ImageFont.load_default()
    _FONT_CACHE[size] = font
    if not FONT_USED:
        FONT_USED = "PIL-default(no-cjk)"
    return font


# --------------------------------------------------------------------------------------
# 绘图基元
# --------------------------------------------------------------------------------------


def vgrad(size: tuple[int, int], top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    """竖直渐变底：1×64 采样条放大，竖向渐变在 PNG 里几乎不占体积。"""
    strip = Image.new("RGB", (1, 64))
    for i in range(64):
        t = i / 63.0
        strip.putpixel((0, i), tuple(round(top[k] + (bottom[k] - top[k]) * t) for k in range(3)))
    return strip.resize(size, Image.BILINEAR)


def T(
    d: ImageDraw.ImageDraw,
    xy: tuple[float, float],
    text: str,
    size: int = 26,
    color: tuple[int, int, int] = INK,
    bold: bool = False,
    anchor: str = "la",
    max_w: int | None = None,
):
    """画一行字；给了 max_w 就自动缩字号直到塞得下。"""
    f = F(size)
    if max_w is not None:
        while size > 13 and d.textlength(text, font=f) > max_w:
            size -= 2
            f = F(size)
    d.text(
        xy,
        text,
        font=f,
        fill=color,
        anchor=anchor,
        stroke_width=1 if (bold and size >= 20) else 0,
        stroke_fill=color,
    )
    return f


def wrap(d: ImageDraw.ImageDraw, text: str, size: int, max_w: int) -> list[str]:
    """按字宽折行（中文无空格，逐字累加）。"""
    f = F(size)
    lines: list[str] = []
    cur = ""
    for ch in text:
        if ch == "\n":
            lines.append(cur)
            cur = ""
            continue
        if cur and d.textlength(cur + ch, font=f) > max_w:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    return lines


def para(
    d: ImageDraw.ImageDraw,
    x: int,
    y: int,
    text: str,
    size: int = 25,
    color: tuple[int, int, int] = INK2,
    max_w: int = W - 2 * M,
    leading: int = 14,
) -> int:
    """画一段折行文字，返回下一行的 y。"""
    for ln in wrap(d, text, size, max_w):
        T(d, (x, y), ln, size, color)
        y += size + leading
    return y


def card(
    d: ImageDraw.ImageDraw,
    box: tuple[float, float, float, float],
    radius: int = 20,
    fill: tuple[int, int, int] = WHITE,
    outline: tuple[int, int, int] = LINE,
) -> None:
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=1)


def pill(
    d: ImageDraw.ImageDraw,
    xy: tuple[float, float],
    text: str,
    size: int = 24,
    fg: tuple[int, int, int] = RED,
    bg: tuple[int, int, int] = (255, 238, 236),
    padx: int = 20,
    pady: int = 9,
) -> float:
    """胶囊标签，返回右边界 x。"""
    f = F(size)
    tw = d.textlength(text, font=f)
    x, y = xy
    d.rounded_rectangle(
        [x, y, x + tw + padx * 2, y + size + pady * 2],
        radius=(size + pady * 2) / 2,
        fill=bg,
    )
    d.text((x + padx, y + pady), text, font=f, fill=fg)
    return x + tw + padx * 2


def status_bar(d: ImageDraw.ImageDraw, t: str = "10:24") -> None:
    """手机状态栏：纯几何图形，无任何真实运营商信息。"""
    d.rectangle([0, 0, W, 56], fill=WHITE)
    T(d, (36, 15), t, 24, INK, bold=True)
    for i, hh in enumerate((7, 11, 15, 19)):
        x = W - 236 + i * 10
        d.rectangle([x, 37 - hh, x + 6, 37], fill=INK)
    d.arc([W - 184, 22, W - 152, 54], 205, 335, fill=INK, width=3)
    d.arc([W - 178, 28, W - 158, 48], 205, 335, fill=INK, width=3)
    d.rounded_rectangle([W - 138, 20, W - 88, 40], radius=5, outline=INK, width=2)
    d.rectangle([W - 134, 24, W - 100, 36], fill=INK)
    d.rectangle([W - 86, 26, W - 82, 34], fill=INK)


def navbar(d: ImageDraw.ImageDraw, title: str) -> None:
    d.rectangle([0, 56, W, 148], fill=WHITE)
    d.line([0, 148, W, 148], fill=LINE, width=1)
    d.line([44, 90, 58, 102], fill=INK, width=3)
    d.line([44, 114, 58, 102], fill=INK, width=3)
    T(d, (W / 2, 102), title, 30, INK, bold=True, anchor="mm")


def footer(d: ImageDraw.ImageDraw) -> None:
    d.text((W / 2, H - 30), "合成样图 · 非真实数据", font=F(18), fill=(186, 188, 196), anchor="mm")


def kv_rows(
    d: ImageDraw.ImageDraw,
    y: int,
    rows: list[tuple[str, str]],
    gap: int = 48,
    label_x: int = M + 26,
    value_right: int = W - M - 26,
    size: int = 22,
) -> list[str]:
    """左标签 / 右值的键值行，返回「标签 值」形式的逻辑行（供 golden_lines 使用）。"""
    for i, (k, v) in enumerate(rows):
        yy = y + i * gap
        T(d, (label_x, yy), k, size, SUB)
        T(d, (value_right, yy), v, size, INK2, anchor="ra", max_w=W - 2 * M - 150)
    return [f"{k} {v}" for k, v in rows]


def list_block(
    d: ImageDraw.ImageDraw,
    y: int,
    title: str,
    items: list[tuple[str, str]],
    row_h: int = 96,
) -> int:
    """通用「更多条目」列表块，用来填满页面下半部分留白。返回底部 y。"""
    h = 74 + row_h * len(items)
    card(d, (M, y, W - M, y + h), radius=18)
    T(d, (M + 28, y + 26), title, 23, SUB)
    for i, (main, meta) in enumerate(items):
        ry = y + 74 + i * row_h
        if i:
            d.line([M + 28, ry - 12, W - M - 28, ry - 12], fill=(244, 245, 248), width=1)
        d.rounded_rectangle([M + 28, ry, M + 28 + 52, ry + 52], radius=10, fill=(232, 234, 240))
        T(d, (M + 100, ry + 4), main, 24, INK2, max_w=W - 2 * M - 160)
        T(d, (M + 100, ry + 36), meta, 20, SUB, max_w=W - 2 * M - 160)
    return y + h


def qr(d: ImageDraw.ImageDraw, rnd: random.Random, box: tuple[float, float, float, float], cells: int = 21) -> None:
    """画一个假二维码（定长伪随机，可复现，不可扫描）。"""
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=WHITE)
    cell = (x1 - x0) / cells
    dark = (24, 24, 28)

    def dot(i: int, j: int) -> None:
        px, py = x0 + i * cell, y0 + j * cell
        d.rectangle([px, py, px + cell - 0.4, py + cell - 0.4], fill=dark)

    def finder(ci: int, cj: int) -> None:
        for i in range(7):
            for j in range(7):
                if i in (0, 6) or j in (0, 6) or (2 <= i <= 4 and 2 <= j <= 4):
                    dot(ci + i, cj + j)

    for i in range(cells):
        for j in range(cells):
            if (i < 8 and j < 8) or (i >= cells - 8 and j < 8) or (i < 8 and j >= cells - 8):
                continue
            if rnd.random() < 0.44:
                dot(i, j)
    finder(0, 0)
    finder(cells - 7, 0)
    finder(0, cells - 7)


# --------------------------------------------------------------------------------------
# 各类别渲染器
# 约定：每个渲染器返回 (image, drawn_lines)，drawn_lines 必须与 golden_lines 完全一致。
# --------------------------------------------------------------------------------------

NAV_DEFAULT = "示例应用"


def _screen(top=(252, 252, 254), bottom=(240, 241, 246)) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = vgrad((W, H), top, bottom)
    return img, ImageDraw.Draw(img)


def r_coupon(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((255, 253, 250), (243, 239, 237))
    status_bar(d)
    navbar(d, "我的优惠券")

    y = 184
    card(d, (M, y, W - M, y + 300), radius=22)
    d.rounded_rectangle([M, y + 12, M + 12, y + 288], radius=6, fill=ORANGE)
    T(d, (M + 44, y + 40), p["main"], 50, INK, bold=True, max_w=W - 2 * M - 90)
    T(d, (M + 44, y + 120), p["sub"], 24, SUB, max_w=W - 2 * M - 90)
    pill(d, (M + 44, y + 170), p["badge"], 26, RED, (255, 238, 236))
    d.rounded_rectangle([W - M - 172, y + 226, W - M - 38, y + 276], radius=25, fill=(235, 235, 240))
    T(d, ((W - M - 172 + W - M - 38) / 2, y + 251), "去使用", 24, (152, 154, 160), anchor="mm")

    y += 336
    card(d, (M, y, W - M, y + 190), radius=18)
    T(d, (M + 32, y + 34), "使用状态", 24, SUB)
    T(d, (M + 32, y + 84), p["status"], 27, RED, bold=True, max_w=W - 2 * M - 64)
    T(d, (M + 32, y + 138), "券面信息为合成示例，无真实权益", 21, SUB)

    d.rounded_rectangle([M, y + 226, W - M, y + 300], radius=16, fill=(248, 248, 250))
    T(d, (M + 32, y + 246), "到期后系统仍会保留截图，可手动清理", 21, SUB, max_w=W - 2 * M - 64)
    T(d, (M + 32, y + 276), "（本条为合成说明文案）", 21, SUB)

    list_block(
        d,
        y + 336,
        "其他已失效券",
        [("满59减5 · 全场通用", "已于 2026-11-01 结束"),
         ("满399减80 · 家电专区", "已于 2026-10-28 结束")],
    )

    footer(d)
    return img, [p["main"], p["badge"], p["status"]]


def r_promo(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((255, 246, 242), (246, 233, 229))
    status_bar(d)
    navbar(d, "限时活动")

    card(d, (M, 184, W - M, 470), radius=22, fill=(255, 244, 240))
    T(d, (M + 40, 220), p["l1"], 56, RED, bold=True, max_w=W - 2 * M - 80)
    T(d, (M + 40, 300), p["l2"], 32, INK, bold=True, max_w=W - 2 * M - 80)
    pill(d, (M + 40, 356), p["l3"], 26, (255, 255, 255), RED)
    T(d, (M + 40, 424), p["l4"], 25, INK2)

    # 倒计时块
    y = 506
    card(d, (M, y, W - M, y + 150), radius=18)
    T(d, (M + 30, y + 26), "距离结束", 22, SUB)
    for i, unit in enumerate(("00", "12", "36")):
        bx = M + 30 + i * 130
        d.rounded_rectangle([bx, y + 62, bx + 96, y + 130], radius=12, fill=(40, 40, 46))
        T(d, (bx + 48, y + 96), unit, 40, WHITE, bold=True, anchor="mm")
        if i < 2:
            T(d, (bx + 112, y + 96), ":", 34, INK, bold=True, anchor="mm")

    y = 690
    card(d, (M, y, W - M, y + 300), radius=18)
    T(d, (M + 30, y + 26), "活动细则（合成）", 24, SUB)
    for i, line in enumerate(
        [
            "1. 限时活动仅在该时间段内生效；",
            "2. 活动结束后价格恢复到原价；",
            "3. 截图不构成任何购买凭证；",
            "4. 本页数据全部为合成示例。",
        ]
    ):
        T(d, (M + 30, y + 72 + i * 44), line, 23, INK2, max_w=W - 2 * M - 60)

    y = 1016
    d.rounded_rectangle([M, y, W - M, y + 88], radius=44, fill=(206, 206, 212))
    T(d, (W / 2, y + 44), "活动已结束，无法参与", 26, (250, 250, 252), anchor="mm")

    footer(d)
    return img, [p["l1"], p["l2"], p["l3"], p["l4"]]


def r_oldprice(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 251, 253), (238, 240, 245))
    status_bar(d)
    navbar(d, "比价记录")

    T(d, (M, 182), p["title"], 34, INK, bold=True)
    T(d, (M, 232), p["sub"], 23, SUB)

    card(d, (M, 282, W - M, 620), radius=20)
    T(d, (M + 30, 310), p["k1"], 24, SUB)
    T(d, (M + 30, 348), p["v1"], 46, GREEN, bold=True, max_w=W - 2 * M - 60)
    d.line([M + 30, 428, W - M - 30, 428], fill=LINE, width=1)
    T(d, (M + 30, 452), p["k2"], 24, SUB)
    T(d, (M + 30, 490), p["v2"], 46, RED, bold=True, max_w=W - 2 * M - 60)
    pill(d, (M + 30, 566), p["tag"], 22, (120, 124, 132), (238, 239, 243))

    # 简易价格柱状图
    y = 660
    card(d, (M, y, W - M, y + 300), radius=20)
    T(d, (M + 30, y + 24), "价格走势（近 90 天）", 23, SUB)
    bars = [0.42, 0.55, 0.38, 0.62, 0.71, 0.58, 0.66, 0.86, 0.74]
    base = y + 258
    for i, v in enumerate(bars):
        bx = M + 40 + i * 68
        bh = int(v * 170)
        d.rounded_rectangle([bx, base - bh, bx + 40, base], radius=6, fill=(178, 196, 226) if i < 8 else (226, 178, 178))
    d.line([M + 30, base, W - M - 30, base], fill=LINE, width=1)

    y = 996
    T(d, (M, y), "说明：本页只记录价格，未包含任何活动截止日期。", 22, SUB, max_w=W - 2 * M)
    T(d, (M, y + 36), "截图来自合成比价页面。", 22, SUB)
    T(d, (M, y + 76), "缺少截止日期 → 按「旧比价」降级处理，不当过期券。", 22, INK2, max_w=W - 2 * M)

    d.rounded_rectangle([M, y + 130, W - M, y + 218], radius=44, fill=(238, 239, 243))
    T(d, (W / 2, y + 174), "查看完整价格历史", 26, (120, 122, 130), anchor="mm")

    footer(d)
    # 每张价格卡在语义上是一整行（标签 + 数值），金标准按整行记
    return img, [p["title"], f'{p["k1"]} {p["v1"]}', f'{p["k2"]} {p["v2"]}', p["chart"]]


def r_live(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((248, 249, 253), (238, 240, 248))
    status_bar(d)
    navbar(d, "直播")

    # 预览区
    d.rounded_rectangle([M, 180, W - M, 560], radius=20, fill=(46, 50, 62))
    d.ellipse([W / 2 - 46, 330, W / 2 + 46, 422], fill=(255, 255, 255))
    d.polygon([(W / 2 - 12, 352), (W / 2 - 12, 400), (W / 2 + 26, 376)], fill=(46, 50, 62))
    d.rounded_rectangle([M + 24, 200, M + 168, 248], radius=12, fill=(212, 54, 54))
    T(d, (M + 96, 224), "直播预告", 22, WHITE, anchor="mm")
    # 结束遮罩条
    d.rectangle([M, 470, W - M, 560], fill=(30, 32, 40))
    T(d, (W / 2, 515), p["ended"], 34, (235, 236, 240), bold=True, anchor="mm")

    T(d, (M, 594), p["title"], 36, INK, bold=True, max_w=W - 2 * M)
    T(d, (M, 654), p["host"], 24, SUB)
    T(d, (M, 700), p["date"], 30, INK2, bold=True)

    y = 760
    card(d, (M, y, W - M, y + 200), radius=18)
    T(d, (M + 30, y + 26), "回放状态", 23, SUB)
    T(d, (M + 30, y + 72), p["status"], 27, RED, bold=True, max_w=W - 2 * M - 60)
    T(d, (M + 30, y + 126), "直播内容由示例账号发布（合成）", 21, SUB)

    d.rounded_rectangle([M, 1000, W - M, 1088], radius=44, fill=(230, 231, 236))
    T(d, (W / 2, 1044), p["btn"], 28, (130, 132, 140), anchor="mm")

    d.line([M, 1112, W - M, 1112], fill=LINE, width=1)
    T(d, (M, 1136), "同主播的其他场次：11月6日 20:00（已结束）", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 1172), "11月2日 19:30（已结束）", 21, SUB, max_w=W - 2 * M)

    footer(d)
    return img, [p["date"], p["ended"], p["btn"]]


def r_flash(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen(WHITE, (245, 246, 250))
    status_bar(d)
    navbar(d, "快讯")

    y = 176
    card(d, (M, y, W - M, y + 360), radius=18)
    d.rounded_rectangle([M + 28, y + 30, M + 132, y + 78], radius=10, fill=RED)
    T(d, (M + 80, y + 54), "快讯", 24, WHITE, anchor="mm")
    T(d, (M + 152, y + 34), p["time"], 22, SUB)
    yy = para(d, M + 28, y + 104, p["title"], 31, INK, W - 2 * M - 56, 12)
    yy = para(d, M + 28, yy + 14, p["body"], 23, INK2, W - 2 * M - 56, 14)
    d.rounded_rectangle([M + 28, yy + 22, M + 214, yy + 68], radius=22, fill=(240, 241, 245))
    T(d, (M + 121, yy + 45), "查看详情", 21, (128, 130, 138), anchor="mm")
    T(d, (M + 28, y + 320), p["src"], 21, SUB)

    y2 = 566
    for i, (head, meta) in enumerate(p["others"]):
        card(d, (M, y2, W - M, y2 + 146), radius=16)
        T(d, (M + 28, y2 + 28), head, 25, INK2, max_w=W - 2 * M - 56)
        T(d, (M + 28, y2 + 74), meta, 21, SUB)
        T(d, (M + 28, y2 + 104), "示例日报 · 合成快讯", 20, (176, 178, 186))
        y2 += 166

    T(d, (M, 960), "以上为示例快讯，时间与内容均为合成。", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 996), "快讯类内容只有很短的有效期，过期后参考价值迅速下降。", 21, SUB, max_w=W - 2 * M)

    d.rounded_rectangle([M, 1064, W - M, 1152], radius=44, fill=(238, 239, 243))
    T(d, (W / 2, 1108), "订阅更多快讯", 26, (120, 122, 130), anchor="mm")

    footer(d)
    return img, [p["title"], p["time"]]


ARTICLE_BODY = [
    "城市的公共空间并不只是通行的背景，它同时承担移动、停留和交流三种功能。",
    "当一处广场同时满足这三件事时，人们愿意停留的时间会明显变长。",
    "步行网络的可达性取决于路口间距、过街等待时间和沿街界面的连续性。",
    "在旧城改造中，保留原有街巷尺度往往比拓宽主干道更有效。",
    "照明设计影响夜间的安全感，色温与照度的组合需要按场景分别讨论。",
    "本文整理了三类常见做法，并给出可复用的观察清单。",
]


def r_article(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((252, 252, 250), (240, 240, 238))
    status_bar(d)
    navbar(d, "长文")

    y = 182
    for ln in wrap(d, p["title"], 38, W - 2 * M)[:3]:
        T(d, (M, y), ln, 38, INK, bold=True)
        y += 52

    T(d, (M, y + 8), p["byline"], 22, SUB)
    y += 56
    d.line([M, y, W - M, y], fill=LINE, width=1)
    y += 34

    T(d, (M, y), p["section"], 28, INK, bold=True)
    y += 52
    for text in p["body"]:
        y = para(d, M, y, text, 24, INK2, W - 2 * M, 15)
        y += 10

    d.line([M, y + 6, W - M, y + 6], fill=LINE, width=1)
    T(d, (M, y + 30), "本文为资料型长文，不含活动截止或失效信息。", 21, SUB, max_w=W - 2 * M)
    T(d, (M, y + 68), "全文约 3,200 字 · 读完约 12 分钟（合成）", 21, SUB, max_w=W - 2 * M)

    footer(d)
    return img, [p["title"], p["byline"], p["section"]]


def r_share_expired(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 251, 254), (238, 240, 246))
    status_bar(d)
    navbar(d, p["platform"])

    y = 184
    card(d, (M, y, W - M, y + 380), radius=20)
    ext = p["name"].rsplit(".", 1)[-1].upper()
    d.rounded_rectangle([M + 30, y + 30, M + 130, y + 150], radius=16, fill=(58, 106, 200))
    T(d, (M + 80, y + 90), ext, 26, WHITE, bold=True, anchor="mm")
    T(d, (M + 152, y + 46), p["name"], 27, INK, bold=True, max_w=W - 2 * M - 190)
    T(d, (M + 152, y + 96), p["size"], 21, SUB)
    d.line([M + 30, y + 180, W - M - 30, y + 180], fill=LINE, width=1)
    T(d, (M + 30, y + 204), p["status"], 32, RED, bold=True, max_w=W - 2 * M - 60)
    T(d, (M + 30, y + 262), p["hint"], 21, SUB, max_w=W - 2 * M - 60)
    d.rounded_rectangle([M + 30, y + 306, M + 230, y + 358], radius=26, fill=(236, 237, 242))
    T(d, (M + 130, y + 332), p["btn"], 23, (140, 142, 150), anchor="mm")

    y2 = 600
    card(d, (M, y2, W - M, y2 + 190), radius=18)
    T(d, (M + 28, y2 + 26), "分享链接", 23, SUB)
    T(d, (M + 28, y2 + 70), p["link"], 22, BLUE, max_w=W - 2 * M - 56)
    T(d, (M + 28, y2 + 118), "链接指向 example.com，为占位域名。", 21, SUB, max_w=W - 2 * M - 56)

    T(d, (M, 838), p["note"], 23, INK2, max_w=W - 2 * M)
    list_block(
        d,
        902,
        "同一分享者的其他文件",
        [("流程说明.docx", "已失效 · 2026-10-21"), ("旧版素材.zip", "已失效 · 2026-10-09")],
        row_h=84,
    )

    footer(d)
    return img, [p["name"], p["status"], p["btn"], p["link"]]


def r_share_link(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 251, 254), (238, 240, 246))
    status_bar(d)
    navbar(d, p["platform"])

    y = 184
    card(d, (M, y, W - M, y + 300), radius=20)
    d.rounded_rectangle([M + 30, y + 30, M + 130, y + 150], radius=16, fill=(44, 156, 122))
    T(d, (M + 80, y + 90), "FILE", 22, WHITE, bold=True, anchor="mm")
    T(d, (M + 152, y + 46), p["name"], 27, INK, bold=True, max_w=W - 2 * M - 190)
    T(d, (M + 152, y + 100), p["meta"], 21, SUB, max_w=W - 2 * M - 190)
    d.rounded_rectangle([M + 30, y + 196, M + 250, y + 252], radius=28, fill=(40, 112, 214))
    T(d, (M + 140, y + 224), "复制链接", 24, WHITE, anchor="mm")
    d.rounded_rectangle([M + 268, y + 196, M + 448, y + 252], radius=28, fill=(238, 239, 243))
    T(d, (M + 358, y + 224), "打开", 24, (128, 130, 138), anchor="mm")

    y2 = 520
    card(d, (M, y2, W - M, y2 + 230), radius=18)
    T(d, (M + 28, y2 + 26), "文件信息", 23, SUB)
    T(d, (M + 28, y2 + 70), p["info"], 23, INK2, max_w=W - 2 * M - 56)
    T(d, (M + 28, y2 + 122), "页面未显示失效状态与有效期。", 22, SUB, max_w=W - 2 * M - 56)

    T(d, (M, 800), "该分享卡片只有平台名、标题与复制链接按钮。", 22, SUB, max_w=W - 2 * M)

    list_block(
        d,
        860,
        "同一分享者的其他文件",
        [("会议纪要.pdf", "6 页 · 3 天前"), ("预算表.xlsx", "1 个工作表 · 8 天前")],
        row_h=86,
    )

    footer(d)
    return img, [p["name"], p["meta"], "复制链接"]


def r_share_cloud(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 251, 255), (236, 239, 248))
    status_bar(d)
    navbar(d, "网盘中转")

    y = 184
    card(d, (M, y, W - M, y + 330), radius=20)
    d.rounded_rectangle([M + 30, y + 30, M + 116, y + 116], radius=18, fill=(58, 106, 200))
    d.rectangle([M + 46, y + 62, M + 100, y + 100], fill=WHITE)
    d.rectangle([M + 46, y + 62, M + 100, y + 72], fill=(58, 106, 200))
    T(d, (M + 140, y + 40), p["name"], 26, INK, bold=True, max_w=W - 2 * M - 180)
    T(d, (M + 140, y + 88), p["sub"], 21, SUB, max_w=W - 2 * M - 180)
    d.line([M + 30, y + 150, W - M - 30, y + 150], fill=LINE, width=1)
    T(d, (M + 30, y + 174), p["valid"], 32, ORANGE, bold=True, max_w=W - 2 * M - 60)
    T(d, (M + 30, y + 230), "中转链接需要转存后才能长期保存", 21, SUB, max_w=W - 2 * M - 60)
    d.rounded_rectangle([M + 30, y + 264, M + 250, y + 314], radius=24, fill=(228, 229, 234))
    T(d, (M + 140, y + 289), "立即转存", 23, (142, 144, 152), anchor="mm")

    y2 = 564
    card(d, (M, y2, W - M, y2 + 210), radius=18)
    T(d, (M + 28, y2 + 24), "链接状态", 23, SUB)
    for i, (k, v) in enumerate(p["rows"]):
        T(d, (M + 28, y2 + 70 + i * 44), k, 22, SUB)
        T(d, (W - M - 28, y2 + 70 + i * 44), v, 22, INK2, anchor="ra", max_w=W - 2 * M - 200)

    T(d, (M, 810), "页面上的有效期日期早于当前时间，中转链接已失效。", 22, SUB, max_w=W - 2 * M)
    T(d, (M, 848), "未转存的中转文件在到期后不会保留副本。", 22, INK2, max_w=W - 2 * M)

    list_block(
        d,
        916,
        "最近的中转记录",
        [("示例网盘 · 中转站", "有效期至 2026-10-18 · 已失效"),
         ("示例网盘 · 中转站", "有效期至 2026-10-07 · 已失效")],
        row_h=94,
    )

    footer(d)
    return img, [p["name"], p["valid"]]


def r_justlink(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen(WHITE, (241, 242, 247))
    status_bar(d)
    navbar(d, "扫码分享")

    d.rounded_rectangle([W / 2 - 210, 220, W / 2 + 210, 640], radius=24, fill=WHITE, outline=LINE, width=1)
    qr(d, rnd, (W / 2 - 160, 260, W / 2 + 160, 580), 21)
    T(d, (W / 2, 686), p["line1"], 30, INK, bold=True, anchor="mm")
    T(d, (W / 2, 736), p["line2"], 24, SUB, anchor="mm")

    y = 800
    card(d, (M, y, W - M, y + 150), radius=18)
    T(d, (M + 28, y + 26), "分享说明（合成）", 23, SUB)
    T(d, (M + 28, y + 70), "本页只有二维码与平台标识，没有失效提示。", 22, INK2, max_w=W - 2 * M - 56)
    T(d, (M + 28, y + 112), "无标题、无日期、无「已失效」字样。", 21, SUB, max_w=W - 2 * M - 56)

    d.rounded_rectangle([M, 1002, W - M, 1090], radius=44, fill=(238, 239, 243))
    T(d, (W / 2, 1046), "保存二维码", 26, (120, 122, 130), anchor="mm")
    T(d, (M, 1136), "缺少失效证据 → 只能给 uncertain，不能当高置信清理项。", 21, SUB, max_w=W - 2 * M)

    footer(d)
    return img, [p["line1"], p["line2"]]


def r_sms(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((247, 247, 250), (238, 238, 243))
    status_bar(d)
    navbar(d, "信息")

    T(d, (W / 2, 200), p["sender"], 24, SUB, anchor="mm")

    # 气泡
    body_lines = wrap(d, p["text"], 28, 520)
    bh = 48 + len(body_lines) * 42
    d.rounded_rectangle([M + 30, 240, W - M - 90, 240 + bh], radius=20, fill=WHITE, outline=LINE, width=1)
    yy = 268
    for ln in body_lines:
        T(d, (M + 60, yy), ln, 28, INK)
        yy += 42

    T(d, (W - M - 96, 240 + bh + 16), p["time"], 20, SUB, anchor="ra")

    # 提醒块
    y = 240 + bh + 80
    card(d, (M, y, W - M, y + 210), radius=18, fill=(255, 249, 246))
    T(d, (M + 28, y + 26), "验证码安全提示", 23, RED)
    T(d, (M + 28, y + 70), "验证码属于临时凭证，转发即失效。", 22, INK2, max_w=W - 2 * M - 56)
    T(d, (M + 28, y + 112), "本截图中的验证码为合成示范值。", 22, INK2, max_w=W - 2 * M - 56)
    T(d, (M + 28, y + 160), "任何情况下都不要把验证码发给他人。", 22, SUB, max_w=W - 2 * M - 56)

    list_block(
        d,
        y + 250,
        "同号码的历史短信",
        [("【示例平台】验证码 000000，5分钟内有效。", "昨天 18:02"),
         ("【示例平台】您的账号于示例设备登录。", "前天 09:11")],
        row_h=84,
    )

    footer(d)
    return img, [p["text"]]


def r_pickup(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 252, 250), (238, 243, 240))
    status_bar(d)
    navbar(d, "取件凭证")

    y = 190
    card(d, (M, y, W - M, y + 420), radius=22)
    T(d, (M + 34, y + 32), "示例驿站 · 取件凭证", 24, SUB)
    T(d, (M + 34, y + 78), p["code"], 62, INK, bold=True, max_w=W - 2 * M - 68)
    d.line([M + 34, y + 178, W - M - 34, y + 178], fill=LINE, width=1)
    T(d, (M + 34, y + 202), p["place"], 23, INK2, max_w=W - 2 * M - 68)
    T(d, (M + 34, y + 246), p["addr"], 22, SUB, max_w=W - 2 * M - 68)
    pill(d, (M + 34, y + 300), p["state"], 26, GREEN, (232, 246, 238))
    T(d, (M + 34, y + 366), "已取件的取件码不再具备任何效力。", 21, SUB, max_w=W - 2 * M - 68)

    y2 = 660
    card(d, (M, y2, W - M, y2 + 200), radius=18)
    T(d, (M + 28, y2 + 26), "为什么建议清理", 23, SUB)
    T(d, (M + 28, y2 + 70), "取件码是一次性凭证，过期后只剩泄露风险。", 22, INK2, max_w=W - 2 * M - 56)
    T(d, (M + 28, y2 + 126), "本图为合成凭证，不含真实收件信息。", 21, SUB, max_w=W - 2 * M - 56)

    list_block(
        d,
        902,
        "历史取件记录",
        [("取件码 1-4-3302", "11-18 · 已取件"), ("取件码 5-1-2087", "11-12 · 已取件")],
        row_h=94,
    )

    footer(d)
    return img, [p["code"], p["place"], p["state"]]


def r_invite(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 250, 254), (238, 239, 248))
    status_bar(d)
    navbar(d, "邀请")

    y = 190
    card(d, (M, y, W - M, y + 400), radius=22)
    d.rounded_rectangle([M + 30, y + 28, M + 78, y + 76], radius=12, fill=(96, 82, 196))
    T(d, (M + 54, y + 52), "邀", 24, WHITE, bold=True, anchor="mm")
    T(d, (M + 98, y + 40), p["kicker"], 26, INK, bold=True)
    T(d, (M + 30, y + 112), p["code"], 46, (96, 82, 196), bold=True, max_w=W - 2 * M - 60)
    d.line([M + 30, y + 196, W - M - 30, y + 196], fill=LINE, width=1)
    T(d, (M + 30, y + 222), p["valid"], 30, ORANGE, bold=True, max_w=W - 2 * M - 60)
    d.rounded_rectangle([M + 30, y + 288, W - M - 30, y + 344], radius=28, fill=(96, 82, 196))
    T(d, (W / 2, y + 316), p["btn"], 24, WHITE, anchor="mm")
    T(d, (M + 30, y + 362), p["hint"], 21, SUB, max_w=W - 2 * M - 60)

    y2 = 640
    card(d, (M, y2, W - M, y2 + 180), radius=18)
    T(d, (M + 28, y2 + 26), "邀请规则（合成）", 23, SUB)
    T(d, (M + 28, y2 + 70), "邀请码过期后无法再用于加入。", 22, INK2, max_w=W - 2 * M - 56)
    T(d, (M + 28, y2 + 114), "截图中的口令为演示值，不可实际使用。", 21, SUB, max_w=W - 2 * M - 56)

    d.rounded_rectangle([M, 872, W - M, 960], radius=44, fill=(240, 238, 250))
    T(d, (W / 2, 916), "查看群聊", 26, (96, 82, 196), anchor="mm")
    T(d, (M, 1010), "临时邀请码只在有效期内有效，过期后属于可清理候选。", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 1048), "历史邀请：11-01 已失效 / 10-29 已失效（合成）", 21, SUB, max_w=W - 2 * M)

    footer(d)
    return img, [p["kicker"], p["code"], p["valid"]]


def r_event_ticket(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((252, 250, 252), (242, 238, 244))
    status_bar(d)
    navbar(d, "抢票提醒")

    y = 186
    card(d, (M, y, W - M, y + 400), radius=22)
    d.rounded_rectangle([M + 30, y + 28, W - M - 30, y + 190], radius=16, fill=(64, 60, 92))
    T(d, (M + 54, y + 56), p["name"], 26, WHITE, bold=True, max_w=W - 2 * M - 108)
    T(d, (M + 54, y + 106), p["hall"], 22, (216, 214, 228), max_w=W - 2 * M - 108)
    T(d, (M + 54, y + 148), p["seat"], 22, (216, 214, 228), max_w=W - 2 * M - 108)
    T(d, (M + 30, y + 224), "开售时间", 23, SUB)
    T(d, (M + 30, y + 262), p["open"], 34, INK, bold=True, max_w=W - 2 * M - 60)
    pill(d, (M + 30, y + 322), p["state"], 26, (120, 124, 132), (236, 237, 242))

    y2 = 626
    card(d, (M, y2, W - M, y2 + 210), radius=18)
    T(d, (M + 28, y2 + 24), "提醒状态", 23, SUB)
    T(d, (M + 28, y2 + 66), p["hint"], 24, RED, bold=True, max_w=W - 2 * M - 56)
    T(d, (M + 28, y2 + 118), "该提醒由示例账号创建（合成）", 21, SUB, max_w=W - 2 * M - 56)

    d.rounded_rectangle([M, 900, W - M, 988], radius=44, fill=(226, 227, 232))
    T(d, (W / 2, 944), "去购票", 28, (132, 134, 142), anchor="mm")

    list_block(
        d,
        1030,
        "其他开售提醒",
        [("示例展览 · 合成场次", "11月4日 12:00 开售 · 已结束")],
        row_h=76,
    )

    footer(d)
    return img, [p["name"], p["open"], p["state"]]


def r_event_signup(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((249, 251, 253), (236, 241, 247))
    status_bar(d)
    navbar(d, "活动报名")

    y = 186
    card(d, (M, y, W - M, y + 336), radius=22)
    d.rounded_rectangle([M, y, W - M, y + 132], radius=22, fill=(46, 118, 190))
    d.rectangle([M, y + 100, W - M, y + 132], fill=(46, 118, 190))
    T(d, (M + 34, y + 40), p["name"], 30, WHITE, bold=True, max_w=W - 2 * M - 68)
    T(d, (M + 34, y + 96), p["sub"], 21, (222, 234, 248), max_w=W - 2 * M - 68)
    T(d, (M + 34, y + 164), "报名截止", 23, SUB)
    T(d, (M + 34, y + 202), p["dead_date"], 34, INK, bold=True, max_w=W - 2 * M - 68)
    pill(d, (M + 34, y + 258), p["state"], 26, (120, 124, 132), (236, 237, 242))

    y2 = 536
    card(d, (M, y2, W - M, y2 + 240), radius=18)
    T(d, (M + 28, y2 + 24), "报名信息", 23, SUB)
    for i, line in enumerate(p["rows"]):
        T(d, (M + 28, y2 + 68 + i * 42), line, 22, INK2, max_w=W - 2 * M - 56)

    d.rounded_rectangle([M, 820, W - M, 908], radius=44, fill=(226, 227, 232))
    T(d, (W / 2, 864), p["btn"], 26, (132, 134, 142), anchor="mm")

    T(d, (M, 948), "报名截止后表单自动关闭，截图只剩记录价值。", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 984), "相同活动的新一期请重新获取报名页。", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 1032), "类似活动：示例夜走活动 · 11-15 截止（已终止）", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 1068), "示例骑行活动 · 11-20 截止（已终止）", 21, SUB, max_w=W - 2 * M)

    footer(d)
    return img, [p["name"], f'报名截止 {p["dead_date"]}', p["state"]]


def r_event_vote(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((251, 251, 253), (239, 240, 246))
    status_bar(d)
    navbar(d, "投票")

    T(d, (M, 186), p["q"], 30, INK, bold=True, max_w=W - 2 * M)
    T(d, (M, 236), p["meta"], 21, SUB)
    pill(d, (M, 276), p["state"], 26, (120, 124, 132), (235, 236, 242))

    y = 356
    for label, pct in p["options"]:
        card(d, (M, y, W - M, y + 132), radius=16)
        T(d, (M + 26, y + 24), label, 25, INK, max_w=W - 2 * M - 140)
        d.rounded_rectangle([M + 26, y + 72, W - M - 26, y + 92], radius=10, fill=(238, 239, 244))
        d.rounded_rectangle([M + 26, y + 72, M + 26 + int((W - 2 * M - 52) * pct), y + 92], radius=10, fill=(150, 172, 214))
        T(d, (W - M - 26, y + 26), f"{int(pct * 100)}%", 24, SUB, anchor="ra")
        y += 152

    T(d, (M, y + 24), "投票结束后选项不可再修改。", 22, SUB, max_w=W - 2 * M)
    T(d, (M, y + 66), "结果页面在结束后只保留统计快照。", 22, SUB, max_w=W - 2 * M)

    d.rounded_rectangle([M, y + 130, W - M, y + 218], radius=44, fill=(238, 239, 243))
    T(d, (W / 2, y + 174), "查看统计详情", 26, (120, 122, 130), anchor="mm")

    footer(d)
    return img, [p["q"], p["state"], p["meta"]]


def r_order(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 250, 252), (238, 239, 243))
    status_bar(d)
    navbar(d, "订单详情")

    d.rectangle([M, 180, W - M, 320], fill=(40, 44, 52))
    d.rounded_rectangle([M, 180, W - M, 320], radius=18, fill=(40, 44, 52))
    T(d, (M + 34, 212), p["state"], 34, WHITE, bold=True)
    T(d, (M + 34, 264), p["tip"], 21, (196, 200, 210), max_w=W - 2 * M - 68)

    y = 356
    card(d, (M, y, W - M, y + 210), radius=18)
    d.rounded_rectangle([M + 26, y + 26, M + 126, y + 126], radius=14, fill=(226, 228, 234))
    T(d, (M + 76, y + 76), "商品", 20, (150, 152, 160), anchor="mm")
    T(d, (M + 148, y + 32), p["goods"], 25, INK, bold=True, max_w=W - 2 * M - 190)
    T(d, (M + 148, y + 78), p["spec"], 21, SUB, max_w=W - 2 * M - 190)
    T(d, (M + 148, y + 120), p["price"], 28, RED, bold=True)

    y2 = 596
    card(d, (M, y2, W - M, y2 + 292), radius=18)
    rows = kv_rows(d, y2 + 28, p["rows"])

    d.rounded_rectangle([M, 932, W - M, 1020], radius=44, fill=(238, 239, 243))
    T(d, (W / 2, 976), "查看物流", 26, (110, 112, 120), anchor="mm")
    T(d, (M, 1064), "订单号、金额与收货信息均为合成占位。", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 1102), "订单截图属于长期凭据，不应作为清理候选。", 21, INK2, max_w=W - 2 * M)

    footer(d)
    return img, [p["state"], rows[0], p["price"]]


def r_invoice(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 252, 251), (237, 243, 240))
    status_bar(d)
    navbar(d, "电子发票")

    y = 184
    card(d, (M, y, W - M, y + 420), radius=20)
    d.rectangle([M, y, W - M, y + 12], fill=(28, 146, 94))
    d.rounded_rectangle([M, y, W - M, y + 12], radius=6, fill=(28, 146, 94))
    T(d, (M + 30, y + 40), p["title"], 30, INK, bold=True)
    T(d, (M + 30, y + 88), p["buyer"], 22, SUB, max_w=W - 2 * M - 60)
    d.line([M + 30, y + 138, W - M - 30, y + 138], fill=LINE, width=1)
    T(d, (M + 30, y + 160), "开票金额", 22, SUB)
    T(d, (M + 30, y + 196), p["amount"], 44, INK, bold=True)
    T(d, (M + 30, y + 262), f'发票号码 {p["no"]}', 22, INK2, max_w=W - 2 * M - 60)
    T(d, (M + 30, y + 306), p["date"], 22, INK2, max_w=W - 2 * M - 60)
    qr(d, rnd, (W - M - 170, y + 250, W - M - 30, y + 390), 21)
    T(d, (M + 30, y + 356), "此发票为合成样张，不可用于报销。", 21, (28, 146, 94), max_w=W - 2 * M - 190)

    y2 = 640
    card(d, (M, y2, W - M, y2 + 230), radius=18)
    T(d, (M + 28, y2 + 24), "发票明细", 23, SUB)
    for i, line in enumerate(p["rows"]):
        T(d, (M + 28, y2 + 68 + i * 42), line, 22, INK2, max_w=W - 2 * M - 56)

    T(d, (M, 910), "发票属于长期有效凭证，建议保留。", 22, SUB, max_w=W - 2 * M)
    T(d, (M, 952), "发票与订单一起构成报销凭据，不属于临时信息。", 21, INK2, max_w=W - 2 * M)

    d.rounded_rectangle([M, 1024, W - M, 1112], radius=44, fill=(236, 244, 240))
    T(d, (W / 2, 1068), "下载 PDF 版", 26, (28, 146, 94), anchor="mm")

    footer(d)
    return img, [p["title"], f'开票金额 {p["amount"]}', f'发票号码 {p["no"]}']


def r_ticket(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 250, 253), (238, 239, 245))
    status_bar(d)
    navbar(d, p["kind"])

    y = 184
    card(d, (M, y, W - M, y + 500), radius=22)
    d.rounded_rectangle([M + 26, y + 26, W - M - 26, y + 150], radius=14, fill=(48, 52, 84))
    T(d, (M + 52, y + 50), p["place"], 26, WHITE, bold=True, max_w=W - 2 * M - 104)
    T(d, (M + 52, y + 96), p["sub"], 21, (206, 210, 232), max_w=W - 2 * M - 104)
    T(d, (M + 30, y + 178), p["seat"], 34, INK, bold=True, max_w=W - 2 * M - 60)
    T(d, (M + 30, y + 232), p["time"], 24, INK2)
    T(d, (M + 30, y + 278), p["row3"], 22, SUB, max_w=W - 2 * M - 220)
    qr(d, rnd, (W - M - 190, y + 236, W - M - 30, y + 396), 21)
    d.line([M + 30, y + 424, W - M - 30, y + 424], fill=LINE, width=1)
    T(d, (M + 30, y + 448), p["warn"], 24, RED, bold=True, max_w=W - 2 * M - 60)

    y2 = 716
    card(d, (M, y2, W - M, y2 + 190), radius=18)
    T(d, (M + 28, y2 + 24), "使用说明", 23, SUB)
    T(d, (M + 28, y2 + 68), "入场时出示本页二维码即可，无需换取纸质票。", 22, INK2, max_w=W - 2 * M - 56)
    T(d, (M + 28, y2 + 118), "票面信息为合成示例，二维码不可扫描。", 21, SUB, max_w=W - 2 * M - 56)

    d.rounded_rectangle([M, 962, W - M, 1050], radius=44, fill=(238, 239, 243))
    T(d, (W / 2, 1006), "添加到相册收藏", 26, (110, 112, 120), anchor="mm")
    T(d, (M, 1100), "票券截图在入场前始终有效，删除后无法补出。", 21, INK2, max_w=W - 2 * M)

    footer(d)
    return img, [p["place"], p["seat"], p["time"], p["warn"]]


def r_slide(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((255, 255, 255), (246, 247, 250))
    # 课件页：顶部标题条 + 公式块，模拟投影截图
    d.rectangle([0, 0, W, 128], fill=(36, 62, 116))
    T(d, (M, 22), "示例课程 · 合成讲义", 21, (176, 192, 222))
    T(d, (M, 54), "第 3 讲  特征值与特征向量", 31, WHITE, bold=True, max_w=W - 2 * M)
    d.rectangle([0, 128, W, 136], fill=(214, 168, 58))

    y = 186
    T(d, (M, y), p["chapter"], 30, (36, 62, 116), bold=True, max_w=W - 2 * M)
    y += 60
    d.rounded_rectangle([M, y, W - M, y + 190], radius=16, fill=(244, 246, 251), outline=(214, 220, 236), width=1)
    T(d, (W / 2, y + 60), p["formula"], 44, (30, 40, 60), bold=True, anchor="mm", max_w=W - 2 * M - 60)
    T(d, (W / 2, y + 140), p["formula2"], 32, (72, 82, 104), anchor="mm", max_w=W - 2 * M - 60)

    y += 240
    T(d, (M, y), "本节要点", 26, (36, 62, 116), bold=True)
    y += 52
    for i, line in enumerate(p["bullets"]):
        d.ellipse([M + 4, y + 12, M + 18, y + 26], fill=(214, 168, 58))
        T(d, (M + 36, y), line, 25, INK2, max_w=W - 2 * M - 36)
        y += 52

    T(d, (M, y + 12), "课件页属于知识型资料，没有时效性，应长期保留。", 21, (150, 156, 170), max_w=W - 2 * M)

    d.line([M, 1150, W - M, 1150], fill=(222, 226, 236), width=1)
    T(d, (M, 1170), p["page"], 22, (140, 146, 158))
    T(d, (W - M, 1170), "示例课件 · 合成内容", 20, (176, 180, 190), anchor="ra")

    return img, [p["chapter"], p["formula"], p["page"]]


def r_longterm(rnd: random.Random, s: dict):
    p = s["payload"]
    img, d = _screen((250, 250, 252), (238, 239, 244))
    status_bar(d)
    navbar(d, "我的图集")

    # 九宫格缩略图
    y0 = 172
    tw = (W - 2 * M - 20) // 3
    for i in range(9):
        r_, c_ = divmod(i, 3)
        x = M + c_ * (tw + 10)
        y = y0 + r_ * (tw + 10)
        shade = 200 + ((i * 37 + 11) % 46)
        d.rounded_rectangle([x, y, x + tw, y + tw], radius=10, fill=(shade, shade - 6, shade - 2))
        d.ellipse([x + tw * 0.34, y + tw * 0.28, x + tw * 0.66, y + tw * 0.6], fill=(shade - 42, shade - 44, shade - 38))

    y = y0 + 3 * (tw + 10) + 14
    card(d, (M, y, W - M, y + 186), radius=18)
    T(d, (M + 28, y + 24), p["title"], 27, INK, bold=True, max_w=W - 2 * M - 56)
    pill(d, (M + 28, y + 70), p["chip"], 24, (28, 146, 94), (230, 246, 238))
    T(d, (M + 28, y + 136), p["note"], 22, SUB, max_w=W - 2 * M - 56)

    d.rounded_rectangle([M, y + 214, W - M, y + 296], radius=44, fill=(232, 246, 238))
    T(d, (W / 2, y + 255), "长期保留 · 不会被自动清理", 26, (28, 146, 94), anchor="mm")
    T(d, (M, y + 330), "命中「已加入长期保留」→ 保护规则必须排除，即使存入很久。", 21, INK2, max_w=W - 2 * M)

    footer(d)
    return img, [p["title"], p["chip"], p["note"]]


def r_blurry(rnd: random.Random, s: dict):
    """真正读不清的灰字：文字形状存在但笔画已被抹掉。

    第一版用的是「低对比度 + 轻度模糊」（前景 222 / 背景 250，blur 1.2）。
    实测表明这**不够**：Tesseract 会先做 Otsu 二值化，把 8 级灰度差重新拉开，
    于是它仍以 0.93 置信度完整读出「满199减30 / 活动已于 2026-11-10 23:59 结束」
    （2026-11-25 设备实测）。本文件因此改为
    **对比度 4 级 + blur 7 + 竖向下采样再放大**：笔画宽度低于二值化能保留的下限。
    改动后重跑 verify_samples.py --record 更新确定性基线。
    """
    p = s["payload"]
    img = vgrad((W, H), (252, 252, 253), (247, 247, 249))
    d = ImageDraw.Draw(img)
    fg = (238, 238, 241)  # 与背景只差 ~12 级，且在模糊后落到同一灰阶

    y = 300
    for line in p["lines"]:
        for ln in wrap(d, line, 34, W - 2 * M):
            d.text((M, y), ln, font=F(30), fill=fg)
            y += 52

    # 先重度模糊抹掉笔画，再竖向下采样制造真实的「失焦+欠采样」损失
    img = img.filter(ImageFilter.GaussianBlur(7.0))
    small = img.resize((W // 6, H // 6), Image.BILINEAR)
    img = small.resize((W, H), Image.BILINEAR)
    img = img.filter(ImageFilter.GaussianBlur(2.0))
    return img, list(p["lines"])


def r_nowords(rnd: random.Random, s: dict):
    """纯照片式渐变，无任何文字。"""
    base = Image.new("RGB", (W, H), (22, 28, 42))
    d = ImageDraw.Draw(base)
    palette = [(46, 58, 92), (72, 96, 128), (120, 132, 148), (28, 36, 56), (96, 84, 120), (52, 78, 86)]
    for i in range(7):
        cx = rnd.randint(-80, W + 80)
        cy = rnd.randint(-80, H + 80)
        r = rnd.randint(220, 560)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=palette[i % len(palette)])
    base = base.filter(ImageFilter.GaussianBlur(72))
    # 轻微暗角
    vig = Image.new("L", (W, H), 0)
    vd = ImageDraw.Draw(vig)
    vd.ellipse([-W * 0.25, -H * 0.15, W * 1.25, H * 1.15], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(90))
    black = Image.new("RGB", (W, H), (10, 12, 18))
    base = Image.composite(base, black, vig)
    return base, []


def r_mixed(rnd: random.Random, s: dict):
    """同一张图里既有订单号又有「限时优惠已结束」→ 保护优先。"""
    p = s["payload"]
    img, d = _screen((250, 250, 252), (238, 239, 243))
    status_bar(d)
    navbar(d, "订单详情")

    d.rounded_rectangle([M, 176, W - M, 268], radius=14, fill=(255, 238, 236))
    T(d, (M + 26, 200), p["banner"], 28, RED, bold=True, max_w=W - 2 * M - 52)
    T(d, (M + 26, 240), "该优惠已于 2026-11-18 结束", 20, (188, 96, 92), max_w=W - 2 * M - 52)

    d.rounded_rectangle([M, 292, W - M, 420], radius=18, fill=(40, 44, 52))
    T(d, (M + 34, 316), p["state"], 32, WHITE, bold=True)
    T(d, (M + 34, 368), "预计 11-26 前送达（合成）", 21, (196, 200, 210))

    y = 444
    card(d, (M, y, W - M, y + 190), radius=18)
    d.rounded_rectangle([M + 26, y + 26, M + 122, y + 122], radius=14, fill=(226, 228, 234))
    T(d, (M + 74, y + 74), "商品", 20, (150, 152, 160), anchor="mm")
    T(d, (M + 144, y + 32), p["goods"], 24, INK, bold=True, max_w=W - 2 * M - 186)
    T(d, (M + 144, y + 78), p["spec"], 21, SUB, max_w=W - 2 * M - 186)
    T(d, (M + 144, y + 118), p["price"], 26, RED, bold=True)

    y2 = 664
    card(d, (M, y2, W - M, y2 + 200), radius=18)
    rows = kv_rows(d, y2 + 26, p["rows"])

    T(d, (M, 904), "订单与售后信息属于长期凭据，优惠文案过期不构成清理理由。", 21, SUB, max_w=W - 2 * M)
    T(d, (M, 942), "即便同一张图里出现过期文案，保护规则也应当优先命中。", 21, INK2, max_w=W - 2 * M)

    d.rounded_rectangle([M, 1014, W - M, 1102], radius=44, fill=(238, 239, 243))
    T(d, (W / 2, 1058), "申请售后", 26, (110, 112, 120), anchor="mm")

    footer(d)
    return img, [p["banner"], rows[0], p["state"]]


def r_rotate(rnd: random.Random, s: dict):
    """文字整体倾斜 15°，模拟翻拍 / 屏幕倾斜截图。"""
    p = s["payload"]
    img, d = _screen((252, 251, 249), (240, 238, 234))
    status_bar(d)
    navbar(d, "截图预览")

    layer = Image.new("RGBA", (W, 520), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.rounded_rectangle([60, 40, W - 60, 460], radius=22, fill=(255, 252, 248, 255), outline=(232, 226, 218, 255), width=2)
    ld.text((W / 2, 120), p["l1"], font=F(52), fill=(30, 30, 34, 255), anchor="mm",
            stroke_width=1, stroke_fill=(30, 30, 34, 255))
    ld.text((W / 2, 210), p["l2"], font=F(30), fill=(150, 82, 44, 255), anchor="mm")
    ld.text((W / 2, 300), p["l3"], font=F(27), fill=(206, 54, 54, 255), anchor="mm")
    ld.rectangle([120, 360, W - 120, 372], fill=(228, 222, 214, 255))

    rot = layer.rotate(15, resample=Image.BICUBIC, center=(W / 2, 260))
    img.paste(rot, (0, 360), rot)

    d = ImageDraw.Draw(img)
    T(d, (M, 980), "本页为倾斜拍摄的合成截图，文字方向约 15°。", 21, SUB, max_w=W - 2 * M)

    footer(d)
    return img, [p["l1"], p["l3"]]


# --------------------------------------------------------------------------------------
# 样本清单（50 张）
# --------------------------------------------------------------------------------------

SPECS: list[dict] = []


def add(**kw) -> None:
    SPECS.append(kw)


# ---- A. 过期优惠 / 活动 ---------------------------------------------------------------
add(file="coupon-expired-01.png", category="expired_coupon", expectation="recommend",
    must_keep_protected=False, saved=9, deadline="2026-11-10",
    signals=["coupon", "expired_date", "deadline"], render=r_coupon, sensitive=[],
    golden=["满199减30", "仅剩2天", "活动已于 2026-11-10 23:59 结束"],
    payload=dict(main="满199减30", sub="全场通用 · 示例商城", badge="仅剩2天",
                 status="活动已于 2026-11-10 23:59 结束"))

add(file="coupon-expired-02.png", category="expired_coupon", expectation="recommend",
    must_keep_protected=False, saved=15, deadline="2026-11-08",
    signals=["coupon", "expired_date", "deadline"], render=r_coupon, sensitive=[],
    golden=["满99减10", "仅剩3天", "活动已于 2026-11-08 23:59 结束"],
    payload=dict(main="满99减10", sub="生鲜专区 · 示例商城", badge="仅剩3天",
                 status="活动已于 2026-11-08 23:59 结束"))

add(file="coupon-expired-03.png", category="expired_coupon", expectation="recommend",
    must_keep_protected=False, saved=6, deadline="2026-11-12",
    signals=["coupon", "expired_date", "deadline"], render=r_coupon, sensitive=[],
    golden=["满299减50", "仅剩1天", "活动已于 2026-11-12 23:59 结束"],
    payload=dict(main="满299减50", sub="数码专区 · 示例商城", badge="仅剩1天",
                 status="活动已于 2026-11-12 23:59 结束"))

add(file="coupon-expired-04.png", category="expired_coupon", expectation="recommend",
    must_keep_protected=False, saved=4, deadline="2026-11-15",
    signals=["coupon", "expired_date", "deadline"], render=r_coupon, sensitive=[],
    golden=["新人立减 20", "仅剩2天", "活动已于 2026-11-15 23:59 结束"],
    payload=dict(main="新人立减 20", sub="首单可用 · 示例商城", badge="仅剩2天",
                 status="活动已于 2026-11-15 23:59 结束"))

add(file="promo-flash-01.png", category="expired_coupon", expectation="recommend",
    must_keep_protected=False, saved=21, deadline="2026-11-03",
    signals=["coupon", "deadline", "expired_date"], render=r_promo, sensitive=[],
    golden=["限时秒杀", "全场 5 折", "今日 24:00 截止", "活动截止 2026-11-03"],
    payload=dict(l1="限时秒杀", l2="全场 5 折", l3="今日 24:00 截止", l4="活动截止 2026-11-03"))

add(file="promo-flash-02.png", category="expired_coupon", expectation="recommend",
    must_keep_protected=False, saved=19, deadline="2026-11-05",
    signals=["coupon", "deadline", "expired_date"], render=r_promo, sensitive=[],
    golden=["限时秒杀", "整点开抢", "今日 24:00 截止", "活动截止 2026-11-05"],
    payload=dict(l1="限时秒杀", l2="整点开抢", l3="今日 24:00 截止", l4="活动截止 2026-11-05"))

add(file="coupon-oldprice-01.png", category="old_price", expectation="lower_priority",
    must_keep_protected=False, saved=32, deadline="",
    signals=["old_price"], render=r_oldprice, sensitive=[],
    golden=["历史价格对比", "历史低价 ¥199", "当前价格 ¥259", "价格走势（近 90 天）"],
    payload=dict(title="历史价格对比", sub="示例商城 · 自动比价记录", k1="历史低价",
                 v1="¥199", k2="当前价格", v2="¥259",
                 tag="仅有价格，没有截止日期", chart="价格走势（近 90 天）"))

add(file="coupon-oldprice-02.png", category="old_price", expectation="lower_priority",
    must_keep_protected=False, saved=37, deadline="",
    signals=["old_price"], render=r_oldprice, sensitive=[],
    golden=["历史价格对比", "历史低价 ¥88", "当前价格 ¥129", "价格走势（近 90 天）"],
    payload=dict(title="历史价格对比", sub="示例商城 · 自动比价记录", k1="历史低价",
                 v1="¥88", k2="当前价格", v2="¥129",
                 tag="旧比价截图，无活动期限", chart="价格走势（近 90 天）"))

# ---- B. 时效性新闻 -------------------------------------------------------------------
add(file="news-live-ended-01.png", category="stale_news", expectation="recommend",
    must_keep_protected=False, saved=13, deadline="",
    signals=["news", "live", "ended"], render=r_live, sensitive=[],
    golden=["直播预告 11月8日 20:00", "本场已结束", "回到直播间"],
    payload=dict(date="直播预告 11月8日 20:00", ended="本场已结束", btn="回到直播间",
                 title="示例主播 · 合成直播间", host="示例账号 · 关注 1.2 万（合成）",
                 status="本场已于 11月8日 21:36 结束"))

add(file="news-live-ended-02.png", category="stale_news", expectation="recommend",
    must_keep_protected=False, saved=12, deadline="",
    signals=["news", "live", "ended"], render=r_live, sensitive=[],
    golden=["直播预告 11月9日 19:30", "本场已结束", "回到直播间"],
    payload=dict(date="直播预告 11月9日 19:30", ended="本场已结束", btn="回到直播间",
                 title="示例分享会 · 合成场次", host="示例账号 · 关注 8,600（合成）",
                 status="本场已于 11月9日 20:48 结束"))

add(file="news-flash-old-01.png", category="stale_news", expectation="recommend",
    must_keep_protected=False, saved=25, deadline="",
    signals=["news", "expired_date"], render=r_flash, sensitive=[],
    golden=["地铁 3 号线延长运营时间", "2026-10-30 07:42"],
    payload=dict(time="2026-10-30 07:42", title="地铁 3 号线延长运营时间",
                 body="受活动影响，10 月 30 日晚间地铁 3 号线末班车延后 30 分钟。",
                 src="示例日报 · 合成快讯",
                 others=[("快讯｜示例城市发布寒潮提示", "2026-10-30 06:10"),
                         ("快讯｜周末两处公园临时闭园", "2026-10-29 21:05")]))

add(file="news-flash-old-02.png", category="stale_news", expectation="recommend",
    must_keep_protected=False, saved=27, deadline="",
    signals=["news", "expired_date"], render=r_flash, sensitive=[],
    golden=["示例城市发布寒潮提示", "2026-10-28 18:05"],
    payload=dict(time="2026-10-28 18:05", title="示例城市发布寒潮提示",
                 body="预计未来 48 小时气温下降 8 至 10 度，请注意添衣保暖。",
                 src="示例日报 · 合成快讯",
                 others=[("快讯｜部分公交线路临时调整", "2026-10-28 15:20"),
                         ("快讯｜本周空气质量预报", "2026-10-27 09:00")]))

_ART_B1 = "城市公共空间的三种功能"
_ART_B2 = "旧城改造中的步行网络"
_ART_B3 = "阅读笔记：照明设计与夜间街道"

add(file="news-article-01.png", category="keep", expectation="keep",
    must_keep_protected=False, saved=46, deadline="",
    signals=["news"], render=r_article, sensitive=[],
    golden=[_ART_B1, "示例杂志 · 深度阅读", "一、通行与停留"],
    payload=dict(title=_ART_B1, byline="示例杂志 · 深度阅读", section="一、通行与停留",
                 body=[ARTICLE_BODY[0], ARTICLE_BODY[1], ARTICLE_BODY[5]]))

add(file="news-article-02.png", category="keep", expectation="keep",
    must_keep_protected=False, saved=51, deadline="",
    signals=["news"], render=r_article, sensitive=[],
    golden=[_ART_B2, "示例杂志 · 城市观察", "二、街巷尺度"],
    payload=dict(title=_ART_B2, byline="示例杂志 · 城市观察", section="二、街巷尺度",
                 body=[ARTICLE_BODY[2], ARTICLE_BODY[3], ARTICLE_BODY[5]]))

add(file="news-article-03.png", category="keep", expectation="keep",
    must_keep_protected=False, saved=58, deadline="",
    signals=["news"], render=r_article, sensitive=[],
    golden=[_ART_B3, "示例账号 · 读书笔记", "三、照度与色温"],
    payload=dict(title=_ART_B3, byline="示例账号 · 读书笔记", section="三、照度与色温",
                 body=[ARTICLE_BODY[4], ARTICLE_BODY[1], ARTICLE_BODY[5]]))

# ---- C. 视频与文件分享 ---------------------------------------------------------------
add(file="share-expired-01.png", category="share", expectation="recommend",
    must_keep_protected=False, saved=20, deadline="",
    signals=["share", "extract_code", "expired_date", "link"], render=r_share_expired, sensitive=[],
    golden=["项目资料.zip", "提取码已过期", "复制链接", "https://pan.example.com/s/AbCd1234"],
    payload=dict(platform="示例网盘", name="项目资料.zip", size="24.6 MB · 合成文件",
                 status="提取码已过期", hint="重新生成的提取码仅在 24 小时内有效",
                 btn="复制链接", link="https://pan.example.com/s/AbCd1234",
                 note="分享页顶部显示「提取码已过期」，说明该链接已无法打开。"))

add(file="share-expired-02.png", category="share", expectation="recommend",
    must_keep_protected=False, saved=18, deadline="",
    signals=["share", "extract_code", "expired_date", "link"], render=r_share_expired, sensitive=[],
    golden=["季度汇报模板.pptx", "提取码已过期", "复制链接", "https://pan.example.com/s/XyZ98765"],
    payload=dict(platform="示例网盘", name="季度汇报模板.pptx", size="8.2 MB · 合成文件",
                 status="提取码已过期", hint="该分享已失效，请联系分享者重新发送",
                 btn="复制链接", link="https://pan.example.com/s/XyZ98765",
                 note="文件仍在列表中，但分享已失效，属于可清理候选。"))

add(file="share-link-01.png", category="share", expectation="uncertain",
    must_keep_protected=False, saved=16, deadline="",
    signals=["share", "link"], render=r_share_link, sensitive=[],
    golden=["团建照片合集", "示例网盘 · 相册", "复制链接"],
    payload=dict(platform="示例网盘", name="团建照片合集", meta="示例网盘 · 相册",
                 info="共 42 张 · 由示例账号分享（合成）"))

add(file="share-link-02.png", category="share", expectation="uncertain",
    must_keep_protected=False, saved=24, deadline="",
    signals=["share", "link"], render=r_share_link, sensitive=[],
    golden=["参考书目整理.pdf", "示例网盘 · 文档", "复制链接"],
    payload=dict(platform="示例网盘", name="参考书目整理.pdf", meta="示例网盘 · 文档",
                 info="共 26 页 · 由示例账号分享（合成）"))

add(file="share-cloud-01.png", category="share", expectation="recommend",
    must_keep_protected=False, saved=22, deadline="2026-11-01",
    signals=["share", "deadline", "expired_date"], render=r_share_cloud, sensitive=[],
    golden=["示例网盘 · 中转站", "有效期至 2026-11-01"],
    payload=dict(name="示例网盘 · 中转站", sub="中部转存 · 合成文件",
                 valid="有效期至 2026-11-01",
                 rows=[("链接类型", "中转分享"), ("转存状态", "未转存"), ("来源", "示例账号")]))

add(file="share-cloud-02.png", category="share", expectation="recommend",
    must_keep_protected=False, saved=26, deadline="2026-10-25",
    signals=["share", "deadline", "expired_date"], render=r_share_cloud, sensitive=[],
    golden=["示例网盘 · 中转站", "有效期至 2026-10-25"],
    payload=dict(name="示例网盘 · 中转站", sub="南部分片 · 合成文件",
                 valid="有效期至 2026-10-25",
                 rows=[("链接类型", "中转分享"), ("转存状态", "未转存"), ("来源", "示例账号")]))

add(file="share-justlink-01.png", category="uncertain", expectation="uncertain",
    must_keep_protected=False, saved=28, deadline="",
    signals=["share"], render=r_justlink, sensitive=[],
    golden=["示例网盘", "扫码查看文件"],
    payload=dict(line1="示例网盘", line2="扫码查看文件"))

# ---- D. 临时码（脱敏） ---------------------------------------------------------------
add(file="code-sms-01.png", category="temp_code", expectation="recommend",
    must_keep_protected=False, saved=3, deadline="",
    signals=["temp_code", "verification_code"], render=r_sms, sensitive=["123456"],
    golden=["【示例平台】验证码 123456，5分钟内有效。"],
    payload=dict(sender="1069****0000", text="【示例平台】验证码 123456，5分钟内有效。", time="10:24"))

add(file="code-sms-02.png", category="temp_code", expectation="recommend",
    must_keep_protected=False, saved=2, deadline="",
    signals=["temp_code", "verification_code"], render=r_sms, sensitive=["246810"],
    golden=["【示例平台】登录验证码 246810，10分钟内有效。"],
    payload=dict(sender="1069****0000", text="【示例平台】登录验证码 246810，10分钟内有效。", time="09:38"))

add(file="code-pickup-01.png", category="temp_code", expectation="recommend",
    must_keep_protected=False, saved=5, deadline="",
    signals=["temp_code", "pickup"], render=r_pickup, sensitive=["8-2-1024"],
    golden=["取件码 8-2-1024", "示例驿站 · 合成网点", "已取件"],
    payload=dict(code="取件码 8-2-1024", place="示例驿站 · 合成网点",
                 addr="示例省示例市示例路 0 号（合成地址）", state="已取件"))

add(file="code-pickup-02.png", category="temp_code", expectation="recommend",
    must_keep_protected=False, saved=7, deadline="",
    signals=["temp_code", "pickup"], render=r_pickup, sensitive=["2-3-0518"],
    golden=["取件码 2-3-0518", "示例驿站 · 合成网点", "已取件"],
    payload=dict(code="取件码 2-3-0518", place="示例驿站 · 合成网点",
                 addr="示例省示例市示例街 00 号（合成地址）", state="已取件"))

add(file="code-invite-01.png", category="temp_code", expectation="recommend",
    must_keep_protected=False, saved=11, deadline="2026-11-02",
    signals=["temp_code", "deadline"], render=r_invite, sensitive=["INV-8842"],
    golden=["临时入群邀请", "邀请码 INV-8842", "有效期至 2026-11-02"],
    payload=dict(kicker="临时入群邀请", code="邀请码 INV-8842", valid="有效期至 2026-11-02",
                 btn="复制邀请链接", hint="链接：https://example.com/invite/INV-8842"))

add(file="code-invite-02.png", category="temp_code", expectation="recommend",
    must_keep_protected=False, saved=9, deadline="2026-11-04",
    signals=["temp_code", "deadline"], render=r_invite, sensitive=["GX-2026-77"],
    golden=["临时入群邀请", "邀请码 GX-2026-77", "有效期至 2026-11-04"],
    payload=dict(kicker="临时入群邀请", code="邀请码 GX-2026-77", valid="有效期至 2026-11-04",
                 btn="复制邀请链接", hint="链接：https://example.com/invite/GX-2026-77"))

# ---- E. 已结束的报名 / 活动提醒 -------------------------------------------------------
add(file="event-ticket-01.png", category="event", expectation="recommend",
    must_keep_protected=False, saved=14, deadline="",
    signals=["event", "ticket", "ended"], render=r_event_ticket, sensitive=[],
    golden=["示例音乐会 · 合成场次", "11月5日 10:00 开售", "已结束"],
    payload=dict(name="示例音乐会 · 合成场次", hall="示例大剧院 · 音乐厅",
                 seat="看台 A 区 · 180 元档", open="11月5日 10:00 开售",
                 state="已结束", hint="该场次开售已结束，提醒不再生效"))

add(file="event-ticket-02.png", category="event", expectation="recommend",
    must_keep_protected=False, saved=12, deadline="",
    signals=["event", "ticket", "ended"], render=r_event_ticket, sensitive=[],
    golden=["示例话剧 · 合成场次", "11月6日 20:00 开售", "已结束"],
    payload=dict(name="示例话剧 · 合成场次", hall="示例剧场 · 二号厅",
                 seat="池座 B 区 · 280 元档", open="11月6日 20:00 开售",
                 state="已结束", hint="该场次开售已结束，提醒不再生效"))

add(file="event-signup-01.png", category="event", expectation="recommend",
    must_keep_protected=False, saved=16, deadline="2026-11-06",
    signals=["event", "deadline", "ended"], render=r_event_signup, sensitive=[],
    golden=["周末城市徒步报名", "报名截止 2026-11-06", "已截止"],
    payload=dict(name="周末城市徒步报名", sub="示例户外俱乐部 · 合成活动",
                 dead_date="2026-11-06", state="已截止",
                 rows=["名额：30 人（合成）", "集合：示例公园南门", "费用：免费"],
                 btn="报名已截止"))

add(file="event-signup-02.png", category="event", expectation="recommend",
    must_keep_protected=False, saved=13, deadline="2026-11-09",
    signals=["event", "deadline", "ended"], render=r_event_signup, sensitive=[],
    golden=["示例读书会报名", "报名截止 2026-11-09", "已截止"],
    payload=dict(name="示例读书会报名", sub="示例读书会 · 合成活动",
                 dead_date="2026-11-09", state="已截止",
                 rows=["名额：20 人（合成）", "地点：示例书店二层", "费用：免费"],
                 btn="报名已截止"))

add(file="event-vote-01.png", category="event", expectation="recommend",
    must_keep_protected=False, saved=17, deadline="",
    signals=["event", "ended"], render=r_event_vote, sensitive=[],
    golden=["群投票：下次聚会地点", "投票已结束", "共 128 人参与"],
    payload=dict(q="群投票：下次聚会地点", meta="共 128 人参与", state="投票已结束",
                 options=[("示例公园", 0.46), ("示例美术馆", 0.33), ("示例书店", 0.21)]))

add(file="event-vote-02.png", category="event", expectation="recommend",
    must_keep_protected=False, saved=19, deadline="",
    signals=["event", "ended"], render=r_event_vote, sensitive=[],
    golden=["问卷：产品体验回访", "问卷已结束", "共 64 人参与"],
    payload=dict(q="问卷：产品体验回访", meta="共 64 人参与", state="问卷已结束",
                 options=[("很满意", 0.52), ("一般", 0.31), ("不满意", 0.17)]))

# ---- F. 保护反例（keep） -------------------------------------------------------------
add(file="protect-order-01.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=30, deadline="",
    signals=["order", "order_no"], render=r_order, sensitive=[],
    golden=["待收货", "订单号 TB202411250001", "实付 ¥128.00"],
    payload=dict(state="待收货", tip="商家已发货，预计 11-27 前送达（合成）",
                 goods="示例商品 · 合成款", spec="颜色：示例灰 / 数量：1", price="实付 ¥128.00",
                 rows=[("订单号", "TB202411250001"), ("下单时间", "2026-11-20 14:02"),
                       ("收货人", "示例用户（合成）"), ("收货地址", "示例省示例市示例路 0 号")]))

add(file="protect-order-02.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=41, deadline="",
    signals=["order", "order_no", "phone"], render=r_order, sensitive=[],
    golden=["待收货", "订单号 TB202411250002", "实付 ¥256.00"],
    payload=dict(state="待收货", tip="包裹运输中，预计 11-28 前送达（合成）",
                 goods="示例商品 · 合成套装", spec="规格：两件装 / 数量：2", price="实付 ¥256.00",
                 rows=[("订单号", "TB202411250002"), ("下单时间", "2026-11-19 09:31"),
                       ("收货人", "示例用户（合成）"), ("联系电话", "138****0000")]))

add(file="protect-order-03.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=55, deadline="",
    signals=["order", "order_no"], render=r_order, sensitive=[],
    golden=["已完成", "订单号 TB202411250003", "实付 ¥59.90"],
    payload=dict(state="已完成", tip="订单已完成，售后有效期 15 天（合成）",
                 goods="示例商品 · 合成小件", spec="颜色：示例白 / 数量：1", price="实付 ¥59.90",
                 rows=[("订单号", "TB202411250003"), ("下单时间", "2026-11-02 20:15"),
                       ("收货人", "示例用户（合成）"), ("收货地址", "示例省示例市示例街 00 号")]))

add(file="protect-invoice-01.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=62, deadline="",
    signals=["invoice", "order_no"], render=r_invoice, sensitive=[],
    golden=["电子发票（普通发票）", "开票金额 ¥1,280.00", "发票号码 00000001（合成）"],
    payload=dict(title="电子发票（普通发票）", buyer="购买方：示例公司（合成）",
                 amount="¥1,280.00", no="00000001（合成）",
                 date="开票日期：2026-10-18",
                 rows=["项目：技术服务费（合成）", "税率：6%", "备注：本发票为合成样张"]))

add(file="protect-invoice-02.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=48, deadline="",
    signals=["invoice", "order_no"], render=r_invoice, sensitive=[],
    golden=["电子发票（普通发票）", "开票金额 ¥368.00", "发票号码 00000002（合成）"],
    payload=dict(title="电子发票（普通发票）", buyer="购买方：示例公司（合成）",
                 amount="¥368.00", no="00000002（合成）",
                 date="开票日期：2026-10-26",
                 rows=["项目：办公用品（合成）", "税率：13%", "备注：本发票为合成样张"]))

add(file="protect-ticket-01.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=33, deadline="",
    signals=["ticket"], render=r_ticket, sensitive=[],
    golden=["示例影院 · 3 号厅", "7 排 12 座", "2026-11-20 19:30", "请勿删除，入场需出示"],
    payload=dict(kind="电影票", place="示例影院 · 3 号厅", sub="影片：合成示例片",
                 seat="7 排 12 座", time="2026-11-20 19:30",
                 row3="取票码 0000 0000（合成）", warn="请勿删除，入场需出示"))

add(file="protect-ticket-02.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=35, deadline="",
    signals=["ticket"], render=r_ticket, sensitive=[],
    golden=["示例站 → 示例站", "08 车 12A 号", "2026-11-22 08:15", "请勿删除，检票需出示"],
    payload=dict(kind="行程信息", place="示例站 → 示例站", sub="G0000 次 · 二等座",
                 seat="08 车 12A 号", time="2026-11-22 08:15",
                 row3="票价 ¥0.00（合成）", warn="请勿删除，检票需出示"))

add(file="protect-slide-01.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=70, deadline="",
    signals=["courseware"], render=r_slide, sensitive=[],
    golden=["第三章 线性代数基础", "A·x = λ·x", "12 / 48"],
    payload=dict(chapter="第三章 线性代数基础", formula="A·x = λ·x", formula2="det(A − λI) = 0",
                 bullets=["特征值与特征向量的定义", "矩阵可对角化的条件", "典型例题与推导步骤"],
                 page="12 / 48"))

add(file="protect-slide-02.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=66, deadline="",
    signals=["courseware"], render=r_slide, sensitive=[],
    golden=["第四章 概率与统计", "P(A|B) = P(A∩B) / P(B)", "27 / 48"],
    payload=dict(chapter="第四章 概率与统计", formula="P(A|B) = P(A∩B) / P(B)",
                 formula2="E(X) = Σ xᵢ · p(xᵢ)",
                 bullets=["条件概率与独立性", "常见分布的期望与方差", "大数定律的直观解释"],
                 page="27 / 48"))

add(file="protect-longterm-01.png", category="keep", expectation="keep",
    must_keep_protected=True, saved=90, deadline="",
    signals=["long_term"], render=r_longterm, sensitive=[],
    golden=["示例相册 · 家庭合影", "已加入长期保留", "共 36 张（合成）"],
    payload=dict(title="示例相册 · 家庭合影", chip="已加入长期保留", note="共 36 张（合成）"))

# ---- G. 难例 -------------------------------------------------------------------------
add(file="hard-blurry-01.png", category="hard", expectation="uncertain",
    must_keep_protected=False, saved=21, deadline="",
    signals=[], render=r_blurry, sensitive=[],
    golden=["满199减30", "活动已于 2026-11-10 23:59 结束"],
    payload=dict(lines=["满199减30", "活动已于 2026-11-10 23:59 结束"]))

add(file="hard-blurry-02.png", category="hard", expectation="uncertain",
    must_keep_protected=False, saved=23, deadline="",
    signals=[], render=r_blurry, sensitive=[],
    golden=["限时秒杀", "今日 24:00 截止"],
    payload=dict(lines=["限时秒杀", "今日 24:00 截止"]))

add(file="hard-nowords-01.png", category="hard", expectation="uncertain",
    must_keep_protected=False, saved=8, deadline="",
    signals=[], render=r_nowords, sensitive=[], golden=[], payload=dict())

add(file="hard-nowords-02.png", category="hard", expectation="uncertain",
    must_keep_protected=False, saved=11, deadline="",
    signals=[], render=r_nowords, sensitive=[], golden=[], payload=dict())

add(file="hard-mixed-01.png", category="hard", expectation="keep",
    must_keep_protected=True, saved=19, deadline="2026-11-18",
    signals=["coupon", "expired_date", "order", "order_no"], render=r_mixed, sensitive=[],
    golden=["限时优惠已结束", "订单号 TB202411250007", "待收货"],
    payload=dict(banner="限时优惠已结束", state="待收货", goods="示例商品 · 合成订单",
                 spec="规格：单件 / 数量：1", price="实付 ¥256.00",
                 rows=[("订单号", "TB202411250007"), ("下单时间", "2026-11-18 11:07"),
                       ("售后状态", "可申请售后")]))

add(file="hard-rotate-01.png", category="hard", expectation="recommend",
    must_keep_protected=False, saved=10, deadline="2026-11-13",
    signals=["coupon", "expired_date", "deadline"], render=r_rotate, sensitive=[],
    golden=["满199减30", "活动已于 2026-11-13 23:59 结束"],
    payload=dict(l1="满199减30", l2="全场通用 · 示例商城",
                 l3="活动已于 2026-11-13 23:59 结束"))


# --------------------------------------------------------------------------------------
# 落盘
# --------------------------------------------------------------------------------------


def save_png(img: Image.Image, path: Path) -> tuple[int, str]:
    """优先原图直存；超限则逐级调色板量化，取第一个达标（且体积最小）的结果。"""
    best: bytes | None = None
    best_tag = "rgb"
    for colors, tag in ((None, "rgb"), (256, "p256"), (192, "p192"), (128, "p128"), (96, "p96"), (64, "p64")):
        if colors is None:
            im = img.convert("RGB")
        else:
            im = img.convert("RGB").quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        buf = io.BytesIO()
        im.save(buf, format="PNG", optimize=True)
        data = buf.getvalue()
        if best is None or len(data) < len(best):
            best, best_tag = data, tag
        if len(data) <= MAX_BYTES:
            path.write_bytes(data)
            return len(data), tag
    assert best is not None
    path.write_bytes(best)
    return len(best), best_tag


def check_golden_consistency(spec: dict) -> None:
    """金标准字段自检：词表、枚举、日期格式。"""
    allowed_cat = {
        "expired_coupon", "stale_news", "share", "temp_code", "event",
        "old_price", "keep", "uncertain", "hard",
    }
    allowed_exp = {"recommend", "lower_priority", "uncertain", "keep"}
    allowed_sig = {
        "coupon", "old_price", "expired_date", "deadline", "news", "live", "share",
        "extract_code", "temp_code", "verification_code", "pickup", "event", "ended",
        "order", "invoice", "ticket", "courseware", "long_term", "link", "phone", "order_no",
    }
    f = spec["file"]
    assert spec["category"] in allowed_cat, f"{f}: bad category {spec['category']}"
    assert spec["expectation"] in allowed_exp, f"{f}: bad expectation {spec['expectation']}"
    for sig in spec["signals"]:
        assert sig in allowed_sig, f"{f}: bad signal {sig}"
    assert isinstance(spec["must_keep_protected"], bool), f
    assert isinstance(spec["saved"], int) and spec["saved"] >= 0, f
    dl = spec["deadline"]
    assert dl == "" or (len(dl) == 10 and dl[4] == "-" and dl[7] == "-"), f"{f}: bad deadline {dl}"
    for tok in spec["sensitive"]:
        joined = "\n".join(spec["golden"])
        assert tok in joined, f"{f}: sensitive token {tok} 未出现在 golden_lines 中"


def main() -> int:
    if len(SPECS) != 50:
        print(f"[fatal] 样本数 {len(SPECS)} != 50", file=sys.stderr)
        return 2
    names = [s["file"] for s in SPECS]
    if len(set(names)) != len(names):
        print("[fatal] 文件名重复", file=sys.stderr)
        return 2

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for old in OUT_DIR.glob("*.png"):
        old.unlink()

    print(f"[generate] out_dir = {OUT_DIR}")
    rows = []
    for idx, spec in enumerate(SPECS, 1):
        check_golden_consistency(spec)
        seed = GLOBAL_SEED ^ zlib.crc32(spec["file"].encode("utf-8"))
        rnd = random.Random(seed)
        img, drawn = spec["render"](rnd, spec)
        if list(drawn) != list(spec["golden"]):
            print(f"[fatal] {spec['file']}: drawn={drawn} != golden={spec['golden']}", file=sys.stderr)
            return 2
        if img.size != (W, H):
            print(f"[fatal] {spec['file']}: size {img.size}", file=sys.stderr)
            return 2
        nbytes, tag = save_png(img, OUT_DIR / spec["file"])
        rows.append((spec, nbytes, tag))
        print(f"[{idx:3d}/50] {spec['file']:<28} {W}x{H}  {nbytes/1024:6.1f} KB  {tag}  {spec['category']}/{spec['expectation']}")

    payload = {
        "version": 1,
        "samples": [
            {
                "file": s["file"],
                "category": s["category"],
                "expectation": s["expectation"],
                "must_keep_protected": s["must_keep_protected"],
                "golden_lines": s["golden"],
                "expected_signals": s["signals"],
                "deadline_date": s["deadline"],
                "screenshot_saved_days_ago": s["saved"],
                "sensitive_tokens": s["sensitive"],
            }
            for s in SPECS
        ],
    }
    with open(GOLDEN_PATH, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    total = sum(r[1] for r in rows)
    print("-" * 78)
    print(f"[summary] files={len(rows)}  total={total/1024/1024:.3f} MB ({total} bytes)  "
          f"max={max(r[1] for r in rows)/1024:.1f} KB  avg={total/len(rows)/1024:.1f} KB")
    print(f"[summary] font={FONT_USED}  golden={GOLDEN_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
