#!/usr/bin/env bash
# #89 模拟器取证：真实设置页 → 漫步来源策略 → 卡片页换批。
#
# ## 为什么是独立脚本文件
#
# `reactivecircus/android-emulator-runner` 的 `script:` 不是"写文件再执行"，
# 而是把内容**逐行**用 `/usr/bin/sh -c` 跑（Ubuntu 上 sh 是 dash）。这带来
# 两个真实踩过的坑：
#
#   1. 跨行 `if ... fi` 直接语法错误：
#      `/usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "fi")`
#      ——run 45372299838 就是这个。更糟的是它把真实的断言失败
#      （`FAILURES!!! Tests run: 5, Failures: 1`）盖成了 exit 2 的语法错误。
#   2. 每一行是独立进程，`INSTRUMENT_EXIT=$?` 拿不到上一行 `am instrument`
#      的退出码（实测该变量恒为空，随后 `[ "" -ne 0 ]` 又是错误）。
#
# 所以 workflow 里只留一行 `bash tools/run-walk-strategy-evidence.sh`。
#
# ## 退出码契约
#
#   0 = 5 条用例全部通过（`OK (5 tests)` 精确匹配）
#   1 = 有断言失败（实现问题，必须修）
#   2 = 环境中断 / 证据缺失（**未完成，不是通过**）
#
# 断言失败优先于环境中断：模拟器抖动不能把真失败盖掉。
#
# ## 环境变量
#
#   OUT_DIR        结果目录（默认 device-results）
#   APK_APP        应用 APK
#   APK_TEST       androidTest APK
#   TEST_CLASS     用例类（默认 com.tabula.v3.WalkStrategySettingsDeviceTest）
#   EXPECTED_TESTS 期望用例数（默认 5）
#   INSTRUMENT_CMD 覆盖整个 instrumentation 命令（单测用，不碰模拟器）
set +e

OUT_DIR="${OUT_DIR:-device-results}"
APK_APP="${APK_APP:-device-apks/app-debug.apk}"
APK_TEST="${APK_TEST:-device-apks/app-debug-androidTest.apk}"
TEST_CLASS="${TEST_CLASS:-com.tabula.v3.WalkStrategySettingsDeviceTest}"
EXPECTED_TESTS="${EXPECTED_TESTS:-5}"
RUNNER="com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner"
INSTRUMENT_CMD="${INSTRUMENT_CMD:-}"

mkdir -p "$OUT_DIR"
WALK_LOG="$OUT_DIR/instrumentation-walk.txt"

echo "=== 安装 APK ==="
if ! adb install -r "$APK_APP" || ! adb install -r "$APK_TEST"; then
  echo "::error::安装 APK 失败，设备测试未执行（环境中断）"
  echo "instrumentation exit=2" > "$OUT_DIR/instrumentation-exit.txt"
  exit 2
fi

adb shell pm grant com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_IMAGES 2>/dev/null
adb shell pm grant com.tabula.v3.recyclebin.test android.permission.READ_MEDIA_VISUAL_USER_SELECTED 2>/dev/null

echo "=== 运行 $TEST_CLASS ==="
if [ -n "$INSTRUMENT_CMD" ]; then
  # 单测入口：给定命令，不走 adb
  sh -c "$INSTRUMENT_CMD" > "$WALK_LOG" 2>&1
  AM=$?
else
  timeout 900s adb shell am instrument -w -r \
    -e class "$TEST_CLASS" "$RUNNER" > "$WALK_LOG" 2>&1
  AM=$?
fi
cat "$WALK_LOG"
echo "instrumentation exit=$AM" | tee "$OUT_DIR/instrumentation-exit.txt"

# 诊断材料：失败时也要有，全部容忍失败
adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'" > "$OUT_DIR/window-focus.txt" 2>/dev/null
adb shell "cat /data/data/com.tabula.v3.recyclebin.test/files/diag-timeout.txt" > "$OUT_DIR/diag-timeout.txt" 2>/dev/null
grep -E 'INSTRUMENTATION_STATUS: (class|numtests|test=)|OK \(|FAILURES' "$WALK_LOG" > "$OUT_DIR/summary.txt" 2>/dev/null
cat "$OUT_DIR/summary.txt" 2>/dev/null

echo "=== 判定 ==="
if [ "$AM" -eq 124 ]; then
  echo "::error::$TEST_CLASS 超时（900s），取证未完成"
  echo "result=environment_interrupted"
  exit 2
fi

if grep -q "FAILURES!!!" "$WALK_LOG"; then
  # 断言失败必须失败：这是实现问题，不是环境问题
  echo "::error::$TEST_CLASS 断言失败（见 instrumentation-walk.txt）"
  echo "result=assertion_failed"
  exit 1
fi

if grep -qxF "OK ($EXPECTED_TESTS tests)" "$WALK_LOG"; then
  echo "result=pass"
  exit 0
fi

if [ "$AM" -ne 0 ]; then
  echo "::error::$TEST_CLASS 未能执行完（am exit=$AM），取证未完成"
  echo "result=environment_interrupted"
  exit 2
fi

# 退出码 0 但拿不到精确的 `OK (N tests)`：不能算通过（可能是 0 条用例跑过）
echo "::error::$TEST_CLASS 没有产出 'OK ($EXPECTED_TESTS tests)' 结论，取证未完成"
echo "result=environment_interrupted"
exit 2
