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


def adb(*args: str, check: bool = True, timeout: int = 180) -> str:
    proc = subprocess.run(
        ["adb", *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} -> {proc.returncode}\n{proc.stdout}")
    return proc.stdout


def sdk_int() -> int:
    return int(adb("shell", "getprop", "ro.build.version.sdk").strip())


def ui_dump(tag: str) -> Path:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
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
    refraction: bool,
    photo: bool,
    offset_y: int = 0,
    offset_x: int = 0,
    height_dp: int = 160,
    diag_blur: bool = False,
    refraction_amount_dp: int = 10,
    shape: str = "RoundedRect",
    backdrop: str | None = None,
    host_scale: int = 100,
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
    adb(*args)
    # 等待首帧与采样层建立
    time.sleep(2.5)


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


def write_summary(api: int) -> None:
    evidence["success"] = not failures
    (OUT / f"api{api}-summary.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8"
    )


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
        write_summary(api)
        return 0 if not failures else 1

    # ===== API 33+：真实采样验证 =====

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
    off_ok = bool(off_audit) and off_audit.get("sampled") == "false"
    record(
        "refraction off falls back to original materials",
        off_ok,
        json.dumps(off_audit, ensure_ascii=False) if off_audit else "no AUDIT marker",
        failure=None if off_ok else "关闭折射后仍报告采样",
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
    adb("shell", "am", "force-stop", PACKAGE)
    start_preview(refraction=True, photo=False, backdrop="SizeDependent")
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
    tap_text("改宿主尺寸", f"api{api}-host-scale-tap")
    time.sleep(1.5)
    resized_host = parse_audit(ui_dump(f"api{api}-host-scale-after"))
    layer_resized = as_int(resized_host, "layerdraw")
    new_host = as_size(resized_host, "host")
    resize_ok = (
        layer_resized > layer_pattern
        and new_host is not None
        and host is not None
        and new_host != host
    )
    evidence["host_resize"] = {
        "layerdraw": {"before": layer_pattern, "after": layer_resized},
        "host": {"before": host, "after": new_host},
    }
    record(
        "changing the host size repaints and re-aligns",
        resize_ok,
        f"layerdraw {layer_pattern} -> {layer_resized}, host {host} -> {new_host}",
        failure=None if resize_ok
        else "改宿主尺寸后采样层没有重画或对齐参数没更新",
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

    write_summary(api)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


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


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # noqa: BLE001
        evidence["error"] = f"{type(error).__name__}: {error}"
        (OUT / "error-summary.json").write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        raise
