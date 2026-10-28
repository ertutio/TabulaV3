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
for class in CloudCleanupUiTest CloudRestoreUiTest; do
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
  if ! grep -Eq '^OK \(2 tests\)' "$output"; then
    echo "Expected two passing tests in $class" >&2
    status=1
  fi
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' "$output"; then
    echo "Failure or skip observed in $class" >&2
    status=1
  fi
  adb logcat -d -v threadtime > "$dir/logcat.txt" || true
  adb pull /sdcard/Android/data/com.tabula.v3.recyclebin.test/files/cloud-ui-failures "$dir/ui-failures" || true
done
exit "$status"
