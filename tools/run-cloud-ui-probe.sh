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
# 预期逐例计数（新增场景须同步声明）：CloudCleanupUiTest 8 例
# （laterCandidate / invalidatedCopy / albumDetailLoadFailureShowsBannerAndRetryRecovers（非空相册） /
#  albumDetailEmptyAlbumLoadFailureShowsErrorAndRetryShowsRealImage（空相册，元数据构造绕开 O2） /
#  albumDetailSlowLoadSwitchShowsNewAlbumContentAndCancelIsSafe（切图集/取消，debug 慢加载接缝） /
#  albumDetailLateResultAfterSwitchDoesNotOverwriteNewPage（晚到竞争，按图集身份阻塞/释放并等待旧请求完成） /
#  archiveFailureShowsToastAndKeepsSource（T01-UIFEEDBACK 归档失败 Toast，debugForceArchiveCopyFailure 接缝） /
#  unknownDeleteVerificationKeepsSourceRecordAndReportsReason），
# CloudRestoreUiTest 3 例（singleRestore / batchRestore / restoreKeepsUnknown）。
# CreateAlbumDiagnosticDeviceTest 2 例（O2 诊断：createBucket 分阶段 + createAlbum 真实入口）。
# ForegroundPermissionUiTest 2 例（C4/T07：外部删除/外部编辑回前台刷新；撤销路径强停应用进程含 instrumentation，探针不可执行，见套件 KDoc）。
# PixelRefreshUiTest 2 例（C4/T07 审计轮：外部编辑后卡片与查看器像素级刷新 + ViewerPollPolicy 跨时刻拼接回归，见套件 KDoc）。
# PartialSelectionPhaseATest 1 例 + PartialSelectionPhaseBTest 1 例（包B：PARTIAL 选择集 A→B
# 真实系统机制两阶段——阶段A 系统权限对话框 Select photos → 照片选择器建立 PARTIAL(A)；
# 宿主 force-stop；阶段B 系统设置页重选 B + 地面真值 + UI/保留/哨兵断言——收尾单元改为
# 跨月夹具与设置页导航，机制结论见 PR57/notes）。
# OtherAppMediaAuthDeviceTest 4 例（包C2：他人媒体真实系统授权流程——直接删除归属边界 /
# 未授权按项保留 / 真实系统授权对话框删除 / 清理入口组合未授权保留，见套件 KDoc）。
for class in CloudCleanupUiTest CloudRestoreUiTest CreateAlbumDiagnosticDeviceTest ForegroundPermissionUiTest PixelRefreshUiTest OtherAppMediaAuthDeviceTest; do
  case "$class" in
    CloudCleanupUiTest) expected_tests=8 ;;
    CloudRestoreUiTest) expected_tests=3 ;;
    CreateAlbumDiagnosticDeviceTest) expected_tests=2 ;;
    ForegroundPermissionUiTest) expected_tests=2 ;;
    PixelRefreshUiTest) expected_tests=2 ;;
    OtherAppMediaAuthDeviceTest) expected_tests=4 ;;
    *) expected_tests=2 ;;
  esac
  dir="device-results/$class"
  mkdir -p "$dir"
  if ! adb shell pm clear com.tabula.v3.recyclebin.test | tr -d '\r' | grep -Fx Success; then
    echo "Failed to clear app data for $class" >&2
    status=1
    continue
  fi
  output="$dir/instrumentation.txt"
  if ! python3 tools/run-volume-device-probe.py window before "$class"; then
    echo "Failed to capture device state before $class" >&2
    status=1
    continue
  fi
  if timeout 300s adb shell am instrument -w -r \
    -e allowSyntheticMediaProbe true \
    -e class "com.tabula.v3.${class}" \
    com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner | tee "$output"; then
    :
  else
    echo "Instrumentation failed or timed out for $class" >&2
    status=1
  fi
  if ! python3 tools/run-volume-device-probe.py window after "$class"; then
    echo "Device state or log check failed for $class" >&2
    status=1
  fi
  if ! grep -Eq "^OK \(${expected_tests} tests?\)" "$output"; then
    echo "Expected ${expected_tests} passing tests in $class" >&2
    status=1
  fi
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' "$output"; then
    echo "Failure or skip observed in $class" >&2
    status=1
  fi
  collect "$class-logcat" 2 "$dir/logcat.txt" adb logcat -d -v threadtime || true
  collect "$class-ui-failures" 0 - adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures "$dir/ui-failures" || true
done
# ===== PARTIAL 选择集 A→B 两阶段（真实系统 UI 驱动）验证（包B）=====
# 阶段A：shell 身份写入+扫描 5 张非应用自有合成媒体（scoped storage 下应用
# 可见集合=严格选中集，选择集语义可观测）→ onboarding 门控 → 系统权限对话框
# Select photos / Allow limited access → 照片选择器点选 A 两格 → 确认 →
# PARTIAL(A) 授权 + 地面真值（授权前查询为空、授权后恰=A）+ 清理组/断点持久化
# + deck/查看器/月份 UI 一致性。
# 宿主 force-stop（不 pm clear，保留应用数据与授权）。
# 阶段B：ACTION_PICK_IMAGES 重选（取消 A 勾选、勾选 B 两格）→ 地面真值恰=B
# + deck/查看器/月份跟随 + 组/断点保留 + 哨兵 shell 存在性 + 夹具精确清理。
# 阶段A前按已知合成夹具目录 shell 预清理并核对图库为空（CI 专用模拟器）。
echo "=== PARTIAL selection A->B two-stage verification ==="
partial_status=0
adb shell pm clear com.tabula.v3.recyclebin.test | tr -d '\r' | grep -Fx Success || partial_status=1
for fixture_dir in "Pictures/PartialSelectionFixtures/" "Pictures/FullRecoveryFixtures/" "Pictures/PixelRefreshFixtures/" "Pictures/ForegroundPermFixtures/" "Pictures/CloudSafetySentinels/" "Pictures/TabulaVolumeProbe/"; do
  adb shell "content delete --uri content://media/external/images/media --where \"relative_path='$fixture_dir'\"" >/dev/null 2>&1 || true
done
adb shell "rm -rf /sdcard/Pictures/PartialSelectionFixtures" >/dev/null 2>&1 || true
gallery_rows=$(adb shell "content query --uri content://media/external/images/media --projection _id" | grep -c "^Row:") || gallery_rows=0
if [ "$gallery_rows" != "0" ]; then
  echo "Gallery not clean before PARTIAL selection suite: $gallery_rows rows" >&2
  partial_status=1
fi
mkdir -p device-results/PartialSelection
# ---- 宿主夹具编排：纯 python 生成 5 张 96x96 纯色 PNG → adb push →
# scan_file 入库（行所有权=shell，非应用自有：scoped storage 下应用可见
# 集合=严格选中集，选择集 A→B 语义可观测）。推送顺序 S→A1→A2→B1→B2，
# 相邻 >1.1s（mtime 派生拍摄时间秒级互异 → 选择器按时间倒序确定）。
# CI 实证（run 42476083194/42477634711）：经 UiAutomation shell 传字节
# 会被截断/吞没——字节传输只走宿主 adb。
python3 - <<'PY'
import os, struct, zlib

def write_png(path, rgb, size=96):
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

out = "device-results/PartialSelection/fixtures"
os.makedirs(out, exist_ok=True)
plan = [("partial-sentinel.png", (136, 136, 136)),
        ("partial-a1.png", (255, 0, 0)),
        ("partial-a2.png", (0, 255, 0)),
        ("partial-b1.png", (255, 255, 0)),
        ("partial-b2.png", (0, 255, 255))]
for name, rgb in plan:
    write_png(os.path.join(out, name), rgb)
print("fixtures generated:", [n for n, _ in plan])
PY
fixture_ok=1
adb shell "mkdir -p /sdcard/Pictures/PartialSelectionFixtures" >/dev/null 2>&1 || true
# 跨月份夹具（收尾单元）：touch -t 设 mtime → scan 派生 date_modified/date_taken
# → A 集合（a1/a2）落在 10 月、B 集合（b1/b2）落在 11 月 → 月份列表承载
# "旧 A 月 vs 新 B 月"可断言差异；排序 DESC 仍 = [B2,B1,A2,A1,S]（B2 11-07 >
# B1 11-05 > A2 10-20 > A1 10-15 > S 10-10）。
for f in partial-sentinel.png partial-a1.png partial-a2.png partial-b1.png partial-b2.png; do
  case "$f" in
    partial-sentinel.png) touch_time=202610100900 ;;
    partial-a1.png) touch_time=202610151000 ;;
    partial-a2.png) touch_time=202610201100 ;;
    partial-b1.png) touch_time=202611051200 ;;
    partial-b2.png) touch_time=202611072000 ;;
  esac
  adb push "device-results/PartialSelection/fixtures/$f" "/sdcard/Pictures/PartialSelectionFixtures/$f" >/dev/null || fixture_ok=0
  adb shell "touch -t $touch_time /sdcard/Pictures/PartialSelectionFixtures/$f" >/dev/null 2>&1 || true
  sleep 1.1
  adb shell "content call --uri content://media/ --method scan_file --arg /sdcard/Pictures/PartialSelectionFixtures/$f" >/dev/null 2>&1 || true
  row=$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"_display_name='$f'\"" | grep -c "^Row:") || row=0
  if [ "$row" != "1" ]; then
    # 兜底扫描路径：MEDIA_SCANNER_SCAN 广播后再核对一次
    adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/PartialSelectionFixtures/$f" >/dev/null 2>&1 || true
    sleep 1
    row=$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"_display_name='$f'\"" | grep -c "^Row:") || row=0
  fi
  if [ "$row" != "1" ]; then
    echo "Host fixture scan failed: $f (rows=$row)" >&2
    fixture_ok=0
  fi
done
if [ "$fixture_ok" != "1" ]; then
  echo "Host fixture orchestration failed" >&2
  status=1
fi
if ! timeout 420s adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
  -e class com.tabula.v3.PartialSelectionPhaseATest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner \
  | tee device-results/PartialSelection/phaseA.txt; then
  echo "PhaseA instrumentation failed" >&2; partial_status=1
fi
grep -Eq "^OK \(1 tests?\)" device-results/PartialSelection/phaseA.txt || { echo "PhaseA expected OK(1)" >&2; partial_status=1; }

# 宿主侧：强停阶段A进程（保留应用数据与 PARTIAL(A) 授权，阶段B 新进程续接）
adb shell am force-stop com.tabula.v3.recyclebin.test || partial_status=1

if ! timeout 420s adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
  -e class com.tabula.v3.PartialSelectionPhaseBTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner \
  | tee device-results/PartialSelection/phaseB.txt; then
  echo "PhaseB instrumentation failed" >&2; partial_status=1
fi
grep -Eq "^OK \(1 tests?\)" device-results/PartialSelection/phaseB.txt || { echo "PhaseB expected OK(1)" >&2; partial_status=1; }
for phase in phaseA phaseB; do
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' "device-results/PartialSelection/$phase.txt"; then
    echo "Failure or skip observed in PARTIAL $phase" >&2
    partial_status=1
  fi
done
collect partial-logcat 2 device-results/PartialSelection/logcat.txt adb logcat -d -v threadtime || true
# 裁决 S6：宿主 grep 摘要（MediaProvider user-selected/PhotoPicker 授权路径 + 测试 verdict/confirm 行），cat 进 job log
grep -iE "user.?select|PhotoPicker|GrantUri|accessible|confirmCommitted|verdict=" device-results/PartialSelection/logcat.txt | tail -40 > device-results/PartialSelection/mediaprovider-grep.txt || true
cat device-results/PartialSelection/mediaprovider-grep.txt || true
collect partial-expected-state 0 - adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/partial-selection-expected-state.json device-results/PartialSelection/ || true
collect partial-ui-failures 0 - adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/PartialSelection/ui-failures || true

if [ "$partial_status" != "0" ]; then
  echo "PARTIAL selection A->B two-stage verification failed" >&2
  status=1
fi
# ===== FULL 恢复两阶段（+缺席断言）验证（审计轮：工作包 2）=====
# 阶段一建立真实清理组/断点；宿主 revoke（应用被强停）→ 缺席断言 →
# grant（恢复 FULL）→ UI 软删除 → 强停 → 新进程读回。阶段间保留应用数据。
echo "=== FULL recovery four-stage verification ==="
full_status=0
adb shell pm clear com.tabula.v3.recyclebin.test | tr -d '' | grep -Fx Success || full_status=1
adb shell pm grant com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES || full_status=1
mkdir -p device-results/FullRecovery
if ! adb shell am instrument -w -r -e allowSyntheticMediaProbe true   -e class "com.tabula.v3.FullRecoveryPhase1Test"   com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner   | tee device-results/FullRecovery/phase1.txt; then
  echo "Phase1 instrumentation failed" >&2; full_status=1
fi
grep -Eq "^OK \(1 tests?\)" device-results/FullRecovery/phase1.txt || { echo "Phase1 expected OK(1)" >&2; full_status=1; }

# 宿主侧权限收窄：应用被系统强停（实证行为）
adb shell pm revoke com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES || full_status=1
sleep 2
if ! adb shell am instrument -w -r -e allowSyntheticMediaProbe true   -e class "com.tabula.v3.FullRecoveryAbsenceTest"   com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner   | tee device-results/FullRecovery/absence.txt; then
  echo "Absence instrumentation failed" >&2; full_status=1
fi
grep -Eq "^OK \(1 tests?\)" device-results/FullRecovery/absence.txt || { echo "Absence expected OK(1)" >&2; full_status=1; }

# 恢复 FULL 后强停缺席进程；由测试启动 MainActivity。
adb shell pm grant com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES || full_status=1
adb shell am force-stop com.tabula.v3.recyclebin.test || full_status=1
if ! adb shell am instrument -w -r -e allowSyntheticMediaProbe true   -e class "com.tabula.v3.FullRecoveryContinuationTest"   com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner   | tee device-results/FullRecovery/continuation.txt; then
  echo "Continuation instrumentation failed" >&2; full_status=1
fi
grep -Eq "^OK \(1 tests?\)" device-results/FullRecovery/continuation.txt || { echo "Continuation expected OK(1)" >&2; full_status=1; }

# UI 软删除已由生产回调提交；强停后用全新进程读取持久确认与回收站。
# 所有 FULL 阶段共享应用数据，这里禁止 pm clear。
adb shell am force-stop com.tabula.v3.recyclebin.test || full_status=1
if ! timeout 300s adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
  -e class com.tabula.v3.FullRecoveryReadbackTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner \
  | tee device-results/FullRecovery/readback.txt; then
  echo "Readback instrumentation failed" >&2; full_status=1
fi
grep -Eq "^OK \(1 tests?\)" device-results/FullRecovery/readback.txt || { echo "Readback expected OK(1)" >&2; full_status=1; }
for phase in phase1 absence continuation readback; do
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' "device-results/FullRecovery/$phase.txt"; then
    echo "Failure or skip observed in FULL $phase" >&2
    full_status=1
  fi
done
collect full-logcat 2 device-results/FullRecovery/logcat.txt adb logcat -d -v threadtime || true
collect full-expected-state 0 - adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/full-recovery-expected-state.json device-results/FullRecovery/ || true
collect full-ui-failures 0 - adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/FullRecovery/ui-failures || true

if [ "$full_status" != "0" ]; then
  echo "FULL recovery four-stage verification failed" >&2
  status=1
fi

exit "$status"
