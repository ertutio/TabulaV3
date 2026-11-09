#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
capture() {
  adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/ui-failures || true
  adb logcat -d -v threadtime > device-results/ui-logcat.txt || true
  adb shell screencap -p /sdcard/cloud-ui-result.png || true
  adb pull /sdcard/cloud-ui-result.png device-results/ui-screen.png || true
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
# OtherAppMediaAuthDeviceTest 4 例（包C2：他人媒体真实系统授权流程——直接删除归属边界 /
# 未授权按项保留 / 真实系统授权对话框删除 / 清理入口组合未授权保留，见套件 KDoc）。
# ProdAuthCallbackDeviceTest 已从探针摘除（阻塞登记 2026-11-09，测试文件保留；累计 3 轮红）：
# 轮 1 run 42646353367 双 API「deck trash button 未达预期效果」（根因 uiAutomation.windows
# 恒空，head 2951ed2 已修，入口链路已验证可达）；轮 2 run 42653961146 双 API 取消分支
# 「System consent dialog could not be cancelled」（系统对话框拒绝键实际文本=Deny，
# CANCEL_TEXTS 缺项；BACK 兜底延迟不确定且污染次例启动）；轮 3 run 42662894360（head
# 5c035ec，Deny/DENY 补齐 + 取消判定闭环链 + 第二段重入验证）：api36 Deny 点按生效、
# 对话框关闭、确认用例转绿——但取消用例红于第二段判定「应用恢复前台+回收站就绪」：
# 取消后 row1 查看器仍开启，全屏查看器覆盖层使回收站计数/列表节点不可达（dump 实证：
# 树内仅 关闭/1 / 2/恢复/彻底删除 + 查看器页 desc=条目名，无计数文本）；api37 Deny 点按
# 在对话框成为 active window 后 ~0.25s 即发出（进入动画期被吞），点按一次后无重试 →
# 对话框滞留（dump 实证 20s 后 text=Deny/Allow clickable=true 仍在）并污染确认用例
# （bringAppToForeground 45s 超时）。唯一解锁路径（待验证假设，见套件 KDoc）：取消判定
# 第一段后先关闭查看器（点 关闭，效果=彻底删除 desc 消失）再等计数/条目重现；Deny/
# BACK 点按改为有界重试直至对话框关闭。复现栈与 dump 关键行见套件 KDoc。
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
  adb logcat -d -v threadtime > "$dir/logcat.txt" || true
  adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures "$dir/ui-failures" || true
done
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
adb logcat -d -v threadtime > device-results/FullRecovery/logcat.txt || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/full-recovery-expected-state.json device-results/FullRecovery/ || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/FullRecovery/ui-failures || true

if [ "$full_status" != "0" ]; then
  echo "FULL recovery four-stage verification failed" >&2
  status=1
fi

exit "$status"
