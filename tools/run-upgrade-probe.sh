#!/usr/bin/env bash
# T13 覆盖安装恢复探针（upgrade-overlay，2026-11-11）。
#
# 编排（android-emulator-runner 的 script 调用，emulator 已就绪）：
#   1. 安装 OLD(43a8da1) debug 应用 + OLD androidTest APK，grant READ_MEDIA_IMAGES
#   2. 运行 OLD 自带 FullRecoveryPhase1Test（真实生产代码建立：月份清理组+断点
#      位置1+expected-state.json；零格式猜测）
#   3. adb root 注入 albums_metadata.json（UpgAlbum isHidden=true，43a8da1
#      AlbumManager.loadMetadata 序列化格式）+ 哨兵文件 + 冷却种子
#      （tabula_pick_timestamps.xml，按 expected-state 成员身份键）
#   4. 启动 OLD 应用一次让其读入元数据 → force-stop
#   5. 覆盖安装：adb install -r NEW app-debug.apk（断言 rc=0 Success、无签名冲突）
#      → 安装 NEW androidTest APK → 运行 UpgradeOverlayDeviceTest
#   6. 两次 am start/force-stop 冷重启 → 运行幂等用例
#   7. 逐门 grep OK(expected_tests)；shell 面媒体真值；logcat FATAL 门；收证。
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
HIDDEN_ALBUM=UpgAlbum
SENTINEL_NAME=t13-upgrade-sentinel.txt
SENTINEL_CONTENT=t13-overlay-sentinel-2026-11-11
EXPECTED_STATE_REMOTE=/sdcard/Android/data/$APP_ID/files/full-recovery-expected-state.json

log() { echo "[t13-upgrade-probe] $*"; }
fail() { echo "[t13-upgrade-probe] FAIL: $*" >&2; status=1; }

capture() {
  adb logcat -d -v threadtime > device-results/t13-logcat.txt 2>/dev/null || true
  adb shell getprop > device-results/t13-properties.txt 2>/dev/null || true
  adb shell dumpsys package "$APP_ID" > device-results/t13-package-dumpsys.txt 2>/dev/null || true
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
    echo "Emulator primary storage did not mount before upgrade probe" >&2; exit 1
  fi
  sleep 2
done

{
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "api_level=${API_LEVEL:-unknown}"
} > device-results/t13-device.json

for apk in device-apks/old/app-debug.apk device-apks/old/app-debug-androidTest.apk \
           device-apks/new/app-debug.apk device-apks/new/app-debug-androidTest.apk; do
  if [ ! -s "$apk" ]; then echo "missing APK input: $apk" >&2; exit 1; fi
done
sha256sum device-apks/old/*.apk device-apks/new/*.apk > device-results/t13-apk-sha256.txt

AAPT="${ANDROID_HOME:-/usr/lib/android-sdk}/build-tools/35.0.0/aapt"
if [ -x "$AAPT" ]; then
  for side in old new; do
    "$AAPT" dump badging "device-apks/$side/app-debug.apk" > "device-results/t13-badging-$side.txt"
    grep -F "package: name='$APP_ID'" "device-results/t13-badging-$side.txt" \
      || fail "badging: $side app package mismatch"
    grep -F "versionCode='5'" "device-results/t13-badging-$side.txt" \
      || fail "badging: $side app versionCode must be 5"
    "$AAPT" dump badging "device-apks/$side/app-debug-androidTest.apk" > "device-results/t13-badging-$side-test.txt"
  done
  old_test_pkg=$(grep -o "package: name='[^']*'" device-results/t13-badging-old-test.txt | head -1)
  new_test_pkg=$(grep -o "package: name='[^']*'" device-results/t13-badging-new-test.txt | head -1)
  [ -n "$old_test_pkg" ] && [ "$old_test_pkg" = "$new_test_pkg" ] \
    || fail "old/new androidTest APK package mismatch: '$old_test_pkg' vs '$new_test_pkg'"
else
  log "aapt unavailable; skipping badging gates"
fi

install_apk() {
  local apk="$1" label="$2" attempt out
  for attempt in 1 2 3; do
    out=$(adb install --no-streaming -r "$apk" 2>&1) || true
    { echo "== $label attempt $attempt =="; echo "$out"; } >> device-results/t13-installs.txt
    if grep -q "^Success" <<<"$out"; then return 0; fi
    adb wait-for-device
    sleep 5
  done
  echo "install failed after retries: $label" >&2
  echo "$out" >&2
  return 1
}

# ===== 1. 安装 OLD（应用 + androidTest）并恢复权限 =====
install_apk device-apks/old/app-debug.apk "OLD app" || fail "OLD app install"
install_apk device-apks/old/app-debug-androidTest.apk "OLD androidTest" || fail "OLD androidTest install"
adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES \
  || fail "pm grant READ_MEDIA_IMAGES (OLD)"

# ===== 2. Phase SEED：OLD 自带 FullRecoveryPhase1Test（真实生产代码） =====
adb logcat -c || true
mkdir -p device-results/t13-seed
if ! python3 tools/run-volume-device-probe.py window before t13-seed; then
  fail "device window before t13-seed"
fi
if ! adb shell am instrument -w -r \
  -e allowSyntheticMediaProbe true \
  -e class "com.tabula.v3.FullRecoveryPhase1Test" \
  "$TEST_ID/$RUNNER" | tee device-results/t13-seed/phase1.txt; then
  fail "Phase1 instrumentation failed"
fi
if ! grep -Eq '^OK \(1 tests?\)' device-results/t13-seed/phase1.txt; then
  fail "Phase1 expected OK(1)"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' \
  device-results/t13-seed/phase1.txt; then
  fail "Phase1 failure or skip observed"
fi
if ! python3 tools/run-volume-device-probe.py window after t13-seed; then
  fail "device window after t13-seed"
fi
if ! adb pull "$EXPECTED_STATE_REMOTE" device-results/t13-seed/full-recovery-expected-state.json; then
  fail "expected-state.json pull failed"
fi

# ===== 3. root 注入：元数据 + 哨兵 + 冷却种子 =====
adb root > /dev/null 2>&1 || true
adb wait-for-device
device_uid=$(adb shell id -u | tr -d '\r')
if [ "$device_uid" != "0" ]; then
  echo "adb root unavailable (google_apis image required for metadata injection)" >&2
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

# 3a. 哨兵（升级前写入 app 私有 files/；升级后由用例 1 断言幸存）
adb shell "echo $SENTINEL_CONTENT > $APP_FILES/$SENTINEL_NAME"
fix_owner "$APP_FILES/$SENTINEL_NAME" || fail "sentinel chown"
sentinel_read=$(adb shell "cat $APP_FILES/$SENTINEL_NAME" | tr -d '\r')
[ "$sentinel_read" = "$SENTINEL_CONTENT" ] || fail "sentinel write/readback mismatch"

# 3b. albums_metadata.json（43a8da1 AlbumManager.AlbumMetadata 序列化格式；
#     UpgAlbum 无系统 bucket 支撑 → 升级后走空图集重建路径）
cat > device-results/t13-seed/albums-metadata-injected.json <<'JSON'
[{"name":"UpgAlbum","color":-1,"textColor":-1,"order":1,"createdAt":1731273600000,"isHidden":true,"isExcludedFromRecommend":false,"coverImageId":-1,"coverVolumeName":""}]
JSON
adb push device-results/t13-seed/albums-metadata-injected.json /data/local/tmp/t13-albums-metadata.json
adb shell "cat /data/local/tmp/t13-albums-metadata.json > $APP_FILES/albums_metadata.json"
adb shell rm -f /data/local/tmp/t13-albums-metadata.json
fix_owner "$APP_FILES/albums_metadata.json" || fail "metadata chown"
adb shell "cat $APP_FILES/albums_metadata.json" > device-results/t13-seed/albums-metadata-readback.json
grep -q '"isHidden":true' device-results/t13-seed/albums-metadata-readback.json \
  || fail "injected albums_metadata.json must carry isHidden:true"

# 3c. 冷却种子（判据 9 的"冷却计数"实体）：按 expected-state 成员身份键写
#     tabula_pick_timestamps.xml（生产 getPickCooldownIndex 读取格式：
#     time_<identityKey>=pickedAt 毫秒 + days_<identityKey>=天数）。
#     pickedAt 取当前时间保证未过期（过期记录生产读取时跳过）。
members_json=$(grep -o '"members":\[[^]]*\]' device-results/t13-seed/full-recovery-expected-state.json | head -1)
members_json=${members_json#\"members\":}
members_json=${members_json#*[}
members_json=${members_json%]}
members_json=${members_json//\"/}
IFS=',' read -r -a member_keys <<<"$members_json"
if [ "${#member_keys[@]}" -lt 2 ]; then
  echo "unexpected members array in expected-state: '$members_json'" >&2
  exit 1
fi
displaynames_json=$(grep -o '"displayNames":\[[^]]*\]' device-results/t13-seed/full-recovery-expected-state.json | head -1)
displaynames_json=${displaynames_json#\"displayNames\":}
displaynames_json=${displaynames_json#*[}
displaynames_json=${displaynames_json%]}
displaynames_json=${displaynames_json//\"/}
picked_at=$(( $(date +%s) * 1000 - 60000 ))
days_cycle=(7 12 24)
cooldown_xml="/data/data/$APP_ID/shared_prefs/tabula_pick_timestamps.xml"
{
  echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
  echo "<map>"
  i=0
  for key in "${member_keys[@]}"; do
    days=${days_cycle[$(( i % 3 ))]}
    printf '    <long name="time_%s" value="%s" />\n' "$key" "$picked_at"
    printf '    <int name="days_%s" value="%s" />\n' "$key" "$days"
    i=$(( i + 1 ))
  done
  echo "</map>"
} > device-results/t13-seed/tabula-pick-timestamps.xml
adb push device-results/t13-seed/tabula-pick-timestamps.xml /data/local/tmp/t13-pick-timestamps.xml
adb shell "cat /data/local/tmp/t13-pick-timestamps.xml > $cooldown_xml"
adb shell rm -f /data/local/tmp/t13-pick-timestamps.xml
sp_dir=/data/data/$APP_ID/shared_prefs
sp_uid=$(adb shell stat -c %u "$sp_dir" | tr -d '\r')
sp_gid=$(adb shell stat -c %g "$sp_dir" | tr -d '\r')
adb shell chown "$sp_uid:$sp_gid" "$cooldown_xml"
adb shell chmod 600 "$cooldown_xml"
adb shell restorecon "$cooldown_xml" || true
adb shell "cat $cooldown_xml" | grep -q 'time_' || fail "cooldown seed write/readback"

# ===== 4. 启动 OLD 应用一次（读入注入元数据）→ 验证元数据未被重写 → force-stop =====
adb shell am start -W -n "$APP_ID/com.tabula.v3.MainActivity" > device-results/t13-seed/old-start.txt 2>&1 || true
sleep 6
if [ -z "$(adb shell pidof "$APP_ID" | tr -d '\r')" ]; then
  fail "OLD app process not alive after am start (startup crash?)"
fi
adb shell "cat $APP_FILES/albums_metadata.json" > device-results/t13-seed/albums-metadata-after-old-read.json
grep -q '"isHidden":true' device-results/t13-seed/albums-metadata-after-old-read.json \
  || fail "OLD app rewrote albums_metadata.json losing isHidden:true"
adb shell am force-stop "$APP_ID" || true
mkdir -p device-results/t13-pre-upgrade
adb shell "cat $APP_DATA/shared_prefs/tabula_album_cleanup.xml" > device-results/t13-pre-upgrade/tabula_album_cleanup.xml 2>/dev/null || true
adb shell "cat $cooldown_xml" > device-results/t13-pre-upgrade/tabula_pick_timestamps.xml 2>/dev/null || true

# ===== 5. Phase OVERLAY：覆盖安装 NEW（同 applicationId / 同 versionCode 5 / 同签名） =====
adb shell dumpsys package "$APP_ID" | grep -E "lastUpdateTime|versionName" > device-results/t13-package-before.txt || true
adb shell am force-stop "$APP_ID" || true
overlay_out=$(adb install --no-streaming -r device-apks/new/app-debug.apk 2>&1) || true
{ echo "== OVERLAY install -r NEW app =="; echo "$overlay_out"; } >> device-results/t13-installs.txt
grep -q "^Success" <<<"$overlay_out" || fail "overlay install must return Success"
grep -qi "signatures do not match" <<<"$overlay_out" && fail "signature conflict on overlay install"
grep -q "INSTALL_FAILED" <<<"$overlay_out" && fail "overlay install failed (INSTALL_FAILED)"
adb shell dumpsys package "$APP_ID" | grep -E "lastUpdateTime|versionName" > device-results/t13-package-after.txt || true

install_apk device-apks/new/app-debug-androidTest.apk "NEW androidTest" || fail "NEW androidTest install"
adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES \
  || fail "pm grant READ_MEDIA_IMAGES (NEW)"

# ===== 6. Phase VERIFY 用例 1：升级幸存（判据 5/6/7/8 生产面/10） =====
mkdir -p device-results/t13-verify
if ! python3 tools/run-volume-device-probe.py window before t13-verify; then
  fail "device window before t13-verify"
fi
if ! adb shell am instrument -w -r \
  -e allowSyntheticMediaProbe true \
  -e class "com.tabula.v3.UpgradeOverlayDeviceTest#verifySurvivalAfterOverlay" \
  "$TEST_ID/$RUNNER" | tee device-results/t13-verify/survival.txt; then
  fail "survival instrumentation failed"
fi
if ! grep -Eq '^OK \(1 tests?\)' device-results/t13-verify/survival.txt; then
  fail "survival expected OK(1)"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' \
  device-results/t13-verify/survival.txt; then
  fail "survival failure or skip observed"
fi
if ! python3 tools/run-volume-device-probe.py window after t13-verify; then
  fail "device window after t13-verify"
fi
adb pull "$EXPECTED_STATE_REMOTE" device-results/t13-verify/full-recovery-expected-state.json || true
adb pull "/sdcard/Android/data/$APP_ID/files/upgrade-overlay-snapshot.json" device-results/t13-verify/ || true
adb pull "/sdcard/Android/data/$APP_ID/files/upgrade-overlay-evidence.json" device-results/t13-verify/ || true

# ===== 7. 媒体真值 shell 面（判据 8 双面之一；生产面已在用例 1 内断言） =====
# 注：MediaProvider 的可见性白名单覆盖 shell uid(2000)，不覆盖 uid 0——adb root
# 之下 content query 对图像行返回空（R1 实测）。先 root 直查；无命中则 unroot
# 以 shell 身份重查；两份原始输出均留证。
names=${displaynames_json//,/ }
media_query() {
  adb shell content query --uri content://media/external/images/media \
    --projection _display_name 2>&1 | tr -d '\r'
}
media_rows=$(media_query)
media_mode="root(uid 0)"
first_name=${names%% *}
if [ -z "$first_name" ] || ! grep -qF "$first_name" <<<"$media_rows"; then
  adb unroot > /dev/null 2>&1 || true
  adb wait-for-device
  sleep 3
  media_rows=$(media_query)
  media_mode="shell(uid 2000)"
  adb root > /dev/null 2>&1 || true
  adb wait-for-device
fi
{ echo "query mode: $media_mode"; echo "--- raw rows ---"; echo "$media_rows"; \
  echo "--- per-name counts ---"; } > device-results/t13-media-truth-shell.txt
for name in $names; do
  count=$(grep -cF "_display_name=$name" <<<"$media_rows")
  echo "$name rows=$count" >> device-results/t13-media-truth-shell.txt
  [ "$count" = "1" ] || fail "shell media truth: $name expected exactly 1 row, got $count"
done

# ===== 8. 两次 am start 冷重启（判据 9 的宿主侧操作） =====
for round in 1 2; do
  adb shell am start -W -n "$APP_ID/com.tabula.v3.MainActivity" > "device-results/t13-restart-$round.txt" 2>&1 || true
  sleep 8
  if [ -z "$(adb shell pidof "$APP_ID" | tr -d '\r')" ]; then
    fail "app process not alive after cold restart round $round (startup crash?)"
  fi
  adb shell am force-stop "$APP_ID" || true
done

# ===== 9. Phase VERIFY 用例 2：偏好幂等（判据 9） =====
mkdir -p device-results/t13-idempotency
if ! python3 tools/run-volume-device-probe.py window before t13-idempotency; then
  fail "device window before t13-idempotency"
fi
if ! adb shell am instrument -w -r \
  -e allowSyntheticMediaProbe true \
  -e class "com.tabula.v3.UpgradeOverlayDeviceTest#verifyIdempotentAfterRestarts" \
  "$TEST_ID/$RUNNER" | tee device-results/t13-idempotency/idempotency.txt; then
  fail "idempotency instrumentation failed"
fi
if ! grep -Eq '^OK \(1 tests?\)' device-results/t13-idempotency/idempotency.txt; then
  fail "idempotency expected OK(1)"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' \
  device-results/t13-idempotency/idempotency.txt; then
  fail "idempotency failure or skip observed"
fi
if ! python3 tools/run-volume-device-probe.py window after t13-idempotency; then
  fail "device window after t13-idempotency"
fi

# ===== 8. 轮询等待语义回归（d541b3c 补，2026-11-12 执行记录补齐：run 43066100515
# 用方法级过滤未覆盖这两个 @Test——本段显式驱动并留设备执行记录） =====
mkdir -p device-results/t13-waitsem
for m in awaitUntilMustFailOnPersistentFalse awaitUntilMustPassAsSoonAsConditionTurnsTrue; do
  if ! adb shell am instrument -w -r     -e allowSyntheticMediaProbe true     -e class "com.tabula.v3.UpgradeOverlayDeviceTest#$m"     "$TEST_ID/$RUNNER" | tee "device-results/t13-waitsem/$m.txt"; then
    fail "wait-semantics instrumentation failed: $m"
  fi
  if ! grep -Eq '^OK \(1 tests?\)' "device-results/t13-waitsem/$m.txt"; then
    fail "wait-semantics expected OK(1): $m"
  fi
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]'     "device-results/t13-waitsem/$m.txt"; then
    fail "wait-semantics failure or skip observed: $m"
  fi
done

# ===== 10. logcat FATAL 门（判据 10）+ 升级后状态留证 =====
adb logcat -d -v threadtime > device-results/t13-logcat-final.txt 2>/dev/null || true
if grep -q "FATAL EXCEPTION" device-results/t13-logcat-final.txt; then
  grep -A 8 "FATAL EXCEPTION" device-results/t13-logcat-final.txt | head -60 > device-results/t13-fatal-context.txt
  fail "FATAL EXCEPTION observed in logcat (see t13-fatal-context.txt)"
fi

mkdir -p device-results/t13-post-upgrade
adb shell "cat $APP_FILES/albums_metadata.json" > device-results/t13-post-upgrade/albums_metadata.json 2>/dev/null || true
grep -q '"isHidden":true' device-results/t13-post-upgrade/albums_metadata.json \
  || fail "post-upgrade albums_metadata.json must retain isHidden:true"
adb shell "cat $APP_DATA/files/recycle_bin.json" > device-results/t13-post-upgrade/recycle_bin.json 2>/dev/null || true
adb shell "cat $APP_DATA/shared_prefs/tabula_album_cleanup.xml" > device-results/t13-post-upgrade/tabula_album_cleanup.xml 2>/dev/null || true
adb shell "cat $cooldown_xml" > device-results/t13-post-upgrade/tabula_pick_timestamps.xml 2>/dev/null || true

if [ "$status" != "0" ]; then
  echo "T13 upgrade verification FAILED (see device-results/ evidence)" >&2
else
  echo "T13 upgrade verification: all gates green"
fi
exit "$status"
