#!/usr/bin/env bash
# 任务 B（有效冷却 + 非空清理进度跨升级）探针（upgrade-cooldown，2026-11-12）。
#
# 编排（android-emulator-runner 的 script 调用，emulator 已就绪）：
#   1. 安装 OLD(43a8da1) debug 应用 + OLD androidTest APK，grant READ_MEDIA_IMAGES
#   2. 运行 UpgradeCooldownSeedTest（本分支种子，拷入 OLD worktree 构建）：
#      140 张合成候选 > 2×batchSize → 生产推荐入口真实写冷却 + 三组清理进度
#      （部分处理/永久完成/无关保留）+ 夹具 JSON（预期全部来自 OLD 实拍）
#   3. root 直读门 A：tabula_pick_timestamps.xml 含目标 time_/days_ 条目；
#      tabula_album_cleanup.xml 含 acknowledged_members_ 与 processed_
#   4. am start OLD（真实生产 deck 取批）→ force-stop → root 直读门 B：
#      目标条目仍在且 time_ 记录数 ≥ 16（判据 1：正常启动+推荐流程后冷却记录
#      真实存在、未被 clearAllPickRecords 清空——T13 缺口的正面验证）
#   5. 覆盖安装：adb install -r NEW app-debug.apk（断言 Success、无签名冲突）
#      → 安装 NEW androidTest APK → 运行 UpgradeCooldownDeviceTest#verifyCooldownAndProgressSurvive
#   6. 两次 am start/force-stop 冷重启（pidof 门）→ 运行 verifyCooldownIdempotentAfterRestarts
#   7. logcat FATAL 门；全程 prefs/夹具/快照留证。
#
# 与 T13（tools/run-upgrade-probe.sh）零共享：结果前缀 cd-、夹具/快照文件名、
# 门集合均独立；不注入 albums_metadata、不写 T13 哨兵。
# 复用 tools/run-volume-device-probe.py 的 window() 稳定性窗（只调用不修改）。
set -uo pipefail

mkdir -p device-results
status=0

APP_ID=com.tabula.v3.recyclebin.test
TEST_ID=com.tabula.v3.recyclebin.test.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
APP_DATA=/data/data/$APP_ID
APP_FILES=$APP_DATA/files
PICK_PREFS_REMOTE=$APP_DATA/shared_prefs/tabula_pick_timestamps.xml
CLEANUP_PREFS_REMOTE=$APP_DATA/shared_prefs/tabula_album_cleanup.xml
FIXTURE_REMOTE=/sdcard/Android/data/$APP_ID/files/upgrade-cooldown-expected-state.json
SNAPSHOT_REMOTE=/sdcard/Android/data/$APP_ID/files/upgrade-cooldown-snapshot.json
EVIDENCE_REMOTE=/sdcard/Android/data/$APP_ID/files/upgrade-cooldown-evidence.json

log() { echo "[cd-upgrade-probe] $*"; }
fail() { echo "[cd-upgrade-probe] FAIL: $*" >&2; status=1; }

capture() {
  adb logcat -d -v threadtime > device-results/cd-logcat.txt 2>/dev/null || true
  adb shell getprop > device-results/cd-properties.txt 2>/dev/null || true
  adb shell dumpsys package "$APP_ID" > device-results/cd-package-dumpsys.txt 2>/dev/null || true
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
    echo "Emulator primary storage did not mount before cooldown probe" >&2; exit 1
  fi
  sleep 2
done

{
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "api_level=${API_LEVEL:-unknown}"
} > device-results/cd-device.json

for apk in device-apks/old/app-debug.apk device-apks/old/app-debug-androidTest.apk \
           device-apks/new/app-debug.apk device-apks/new/app-debug-androidTest.apk; do
  if [ ! -s "$apk" ]; then echo "missing APK input: $apk" >&2; exit 1; fi
done
sha256sum device-apks/old/*.apk device-apks/new/*.apk > device-results/cd-apk-sha256.txt

AAPT="${ANDROID_HOME:-/usr/lib/android-sdk}/build-tools/35.0.0/aapt"
if [ -x "$AAPT" ]; then
  for side in old new; do
    "$AAPT" dump badging "device-apks/$side/app-debug.apk" > "device-results/cd-badging-$side.txt"
    grep -F "package: name='$APP_ID'" "device-results/cd-badging-$side.txt" \
      || fail "badging: $side app package mismatch"
    grep -F "versionCode='5'" "device-results/cd-badging-$side.txt" \
      || fail "badging: $side app versionCode must be 5"
    "$AAPT" dump badging "device-apks/$side/app-debug-androidTest.apk" > "device-results/cd-badging-$side-test.txt"
  done
  old_test_pkg=$(grep -o "package: name='[^']*'" device-results/cd-badging-old-test.txt | head -1)
  new_test_pkg=$(grep -o "package: name='[^']*'" device-results/cd-badging-new-test.txt | head -1)
  [ -n "$old_test_pkg" ] && [ "$old_test_pkg" = "$new_test_pkg" ] \
    || fail "old/new androidTest APK package mismatch: '$old_test_pkg' vs '$new_test_pkg'"
else
  log "aapt unavailable; skipping badging gates"
fi

install_apk() {
  local apk="$1" label="$2" attempt out
  for attempt in 1 2 3; do
    out=$(adb install --no-streaming -r "$apk" 2>&1) || true
    { echo "== $label attempt $attempt =="; echo "$out"; } >> device-results/cd-installs.txt
    if grep -q "^Success" <<<"$out"; then return 0; fi
    adb wait-for-device
    sleep 5
  done
  echo "install failed after retries: $label" >&2
  echo "$out" >&2
  return 1
}

run_instrumentation() {
  local tag="$1" klass="$2" out="$3"
  python3 tools/run-volume-device-probe.py window before "$tag" || fail "device window before $tag"
  if ! adb shell am instrument -w -r \
    -e allowSyntheticMediaProbe true \
    -e class "$klass" \
    "$TEST_ID/$RUNNER" | tee "$out"; then
    fail "$tag instrumentation failed"
  fi
  if ! grep -Eq '^OK \(1 tests?\)' "$out"; then
    fail "$tag expected OK(1)"
  fi
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' \
    "$out"; then
    fail "$tag failure or skip observed"
  fi
  python3 tools/run-volume-device-probe.py window after "$tag" || fail "device window after $tag"
}

# ===== 1. 安装 OLD（应用 + androidTest）并恢复权限 =====
install_apk device-apks/old/app-debug.apk "OLD app" || fail "OLD app install"
install_apk device-apks/old/app-debug-androidTest.apk "OLD androidTest" || fail "OLD androidTest install"
adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES \
  || fail "pm grant READ_MEDIA_IMAGES (OLD)"

# ===== 2. Phase SEED：UpgradeCooldownSeedTest（真实生产入口建立冷却与三组进度） =====
adb logcat -c || true
mkdir -p device-results/cd-seed
run_instrumentation cd-seed "com.tabula.v3.UpgradeCooldownSeedTest" \
  device-results/cd-seed/seed.txt
if ! adb pull "$FIXTURE_REMOTE" device-results/cd-seed/upgrade-cooldown-expected-state.json; then
  fail "cooldown fixture pull failed"
fi

# ===== 3. root 直读门 A：种子真实落盘（判据 2 的 root 直读面） =====
adb root > /dev/null 2>&1 || true
adb wait-for-device
device_uid=$(adb shell id -u | tr -d '\r')
if [ "$device_uid" != "0" ]; then
  echo "adb root unavailable (google_apis image required for prefs readback)" >&2
  exit 1
fi

target_key=$(grep -o '"identityKey":"[^"]*"' \
  device-results/cd-seed/upgrade-cooldown-expected-state.json | head -1 | sed 's/"identityKey":"//; s/"$//')
if [ -z "$target_key" ]; then
  echo "fixture cooldownTarget.identityKey missing" >&2; exit 1
fi
log "cooldown target identity key: $target_key"

mkdir -p device-results/cd-pre
adb shell "cat $PICK_PREFS_REMOTE" > device-results/cd-pre/tabula_pick_timestamps.xml 2>/dev/null \
  || fail "pick prefs readback (pre)"
adb shell "cat $CLEANUP_PREFS_REMOTE" > device-results/cd-pre/tabula_album_cleanup.xml 2>/dev/null \
  || fail "cleanup prefs readback (pre)"
grep -qF "time_$target_key" device-results/cd-pre/tabula_pick_timestamps.xml \
  || fail "pick prefs must contain target time_ entry after seed"
grep -qF "days_$target_key" device-results/cd-pre/tabula_pick_timestamps.xml \
  || fail "pick prefs must contain target days_ entry after seed"
grep -q "acknowledged_members_" device-results/cd-pre/tabula_album_cleanup.xml \
  || fail "cleanup prefs must contain acknowledged members after seed"
grep -q '"processed_' device-results/cd-pre/tabula_album_cleanup.xml \
  || fail "cleanup prefs must contain permanent processed marker after seed"

# ===== 4. OLD 正常启动 + 推荐流程（真实生产 deck 取批）→ 门 B =====
adb shell am start -W -n "$APP_ID/com.tabula.v3.MainActivity" > device-results/cd-oldflow-start.txt 2>&1 || true
sleep 8
if [ -z "$(adb shell pidof "$APP_ID" | tr -d '\r')" ]; then
  fail "OLD app process not alive after am start (startup crash?)"
fi
adb shell am force-stop "$APP_ID" || true
sleep 2
mkdir -p device-results/cd-oldflow
adb shell "cat $PICK_PREFS_REMOTE" > device-results/cd-oldflow/tabula_pick_timestamps.xml 2>/dev/null \
  || fail "pick prefs readback (after OLD flow)"
time_entries=$(grep -c '<long name="time_' device-results/cd-oldflow/tabula_pick_timestamps.xml || true)
if [ "$time_entries" -lt 16 ]; then
  fail "after OLD start+recommendation flow pick records must be >=16 (target+deck batch), got $time_entries"
fi
grep -qF "time_$target_key" device-results/cd-oldflow/tabula_pick_timestamps.xml \
  || fail "OLD start+recommendation flow must NOT clear the target cooldown entry (T13 gap regression)"
adb shell "cat $CLEANUP_PREFS_REMOTE" > device-results/cd-oldflow/tabula_album_cleanup.xml 2>/dev/null || true
grep -q "acknowledged_members_" device-results/cd-oldflow/tabula_album_cleanup.xml \
  || fail "cleanup progress must survive OLD app real startup"

# ===== 5. Phase OVERLAY：覆盖安装 NEW（同 applicationId / 同 versionCode 5 / 同签名） =====
adb shell dumpsys package "$APP_ID" | grep -E "lastUpdateTime|versionName" > device-results/cd-package-before.txt || true
adb shell am force-stop "$APP_ID" || true
overlay_out=$(adb install --no-streaming -r device-apks/new/app-debug.apk 2>&1) || true
{ echo "== OVERLAY install -r NEW app =="; echo "$overlay_out"; } >> device-results/cd-installs.txt
grep -q "^Success" <<<"$overlay_out" || fail "overlay install must return Success"
grep -qi "signatures do not match" <<<"$overlay_out" && fail "signature conflict on overlay install"
grep -q "INSTALL_FAILED" <<<"$overlay_out" && fail "overlay install failed (INSTALL_FAILED)"
adb shell dumpsys package "$APP_ID" | grep -E "lastUpdateTime|versionName" > device-results/cd-package-after.txt || true

install_apk device-apks/new/app-debug-androidTest.apk "NEW androidTest" || fail "NEW androidTest install"
adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES \
  || fail "pm grant READ_MEDIA_IMAGES (NEW)"

# ===== 6. Phase VERIFY 用例 1：冷却 + 进度幸存（判据 3/4/5） =====
mkdir -p device-results/cd-verify
run_instrumentation cd-verify \
  "com.tabula.v3.UpgradeCooldownDeviceTest#verifyCooldownAndProgressSurvive" \
  device-results/cd-verify/survival.txt
adb pull "$FIXTURE_REMOTE" device-results/cd-verify/upgrade-cooldown-expected-state.json || true
adb pull "$SNAPSHOT_REMOTE" device-results/cd-verify/ || true
adb pull "$EVIDENCE_REMOTE" device-results/cd-verify/ || true

# ===== 7. 两次 am start 冷重启（判据 3：重复启动后再次验证的前置宿主操作） =====
for round in 1 2; do
  adb shell am start -W -n "$APP_ID/com.tabula.v3.MainActivity" > "device-results/cd-restart-$round.txt" 2>&1 || true
  sleep 8
  if [ -z "$(adb shell pidof "$APP_ID" | tr -d '\r')" ]; then
    fail "app process not alive after cold restart round $round (startup crash?)"
  fi
  adb shell am force-stop "$APP_ID" || true
  sleep 2
done

# ===== 8. Phase VERIFY 用例 2：幂等（判据 3/4 跨重启） =====
mkdir -p device-results/cd-idem
run_instrumentation cd-idem \
  "com.tabula.v3.UpgradeCooldownDeviceTest#verifyCooldownIdempotentAfterRestarts" \
  device-results/cd-idem/idempotency.txt

# ===== 9. logcat FATAL 门 + 升级后状态留证 =====
adb logcat -d -v threadtime > device-results/cd-logcat-final.txt 2>/dev/null || true
if grep -q "FATAL EXCEPTION" device-results/cd-logcat-final.txt; then
  grep -A 8 "FATAL EXCEPTION" device-results/cd-logcat-final.txt | head -60 > device-results/cd-fatal-context.txt
  fail "FATAL EXCEPTION observed in logcat (see cd-fatal-context.txt)"
fi

mkdir -p device-results/cd-post
adb shell "cat $PICK_PREFS_REMOTE" > device-results/cd-post/tabula_pick_timestamps.xml 2>/dev/null || true
adb shell "cat $CLEANUP_PREFS_REMOTE" > device-results/cd-post/tabula_album_cleanup.xml 2>/dev/null || true
grep -qF "time_$target_key" device-results/cd-post/tabula_pick_timestamps.xml \
  || fail "post-verification pick prefs must still contain target entry"

if [ "$status" != "0" ]; then
  echo "Task B cooldown upgrade verification FAILED (see device-results/ evidence)" >&2
else
  echo "Task B cooldown upgrade verification: all gates green"
fi
exit "$status"
