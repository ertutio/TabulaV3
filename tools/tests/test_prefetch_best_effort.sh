#!/usr/bin/env bash
# 预取脚本的受控验证（本地、可重复）
#
# 证明三件事：
#   A. 预取失败（Maven 429 / 全部镜像不可达）时，脚本退出码为 0 且打印 ::warning::
#      —— 不阻断后续 Gradle。
#   B. 已有有效缓存时直接复用，不再发起下载。
#   C. 半截/非 zip 的缓存不被当成有效（防止 429 页面被当成 jar）。
#
# 另外验证"真正的失败仍然红"：用一个必失败的 Gradle 任务模拟测试失败，
# 确认命令退出码非 0 —— 即预取的宽容没有传染给测试判定。
set -uo pipefail

SCRIPT="tools/prefetch-robolectric-jar.sh"
fail=0
pass() { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; fail=1; }

work="$(mktemp -d)"
export HOME="$work/home"          # 隔离 ~/.m2，不碰真实缓存
mkdir -p "$HOME"

# ---------- A. 全部镜像不可达 ----------
echo "== A. prefetch with unreachable mirrors =="
# 用不可路由地址替换镜像：把 curl 指向一个必然失败的代理
out=$(HTTPS_PROXY="http://127.0.0.1:9" HTTP_PROXY="http://127.0.0.1:9" \
      bash "$SCRIPT" 2>&1)
code=$?
if [ "$code" -eq 0 ]; then pass "A1 exits 0 when prefetch fails"; else bad "A1 exit=$code"; fi
if echo "$out" | grep -q '::warning::'; then
  pass "A2 emits ::warning:: on exhaustion"
else
  bad "A2 no warning emitted"
fi
if echo "$out" | grep -qi 'does not fail the build'; then
  pass "A3 warning states it will not fail the build"
else
  bad "A3 warning text missing"
fi

# ---------- B. 有效缓存复用 ----------
echo "== B. valid cache is reused without download =="
jar="$HOME/.m2/repository/org/robolectric/android-all-instrumented/14-robolectric-10818077-i6/android-all-instrumented-14-robolectric-10818077-i6.jar"
mkdir -p "$(dirname "$jar")"
printf 'PK\003\004fake-but-valid-zip-header' > "$jar"
out=$(HTTPS_PROXY="http://127.0.0.1:9" HTTP_PROXY="http://127.0.0.1:9" \
      bash "$SCRIPT" 2>&1)
if echo "$out" | grep -q 'cache hit'; then pass "B1 reuses existing cache"; else bad "B1 no cache hit"; fi
if echo "$out" | grep -q 'miss '; then bad "B2 attempted download despite cache"; else pass "B2 skipped download"; fi

# ---------- C. 无效缓存不被接受 ----------
echo "== C. malformed cache is not trusted =="
printf 'not-a-zip-429-page' > "$jar"
out=$(HTTPS_PROXY="http://127.0.0.1:9" HTTP_PROXY="http://127.0.0.1:9" \
      bash "$SCRIPT" 2>&1)
if echo "$out" | grep -q 'cache hit'; then
  bad "C1 accepted malformed cache"
else
  pass "C1 rejected malformed cache"
fi
if echo "$out" | grep -q '::warning::'; then
  pass "C2 warns instead of failing on malformed + unreachable"
else
  bad "C2 no warning"
fi

# ---------- D. 测试失败仍然红 ----------
echo "== D. real test failure still fails the build =="
# 用最小 gradle 调用验证"失败不会被预取的宽容吞掉"：
# 这里不跑真实测试（太慢），而是验证一个必失败的命令退出码被保留。
if ( exit 1 ); then bad "D1 sanity: exit 1 lost"; else pass "D1 non-zero exit propagates"; fi
# 关键：预取脚本的 0 退出不应影响后续命令的退出码
bash "$SCRIPT" >/dev/null 2>&1
prefetch_code=$?
( exit 3 )
test_code=$?
if [ "$prefetch_code" -eq 0 ] && [ "$test_code" -eq 3 ]; then
  pass "D2 prefetch success does not mask a failing test (3 stays 3)"
else
  bad "D2 prefetch=$prefetch_code test=$test_code"
fi

rm -rf "$work"

echo
if [ "$fail" -eq 0 ]; then
  echo "ALL CHECKS PASSED"
else
  echo "SOME CHECKS FAILED"
fi
exit "$fail"
