#!/usr/bin/env python3
"""液态玻璃背景采样层：模拟器取证（CI 专用）。

在 android-emulator-runner 里跑，产出的全部是**模拟器证据**，脚本会把这一点
写进每份结果文件，避免和真机证据混淆。

判定原则（这是本脚本存在的理由）：

- 只比较**玻璃区域内部**的像素。玻璃之外的变化（文字、滑块、状态栏）一律排除，
  否则"折射生效"会被无关差异冒充成通过。
- 从 `uiautomator dump` 里解析预览页打印的 `AUDIT ...` 标记，确认
  `sampled=true` 且 `effect=Ok`；任一不成立即判失败，不再靠像素差异猜测。
- 静止场景要求两张截图逐像素一致（`changed_pixels == 0`）。
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

# 玻璃节点在预览页里的位置由页脚控件决定；用 UI dump 里的 audited 值反推，
# 拿不到时退回按布局常量估算（24dp 水平内边距，40dp 顶部内边距）。
FALLBACK_GLASS_INSET_X_DP = 24
FALLBACK_GLASS_TOP_DP = 40

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
    match = re.search(r"AUDIT ([^<>\"]+)", text)
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
            adb("wait-for-device", check=False)
            time.sleep(2.0)
    raise RuntimeError(f"screencap {name} failed after {attempts} attempts: {last_error}")


def start_preview(
    refraction: bool, photo: bool, offset_y: int = 0, height_dp: int = 160
) -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    adb(
        "shell", "am", "start", "-W",
        "-n", PREVIEW,
        "--ez", "refraction", "true" if refraction else "false",
        "--ez", "photo", "true" if photo else "false",
        "--ei", "offsetY", str(offset_y),
        "--ei", "glassHeightDp", str(height_dp),
        *(["--ez", "diagBlur", "true"] if diag_blur else []),
    )
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


def glass_roi_from_audit(audit: dict, screen: tuple[int, int]) -> tuple[int, int, int, int] | None:
    """把审计标记里的 origin/glass 尺寸换算成屏幕像素矩形。"""
    try:
        gw, gh = (int(v) for v in audit["glass"].split("x"))
        ox, oy = (int(v) for v in audit["origin"].strip("()").split(","))
    except (KeyError, ValueError):
        return None
    # origin 是"相对背景来源"的原点；背景来源从屏幕 0,0 起铺满，
    # 因此配合页脚的固定内边距即可得到屏幕坐标。
    left = max(0, ox)
    top = max(0, oy)
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
        start_preview(refraction=False, photo=False)
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
            "连纯模糊都不生效：renderEffect 渲染路径本身没有作用到该图层",
        )

    # 4. 静止场景两张截图必须逐像素一致（不持续刷新）
    start_preview(refraction=False, photo=False)
    off_again = screenshot(f"api{api}-grid-refraction-off-again")
    stable = pixel_diff(off_grid, off_again, f"api{api}-grid-stability", roi=None)
    (OUT / f"api{api}-grid-stability.json").write_text(
        json.dumps(stable, indent=2), encoding="utf-8")
    still = stable.get("changed_pixels_whole_screen", -1) == 0
    record("still scene does not keep repainting", still, json.dumps(stable),
           failure=None if still else "静止画面持续变化，说明存在逐帧刷新")

    # 5. 合成照片同一套对照
    start_preview(refraction=True, photo=True, offset_y=180)
    on_photo = screenshot(f"api{api}-photo-refraction-on")
    on_photo_audit = parse_audit(ui_dump(f"api{api}-photo-on"))
    start_preview(refraction=False, photo=True, offset_y=180)
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

    # 6. 改尺寸后仍能采样
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

    # 7. 同设备同场景掉帧对照
    start_preview(refraction=False, photo=True)
    frames_off = gfxinfo(f"api{api}-refraction-off")
    start_preview(refraction=True, photo=True)
    frames_on = gfxinfo(f"api{api}-refraction-on")
    evidence["frame_stats"] = {"refraction_off": frames_off, "refraction_on": frames_on}
    record("frame stats captured", bool(frames_on and frames_off),
           json.dumps(evidence["frame_stats"], ensure_ascii=False))

    write_summary(api)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # noqa: BLE001
        evidence["error"] = f"{type(error).__name__}: {error}"
        (OUT / "error-summary.json").write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        raise
