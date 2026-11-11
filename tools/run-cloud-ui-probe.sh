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
# PartialSelectionPhaseATest 1 例 + PartialSelectionPhaseBTest 1 例（包B：PARTIAL 选择集 A→B
# 真实系统机制两阶段——阶段A 系统权限对话框 Select photos → 照片选择器建立 PARTIAL(A)；
# 宿主 force-stop；阶段B ACTION_PICK_IMAGES 重选 B + 地面真值 + UI/保留/哨兵断言）。
# FullPartialFullPhase1Test 1 例 + FullPartialFullPhase2Test 1 例 + FullPartialFullPhase3Test 1 例
# （B2 收尾：T07 FULL→PARTIAL→FULL 三阶段——FULL 建立+处理 g1+断点指向 g2+落盘；
# pm revoke 系统强停→PARTIAL(B) 像素重选+进度持有读回；pm grant+force-stop→FULL 恢复
# +已处理保持+g2 续接+旧快照晚到不覆盖竞争门，见套件 KDoc 与 coord/notes-B2.md）。
for class in CloudCleanupUiTest CloudRestoreUiTest CreateAlbumDiagnosticDeviceTest ForegroundPermissionUiTest PixelRefreshUiTest; do
  case "$class" in
    CloudCleanupUiTest) expected_tests=8 ;;
    CloudRestoreUiTest) expected_tests=3 ;;
    CreateAlbumDiagnosticDeviceTest) expected_tests=2 ;;
    ForegroundPermissionUiTest) expected_tests=2 ;;
    PixelRefreshUiTest) expected_tests=2 ;;
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
adb logcat -d -v threadtime > device-results/PartialSelection/logcat.txt || true
# 裁决 S6：宿主 grep 摘要（MediaProvider user-selected/PhotoPicker 授权路径 + 测试 verdict/confirm 行），cat 进 job log
grep -iE "user.?select|PhotoPicker|GrantUri|accessible|confirmCommitted|verdict=" device-results/PartialSelection/logcat.txt | tail -40 > device-results/PartialSelection/mediaprovider-grep.txt || true
cat device-results/PartialSelection/mediaprovider-grep.txt || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/partial-selection-expected-state.json device-results/PartialSelection/ || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/PartialSelection/ui-failures || true

if [ "$partial_status" != "0" ]; then
  echo "PARTIAL selection A->B two-stage verification failed" >&2
  status=1
fi
# ===== T07 FULL→PARTIAL→FULL 三阶段验证（B2 收尾单元）=====
# 成员（全部宿主 shell 绑定所有权，Pictures/FullPartialFullFixtures/）：
# G 组 g1/g2（生产写入口成组、g1 经真实 UI 上滑部分处理）、非组成员 n1（FULL 下
# 可见、PARTIAL(B) 下不在 B 集合）、B 集合专有 b1/b2（PARTIAL 阶段才可读）、
# 哨兵 s1（任何阶段不进任何集合/选择集）。推送顺序 S→N1→G1→G2→B1→B2（mtime
# 升序、全落 2026-11、相邻 >1.1s）→ 选择器网格时间倒序 [B2,B1,G2,G1,N1,S1]
# → B = 网格索引 {0,1}（#57 像素驱动原语）。
# 阶段1（FULL 建立+真实 UI 处理 g1+断点指向 g2+期望状态落盘，FullPartialFullPhase1Test
# 1 例）→ 宿主 pm revoke（应用被系统强停，#52 实证口径；应用数据保留）→
# 阶段2（真实系统对话框 Select photos 像素重选 b1/b2 → PARTIAL(B) 地面真值 +
# 进度持有读回，FullPartialFullPhase2Test 1 例）→ 宿主 pm grant（不杀进程）+
# am force-stop（#52 显式重启口径）→ 阶段3（FULL 恢复地面真值 + 已处理保持 +
# g2 续接 + 旧快照晚到不覆盖竞争门，FullPartialFullPhase3Test 1 例）。
# 阶段间仅权限与进程边界变化，禁止 pm clear（期望状态 files/ 落盘跨阶段读回）。
echo "=== T07 FULL->PARTIAL->FULL three-stage verification ==="
fpf_status=0
adb shell pm clear com.tabula.v3.recyclebin.test | tr -d '\r' | grep -Fx Success || fpf_status=1
# VISUAL_USER_SELECTED 清理（判据 1：pm clear 天然清理 + 兜底 revoke）
adb shell pm revoke com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_VISUAL_USER_SELECTED >/dev/null 2>&1 || true
adb shell pm grant com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES || fpf_status=1
for fixture_dir in "Pictures/FullPartialFullFixtures/" "Pictures/PartialSelectionFixtures/" "Pictures/FullRecoveryFixtures/" "Pictures/PixelRefreshFixtures/" "Pictures/ForegroundPermFixtures/" "Pictures/CloudSafetySentinels/" "Pictures/TabulaVolumeProbe/"; do
  adb shell "content delete --uri content://media/external/images/media --where \"relative_path='$fixture_dir'\"" >/dev/null 2>&1 || true
done
adb shell "rm -rf /sdcard/Pictures/FullPartialFullFixtures" >/dev/null 2>&1 || true
gallery_rows=$(adb shell "content query --uri content://media/external/images/media --projection _id" | grep -c "^Row:") || gallery_rows=0
if [ "$gallery_rows" != "0" ]; then
  adb shell "content query --uri content://media/external/images/media --projection _display_name" | head -20 >&2 || true
  echo "Gallery not clean before FULL->PARTIAL->FULL suite: $gallery_rows rows" >&2
  fpf_status=1
fi
mkdir -p device-results/FullPartialFull
# ---- 宿主夹具编排：纯 python 生成 6 张 96x96 纯色 PNG → adb push → scan_file
#      入库（行所有权=shell，非应用自有：scoped storage 下应用可见集合=严格授权
#      级别决定的集合；字节传输只走宿主 adb——CI run 42476083194/42477634711 实证）----
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

out = "device-results/FullPartialFull/fixtures"
os.makedirs(out, exist_ok=True)
plan = [("fpf-s1.png", (136, 136, 136)),
        ("fpf-n1.png", (0, 0, 255)),
        ("fpf-g1.png", (255, 0, 0)),
        ("fpf-g2.png", (255, 0, 255)),
        ("fpf-b1.png", (255, 255, 0)),
        ("fpf-b2.png", (0, 255, 255))]
for name, rgb in plan:
    write_png(os.path.join(out, name), rgb)
print("fixtures generated:", [n for n, _ in plan])
PY
fixture_ok=1
adb shell "mkdir -p /sdcard/Pictures/FullPartialFullFixtures" >/dev/null 2>&1 || true
for f in fpf-s1.png fpf-n1.png fpf-g1.png fpf-g2.png fpf-b1.png fpf-b2.png; do
  case "$f" in
    fpf-s1.png) touch_time=202611010900 ;;
    fpf-n1.png) touch_time=202611031000 ;;
    fpf-g1.png) touch_time=202611051100 ;;
    fpf-g2.png) touch_time=202611061200 ;;
    fpf-b1.png) touch_time=202611071300 ;;
    fpf-b2.png) touch_time=202611082000 ;;
  esac
  adb push "device-results/FullPartialFull/fixtures/$f" "/sdcard/Pictures/FullPartialFullFixtures/$f" >/dev/null || fixture_ok=0
  adb shell "touch -t $touch_time /sdcard/Pictures/FullPartialFullFixtures/$f" >/dev/null 2>&1 || true
  sleep 1.1
  adb shell "content call --uri content://media/ --method scan_file --arg /sdcard/Pictures/FullPartialFullFixtures/$f" >/dev/null 2>&1 || true
  row=$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"_display_name='$f'\"" | grep -c "^Row:") || row=0
  if [ "$row" != "1" ]; then
    # 兜底扫描路径：MEDIA_SCANNER_SCAN 广播后再核对一次
    adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/FullPartialFullFixtures/$f" >/dev/null 2>&1 || true
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
  fpf_status=1
fi
# 依赖跳过辅助（coord/notes-B2.md §7.3.6）：上游阶段红 → 下游 instrumentation 跳过
# （"dependency-unmet" 日志，fpf_status 保持 1，不跑依赖阶段）；跳过时宿主侧按名
# 兜底清理 6 夹具（依赖阶段未跑 → 其 @After 清理未执行，防残留污染后续 probe 段）。
fpf_cleanup_fixtures() {
  for f in fpf-s1.png fpf-n1.png fpf-g1.png fpf-g2.png fpf-b1.png fpf-b2.png; do
    adb shell "content delete --uri content://media/external/images/media --where \"_display_name='$f' AND relative_path='Pictures/FullPartialFullFixtures/'\"" >/dev/null 2>&1 || true
    adb shell "rm -f /sdcard/Pictures/FullPartialFullFixtures/$f" >/dev/null 2>&1 || true
  done
  adb shell "rm -rf /sdcard/Pictures/FullPartialFullFixtures" >/dev/null 2>&1 || true
  echo "FPF skip fallback: 6 fixtures removed by name"
}
fpf_phase1_ok=1
if ! timeout 420s adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
  -e class com.tabula.v3.FullPartialFullPhase1Test \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner \
  | tee device-results/FullPartialFull/phase1.txt; then
  echo "FPF Phase1 instrumentation failed" >&2; fpf_status=1; fpf_phase1_ok=0
fi
grep -Eq "^OK \(1 tests?\)" device-results/FullPartialFull/phase1.txt || { echo "FPF Phase1 expected OK(1)" >&2; fpf_status=1; fpf_phase1_ok=0; }
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' device-results/FullPartialFull/phase1.txt; then
  echo "Failure or skip observed in FPF phase1" >&2; fpf_status=1; fpf_phase1_ok=0
fi

if [ "$fpf_phase1_ok" != "1" ]; then
  echo "dependency-unmet: FPF Phase2 skipped (Phase1 red) — downstream instrumentation not run" >&2
  fpf_phase2_ok=0
  fpf_cleanup_fixtures
else
  # 宿主侧权限收窄：应用被系统强停（#52 实证行为）；应用数据保留，阶段2 新进程续接
  adb shell pm revoke com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES || fpf_status=1
  sleep 2
  fpf_phase2_ok=1
  if ! timeout 420s adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
    -e class com.tabula.v3.FullPartialFullPhase2Test \
    com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner \
    | tee device-results/FullPartialFull/phase2.txt; then
    echo "FPF Phase2 instrumentation failed" >&2; fpf_status=1; fpf_phase2_ok=0
  fi
  grep -Eq "^OK \(1 tests?\)" device-results/FullPartialFull/phase2.txt || { echo "FPF Phase2 expected OK(1)" >&2; fpf_status=1; fpf_phase2_ok=0; }
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' device-results/FullPartialFull/phase2.txt; then
    echo "Failure or skip observed in FPF phase2" >&2; fpf_status=1; fpf_phase2_ok=0
  fi
fi

if [ "$fpf_phase2_ok" != "1" ]; then
  echo "dependency-unmet: FPF Phase3 skipped (Phase2 red) — downstream instrumentation not run" >&2
  fpf_cleanup_fixtures
else
  # 恢复 FULL：grant 不杀进程 → 显式 force-stop 对齐 #52 重启口径（应用数据保留）
  adb shell pm grant com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES || fpf_status=1
  adb shell am force-stop com.tabula.v3.recyclebin.test || fpf_status=1
  if ! timeout 420s adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
    -e class com.tabula.v3.FullPartialFullPhase3Test \
    com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner \
    | tee device-results/FullPartialFull/phase3.txt; then
    echo "FPF Phase3 instrumentation failed" >&2; fpf_status=1
  fi
  grep -Eq "^OK \(1 tests?\)" device-results/FullPartialFull/phase3.txt || { echo "FPF Phase3 expected OK(1)" >&2; fpf_status=1; }
fi
for phase in phase1 phase2 phase3; do
  [ -f "device-results/FullPartialFull/$phase.txt" ] || continue
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' "device-results/FullPartialFull/$phase.txt"; then
    echo "Failure or skip observed in FPF $phase" >&2
    fpf_status=1
  fi
done
adb logcat -d -v threadtime > device-results/FullPartialFull/logcat.txt || true
# 裁决 S6 同款：宿主 grep 摘要（MediaProvider user-selected/PhotoPicker 授权路径 +
# 测试 verdict/evidence 行）cat 进 job log
grep -iE "user.?select|PhotoPicker|GrantUri|accessible|confirmCommitted|verdict=|FullPartialFull" device-results/FullPartialFull/logcat.txt | tail -40 > device-results/FullPartialFull/mediaprovider-grep.txt || true
cat device-results/FullPartialFull/mediaprovider-grep.txt || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/full-partial-full-expected-state.json device-results/FullPartialFull/ || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/FullPartialFull/ui-failures || true

if [ "$fpf_status" != "0" ]; then
  echo "T07 FULL->PARTIAL->FULL three-stage verification failed" >&2
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
adb logcat -d -v threadtime > device-results/FullRecovery/logcat.txt || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/full-recovery-expected-state.json device-results/FullRecovery/ || true
adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures device-results/FullRecovery/ui-failures || true

if [ "$full_status" != "0" ]; then
  echo "FULL recovery four-stage verification failed" >&2
  status=1
fi

exit "$status"
