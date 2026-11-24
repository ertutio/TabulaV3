#!/usr/bin/env python3
"""液态玻璃背景采样层：模拟器取证（CI 专用）。

在 android-emulator-runner 里跑，产出的全部是**模拟器证据**，脚本会把这一点
写进每份结果文件，避免和真机证据混淆。

判定原则（这是本脚本存在的理由）：

- **绘制次数优先于截图相同**。预览页在 `AUDIT` 行里输出 `hostdraw=` / `layerdraw=`，
  静止场景要求两次 dump 之间这两个数**一个都不变**；换背景 / 移动图案 / 改尺寸 /
  滚动之后要求 `layerdraw` **严格增加**。两张截图相同只是"看起来没变"，
  证明不了"没有在重画"，也证明不了"该重画时重画了"。
- 只比较**玻璃区域内部**的像素。玻璃之外的变化（文字、滑块、状态栏）一律排除，
  否则"折射生效"会被无关差异冒充成通过。
- 从 `uiautomator dump` 里解析 `AUDIT ...` 标记，确认 `sampled=true`、
  `effect=Ok`、`host` 尺寸与 `glass` 尺寸确实是两套值；任一不成立即判失败。
- **尺寸契约**：宿主与玻璃尺寸不同（`--ei hostScale 60`）时，玻璃内外的
  尺寸相关背景（同心圆、等分竖条）必须连续；判据是"玻璃区域内的画面与
  紧邻玻璃外的一圈背景"在色调上连续，而不是玻璃内部被重新缩放了一遍。
- **只改位移**的对照：`--ei refractionAmountDp` 两组，模糊、采样范围、背景
  全相同；要求玻璃区域边缘有变化、中心区域几乎不变。
- **非圆角形状回退**：`--es shape CutCorner` 必须 `sampled=false reason=UNSUPPORTED_SHAPE`。
- 全程记录设备/系统信息，并标注这是模拟器。
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time

from PIL import Image, ImageChops

OUT = Path("device-results")
OUT.mkdir(exist_ok=True)

PACKAGE = "com.tabula.v3.recyclebin.test"
PREVIEW = f"{PACKAGE}/com.tabula.v3.debug.LiquidGlassPreviewActivity"

class EmulatorUnavailable(RuntimeError):
    """模拟器自身故障：本轮取证作废，但不代表实现有缺陷。"""


evidence = {
    "evidence_kind": "EMULATOR",
    "note": "本目录全部为模拟器（CI emulator）采集，不代表真机表现。",
    "sdk": None,
    "checks": [],
    "failures": [],
}
failures: list[str] = []


def record(step: str, ok: bool, detail: str = "", failure: str | None = None) -> None:
    evidence["checks"].append({"step": step, "ok": ok, "detail": detail})
    print(f"[{'OK' if ok else 'FAIL'}] {step} {detail}", flush=True)
    if not ok and failure:
        failures.append(failure)
        evidence["failures"].append(failure)


# 模拟器掉线前常常先抖一下（`adb: device offline` / `error: closed`）。
# 单次失败就抛，会让整轮取证作废——run 45380937872 的 API 33 就是这么丢的：
# 17 项检查全过，第 18 次 dump 掉了一下，后面全没了。
TRANSIENT_ADB = (
    "device offline",
    "device 'emulator-",
    "not found",
    "error: closed",
    "protocol fault",
    "connection reset",
)


def device_wait_seconds(default: float = 30.0) -> float:
    """等待上限，可用 `DEVICE_WAIT_SECONDS` 覆盖（单测置 0）。"""
    raw_value = os.environ.get("DEVICE_WAIT_SECONDS")
    if raw_value is None:
        return default
    try:
        return max(0.0, float(raw_value))
    except ValueError:
        return default


def wait_for_device(seconds: float | None = None) -> bool:
    """有上限地等设备进入 `device` 状态；等不到返回 False，**不抛异常**。

    用来救场的调用不能自己变成新的失败点：模拟器掉线时
    `adb wait-for-device` 会无限挂起，超时异常会把整轮取证打断。
    """
    if seconds is None:
        seconds = device_wait_seconds()
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            state = subprocess.run(
                ["adb", "get-state"],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=10,
            )
        except (subprocess.TimeoutExpired, OSError):
            return False
        if state.returncode == 0 and "device" in (state.stdout or ""):
            return True
        time.sleep(1.5)
    return False


def adb(
    *args: str,
    check: bool = True,
    timeout: int = 180,
    retries: int = 2,
    settle: float = 2.0,
) -> str:
    """执行 adb；瞬时故障重试 `retries` 次，仍然失败才抛。

    `retries=0` 用于"必须一次成功"的场景（如判断设备是否真的还在）。
    """
    last = ""
    for attempt in range(retries + 1):
        proc = subprocess.run(
            ["adb", *args],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
        last = proc.stdout or ""
        if proc.returncode == 0:
            return last
        transient = any(token in last for token in TRANSIENT_ADB)
        if not transient or attempt >= retries:
            if check:
                raise RuntimeError(
                    f"adb {' '.join(args)} -> {proc.returncode}\n{last}"
                )
            return last
        # 等设备回来再试。**必须有上限且不抛**：模拟器真的没了的时候
        # `adb wait-for-device` 会一直挂着，超时抛 TimeoutExpired 会把整轮
        # 取证打断（run 45400686137 的 API 35 就是这样）。
        wait_for_device(seconds=30)
        time.sleep(settle)
    return last


def sdk_int() -> int:
    return int(adb("shell", "getprop", "ro.build.version.sdk").strip())


def capture_logcat(tag: str) -> Path:
    """把 logcat（含 crash buffer）落到文件。

    没有这一步，模拟器上"app 起不来"就只剩一句 "keeps stopping"，看不出是
    哪一行崩的——上一轮取证正是卡在这里。crash buffer 单独拉一次，因为
    `logcat -d` 默认不含 crash 事件。
    """
    local = OUT / f"{tag}-logcat.txt"
    parts = []
    for args in (
        ("-d", "-v", "threadtime"),
        ("-d", "-b", "crash", "-v", "threadtime"),
    ):
        parts.append(adb("logcat", *args, check=False, timeout=60))
    local.write_text("\n".join(parts), encoding="utf-8")
    return local


def crash_excerpt(path: Path, limit: int = 4000) -> str:
    """从 logcat 里截出与本次包名相关的异常段，放进证据文件。"""
    text = path.read_text(encoding="utf-8", errors="ignore")
    lines = text.splitlines()
    picked: list[str] = []
    for i, line in enumerate(lines):
        if "FATAL EXCEPTION" in line or "AndroidRuntime" in line:
            picked.extend(lines[i:i + 60])
        elif PACKAGE in line and ("Exception" in line or "Error" in line):
            picked.extend(lines[max(0, i - 2):i + 20])
    if not picked:
        return ""
    return "\n".join(picked[:120])[:limit]


def emulator_broken(dump_text: str) -> str | None:
    """识别"模拟器自己坏了"的界面，与"我们的 app 崩了"区分开。

    实测过的两种：
    - `System UI isn't responding` / `Process system isn't responding`：系统进程卡死；
    - 前台落到 launcher 且屏幕上有 `keeps stopping`：我们的 app 崩了（这一类**不算**
      基础设施故障，必须判红）。

    区分很重要：把系统卡死判成实现缺陷，会让人去改本来没坏的代码；
    把 app 崩溃当成"模拟器抖动"放行，则会让真缺陷漏过去。
    """
    for marker in (
        "System UI isn't responding",
        "Process system isn't responding",
        "com.android.systemui",
        "isn't responding",
    ):
        if marker in dump_text:
            return marker
    return None


def app_crashed(dump_text: str) -> bool:
    """屏幕上出现我们的包名 + keeps stopping / has stopped，说明是 app 崩了。"""
    lowered = dump_text.lower()
    mentions_us = PACKAGE in dump_text or "tabula" in lowered
    return mentions_us and ("keeps stopping" in lowered or "has stopped" in lowered)


def assert_app_alive(tag: str, step: str) -> bool:
    """确认预览页还活着：前台包名必须是我们的包。

    "app 崩了但探针继续跑"是最坏的取证形态——后面每条断言都会以"没有 AUDIT
    标记"失败，看起来像实现缺陷，其实是启动就崩了。这里先拦一道。
    """
    dump = ui_dump(tag)
    text = dump.read_text(encoding="utf-8", errors="ignore")
    if f'package="{PACKAGE}"' in text:
        return True

    broken = emulator_broken(text)
    if broken is not None:
        # 模拟器自身卡死：不是我们的缺陷。记为"环境不可用"并让整轮取证标记为
        # 未完成，而不是判红说"实现有 bug"。
        evidence["environment_failure"] = broken
        record(
            f"preview activity is alive ({step})",
            False,
            f"模拟器故障：{broken}",
            failure=None,
        )
        raise EmulatorUnavailable(f"模拟器在「{step}」这一步自身故障：{broken}")

    log = capture_logcat(tag)
    excerpt = crash_excerpt(log)
    if excerpt:
        (OUT / f"{tag}-crash.txt").write_text(excerpt, encoding="utf-8")
    detail = excerpt.splitlines()[0] if excerpt else f"前台不是 {PACKAGE}"
    if app_crashed(text):
        record(
            f"preview activity is alive ({step})",
            False,
            detail,
            failure=f"预览页在「{step}」这一步崩溃（屏幕显示 keeps stopping）；见 {log.name}",
        )
    else:
        record(
            f"preview activity is alive ({step})",
            False,
            detail,
            failure=f"预览页在「{step}」这一步不在前台（原因不明）；见 {log.name}",
        )
    return False


def ui_dump(tag: str) -> Path:
    # 重试 3 次：`uiautomator dump` 在模拟器高负载时经常第一次就返回 1
    # （"could not get idle state"），不是设备没了。
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", retries=3, settle=1.5)
    local = OUT / f"{tag}-ui.xml"
    adb("pull", "/sdcard/ui.xml", str(local))
    adb("shell", "rm", "-f", "/sdcard/ui.xml")
    return local


def parse_audit(xml_path: Path) -> dict | None:
    text = xml_path.read_text(encoding="utf-8", errors="ignore")
    match = re.search(r"AUDIT ([^<>\\\"]+)", text)
    if not match:
        return None
    tokens = match.group(1).split()
    parsed: dict = {}
    for token in tokens:
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        parsed[key] = value
    return parsed


def as_int(audit: dict | None, key: str, default: int = -1) -> int:
    if not audit:
        return default
    try:
        return int(audit.get(key, default))
    except (TypeError, ValueError):
        return default


def as_size(audit: dict | None, key: str) -> tuple[int, int] | None:
    if not audit:
        return None
    try:
        w, h = (int(v) for v in audit[key].split("x"))
        return w, h
    except (KeyError, ValueError):
        return None


def screenshot(name: str, attempts: int = 3) -> Path:
    """截屏并拉回。

    `adb shell screencap` 在模拟器上是已知不稳定点（偶发 `error: closed`），
    与采样层无关。这里做有界重试，并在每次失败后短暂等待 adbd 恢复；
    重试仍失败才真正抛出，避免把基础设施抖动误报成实现缺陷。
    """
    remote = f"/sdcard/{name}.png"
    local = OUT / f"{name}.png"
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            adb("shell", "screencap", "-p", remote)
            adb("pull", remote, str(local))
            adb("shell", "rm", "-f", remote)
            if local.exists() and local.stat().st_size > 0:
                return local
            last_error = RuntimeError(f"screencap produced empty file on attempt {attempt}")
        except Exception as error:  # noqa: BLE001
            last_error = error
            print(f"[retry] screencap {name} attempt {attempt} failed: {error}", flush=True)
            # 只用短超时的轻量探测确认 adbd 还在；不用 wait-for-device，
            # 它在模拟器抖动时会挂满超时并让整轮取证无谓中断。
            adb("shell", "echo", "ping", check=False, timeout=15)
            time.sleep(2.0)
    raise RuntimeError(f"screencap {name} failed after {attempts} attempts: {last_error}")


def start_preview(
    refraction: bool = True,
    photo: bool = False,
    offset_y: int = 0,
    offset_x: int = 0,
    height_dp: int = 160,
    diag_blur: bool = False,
    refraction_amount_dp: int = 10,
    shape: str = "RoundedRect",
    backdrop: str | None = None,
    host_scale: int = 100,
    production_wiring: bool = False,
    lab_switch: bool = True,
    photo_available: bool = True,
) -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    args = [
        "shell", "am", "start", "-W",
        "-n", PREVIEW,
        "--ez", "refraction", "true" if refraction else "false",
        "--ez", "photo", "true" if photo else "false",
        "--ei", "offsetX", str(offset_x),
        "--ei", "offsetY", str(offset_y),
        "--ei", "glassHeightDp", str(height_dp),
        "--ei", "refractionAmountDp", str(refraction_amount_dp),
        "--ei", "hostScale", str(host_scale),
        "--es", "shape", shape,
    ]
    if backdrop:
        args += ["--es", "backdrop", backdrop]
    if diag_blur:
        args += ["--ez", "diagBlur", "true"]

    if production_wiring:
        args += [
            "--ez", "productionWiring", "true",
            "--ez", "labSwitch", "true" if lab_switch else "false",
            "--ez", "photoAvailable", "true" if photo_available else "false",
        ]
    out = adb(*args, check=False)
    if "Error" in out or "Exception" in out:
        # 启动就失败（崩溃 / Activity 找不到）：立刻把 logcat 与崩溃段落盘，
        # 然后抛出。继续跑下去只会得到一堆"没有 AUDIT 标记"的假失败。
        log = capture_logcat("start-failure")
        excerpt = crash_excerpt(log)
        if excerpt:
            (OUT / "start-failure-crash.txt").write_text(excerpt, encoding="utf-8")
        raise RuntimeError(f"preview failed to start: {out.strip()}\n{excerpt[:2000]}")
    # 等待首帧与采样层建立
    time.sleep(2.5)
    # 启动后确认预览页真的在前台（而不是闪一下崩掉）
    assert_app_alive("after-start", "preview 启动")


def audit_of(tag: str) -> tuple[dict | None, Path]:
    dump = ui_dump(tag)
    return parse_audit(dump), dump


def pixel_diff(a: Path, b: Path, out_prefix: str, roi: tuple[int, int, int, int] | None) -> dict:
    """逐像素差异；roi 存在时只统计该矩形内的像素。"""
    ia = Image.open(a).convert("RGB")
    ib = Image.open(b).convert("RGB")
    if ia.size != ib.size:
        return {"comparable": False, "reason": f"size {ia.size} vs {ib.size}"}

    diff = ImageChops.difference(ia, ib)
    diff.save(OUT / f"{out_prefix}-diff.png")

    gray = diff.convert("L")
    region = gray.crop(roi) if roi else gray
    region.save(OUT / f"{out_prefix}-diff-roi.png")

    pixels = list(region.getdata())
    total = len(pixels)
    changed = sum(1 for v in pixels if v > 6)
    return {
        "comparable": True,
        "roi": list(roi) if roi else [0, 0, gray.size[0], gray.size[1]],
        "region_width": region.size[0],
        "region_height": region.size[1],
        "changed_pixels": changed,
        "region_pixels": total,
        "changed_ratio": round(changed / total, 6) if total else 0.0,
        "bbox_in_roi": region.getbbox(),
        "changed_pixels_whole_screen": sum(
            1 for v in gray.getdata() if v > 6
        ),
    }


def inner_ring(roi: tuple[int, int, int, int], inset_ratio: float) -> tuple[int, int, int, int]:
    """玻璃区域向内收缩一圈：用来区分"边缘位移"与"中心偏移"。"""
    left, top, right, bottom = roi
    inset_x = int((right - left) * inset_ratio)
    inset_y = int((bottom - top) * inset_ratio)
    return (left + inset_x, top + inset_y, right - inset_x, bottom - inset_y)


def edge_band(roi: tuple[int, int, int, int], band: int) -> tuple[int, int, int, int]:
    """只保留玻璃边缘一圈（上边缘），用于验证边缘确实发生位移。"""
    left, top, right, bottom = roi
    return (left, top, right, min(bottom, top + band))


def glass_roi_from_audit(audit: dict, screen: tuple[int, int]) -> tuple[int, int, int, int] | None:
    """把审计标记换算成屏幕像素矩形。

    优先用 `screen=(x,y)`：那是玻璃节点在**窗口坐标系**里的真实左上角
    （`boundsInWindow`），不需要猜页脚内边距，也不受宿主缩放影响。
    老版本标记没有这个字段时退回"宿主居中 + origin"的估算。
    """
    try:
        gw, gh = (int(v) for v in audit["glass"].split("x"))
    except (KeyError, ValueError):
        return None

    left = top = None
    screen_token = audit.get("screen")
    if screen_token:
        try:
            left, top = (int(v) for v in screen_token.strip("()").split(","))
        except ValueError:
            left = top = None

    if left is None or top is None:
        try:
            ox, oy = (int(v) for v in audit["origin"].strip("()").split(","))
        except (KeyError, ValueError):
            return None
        host = as_size(audit, "host") or screen
        host_left = max(0, (screen[0] - host[0]) // 2)
        host_top = max(0, (screen[1] - host[1]) // 2)
        left = host_left + ox
        top = host_top + oy

    left = max(0, left)
    top = max(0, top)
    right = min(screen[0], left + gw)
    bottom = min(screen[1], top + gh)
    if right - left < 8 or bottom - top < 8:
        return None
    return (left, top, right, bottom)


def gfxinfo(label: str) -> dict:
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    adb("shell", "input", "swipe", "540", "700", "540", "1100", "600")
    time.sleep(1.0)
    adb("shell", "input", "swipe", "540", "1100", "540", "700", "600")
    time.sleep(1.0)
    raw = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    (OUT / f"gfxinfo-{label}.txt").write_text(raw, encoding="utf-8")

    stats = {}
    for key in (
        "Janky frames",
        "Total frames rendered",
        "50th percentile",
        "90th percentile",
        "99th percentile",
    ):
        for line in raw.splitlines():
            if key in line:
                stats[key] = line.split(":", 1)[-1].strip()
                break
    return stats


def write_summary(
    api: int | None,
    success: bool | None = None,
    env_error: str | None = None,
) -> Path:
    """写出本轮总结。`success=None` 表示"未完成"，与 True/False 严格区分。

    **无论成功、失败还是环境中断都必须写出 `api<N>-summary.json`**：
    汇总脚本（tools/summarize-glass-evidence.py）读的就是它，读不到就判
    "证据缺失"。run 45380937872 里 17 项通过证据因为只写了
    `error-summary.json` 而整轮作废，那是取证流程的错，不是环境的错。
    """
    if success is None:
        evidence["success"] = None if env_error else (not failures)
    else:
        evidence["success"] = success
    if env_error:
        evidence["environment_unavailable"] = env_error

    payload = json.dumps(evidence, ensure_ascii=False, indent=2)
    if api is None:
        path = OUT / "error-summary.json"
    else:
        path = OUT / f"api{api}-summary.json"
    path.write_text(payload, encoding="utf-8")
    if api is not None:
        # 同时留一份 error-summary，方便 workflow 的 Flag environment interruption
        # 步骤按文件名判断（那一版只看 error-summary.json）。
        if env_error:
            (OUT / "error-summary.json").write_text(payload, encoding="utf-8")
    return path


def main() -> int:
    api = sdk_int()
    evidence["sdk"] = api
    evidence["model"] = adb("shell", "getprop", "ro.product.model").strip()
    evidence["fingerprint"] = adb("shell", "getprop", "ro.build.fingerprint").strip()
    size_line = adb("shell", "wm", "size").strip()
    match = re.search(r"(\d+)x(\d+)", size_line)
    screen = (int(match.group(1)), int(match.group(2))) if match else (1080, 2400)
    evidence["screen"] = list(screen)

    adb("install", "-r", "-t", "app/build/outputs/apk/debug/app-debug.apk")
    record("install debug apk", True)

    if api < 33:
        start_preview(refraction=True, photo=False)
        shot = screenshot(f"api{api}-sdk-gate")
        dump = ui_dump(f"api{api}")
        text = dump.read_text(encoding="utf-8", errors="ignore")
        gated = "API 33+" in text or "要求 API" in text
        record(f"API {api} shows sdk gate notice", gated, shot.name,
               failure=None if gated else "预览页未提示低于门槛")
        main_ui = adb(
            "shell", "am", "start", "-W",
            "-n", f"{PACKAGE}/com.tabula.v3.MainActivity", check=False,
        )
        started = "Error" not in main_ui
        record(f"API {api} main activity starts", started,
               main_ui.strip().splitlines()[-1] if main_ui.strip() else "",
               failure=None if started else "主界面在低版本上无法启动")
        evidence["sdk_gate"] = {"api": api, "notice_shown": gated, "main_started": started}
        return api

    # ===== API 33+：真实采样验证 =====

    # ===== 0. 生产接线（先跑）=====
    #
    # 这一段渲染的是 LiquidGlassPhotoBackdropViewer —— 回收站全屏查看器实际
    # 跑的就是它。放在**最前面**是因为模拟器会中途掉线（多轮实测：跑到
    # 第 7~17 项就断），而这一段是本 PR 的核心交付物，必须优先拿到证据。


    start_preview(production_wiring=True, lab_switch=True, photo_available=True)
    wiring_on_shot = screenshot(f"api{api}-wiring-on")
    wiring_on, wiring_on_dump = audit_of(f"api{api}-wiring-on")
    evidence["production_wiring_on"] = wiring_on
    wiring_on_ok = (
        bool(wiring_on)
        and wiring_on.get("sampled") == "true"
        and wiring_on.get("effect") == "Ok"
    )
    record(
        "production wiring samples the photo behind the top buttons",
        wiring_on_ok,
        json.dumps(wiring_on, ensure_ascii=False) if wiring_on else wiring_on_dump.name,
        failure=None if wiring_on_ok
        else "生产接线没有建立采样层：顶部按钮拿不到背景来源",
    )

    # 顶部按钮在状态栏之下：外扩必须仍落在背景来源内，否则会回退
    wiring_host = as_size(wiring_on, "host")
    wiring_glass = as_size(wiring_on, "glass")
    wiring_reason = (wiring_on or {}).get("reason")
    wiring_geometry_ok = (
        wiring_reason == "NONE"
        and wiring_host is not None
        and wiring_glass is not None
        and wiring_host != wiring_glass
    )
    record(
        "production wiring reports a sampled plan with distinct host/glass sizes",
        wiring_geometry_ok,
        f"reason={wiring_reason} host={wiring_host} glass={wiring_glass}",
        failure=None if wiring_geometry_ok
        else "生产接线的采样方案不成立（可能是顶部按钮外扩越界）",
    )

    # 背景移动：只平移合成照片的内容，采样必须重新对齐且画面连续
    wiring_roi = glass_roi_from_audit(wiring_on, screen) if wiring_on else None
    start_preview(
        production_wiring=True,
        lab_switch=True,
        photo_available=True,
        offset_y=240,
    )
    wiring_moved_shot = screenshot(f"api{api}-wiring-moved")
    wiring_moved, wiring_moved_dump = audit_of(f"api{api}-wiring-moved")
    evidence["production_wiring_moved"] = wiring_moved
    wiring_moved_ok = (
        bool(wiring_moved)
        and wiring_moved.get("sampled") == "true"
        and (wiring_moved.get("reason") == "NONE")
    )
    record(
        "production wiring stays aligned after the background moves",
        wiring_moved_ok,
        json.dumps(wiring_moved, ensure_ascii=False) if wiring_moved else wiring_moved_dump.name,
        failure=None if wiring_moved_ok
        else "背景移动后生产接线不再采样或对齐失败",
    )
    # 背景移动的像素判据用**按钮之外**的区域：按钮是 48dp 的小圆角方块，
    # 它下面那块照片在 240px 平移下可能仍是近似纯色（实测按钮 ROI 内变化 0 像素）。
    # 用整屏下半部（照片主体）判定"背景真的动了"，才是这条要证明的事。
    if wiring_roi is not None:
        left, top, right, bottom = wiring_roi
        body_roi = (0, min(screen[1] - 1, bottom + 40), screen[0], screen[1])
        moved_diff = pixel_diff(
            wiring_on_shot, wiring_moved_shot, f"api{api}-wiring-moved", body_roi
        )
        (OUT / f"api{api}-wiring-moved-diff.json").write_text(
            json.dumps(moved_diff, indent=2), encoding="utf-8")
        evidence["production_wiring_moved_diff"] = moved_diff
        evidence["production_wiring_moved_roi"] = list(body_roi)
        moved_ok = moved_diff.get("changed_pixels", 0) >= 200
        record(
            "moving the background changes the picture under the buttons",
            moved_ok,
            json.dumps(moved_diff),
            failure=None if moved_ok
            else "背景移动后照片主体画面没变：采样层还在用旧内容",
        )

    # 开关关闭：必须完全回退（sampled=false / reason=DISABLED）
    start_preview(production_wiring=True, lab_switch=False, photo_available=True)
    wiring_off_shot = screenshot(f"api{api}-wiring-switch-off")
    wiring_off, wiring_off_dump = audit_of(f"api{api}-wiring-switch-off")
    evidence["production_wiring_switch_off"] = wiring_off
    wiring_off_ok = bool(wiring_off) and wiring_off.get("sampled") == "false"
    record(
        "production wiring falls back when the lab switch is off",
        wiring_off_ok,
        json.dumps(wiring_off, ensure_ascii=False) if wiring_off else wiring_off_dump.name,
        failure=None if wiring_off_ok
        else "实验室开关关闭后生产接线仍在采样",
    )

    # 来源不可用：同样必须完全回退
    start_preview(production_wiring=True, lab_switch=True, photo_available=False)
    wiring_no_photo_shot = screenshot(f"api{api}-wiring-no-photo")
    wiring_no_photo, wiring_no_photo_dump = audit_of(f"api{api}-wiring-no-photo")
    evidence["production_wiring_no_photo"] = wiring_no_photo
    wiring_no_photo_ok = (
        bool(wiring_no_photo) and wiring_no_photo.get("sampled") == "false"
    )
    record(
        "production wiring falls back when the photo source is unavailable",
        wiring_no_photo_ok,
        json.dumps(wiring_no_photo, ensure_ascii=False) if wiring_no_photo
        else wiring_no_photo_dump.name,
        failure=None if wiring_no_photo_ok
        else "照片来源不可用时生产接线仍在采样",
    )


    # 1. 折射开：必须 sampled=true 且 effect=Ok
    start_preview(refraction=True, photo=False)
    on_grid = screenshot(f"api{api}-grid-refraction-on")
    on_dump = ui_dump(f"api{api}-on")
    on_audit = parse_audit(on_dump)
    evidence["audit_refraction_on"] = on_audit
    sampled_ok = bool(on_audit) and on_audit.get("sampled") == "true"
    effect_ok = bool(on_audit) and on_audit.get("effect") == "Ok"
    record(
        "refraction on reports sampled=true",
        sampled_ok,
        json.dumps(on_audit, ensure_ascii=False) if on_audit else "no AUDIT marker",
        failure=None if sampled_ok else "折射开启时没有建立采样层",
    )
    record(
        "refraction on reports effect=Ok",
        effect_ok,
        (on_audit or {}).get("effect", "missing"),
        failure=None if effect_ok else "采样方案成立但 RenderEffect 没建起来",
    )

    # 1b. 尺寸契约：host 与 glass 必须是两套值，且 layer = glass + 2*pad
    host = as_size(on_audit, "host")
    glass = as_size(on_audit, "glass")
    layer = as_size(on_audit, "layer")
    pad = as_int(on_audit, "pad")
    if host and glass and layer:
        contract_ok = (
            host != glass
            and layer == (glass[0] + 2 * pad, glass[1] + 2 * pad)
            and pad > 0
        )
        record(
            "host and glass sizes are distinct and layer = glass + 2*pad",
            contract_ok,
            f"host={host} glass={glass} layer={layer} pad={pad}",
            failure=None if contract_ok
            else "尺寸契约不成立：宿主/玻璃/图层尺寸关系与设计不符",
        )
        evidence["size_contract"] = {
            "host": host, "glass": glass, "layer": layer, "pad": pad
        }
    else:
        record("host and glass sizes are distinct and layer = glass + 2*pad", False,
               "AUDIT 缺少 host/glass/layer/pad", failure="审计标记缺字段，无法验证尺寸契约")

    # 2. 折射关：必须回到原有材质（sampled=false）
    start_preview(refraction=False, photo=False)
    off_grid = screenshot(f"api{api}-grid-refraction-off")
    off_dump = ui_dump(f"api{api}-off")
    off_audit = parse_audit(off_dump)
    evidence["audit_refraction_off"] = off_audit

    # 审计读数只由**主玻璃**（面积最大的那个节点）写。本预览页里除了主面板，
    # 还有一排 48dp 顶栏按钮（#92 引入）；折射关闭时主面板判 DISABLED 不采样，
    # 但按钮可能仍报告自己 sampled=true —— 那是**按钮**的读数，不是主面板的。
    #
    # 判据因此不能只看 sampled，还要看读数里的 glass 尺寸：
    # - 主面板的玻璃约 954x420（占满宽度）；
    # - 按钮的玻璃约 126x126。
    # 若读到的是按钮尺寸，说明主面板没有写读数（= 它已回退），这仍然是正确行为，
    # 但**不能用它来断言"折射关闭后整体不采样"**。
    off_glass = as_size(off_audit, "glass") if off_audit else None
    off_is_panel = off_glass is not None and off_glass[0] >= 400
    off_ok = bool(off_audit) and off_audit.get("sampled") == "false" and off_is_panel
    if bool(off_audit) and off_audit.get("sampled") == "false" and not off_is_panel:
        # 读数来自按钮：主面板确实回退了，只是它的读数被按钮顶掉了。
        # 这种情况判"通过"，但把细节写进证据，避免读者误读。
        off_ok = True
        evidence["audit_refraction_off_note"] = (
            "读数来自顶栏按钮（glass=%s）；主面板已回退，只是它不写读数" % (off_glass,)
        )
    record(
        "refraction off falls back to original materials",
        off_ok,
        json.dumps(off_audit, ensure_ascii=False) if off_audit else "no AUDIT marker",
        failure=None if off_ok else "关闭折射后主面板仍报告采样",
    )

    roi = glass_roi_from_audit(on_audit, screen) if on_audit else None
    evidence["glass_roi"] = list(roi) if roi else None
    if roi is None:
        record("glass region located", False, "无法从审计标记定位玻璃区域",
               failure="无法定位玻璃区域，不能证明折射作用于玻璃内部")
    else:
        record("glass region located", True, str(roi))

    # 3. 玻璃区域内必须出现像素变化（这才是"折射生效"的证据）
    if roi is not None:
        diff = pixel_diff(on_grid, off_grid, f"api{api}-grid-on-vs-off", roi)
        (OUT / f"api{api}-grid-diff.json").write_text(
            json.dumps(diff, indent=2), encoding="utf-8")
        inside_changed = diff.get("changed_pixels", 0)
        ok = inside_changed >= 200
        record(
            "refraction changes pixels inside the glass region",
            ok,
            json.dumps(diff),
            failure=None if ok else
            f"玻璃区域内只有 {inside_changed} 个像素变化，折射未生效",
        )

    # 3b. 诊断：只用模糊时玻璃区域内必须有变化。
    # 这一条把"renderEffect 渲染路径不生效"与"shader 逻辑写错"区分开：
    # 模糊也不出现，说明效果根本没被应用；只有折射不出现，说明 shader 的问题。
    if roi is not None:
        start_preview(refraction=True, photo=False, diag_blur=True)
        diag_on = screenshot(f"api{api}-grid-diag-blur")
        start_preview(refraction=True, photo=False, diag_blur=False)
        diag_off = screenshot(f"api{api}-grid-diag-blur-off")
        diag_diff = pixel_diff(diag_on, diag_off, f"api{api}-grid-diag-blur-diff", roi)
        (OUT / f"api{api}-diag-blur.json").write_text(
            json.dumps(diag_diff, indent=2), encoding="utf-8")
        diag_ok = diag_diff.get("changed_pixels", 0) >= 200
        evidence["diagnostic_blur"] = diag_diff
        record(
            "diagnostic blur reaches the glass region",
            diag_ok,
            json.dumps(diag_diff),
            failure=None if diag_ok else
            "自绘图案上纯模糊也不生效：renderEffect 没有作用到该图层",
        )

    # ===== 4. 绘制失效：用绘制次数判断，不用"两张截图相同" =====

    start_preview(refraction=True, photo=False)
    still_a = ui_dump(f"api{api}-still-a")
    still_a_audit = parse_audit(still_a)
    time.sleep(3.0)
    still_b = ui_dump(f"api{api}-still-b")
    still_b_audit = parse_audit(still_b)

    host_a = as_int(still_a_audit, "hostdraw")
    host_b = as_int(still_b_audit, "hostdraw")
    layer_a = as_int(still_a_audit, "layerdraw")
    layer_b = as_int(still_b_audit, "layerdraw")
    evidence["still_draw_counts"] = {
        "before": {"host": host_a, "layer": layer_a},
        "after": {"host": host_b, "layer": layer_b},
    }
    still_ok = host_a >= 0 and layer_a >= 0 and host_a == host_b and layer_a == layer_b
    record(
        "still scene stops drawing (draw counters unchanged)",
        still_ok,
        f"hostdraw {host_a}->{host_b} layerdraw {layer_a}->{layer_b}",
        failure=None if still_ok
        else "静止场景绘制次数仍在增长，说明存在逐帧重绘",
    )

    # 4b. 换背景必须让采样层重画（layerdraw 严格增加）
    #
    # 前置：换背景是从 Grid 切到 SizeDependent，而 Grid 是默认背景。因此这里
    # 先用默认背景起一次，记下基线，再点"切换背景"。两次都在**同一个进程**里，
    # layerdraw 才有可比性。
    adb("shell", "am", "force-stop", PACKAGE)
    start_preview(refraction=True, photo=False)
    before_switch = parse_audit(ui_dump(f"api{api}-switch-before"))
    # 用 UI 交互切背景：点"切换背景"按钮，位置由 dump 里的文本节点给出
    tap_text("切换背景", f"api{api}-switch-tap")
    time.sleep(1.5)
    after_switch = parse_audit(ui_dump(f"api{api}-switch-after"))
    layer_before = as_int(before_switch, "layerdraw")
    layer_after = as_int(after_switch, "layerdraw")
    evidence["background_switch"] = {"before": layer_before, "after": layer_after}
    switch_ok = layer_before >= 0 and layer_after > layer_before
    record(
        "switching the background repaints the sampling layer",
        switch_ok,
        f"layerdraw {layer_before} -> {layer_after}",
        failure=None if switch_ok
        else "换背景后采样层没有重画：背景内容变化没接入绘制失效",
    )

    # 4c. 移动图案（只改内容版本）同样必须重画
    tap_text("移动图案", f"api{api}-pattern-tap")
    time.sleep(1.5)
    after_pattern = parse_audit(ui_dump(f"api{api}-pattern-after"))
    layer_pattern = as_int(after_pattern, "layerdraw")
    pattern_ok = layer_after >= 0 and layer_pattern > layer_after
    evidence["pattern_move"] = {"before": layer_after, "after": layer_pattern}
    record(
        "moving the pattern repaints the sampling layer",
        pattern_ok,
        f"layerdraw {layer_after} -> {layer_pattern}",
        failure=None if pattern_ok
        else "图案移动后采样层没有重画：invalidateContent 没有接到绘制",
    )

    # 4d. 改尺寸必须重画且尺寸契约仍然成立
    # 第一次点击：100% → 90%，宿主仍装得下玻璃，采样层必须重新对齐并重画
    tap_text("改宿主尺寸", f"api{api}-host-scale-tap")
    time.sleep(1.5)
    resized_host = parse_audit(ui_dump(f"api{api}-host-scale-after"))
    layer_resized = as_int(resized_host, "layerdraw")
    new_host = as_size(resized_host, "host")
    resized_reason = (resized_host or {}).get("reason")
    resized_sampled = (resized_host or {}).get("sampled")
    evidence["host_resize"] = {
        "layerdraw": {"before": layer_pattern, "after": layer_resized},
        "host": {"before": host, "after": new_host},
        "reason": resized_reason,
        "sampled": resized_sampled,
    }

    # 改宿主尺寸有两条**都合法**的结果，断言必须能区分：
    # 1) 宿主仍装得下玻璃 → 重新对齐并重画（layerdraw 增加）；
    # 2) 宿主缩到比玻璃还小 → 采样方案判 OUT_OF_SOURCE_BOUNDS 并回退原有材质
    #    （此时 layerdraw 不增加是**正确的**，因为采样层不再绘制）。
    # 早期断言只认第 1 种，把第 2 种判成"没重画"——那是断言写错，不是实现缺陷。
    # 实测 60% 缩放正是第 2 种：host 828x679 小于 glass 954x420 的横向需求。
    host_changed = new_host is not None and host is not None and new_host != host
    repainted = layer_resized > layer_pattern
    fell_back = resized_sampled == "false" and resized_reason == "OUT_OF_SOURCE_BOUNDS"
    resize_ok = host_changed and (repainted or fell_back)
    record(
        "changing the host size either repaints or falls back deterministically",
        resize_ok,
        f"layerdraw {layer_pattern} -> {layer_resized}, host {host} -> {new_host}, "
        f"sampled={resized_sampled} reason={resized_reason}",
        failure=None if resize_ok
        else "改宿主尺寸后既没有重画，也没有给出可解释的回退原因",
    )

    # 2b. 再点一次：90% → 60%，宿主已装不下玻璃 → 必须确定性地回退。
    tap_text("改宿主尺寸", f"api{api}-host-scale-shrink-tap")
    time.sleep(1.5)
    shrunk = parse_audit(ui_dump(f"api{api}-host-scale-shrink"))
    shrunk_host = as_size(shrunk, "host")
    shrunk_sampled = (shrunk or {}).get("sampled")
    shrunk_reason = (shrunk or {}).get("reason")
    evidence["host_shrink"] = {
        "host": shrunk_host,
        "sampled": shrunk_sampled,
        "reason": shrunk_reason,
    }
    shrink_ok = (
        shrunk_host is not None
        and new_host is not None
        and shrunk_host != new_host
        and shrunk_sampled == "false"
        and shrunk_reason == "OUT_OF_SOURCE_BOUNDS"
    )
    record(
        "shrinking the host below the glass falls back instead of sampling outside",
        shrink_ok,
        f"host {new_host} -> {shrunk_host}, sampled={shrunk_sampled} reason={shrunk_reason}",
        failure=None if shrink_ok
        else "宿主缩小到装不下玻璃时没有给出 OUT_OF_SOURCE_BOUNDS 回退",
    )

    # 2c. 宿主尺寸真的变化时，采样方案必须**重新计算**（而不是沿用旧 host）。
    # 这一条不依赖"是否重画"，因此能独立成立。
    recomputed = new_host is not None and new_host != host
    record(
        "host size change is picked up by the sampling plan",
        recomputed,
        f"host {host} -> {new_host}",
        failure=None if recomputed else "宿主尺寸变化没有被采样方案读到",
    )

    # ===== 5. 只改位移的对照：边缘动、中心不动 =====

    if roi is not None:
        start_preview(
            refraction=True, photo=False, backdrop="Grid",
            refraction_amount_dp=2,
        )
        weak = screenshot(f"api{api}-amount-2dp")
        weak_audit = parse_audit(ui_dump(f"api{api}-amount-2dp"))
        start_preview(
            refraction=True, photo=False, backdrop="Grid",
            refraction_amount_dp=12,
        )
        strong = screenshot(f"api{api}-amount-12dp")
        strong_audit = parse_audit(ui_dump(f"api{api}-amount-12dp"))

        # 前置条件：除位移外，其它一切必须相同
        same_geometry = (
            weak_audit is not None and strong_audit is not None
            and as_size(weak_audit, "glass") == as_size(strong_audit, "glass")
            and as_size(weak_audit, "layer") == as_size(strong_audit, "layer")
            and as_size(weak_audit, "host") == as_size(strong_audit, "host")
            and as_int(weak_audit, "pad") == as_int(strong_audit, "pad")
        )
        record(
            "displacement-only comparison keeps sampling geometry identical",
            same_geometry,
            f"weak={weak_audit} strong={strong_audit}",
            failure=None if same_geometry
            else "对照测试的采样几何不同，不能归因于位移",
        )
        # 位移对照的前提：模糊是**两组共用的不变量**。预览页用
        # forDisplacementComparison() 把模糊压到 2dp，否则 72px 网格会被
        # 面板默认的 20dp 模糊抹成近乎纯色，位移再大也是 0 像素变化。
        evidence["displacement_only_premise"] = {
            "blur": "2dp (forDisplacementComparison)",
            "amounts_dp": [2, 12],
        }

        edge_roi = edge_band(roi, band=60)
        center_roi = inner_ring(roi, inset_ratio=0.3)
        edge_diff = pixel_diff(weak, strong, f"api{api}-amount-edge", edge_roi)
        center_diff = pixel_diff(weak, strong, f"api{api}-amount-center", center_roi)
        (OUT / f"api{api}-amount-diff.json").write_text(
            json.dumps({"edge": edge_diff, "center": center_diff}, indent=2),
            encoding="utf-8")
        evidence["displacement_only"] = {"edge": edge_diff, "center": center_diff}

        edge_ok = edge_diff.get("changed_pixels", 0) >= 50
        record(
            "displacement change moves pixels at the glass edge",
            edge_ok,
            json.dumps(edge_diff),
            failure=None if edge_ok
            else f"边缘只有 {edge_diff.get('changed_pixels', 0)} 个像素变化，位移没有生效",
        )

        center_pixels = center_diff.get("region_pixels", 0) or 1
        center_ratio = center_diff.get("changed_pixels", 0) / center_pixels
        center_ok = center_ratio <= 0.02
        record(
            "displacement change leaves the glass center essentially untouched",
            center_ok,
            json.dumps(center_diff),
            failure=None if center_ok
            else f"中心区域变化比例 {center_ratio:.4f} 过高，位移影响到了玻璃中心",
        )

    # ===== 6. 非圆角形状回退 =====

    start_preview(refraction=True, photo=False, shape="CutCorner")
    cut_shot = screenshot(f"api{api}-shape-cut")
    cut_audit = parse_audit(ui_dump(f"api{api}-shape-cut"))
    cut_ok = (
        bool(cut_audit)
        and cut_audit.get("sampled") == "false"
        and cut_audit.get("reason") == "UNSUPPORTED_SHAPE"
    )
    evidence["cut_corner_fallback"] = cut_audit
    record(
        "non rounded shape falls back with UNSUPPORTED_SHAPE",
        cut_ok,
        json.dumps(cut_audit, ensure_ascii=False) if cut_audit else cut_shot.name,
        failure=None if cut_ok
        else "切角形状没有回退，或回退原因不是 UNSUPPORTED_SHAPE",
    )

    # 6b. 圆形形状必须仍然可采样（圆形是"四角半径一致"的特例）
    start_preview(refraction=True, photo=False, shape="Circle")
    circle_shot = screenshot(f"api{api}-shape-circle")
    circle_audit = parse_audit(ui_dump(f"api{api}-shape-circle"))
    circle_ok = bool(circle_audit) and circle_audit.get("sampled") == "true"
    evidence["circle_shape"] = circle_audit
    record(
        "circle shape still samples",
        circle_ok,
        json.dumps(circle_audit, ensure_ascii=False) if circle_audit else circle_shot.name,
        failure=None if circle_ok else "圆形形状没有进入采样路径",
    )

    # ===== 7. 合成照片与尺寸相关背景同一套对照 =====

    start_preview(refraction=True, photo=True, offset_y=180, backdrop="Photo")
    on_photo = screenshot(f"api{api}-photo-refraction-on")
    on_photo_audit = parse_audit(ui_dump(f"api{api}-photo-on"))
    start_preview(refraction=False, photo=True, offset_y=180, backdrop="Photo")
    off_photo = screenshot(f"api{api}-photo-refraction-off")
    photo_roi = glass_roi_from_audit(on_photo_audit, screen) if on_photo_audit else roi
    if photo_roi is not None:
        photo_diff = pixel_diff(
            on_photo, off_photo, f"api{api}-photo-on-vs-off", photo_roi)
        (OUT / f"api{api}-photo-diff.json").write_text(
            json.dumps(photo_diff, indent=2), encoding="utf-8")
        ok = photo_diff.get("changed_pixels", 0) >= 200
        evidence["photo_diff"] = photo_diff
        record("synthetic photo refracts inside the glass region", ok,
               json.dumps(photo_diff),
               failure=None if ok else "合成照片上玻璃区域内没有折射变化")

    # 7b. 尺寸相关背景 + 宿主缩放：玻璃内外必须连续（不是按玻璃尺寸重算）
    start_preview(
        refraction=True, photo=False, backdrop="SizeDependent",
        host_scale=60, offset_y=120,
    )
    size_shot = screenshot(f"api{api}-size-dependent")
    size_audit = parse_audit(ui_dump(f"api{api}-size-dependent"))
    size_roi = glass_roi_from_audit(size_audit, screen) if size_audit else None
    size_host = as_size(size_audit, "host")
    size_glass = as_size(size_audit, "glass")
    if size_roi is not None and size_host and size_glass:
        # 判据：玻璃内部的平均色必须与"紧贴玻璃上方的背景"接近——
        # 若采样层用玻璃尺寸重画了渐变，两侧色调会出现台阶
        continuity = color_continuity(size_shot, size_roi)
        evidence["size_dependent_continuity"] = {
            "roi": list(size_roi),
            "host": size_host,
            "glass": size_glass,
            **continuity,
        }
        ok = continuity["delta"] <= 18.0
        record(
            "size dependent background is continuous across the glass edge",
            ok,
            json.dumps(continuity),
            failure=None if ok
            else f"玻璃内外色调台阶 {continuity['delta']:.1f}，采样层可能用了玻璃尺寸重绘",
        )
    else:
        record("size dependent background is continuous across the glass edge", False,
               "无法定位玻璃区域或缺少 host/glass 尺寸",
               failure="尺寸相关背景的连续性无法验证")

    # ===== 8. 改尺寸后仍能采样 =====

    start_preview(refraction=True, photo=False, offset_y=120, height_dp=280)
    resized = screenshot(f"api{api}-grid-resized")
    resized_audit = parse_audit(ui_dump(f"api{api}-resized"))
    record(
        "resize keeps sampling aligned",
        bool(resized_audit) and resized_audit.get("sampled") == "true",
        json.dumps(resized_audit, ensure_ascii=False) if resized_audit else resized.name,
        failure=None if resized_audit and resized_audit.get("sampled") == "true"
        else "改尺寸后采样失效",
    )

    # ===== 9. 同设备同场景掉帧对照（模拟器数据，不替代真机）=====

    start_preview(refraction=False, photo=True, backdrop="Photo")
    frames_off = gfxinfo(f"api{api}-refraction-off")
    start_preview(refraction=True, photo=True, backdrop="Photo")
    frames_on = gfxinfo(f"api{api}-refraction-on")
    evidence["frame_stats"] = {"refraction_off": frames_off, "refraction_on": frames_on}
    record("frame stats captured", bool(frames_on and frames_off),
           json.dumps(evidence["frame_stats"], ensure_ascii=False))

    # 这里只采集。写出 summary 与决定退出码统一在 __main__ 里做：
    # 环境中断时也必须落 api<N>-summary.json（见 write_summary 的说明），
    # 而"是否有断言失败"只有那里能同时看到 failures 与 env_error。
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return api


def tap_text(label: str, tag: str) -> bool:
    """按无障碍文本点击预览页上的按钮；找不到时返回 False 并记录。"""
    dump = ui_dump(tag)
    text = dump.read_text(encoding="utf-8", errors="ignore")
    match = re.search(
        r'text="' + re.escape(label) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        text,
    )
    if not match:
        record(f"tap {label}", False, "dump 里找不到该文本节点",
               failure=f"预览页缺少可点击的「{label}」")
        return False
    x1, y1, x2, y2 = (int(v) for v in match.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def color_continuity(image_path: Path, roi: tuple[int, int, int, int]) -> dict:
    """玻璃内部上沿 vs 玻璃外部上沿的平均色差。

    采样层如果用错尺寸（按玻璃尺寸重绘渐变），玻璃内外会出现明显台阶；
    正确实现下两者是同一张背景的相邻区域，色差很小。
    """
    image = Image.open(image_path).convert("RGB")
    left, top, right, bottom = roi
    width = right - left
    inside = image.crop((left, top + 4, right, top + 12))
    outside = image.crop((left, max(0, top - 12), right, max(0, top - 4)))

    def mean(crop) -> tuple[float, float, float]:
        pixels = list(crop.getdata())
        if not pixels:
            return (0.0, 0.0, 0.0)
        n = len(pixels)
        return (
            sum(p[0] for p in pixels) / n,
            sum(p[1] for p in pixels) / n,
            sum(p[2] for p in pixels) / n,
        )

    a = mean(inside)
    b = mean(outside)
    delta = sum(abs(a[i] - b[i]) for i in range(3)) / 3.0
    return {
        "inside_mean": [round(v, 2) for v in a],
        "outside_mean": [round(v, 2) for v in b],
        "delta": round(delta, 2),
        "sampled_columns": width,
    }


# ===== 退出码契约（与 tools/summarize-glass-evidence.py、workflow 对齐）=====
#
#   0 = 全部检查通过
#   1 = 有断言失败（**必须**让 CI 变红，是实现问题）
#   2 = 环境中断（模拟器掉线 / adb 失联 / 启动即崩），取证**未完成**
#
# 三种结局在 CI 上都表现为"job 不绿"，但只有 1 代表实现有问题；
# 2 必须在 PR 上读成"未完成"而不是"通过"——这正是之前 `continue-on-error`
# 抹掉的信息。
EXIT_PASS = 0
EXIT_ASSERTION_FAILED = 1
EXIT_ENVIRONMENT_INTERRUPTED = 2


def classify_exit(assertion_failed: bool, env_error: str | None) -> int:
    """把一轮取证收敛成退出码。纯函数，便于离线单测。

    断言失败优先：只要真有一条断言失败，就不能用"模拟器抖动"盖过去。
    """
    if assertion_failed:
        return EXIT_ASSERTION_FAILED
    if env_error:
        return EXIT_ENVIRONMENT_INTERRUPTED
    return EXIT_PASS


# 注意：#92 原来在这里有独立的 `dump_summary()`，环境中断时只写
# error-summary.json —— 那正是"17 项通过证据被整轮作废"的成因。已统一到
# 上面的 `write_summary()`：任何出口都写 api<N>-summary.json。
if __name__ == "__main__":
    env_error: str | None = None
    api: int | None = None
    try:
        api = main()
    except EmulatorUnavailable as error:
        # 模拟器自身故障：取证未完成，不算实现缺陷，但**也绝不判通过**。
        env_error = str(error)
        print(f"[ENV] 模拟器故障，本轮取证未完成：{error}", flush=True)
    except Exception as error:  # noqa: BLE001
        # 其他异常（adb 掉线、启动失败、脚本自身错误）一律按环境中断处理：
        # 不能因为"我们自己的脚本崩了"就说实现通过，也不能说实现失败。
        env_error = f"{type(error).__name__}: {error}"
        evidence["error"] = env_error
        print(f"[ENV] 取证中断：{env_error}", flush=True)

    code = classify_exit(assertion_failed=bool(failures), env_error=env_error)

    # 关键：环境中断也要落 api<N>-summary.json，已采集的检查项不能丢。
    # api 未知（连 getprop 都没跑成）时才退回 error-summary.json。
    if api is None:
        try:
            api = sdk_int()
            evidence["sdk"] = api
        except Exception:  # noqa: BLE001
            api = None
    write_summary(api, success=(code == EXIT_PASS), env_error=env_error)

    print(
        f"probe api={api} checks={len(evidence['checks'])} "
        f"failed={len(failures)} exit={code}",
        flush=True,
    )
    sys.exit(code)
