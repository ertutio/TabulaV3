#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
capture() {
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
for class in CloudCleanupUiTest CloudRestoreUiTest; do
  adb shell pm clear com.tabula.v3.recyclebin.test | tr -d '\r' | grep -Fx Success
  output="device-results/${class}.txt"
  timeout 300s adb shell am instrument -w -r \
    -e allowSyntheticMediaProbe true \
    -e class "com.tabula.v3.${class}" \
    com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner | tee "$output"
  grep -Eq '^OK \(2 tests\)' "$output"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' "$output"
done
