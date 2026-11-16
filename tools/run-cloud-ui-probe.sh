#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
# ===== 采集诊断包装（收尾单元 2026-11-10）=====
# 定位结论：run 42662260995 / 42844759640 API36 两连败的根因不是本脚本的
# 未保护采集命令（全部带 || true），也不是外层 timeout=720s（两次 UI 探针段
# 实测 ≈467s / ≈542s，且外层报的是 CalledProcessError 而非 TimeoutExpired），
# 而是 tools/run-volume-device-probe.py window() 里的套件后证据采集命令
# `adb logcat -d -v threadtime -T <窗口起点>` 在 API36 模拟器上非零退出
# （输出截断于行中、无错误文本——adbd/logd 流式读取中断的形态）。
# 此处为本脚本侧的证据采集（logcat/pull/screencap）提供统一登记：
# 命令、返回码、耗时、输出摘要、adb devices 连接状态，失败输出留档
# device-results/；首败即登记不吞；logcat -d 类只读采集加 ≤2 次有界重试。
# 证据采集失败不阻断门禁（仍保持原 || true 语义）；门禁仍由套件断言与
# window before/after 设备健康检查（boot_id/SurfaceFlinger PID/DeadSystem
# 图形断言）把守。python 侧对应实现见 run-volume-device-probe.py 的
# adb_readonly()（窗口 logcat 采集走同一诊断协议，采集失败仍阻断）。
diag_log="device-results/collection-diagnostics.log"
collect() {
  local label="$1" retries="$2" artifact="$3"; shift 3
  local max_attempts=$((retries + 1)) attempt rc=1 start end tmp
  tmp="device-results/.collect-tmp-$$.log"
  for attempt in $(seq 1 "$max_attempts"); do
    start=$(date +%s)
    if "$@" >"$tmp" 2>&1; then
      rc=0
    else
      rc=$?
    fi
    end=$(date +%s)
    {
      echo "=== bash/$label attempt=$attempt/$max_attempts rc=$rc elapsed=$((end - start))s cmd=$*"
      adb devices || echo "adb devices failed"
      echo "--- output head ---"
      head -n 3 "$tmp"
      echo "--- output tail ---"
      tail -n 8 "$tmp"
    } >>"$diag_log" 2>&1
    if [ "$rc" -eq 0 ]; then
      if [ "$artifact" != "-" ]; then mv "$tmp" "$artifact"; fi
      break
    fi
    cp "$tmp" "device-results/collection-failure-bash-$label-attempt$attempt.log"
    if [ "$attempt" -lt "$max_attempts" ]; then sleep 2; fi
  done
  rm -f "$tmp"
  if [ "$rc" -ne 0 ]; then
    echo "COLLECTION FAILED (evidence recorded in $diag_log, non-gating): bash/$label rc=$rc" >&2
  fi
  return "$rc"
}
capture() {
  collect trap-ui-failures-pull 0 - adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/ui-failures || true
  collect trap-logcat 2 device-results/ui-logcat.txt adb logcat -d -v threadtime || true
  collect trap-screencap 0 - adb shell screencap -p /sdcard/cloud-ui-result.png || true
  collect trap-screen-pull 0 - adb pull /sdcard/cloud-ui-result.png device-results/ui-screen.png || true
  echo "=== collection diagnostics (bash) ==="
  cat "$diag_log" 2>/dev/null || true
}
trap capture EXIT
adb wait-for-device
case "${API_LEVEL:?API_LEVEL must identify the workflow device}" in
  36|37) ;;
  *) echo "Unsupported emulator API: $API_LEVEL" >&2; exit 1 ;;
esac
adb shell getprop ro.build.version.sdk | tr -d '\r' | grep -Fx "$API_LEVEL"
status=0
# Mutation-only: focused StaleScan probe; outer workflow and wrapper remain unchanged.
# ===== Unit B（2026-11-15）：StaleScanLateReturn 旧扫描晚返回设备验收 =====
# StaleScanLateReturnDeviceTest 1 例 = 1 次独立 instrumentation（单方法五步
# 显式握手，全程同进程）。场景：真实系统机制建立 PARTIAL(B)（系统权限对话框
# Select photos → 照片选择器 7 格像素驱动只选 {1,2}=b1,b2 + 哨兵 s1）→
# MediaLoadTestHook.armOnce()（debug 接缝一次性门，仅 DEBUG 生效——
# loadAndApplyMediaSnapshot IO 块内 parkBeforeCommit，恢复期取消检查归生产链）
# → Home→am start（onResume 级别不变+foregroundRefresh → revision++）→ 旧扫描
# 停在可控位（parkedRows()==2 = PARTIAL 选中集合规模，读取已返回投影在手上）→
# 会话内 shell 注入 40 填充（MediaStore=47，应用可见仍={b1,b2}）→ pm grant 升级
# FULL（grant 不杀进程 #52 口径）→ Home→am start（级别变化分支 revision++ →
# 被暂停旧扫描被取消（NonCancellable 推迟）+ 新扫描全池提交）→ 断言：集合面
# = 47 名全集（哨兵存在性、无重复）/ deck 存活行同步（无门控/白屏）/ 真实
# Settings 推荐模式现值重选 → STALE_BOUNDARY_MARKER 边界后 batch-site-4 批次
# 日志行 id 集 == 47 池集 → release() 释放（logcat "resumed rows=2" = 旧读取
# 确实返回）→ 释放后观察窗（集合面仍 47 / 无门控 / 无"回收站记录未能读取"
# 对话框 / 释放标记后无 2 行批次事件 / 无 TabulaApp "Could not load/reconcile
# recycle bin" 错误行 = 取消不误报加载失败）→ 清理状态面读回（暂停前经生产写
# 入口建立的 组{g1,g2}+断点([G],0)+已处理{g1} 原样 = stale initialize 无持久
# 污染）→ 全程 pidof 恒等。
# 覆盖层级：真实接线级（竞态构造点在生产链内 debug 接缝；单测级竞态语义由
# PR50 MediaSnapshotLoadRaceTest 参数注入覆盖，两级口径各自保留）。
# 夹具：全部 shell 身份测试侧注入（base64 文本模板复制通道，宿主不推）——
# stale-s1/n1/g1/g2/g3/b1/b2 + stale-fill-01..40，Pictures/StaleScanLateReturnFixtures/；
# 用例末自清 + @After 兜底 + 本段失败按目录兜底清理。
echo "=== StaleScanLateReturn old-scan late-return device verification ==="
stale_status=0
adb shell pm clear com.tabula.v3.recyclebin.test | tr -d '\r' | grep -Fx Success || stale_status=1
adb shell pm revoke com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_VISUAL_USER_SELECTED >/dev/null 2>&1 || true
adb shell pm revoke com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
for fixture_dir in "Pictures/StaleScanLateReturnFixtures/" "Pictures/B2MediaAccessRevisionFixtures/" "Pictures/B2PermissionRefreshFixtures/" "Pictures/FullPartialFullFixtures/"; do
  adb shell "content delete --uri content://media/external/images/media --where \"relative_path='$fixture_dir'\"" >/dev/null 2>&1 || true
done
adb shell "rm -rf /sdcard/Pictures/StaleScanLateReturnFixtures" >/dev/null 2>&1 || true
stale_gallery_rows=$(adb shell "content query --uri content://media/external/images/media --projection _id" | grep -c "^Row:") || stale_gallery_rows=0
if [ "$stale_gallery_rows" != "0" ]; then
  adb shell "content query --uri content://media/external/images/media --projection _display_name" | head -20 >&2 || true
  echo "Gallery not clean before StaleScanLateReturn suite: $stale_gallery_rows rows" >&2
  stale_status=1
fi
mkdir -p device-results/StaleScanLateReturn
stale_cleanup_fixtures() {
  adb shell "content delete --uri content://media/external/images/media --where \"relative_path='Pictures/StaleScanLateReturnFixtures/'\"" >/dev/null 2>&1 || true
  adb shell "rm -rf /sdcard/Pictures/StaleScanLateReturnFixtures" >/dev/null 2>&1 || true
  echo "StaleScanLateReturn fallback: fixtures removed by dir"
}
if ! python3 tools/run-volume-device-probe.py window before StaleScanLateReturn; then
  echo "Failed to capture device state before StaleScanLateReturn" >&2; stale_status=1
fi
if ! timeout 600s adb shell am instrument -w -r -e allowSyntheticMediaProbe true -e staleScanForceRecoveryAfterOverwrite true \
  -e class com.tabula.v3.StaleScanLateReturnDeviceTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner \
  | tee device-results/StaleScanLateReturn/test.txt; then
  echo "StaleScanLateReturn instrumentation failed" >&2; stale_status=1
fi
if ! python3 tools/run-volume-device-probe.py window after StaleScanLateReturn; then
  echo "Device state or log check failed for StaleScanLateReturn" >&2; stale_status=1
fi
grep -Eq "^OK \(1 tests?\)" device-results/StaleScanLateReturn/test.txt || { echo "StaleScanLateReturn expected OK(1)" >&2; stale_status=1; }
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' device-results/StaleScanLateReturn/test.txt; then
  echo "Failure or skip observed in StaleScanLateReturn" >&2; stale_status=1
fi
if [ "$stale_status" != "0" ]; then
  stale_cleanup_fixtures
fi
collect stale-logcat 2 device-results/StaleScanLateReturn/logcat.txt adb logcat -d -v threadtime || true
# 裁决摘要：权限/批次/接缝证据行 cat 进 job log（套件 Log.i 锚 + 接缝 parked/resumed 行 + 生产批次行）
grep -iE "StaleScanLateReturn|MediaLoadTestHook|currentBatch<-\[|MediaProvider|user.?select" device-results/StaleScanLateReturn/logcat.txt | tail -30 > device-results/StaleScanLateReturn/wiring-grep.txt || true
cat device-results/StaleScanLateReturn/wiring-grep.txt || true
collect stale-ui-failures 0 - adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/StaleScanLateReturn/ui-failures || true
# 段末卫生：回收权限（probe 末段，revoke 强停无害）
adb shell pm revoke com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
adb shell pm revoke com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_VISUAL_USER_SELECTED >/dev/null 2>&1 || true

if [ "$stale_status" != "0" ]; then
  echo "StaleScanLateReturn old-scan late-return device verification failed" >&2
  status=1
fi

exit "$status"
