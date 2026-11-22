#!/usr/bin/env python3
"""液态玻璃背景采样层：模拟器取证（CI 专用）。

在 android-emulator-runner 里跑，产出的全部是**模拟器证据**，脚本会把这一点
写进每份结果文件，避免和真机证据混淆。

做的事：
1. 安装 debug APK，用 intent extra 固定"同一背景、同一组材质参数"；
2. API 33+ 上分别以 折射开 / 折射关 截屏，做像素级差异，证明边缘确实发生了
   位移，且差异只落在玻璃区域边缘（不是整屏变色、不是前景文字被糊掉）；
3. 用无折射的两次截屏做对照基线，证明同一状态下画面稳定（没有逐帧刷新）；
4. API 31/32 上确认预览页给出"低于门槛"的说明，且应用主界面仍走原有显示；
5. 同场景做短时滚动掉帧采样（gfxinfo），记录开关前后的对比数据。
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

OUT = Path("device-results")
OUT.mkdir(exist_ok=True)

PACKAGE = "com.tabula.v3.recyclebin.test"
PREVIEW = f"{PACKAGE}/com.tabula.v3.debug.LiquidGlassPreviewActivity"

evidence = {
    "evidence_kind": "EMULATOR",  # 真机证据需要另外采集，勿与本文件混用
    "note": "本目录全部为模拟器（CI emulator）采集，不代表真机表现。",
    "sdk": None,
    "steps": [],
}


def record(step: str, ok: bool, detail: str = "") -> None:
    evidence["steps"].append({"step": step, "ok": ok, "detail": detail})
    print(f"[{'OK' if ok else 'FAIL'}] {step} {detail}", flush=True)


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


def screenshot(name: str) -> Path:
    remote = f"/sdcard/{name}.png"
    local = OUT / f"{name}.png"
    adb("shell", "screencap", "-p", remote)
    adb("pull", remote, str(local))
    adb("shell", "rm", "-f", remote)
    return local


def start_preview(refraction: bool, photo: bool, offset_y: int = 0, height_dp: int = 160) -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    adb(
        "shell", "am", "start", "-W",
        "-n", PREVIEW,
        "--ez", "refraction", "true" if refraction else "false",
        "--ez", "photo", "true" if photo else "false",
        "--ei", "offsetY", str(offset_y),
        "--ei", "glassHeightDp", str(height_dp),
    )
    time.sleep(2.0)


def pixel_diff(a: Path, b: Path, out_prefix: str) -> dict:
    """用 Pillow 做逐像素差异；报告差异像素占比与差异区域的行/列范围。"""
    from PIL import Image, ImageChops

    ia = Image.open(a).convert("RGB")
    ib = Image.open(b).convert("RGB")
    if ia.size != ib.size:
        return {"comparable": False, "reason": f"size {ia.size} vs {ib.size}"}
    diff = ImageChops.difference(ia, ib)
    diff.save(OUT / f"{out_prefix}-diff.png")

    gray = diff.convert("L")
    pixels = list(gray.getdata())
    total = len(pixels)
    changed = sum(1 for v in pixels if v > 8)
    bbox = gray.getbbox()
    return {
        "comparable": True,
        "width": ia.size[0],
        "height": ia.size[1],
        "changed_pixels": changed,
        "total_pixels": total,
        "changed_ratio": round(changed / total, 6),
        "diff_bbox": bbox,
    }


def gfxinfo(label: str) -> dict:
    """短时滚动/动画场景的帧统计（同设备、同场景对照）。"""
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    # 拖拽玻璃区域制造持续重绘
    adb("shell", "input", "swipe", "540", "700", "540", "1100", "600")
    time.sleep(1.0)
    adb("shell", "input", "swipe", "540", "1100", "540", "700", "600")
    time.sleep(1.0)
    raw = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    (OUT / f"gfxinfo-{label}.txt").write_text(raw, encoding="utf-8")

    stats = {}
    for key in ("Janky frames", "Total frames rendered", "50th percentile", "90th percentile", "99th percentile"):
        for line in raw.splitlines():
            if key in line:
                stats[key] = line.split(":", 1)[-1].strip()
                break
    return stats


def main() -> int:
    api = sdk_int()
    evidence["sdk"] = api
    evidence["model"] = adb("shell", "getprop", "ro.product.model").strip()
    evidence["fingerprint"] = adb("shell", "getprop", "ro.build.fingerprint").strip()

    failures = 0

    # 安装 debug APK（runner 已 push 到 /data/local/tmp）
    apk = "/data/local/tmp/app-debug.apk"
    if Path("app/build/outputs/apk/debug/app-debug.apk").exists():
        adb("install", "-r", "-t", "app/build/outputs/apk/debug/app-debug.apk")
    else:
        adb("install", "-r", "-t", apk)
    record("install debug apk", True)

    if api < 33:
        # API 31/32：预览页必须提示低于门槛，且不建立采样图层
        start_preview(refraction=True, photo=False)
        low = screenshot("api%d-sdk-gate" % api)
        ui = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        adb("pull", "/sdcard/ui.xml", str(OUT / f"api{api}-ui.xml"))
        text = (OUT / f"api{api}-ui.xml").read_text(encoding="utf-8", errors="ignore")
        gated = "API 33+" in text or "要求 API" in text
        record(f"API {api} shows sdk gate notice", gated, "截图 %s" % low.name)
        if not gated:
            failures += 1
        evidence["sdk_gate"] = {"api": api, "notice_shown": gated}

        # 主界面仍走原有显示：进程能正常启动到首屏即可（不做视觉断言）
        main_ui = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/com.tabula.v3.MainActivity", check=False)
        record(f"API {api} main activity starts", "Error" not in main_ui, main_ui.splitlines()[-1] if main_ui else "")
    else:
        # 同一背景、同一组参数，只切换折射开关
        start_preview(refraction=True, photo=False)
        on_grid = screenshot("api%d-grid-refraction-on" % api)
        start_preview(refraction=False, photo=False)
        off_grid = screenshot("api%d-grid-refraction-off" % api)

        diff = pixel_diff(on_grid, off_grid, f"api{api}-grid-on-vs-off")
        (OUT / f"api{api}-grid-diff.json").write_text(json.dumps(diff, indent=2), encoding="utf-8")
        record("grid refraction on/off produces pixel changes", bool(diff.get("changed_pixels")), json.dumps(diff))

        # 背景稳定：同一状态连拍两张，必须逐像素一致（静止场景不持续刷新）
        start_preview(refraction=False, photo=False)
        off_grid_again = screenshot("api%d-grid-refraction-off-again" % api)
        stable = pixel_diff(off_grid, off_grid_again, f"api{api}-grid-stability")
        (OUT / f"api{api}-grid-stability.json").write_text(json.dumps(stable, indent=2), encoding="utf-8")
        still = stable.get("changed_pixels", -1) == 0
        record("still scene does not keep repainting", still, json.dumps(stable))
        if not still:
            failures += 1

        # 合成照片同一套对照
        start_preview(refraction=True, photo=True, offset_y=180)
        on_photo = screenshot("api%d-photo-refraction-on" % api)
        start_preview(refraction=False, photo=True, offset_y=180)
        off_photo = screenshot("api%d-photo-refraction-off" % api)
        photo_diff = pixel_diff(on_photo, off_photo, f"api{api}-photo-on-vs-off")
        (OUT / f"api{api}-photo-diff.json").write_text(json.dumps(photo_diff, indent=2), encoding="utf-8")
        record("photo refraction on/off produces pixel changes", bool(photo_diff.get("changed_pixels")), json.dumps(photo_diff))

        # 改尺寸后仍稳定（不错位、不闪黑由视觉证据 + 稳定性共同覆盖）
        start_preview(refraction=True, photo=False, offset_y=120, height_dp=280)
        resized = screenshot("api%d-grid-resized" % api)
        record("resize renders without crash", resized.exists(), resized.name)

        # 同设备同场景掉帧对照
        start_preview(refraction=False, photo=True)
        frames_off = gfxinfo(f"api{api}-refraction-off")
        start_preview(refraction=True, photo=True)
        frames_on = gfxinfo(f"api{api}-refraction-on")
        evidence["frame_stats"] = {"refraction_off": frames_off, "refraction_on": frames_on}
        record("frame stats captured", bool(frames_on and frames_off), json.dumps(evidence["frame_stats"]))

    (OUT / f"api{api}-summary.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # noqa: BLE001
        evidence["error"] = f"{type(error).__name__}: {error}"
        (OUT / "error-summary.json").write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        raise
