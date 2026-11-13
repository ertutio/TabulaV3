#!/usr/bin/env bash
# 任务 A：非空回收记录跨版本升级与恢复探针（upgrade-recycle，2026-11-13）。
#
# 编排（android-emulator-runner 的 script 调用，emulator 已就绪）：
#   1. 安装 OLD(43a8da1) debug 应用 + OLD androidTest APK（内含构建期拷入的
#      UpgradeRecycleSeedTest），grant READ_MEDIA_IMAGES
#   2. 运行 OLD 侧种子：真实生产写入口 addAllToRecycleBin 建立 3 成员回收记录
#      + 集合外哨兵 s1 + 期望状态 JSON（零格式猜测）
#   3. 判据 2 pre-upgrade 三面 shell 核：force-stop → adb root 直读
#      recycle_bin.json（文件面）+ content query 每名恰 1 行（shell 面）
#      （app 查询面=种子测试内断言）；并把 pre-launch 磁盘真值以 root 重定向
#      注入 files/upgrade-recycle-disk-truth.json
#   4. 覆盖安装：adb install -r NEW app-debug.apk（无 pm clear；断言 rc=0
#      Success、无签名冲突）→ 安装 NEW androidTest APK
#   5. 判据 3/4：UpgradeRecycleDeviceTest#verifyDiskTruthThenRestoreViaUi
#      （三面一致 → 真实回收站 UI 恢复 → 生产/UI/物理三面核对）；
#      随后 pull 恢复后 recycle_bin.json + 注入 postrestore 磁盘真值 + shell 复查
#   6. 判据 5：force-stop → am start 冷重启 → force-stop →
#      UpgradeRecycleDeviceTest#verifyRestorePersistsInNewProcess（新进程读回
#      持久 + 两次 loadRecycleBin 幂等）
#   7. logcat FATAL 门 + 收证。
#
# 复用 tools/run-volume-device-probe.py 的 window() 稳定性窗（不动该脚本）。
set -uo pipefail

mkdir -p device-results
status=0

APP_ID=com.tabula.v3.recyclebin.test
TEST_ID=com.tabula.v3.recyclebin.test.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
APP_DATA=/data/data/$APP_ID
APP_FILES=$APP_DATA/files
BIN_REMOTE=$APP_FILES/recycle_bin.json
EXPECTED_STATE_REMOTE=/sdcard/Android/data/$APP_ID/files/upgrade-recycle-expected-state.json
DISK_TRUTH_REMOTE=$APP_FILES/upgrade-recycle-disk-truth.json
POSTRESTORE_DISK_TRUTH_REMOTE=$APP_FILES/upgrade-recycle-postrestore-disk-truth.json

log() { echo "[upgrade-recycle-probe] $*"; }
fail() { echo "[upgrade-recycle-probe] FAIL: $*" >&2; status=1; }

capture() {
  adb logcat -d -v threadtime > device-results/r-logcat.txt 2>/dev/null || true
  adb shell getprop > device-results/r-properties.txt 2>/dev/null || true
  adb shell dumpsys package "$APP_ID" > device-results/r-package-dumpsys.txt 2>/dev/null || true
}
trap capture EXIT

# ===== 0. 前置：设备就绪 + 输入 APK 门 =====
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]; then
  echo "This probe only runs on a freshly created emulator" >&2; exit 1
fi
adb shell cmd overlay enable-exclusive --category \
  com.android.internal.systemui.navbar.threebutton || true

deadline=$((SECONDS + 90))
until adb shell sm list-volumes all 2>/dev/null | grep -Eq "emulated;0[[:space:]]+mounted\b"; do
  if [ $SECONDS -ge $deadline ]; then
    echo "Emulator primary storage did not mount before upgrade-recycle probe" >&2; exit 1
  fi
  sleep 2
done

{
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "api_level=${API_LEVEL:-unknown}"
} > device-results/r-device.json

for apk in device-apks/old/app-debug.apk device-apks/old/app-debug-androidTest.apk \
           device-apks/new/app-debug.apk device-apks/new/app-debug-androidTest.apk; do
  if [ ! -s "$apk" ]; then echo "missing APK input: $apk" >&2; exit 1; fi
done
sha256sum device-apks/old/*.apk device-apks/new/*.apk > device-results/r-apk-sha256.txt

AAPT="${ANDROID_HOME:-/usr/lib/android-sdk}/build-tools/35.0.0/aapt"
if [ -x "$AAPT" ]; then
  for side in old new; do
    "$AAPT" dump badging "device-apks/$side/app-debug.apk" > "device-results/r-badging-$side.txt"
    grep -F "package: name='$APP_ID'" "device-results/r-badging-$side.txt" \
      || fail "badging: $side app package mismatch"
    grep -F "versionCode='5'" "device-results/r-badging-$side.txt" \
      || fail "badging: $side app versionCode must be 5"
    "$AAPT" dump badging "device-apks/$side/app-debug-androidTest.apk" > "device-results/r-badging-$side-test.txt"
  done
  old_test_pkg=$(grep -o "package: name='[^']*'" device-results/r-badging-old-test.txt | head -1)
  new_test_pkg=$(grep -o "package: name='[^']*'" device-results/r-badging-new-test.txt | head -1)
  [ -n "$old_test_pkg" ] && [ "$old_test_pkg" = "$new_test_pkg" ] \
    || fail "old/new androidTest APK package mismatch: '$old_test_pkg' vs '$new_test_pkg'"
else
  log "aapt unavailable; skipping badging gates"
fi

install_apk() {
  local apk="$1" label="$2" attempt out
  for attempt in 1 2 3; do
    out=$(adb install --no-streaming -r "$apk" 2>&1) || true
    { echo "== $label attempt $attempt =="; echo "$out"; } >> device-results/r-installs.txt
    if grep -q "^Success" <<<"$out"; then return 0; fi
    adb wait-for-device
    sleep 5
  done
  echo "install failed after retries: $label" >&2
  echo "$out" >&2
  return 1
}

# ===== 1. 安装 OLD（应用 + androidTest，含种子测试）并恢复权限 =====
install_apk device-apks/old/app-debug.apk "OLD app" || fail "OLD app install"
install_apk device-apks/old/app-debug-androidTest.apk "OLD androidTest" || fail "OLD androidTest install"
adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES \
  || fail "pm grant READ_MEDIA_IMAGES (OLD)"

# ===== 2. Phase SEED：OLD 侧种子（真实生产写入口 addAllToRecycleBin） =====
adb logcat -c || true
mkdir -p device-results/r-seed
if ! python3 tools/run-volume-device-probe.py window before r-seed; then
  fail "device window before r-seed"
fi
if ! adb shell am instrument -w -r \
  -e allowSyntheticMediaProbe true \
  -e class "com.tabula.v3.UpgradeRecycleSeedTest" \
  "$TEST_ID/$RUNNER" | tee device-results/r-seed/seed.txt; then
  fail "recycle seed instrumentation failed"
fi
if ! grep -Eq '^OK \(1 tests?\)' device-results/r-seed/seed.txt; then
  fail "recycle seed expected OK(1)"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' \
  device-results/r-seed/seed.txt; then
  fail "recycle seed failure or skip observed"
fi
if ! python3 tools/run-volume-device-probe.py window after r-seed; then
  fail "device window after r-seed"
fi
if ! adb pull "$EXPECTED_STATE_REMOTE" device-results/r-seed/upgrade-recycle-expected-state.json; then
  fail "upgrade-recycle expected-state.json pull failed"
fi

# 从期望状态提取身份清单（sentinel 对象 + 全部唯一 displayName）。
EXPECTED_LOCAL=device-results/r-seed/upgrade-recycle-expected-state.json
sentinel_obj=$(grep -o '"outsideSentinel":{[^}]*}' "$EXPECTED_LOCAL" | head -1)
[ -n "$sentinel_obj" ] || fail "expected-state must carry outsideSentinel object"
sentinel_name=$(printf '%s' "$sentinel_obj" | grep -o '"displayName":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$sentinel_name" ] || fail "expected-state sentinel displayName missing"
restored_obj=$(grep -o '"restoredCandidate":{[^}]*}' "$EXPECTED_LOCAL" | head -1)
[ -n "$restored_obj" ] || fail "expected-state must carry restoredCandidate object"
restored_name=$(printf '%s' "$restored_obj" | grep -o '"displayName":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$restored_name" ] || fail "expected-state restoredCandidate displayName missing"
all_names=$(grep -o '"displayName":"[^"]*"' "$EXPECTED_LOCAL" | cut -d'"' -f4 | sort -u)
member_names=$(printf '%s\n' "$all_names" | grep -vx "$sentinel_name" | sort -u)
member_count=$(printf '%s\n' "$member_names" | grep -c . || true)
[ "$member_count" = "3" ] || fail "expected-state must carry exactly 3 member names, got $member_count"
log "seeded members: $member_names; restored candidate: $restored_name; sentinel: $sentinel_name"

# ===== 3. root 面：判据 2 的文件面 + shell 查询面 + pre-launch 磁盘真值注入 =====
adb root > /dev/null 2>&1 || true
adb wait-for-device
device_uid=$(adb shell id -u | tr -d '\r')
if [ "$device_uid" != "0" ]; then
  echo "adb root unavailable (google_apis image required for recycle bin disk truth)" >&2
  exit 1
fi

fix_owner() {
  local path="$1" fuid fgid
  fuid=$(adb shell stat -c %u "$APP_FILES" | tr -d '\r')
  fgid=$(adb shell stat -c %g "$APP_FILES" | tr -d '\r')
  adb shell chown "$fuid:$fgid" "$path" || return 1
  adb shell chmod 600 "$path" || return 1
  adb shell restorecon "$path" || true
}

mkdir -p device-results/r-pre-upgrade
# 文件面（判据 2）：OLD 持久化的 recycle_bin.json 原文留证后逐门 grep。
adb shell am force-stop "$APP_ID" || true
if ! adb shell "cat $BIN_REMOTE" > device-results/r-pre-upgrade/recycle-bin-prelaunch.json; then
  fail "pre-upgrade recycle_bin.json read failed"
fi
[ -s device-results/r-pre-upgrade/recycle-bin-prelaunch.json ] \
  || fail "pre-upgrade recycle_bin.json must be non-empty (records must survive OLD side)"
pre_display_name_count=$(grep -o '"displayName"' device-results/r-pre-upgrade/recycle-bin-prelaunch.json | grep -c . || true)
[ "$pre_display_name_count" = "3" ] \
  || fail "pre-upgrade bin file must carry exactly 3 records, got $pre_display_name_count"
grep -q '"volumeName":"external_primary"' device-results/r-pre-upgrade/recycle-bin-prelaunch.json \
  || fail "pre-upgrade bin file must carry volumeName identity (external_primary)"
if grep -qF "$sentinel_name" device-results/r-pre-upgrade/recycle-bin-prelaunch.json; then
  fail "pre-upgrade bin file must not contain out-of-set sentinel $sentinel_name"
fi
for name in $member_names; do
  count=$(grep -cF "\"displayName\":\"$name\"" device-results/r-pre-upgrade/recycle-bin-prelaunch.json || true)
  [ "$count" = "1" ] || fail "pre-upgrade bin file: $name expected exactly 1 record, got $count"
done

# pre-launch 磁盘真值注入（判据 3 的 (a) 面）：NEW app 启动前的原文副本。
adb shell "cat $BIN_REMOTE > $DISK_TRUTH_REMOTE"
fix_owner "$DISK_TRUTH_REMOTE" || fail "disk truth chown"
adb shell "cat $DISK_TRUTH_REMOTE" > device-results/r-pre-upgrade/disk-truth-readback.json
grep -qF "\"displayName\":\"$restored_name\"" device-results/r-pre-upgrade/disk-truth-readback.json \
  || fail "disk truth injection readback must carry restored candidate record"

# ===== 4. shell 查询面（判据 2；root 全列表，unroot 回退，双份留证） =====
# MediaProvider 可见性白名单覆盖 shell uid(2000) 不覆盖 uid 0（T13 R1 实测）：
# 先 root 直查；无命中则 unroot 以 shell 身份重查；两份原始输出均留证。
shell_media_query() {
  adb shell content query --uri content://media/external/images/media \
    --projection _display_name 2>&1 | tr -d '\r'
}
media_truth_round() {
  local outdir="$1" mode rows first_name name
  rows=$(shell_media_query)
  mode="root(uid 0)"
  first_name=$(printf '%s\n' $all_names | head -1)
  if [ -z "$first_name" ] || ! grep -qF "$first_name" <<<"$rows"; then
    adb unroot > /dev/null 2>&1 || true
    adb wait-for-device
    sleep 3
    rows=$(shell_media_query)
    mode="shell(uid 2000)"
    adb root > /dev/null 2>&1 || true
    adb wait-for-device
  fi
  { echo "query mode: $mode"; echo "--- raw rows ---"; echo "$rows"; \
    echo "--- per-name counts ---"; } > "$outdir/media-truth-shell.txt"
  for name in $all_names; do
    count=$(grep -cF "_display_name=$name" <<<"$rows")
    echo "$name rows=$count" >> "$outdir/media-truth-shell.txt"
    [ "$count" = "1" ] || fail "shell media truth ($outdir): $name expected exactly 1 row, got $count"
  done
}
media_truth_round device-results/r-pre-upgrade

# ===== 5. Phase OVERLAY：覆盖安装 NEW（同 applicationId / 同 versionCode 5 / 同签名） =====
adb shell dumpsys package "$APP_ID" | grep -E "lastUpdateTime|versionName" > device-results/r-package-before.txt || true
adb shell am force-stop "$APP_ID" || true
overlay_out=$(adb install --no-streaming -r device-apks/new/app-debug.apk 2>&1) || true
{ echo "== OVERLAY install -r NEW app =="; echo "$overlay_out"; } >> device-results/r-installs.txt
grep -q "^Success" <<<"$overlay_out" || fail "overlay install must return Success"
if grep -qi "signatures do not match" <<<"$overlay_out"; then
  fail "signature conflict on overlay install"
fi
if grep -q "INSTALL_FAILED" <<<"$overlay_out"; then
  fail "overlay install failed (INSTALL_FAILED)"
fi
adb shell dumpsys package "$APP_ID" | grep -E "lastUpdateTime|versionName" > device-results/r-package-after.txt || true

install_apk device-apks/new/app-debug-androidTest.apk "NEW androidTest" || fail "NEW androidTest install"
adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES \
  || fail "pm grant READ_MEDIA_IMAGES (NEW)"

# ===== 6. Phase VERIFY 用例 1：磁盘读回三面一致 + 真实 UI 恢复（判据 3/4） =====
mkdir -p device-results/r-verify
if ! python3 tools/run-volume-device-probe.py window before r-verify; then
  fail "device window before r-verify"
fi
if ! adb shell am instrument -w -r \
  -e allowSyntheticMediaProbe true \
  -e class "com.tabula.v3.UpgradeRecycleDeviceTest#verifyDiskTruthThenRestoreViaUi" \
  "$TEST_ID/$RUNNER" | tee device-results/r-verify/disktruth-restore.txt; then
  fail "disktruth-restore instrumentation failed"
fi
if ! grep -Eq '^OK \(1 tests?\)' device-results/r-verify/disktruth-restore.txt; then
  fail "disktruth-restore expected OK(1)"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' \
  device-results/r-verify/disktruth-restore.txt; then
  fail "disktruth-restore failure or skip observed"
fi
if ! python3 tools/run-volume-device-probe.py window after r-verify; then
  fail "device window after r-verify"
fi
adb pull "$EXPECTED_STATE_REMOTE" device-results/r-verify/upgrade-recycle-expected-state.json || true
adb pull "/sdcard/Android/data/$APP_ID/files/upgrade-recycle-postrestore.json" device-results/r-verify/ || true
adb pull "/sdcard/Android/data/$APP_ID/files/upgrade-recycle-evidence.json" device-results/r-verify/ || true

# ===== 7. 恢复后 shell 门（判据 4 的文件面/shell 面）+ postrestore 磁盘真值注入 =====
mkdir -p device-results/r-verify
if ! adb shell "cat $BIN_REMOTE" > device-results/r-verify/recycle-bin-postrestore.json; then
  fail "post-restore recycle_bin.json read failed"
fi
[ -s device-results/r-verify/recycle-bin-postrestore.json ] \
  || fail "post-restore recycle_bin.json must be non-empty (unrestored records must persist)"
post_display_name_count=$(grep -o '"displayName"' device-results/r-verify/recycle-bin-postrestore.json | grep -c . || true)
[ "$post_display_name_count" = "2" ] \
  || fail "post-restore bin file must carry exactly 2 records, got $post_display_name_count"
if grep -qF "\"displayName\":\"$restored_name\"" device-results/r-verify/recycle-bin-postrestore.json; then
  fail "post-restore bin file must not contain restored record $restored_name"
fi
if grep -qF "$sentinel_name" device-results/r-verify/recycle-bin-postrestore.json; then
  fail "post-restore bin file must not contain out-of-set sentinel $sentinel_name"
fi
for name in $member_names; do
  count=$(grep -cF "\"displayName\":\"$name\"" device-results/r-verify/recycle-bin-postrestore.json || true)
  if [ "$name" = "$restored_name" ]; then
    [ "$count" = "0" ] || fail "post-restore bin file: restored $name must be absent, got $count"
  else
    [ "$count" = "1" ] || fail "post-restore bin file: unrestored $name expected exactly 1 record, got $count"
  fi
done
grep -q '"volumeName":"external_primary"' device-results/r-verify/recycle-bin-postrestore.json \
  || fail "post-restore bin file must carry volumeName identity (external_primary)"

# 物理媒体身份 shell 面（判据 4）：恢复不得损毁任何物理媒体行。
media_truth_round device-results/r-verify

# postrestore 磁盘真值注入（判据 5 的三面之一）。
adb shell "cat $BIN_REMOTE > $POSTRESTORE_DISK_TRUTH_REMOTE"
fix_owner "$POSTRESTORE_DISK_TRUTH_REMOTE" || fail "postrestore disk truth chown"
adb shell "cat $POSTRESTORE_DISK_TRUTH_REMOTE" > device-results/r-verify/postrestore-disk-truth-readback.json
if grep -qF "\"displayName\":\"$restored_name\"" device-results/r-verify/postrestore-disk-truth-readback.json; then
  fail "postrestore disk truth must not contain restored record $restored_name"
fi

# ===== 8. force-stop → 冷重启 → force-stop（判据 5 的宿主侧换代操作） =====
for round in 1 2; do
  if ! adb shell am force-stop "$APP_ID"; then
    fail "force-stop before cold restart round $round"
    exit 1
  fi
  sleep 2
  if [ -n "$(adb shell pidof "$APP_ID" | tr -d '\r')" ]; then
    fail "app process still alive before cold restart round $round"
    exit 1
  fi
  adb shell am start -W -n "$APP_ID/com.tabula.v3.MainActivity" > "device-results/r-restart-$round.txt" 2>&1 || true
  sleep 8
  if [ -z "$(adb shell pidof "$APP_ID" | tr -d '\r')" ]; then
    fail "app process not alive after cold restart round $round (startup crash?)"
  fi
  adb shell am force-stop "$APP_ID" || true
done

# ===== 9. Phase VERIFY 用例 2：新进程读回持久 + 幂等（判据 5） =====
mkdir -p device-results/r-persist
if ! python3 tools/run-volume-device-probe.py window before r-persist; then
  fail "device window before r-persist"
fi
if ! adb shell am instrument -w -r \
  -e allowSyntheticMediaProbe true \
  -e class "com.tabula.v3.UpgradeRecycleDeviceTest#verifyRestorePersistsInNewProcess" \
  "$TEST_ID/$RUNNER" | tee device-results/r-persist/persist.txt; then
  fail "persist instrumentation failed"
fi
if ! grep -Eq '^OK \(1 tests?\)' device-results/r-persist/persist.txt; then
  fail "persist expected OK(1)"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' \
  device-results/r-persist/persist.txt; then
  fail "persist failure or skip observed"
fi
if ! python3 tools/run-volume-device-probe.py window after r-persist; then
  fail "device window after r-persist"
fi

# ===== 10. logcat FATAL 门（判据覆盖）+ 升级后状态留证 =====
adb logcat -d -v threadtime > device-results/r-logcat-final.txt 2>/dev/null || true
if grep -q "FATAL EXCEPTION" device-results/r-logcat-final.txt; then
  grep -A 8 "FATAL EXCEPTION" device-results/r-logcat-final.txt | head -60 > device-results/r-fatal-context.txt
  fail "FATAL EXCEPTION observed in logcat (see r-fatal-context.txt)"
fi

mkdir -p device-results/r-post-upgrade
adb shell "cat $BIN_REMOTE" > device-results/r-post-upgrade/recycle_bin.json 2>/dev/null || true
adb shell "cat $DISK_TRUTH_REMOTE" > device-results/r-post-upgrade/upgrade-recycle-disk-truth.json 2>/dev/null || true
if ! grep -qF "\"displayName\":\"$restored_name\"" device-results/r-post-upgrade/upgrade-recycle-disk-truth.json 2>/dev/null; then
  fail "post-upgrade pre-launch disk truth evidence missing or rewritten"
fi

if [ "$status" != "0" ]; then
  echo "Upgrade recycle verification FAILED (see device-results/ evidence)" >&2
else
  echo "Upgrade recycle verification: all gates green"
fi
exit "$status"
