#!/usr/bin/env bash
# 端到端受控验证：预取失败后，真实 Gradle 仍会运行；测试失败仍让整步红。
#
# 与 test_prefetch_best_effort.sh 的分工：
#   - 那个脚本验证预取脚本自身的契约（退出码 / 警告 / 缓存语义）；
#   - 这个脚本跑**真实 Gradle**，证明两件事：
#       E. 预取失败（模拟 Maven 429）之后，:app:compileDebugKotlin 照常执行并成功；
#       F. 一个真实的测试失败会让 Gradle 退出码非 0（红），不会被预取的宽容吞掉。
#
# 用法：bash tools/tests/test_prefetch_then_gradle.sh
# 需要：本机能跑 ./gradlew（JDK 21 + Android SDK，与仓库其余脚本一致）。
set -uo pipefail

SCRIPT="tools/prefetch-robolectric-jar.sh"
fail=0
pass() { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; fail=1; }

# 平台适配：Linux/CI 用 ./gradlew，Windows 用 gradlew.bat
if [ -x "./gradlew" ]; then GRADLE="./gradlew"; else GRADLE="./gradlew.bat"; fi
echo "using gradle wrapper: $GRADLE"

echo "== E. prefetch failure, then real Gradle still runs =="
# 模拟 Maven 429：把 HOME 隔离 + 代理指向不可路由端口
work="$(mktemp -d)"
# 转绝对路径：下面会改 HOME，相对路径会失效
work="$(cd "$work" && pwd)"
export HOME="$work/home"
mkdir -p "$HOME"

HTTPS_PROXY="http://127.0.0.1:9" HTTP_PROXY="http://127.0.0.1:9" \
  bash "$SCRIPT" > "$work/prefetch.log" 2>&1
prefetch_code=$?
echo "prefetch exit=$prefetch_code"
grep -q '::warning::' "$work/prefetch.log" && pass "E1 prefetch warned" || bad "E1 no warning"
[ "$prefetch_code" -eq 0 ] && pass "E2 prefetch exit 0" || bad "E2 exit=$prefetch_code"

# 真实 Gradle：预取失败后必须仍能执行（这里只编译，不跑测试，速度可控）
JAVA_HOME="${JAVA_HOME:-C:/tools/jdk-21}" \
ANDROID_HOME="${ANDROID_HOME:-D:/snh/.tools/android-sdk}" \
  "$GRADLE" --no-daemon -q :app:compileDebugKotlin > "$work/gradle.log" 2>&1
gradle_code=$?
if [ "$gradle_code" -eq 0 ]; then
  pass "E3 Gradle compiled after prefetch failure (exit 0)"
else
  bad "E3 Gradle failed after prefetch failure (exit=$gradle_code)"
  tail -20 "$work/gradle.log"
fi

echo "== F. a real failing test still turns the build red =="
# 注入一个必失败的测试类，跑它，断言 Gradle 非 0，然后删除。
probe="app/src/test/java/com/tabula/v3/CiPrefetchProbeTest.kt"
cat > "$probe" <<'KT'
package com.tabula.v3

import org.junit.Assert.fail
import org.junit.Test

/** 受控验证用：故意失败的测试，验证"测试失败仍让 CI 红"。跑完即删。 */
class CiPrefetchProbeTest {
    @Test
    fun deliberatelyFails() {
        fail("controlled failure: this test must make Gradle exit non-zero")
    }
}
KT

JAVA_HOME="${JAVA_HOME:-C:/tools/jdk-21}" \
ANDROID_HOME="${ANDROID_HOME:-D:/snh/.tools/android-sdk}" \
  "$GRADLE" --no-daemon --console=plain :app:testDebugUnitTest \
  --tests 'com.tabula.v3.CiPrefetchProbeTest' > "$work/fail.log" 2>&1
fail_code=$?
rm -f "$probe"

if [ "$fail_code" -ne 0 ]; then
  pass "F1 failing test makes Gradle exit non-zero (exit=$fail_code)"
else
  bad "F1 Gradle exited 0 despite a failing test"
fi
# 用**测试结果 XML** 判定探针真的跑了，而不是解析 stdout——
# Gradle 的输出流在改了 HOME 之后不稳定（实测 fail.log 会为空）。
xml="app/build/test-results/testDebugUnitTest/TEST-com.tabula.v3.CiPrefetchProbeTest.xml"
if [ -f "$xml" ] && grep -q 'deliberatelyFails' "$xml" && grep -q '<failure' "$xml"; then
  pass "F2 the probe test actually ran and failed (per JUnit XML)"
else
  bad "F2 probe test did not run or did not fail"
  ls -la "$(dirname "$xml")" 2>/dev/null | head -5 || true
  tail -20 "$work/fail.log" 2>/dev/null || true
fi

rm -rf "$work"

echo
if [ "$fail" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit "$fail"
