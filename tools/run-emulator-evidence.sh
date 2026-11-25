#!/usr/bin/env bash
# 模拟器取证：预览页探针 + 真实回收站查看器设备测试。
#
# ## 为什么是独立脚本文件，而不是直接写在 workflow 的 `script:` 里
#
# `reactivecircus/android-emulator-runner` 的 `script:` 不是"写一个文件然后
# 执行"——它把内容**逐行**用 `/usr/bin/sh -c` 跑（Ubuntu 上 sh 是 dash）。
# 这带来两个坑，两个都真实踩过：
#
#   1. `/usr/bin/sh: 1: set: Illegal option -o pipefail`
#      dash 不支持 pipefail，脚本第一行就退出 2，探针和设备测试一行都没跑，
#      job 红了但跟实现毫无关系（run 45368399281 的三个 API 全是这个）。
#   2. 跨行变量、跨行 `if` 都不可靠：每一行是独立进程，`PROBE=$?` 拿不到
#      上一行的退出码。
#
# 所以 workflow 里只留一行 `bash tools/run-emulator-evidence.sh`，逻辑全部
# 放进这个文件：bash 语义确定、可本地跑、可 review。
#
# ## 退出码契约（与 tools/summarize-glass-evidence.py 对齐）
#
#   0 = 探针与设备测试都通过
#   1 = 有断言失败（实现问题，必须修）
#   2 = 环境中断 / 证据缺失（**未完成，不是通过**）
#
# 断言失败优先于环境中断。
#
# ## 环境变量
#
#   API_LEVEL       目标 API（默认取 $API_LEVEL，缺省 33）
#   VIEWER_ATTEMPTS 设备测试在环境中断时的尝试次数（默认 2；断言失败不重试）
#   TIMEOUT_CMD     超时保护命令（默认 `timeout`；置空则不加超时，供本地单测）
#   DEVICE_WAIT_SECONDS 等设备就绪的轮数上限（默认 30，每轮 2s；0 = 不等）
#   OUT_DIR     结果目录（默认 device-results）
#   APK_APP     应用 APK 路径
#   APK_TEST    androidTest APK 路径
#   PROBE_CMD   探针命令（默认 python tools/run-liquid-glass-preview-probe.py）
set +e

API_LEVEL="${API_LEVEL:-33}"
OUT_DIR="${OUT_DIR:-device-results}"
APK_APP="${APK_APP:-app/build/outputs/apk/debug/app-debug.apk}"
APK_TEST="${APK_TEST:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
PROBE_CMD="${PROBE_CMD:-python tools/run-liquid-glass-preview-probe.py}"

TEST_PKG="com.tabula.v3.recyclebin.test"
RUNNER="$TEST_PKG.test/androidx.test.runner.AndroidJUnitRunner"
VIEWER_CLASS="com.tabula.v3.RecycleBinViewerBackdropDeviceTest"
# 超时保护用哪个命令。默认 `timeout`（CI 上就是 coreutils 的那个）；
# 本地单测置空即直接调用 adb——MSYS 的 timeout.exe 无法 exec shell 脚本。
TIMEOUT_CMD="${TIMEOUT_CMD-timeout}"

mkdir -p "$OUT_DIR"

echo "=== API $API_LEVEL: 真实查看器设备测试（先跑：会话越短越不容易掉线）==="
VIEWER=0
if [ "$API_LEVEL" -ge 33 ]; then
  # 采样层要求 API 33+。低版本写 `na`：不算通过，也不把合法矩阵项判成未完成。
  VIEWER=2

  # `Boot completed` 打印之后 adb 还要一小会儿才认设备，此时 install 直接
  # 报 `device offline`（run 45400686137 的 API 35）。先等设备就绪，有上限。
  # 等待上限可配：CI 用默认 30 次 × 2s；单测置 DEVICE_WAIT_SECONDS=0 立即返回。
  wait_for_device() {
    WAIT=0
    while [ "$WAIT" -lt "${DEVICE_WAIT_SECONDS:-30}" ]; do
      if adb get-state 2>/dev/null | grep -q device; then
        return 0
      fi
      sleep 2
      WAIT=$((WAIT + 1))
    done
    # 上限为 0 时也算"不等了"，返回当前状态而不是无条件失败
    adb get-state 2>/dev/null | grep -q device
  }

  install_apks() {
    install_attempt=1
    while [ "$install_attempt" -le 3 ]; do
      if adb install -r -t "$APK_APP" && adb install -r -t "$APK_TEST"; then
        return 0
      fi
      echo "::warning::安装 APK 失败（第 $install_attempt 次），等设备就绪后重试"
      wait_for_device
      sleep 3
      install_attempt=$((install_attempt + 1))
    done
    return 1
  }

  wait_for_device

  # 应用 APK 缺失是真的装不上；androidTest APK 缺失通常意味着**本分支没有
  # 设备测试**（例如 #91 的 RecycleBinViewerBackdropDeviceTest 属于 #92）。
  # 后者判 `na`：不冒充通过，也不假装"环境中断"把 PR 永远卡住。
  if [ ! -f "$APK_TEST" ]; then
    echo "viewer exit=na  # 本分支没有 androidTest APK（$APK_TEST 不存在）" \
      | tee "$OUT_DIR/viewer-exit.txt"
    VIEWER=0
    SKIP_VIEWER=1
  fi

  if [ "${SKIP_VIEWER:-0}" -ne 1 ] && install_apks; then
    adb shell pm grant "$TEST_PKG" android.permission.READ_MEDIA_IMAGES 2>/dev/null
    adb shell pm grant "$TEST_PKG" android.permission.READ_MEDIA_VISUAL_USER_SELECTED 2>/dev/null

    ATTEMPT=1
    while [ "$ATTEMPT" -le "${VIEWER_ATTEMPTS:-2}" ]; do
      echo "--- viewer attempt $ATTEMPT ---"
      if [ -n "$TIMEOUT_CMD" ]; then
        $TIMEOUT_CMD 900s adb shell am instrument -w -r \
          -e allowSyntheticMediaProbe true \
          -e class "$VIEWER_CLASS" \
          "$RUNNER" > "$OUT_DIR/instrumentation-viewer.txt" 2>&1
      else
        adb shell am instrument -w -r \
          -e allowSyntheticMediaProbe true \
          -e class "$VIEWER_CLASS" \
          "$RUNNER" > "$OUT_DIR/instrumentation-viewer.txt" 2>&1
      fi
      AM=$?
      cat "$OUT_DIR/instrumentation-viewer.txt"

      if [ "$AM" -eq 124 ]; then
        echo "::error::真实查看器设备测试超时（900s），取证未完成"
        VIEWER=2
        break
      fi
      if grep -q "FAILURES!!!" "$OUT_DIR/instrumentation-viewer.txt"; then
        # 断言失败必须失败：这是实现问题，重跑没有意义
        echo "::error::真实查看器设备测试断言失败（见 instrumentation-viewer.txt）"
        VIEWER=1
        break
      fi
      if grep -qE "^OK \([0-9]+ tests\)" "$OUT_DIR/instrumentation-viewer.txt"; then
        echo "viewer device test: OK"
        VIEWER=0
        break
      fi

      # 没有结论：先看设备还在不在，等它回来再试一次
      VIEWER=2
      if [ "$ATTEMPT" -lt "${VIEWER_ATTEMPTS:-2}" ]; then
        echo "::warning::设备测试没有产出结论（am exit=$AM），等设备恢复后重试"
        wait_for_device
        sleep 5
      else
        echo "::error::真实查看器设备测试未能执行完（am exit=$AM），取证未完成"
      fi
      ATTEMPT=$((ATTEMPT + 1))
    done
  elif [ "${SKIP_VIEWER:-0}" -ne 1 ]; then
    echo "::error::安装 APK 失败，设备测试未执行（环境中断）"
    VIEWER=2
  fi
  if [ "${SKIP_VIEWER:-0}" -ne 1 ]; then
    echo "viewer exit=$VIEWER" | tee "$OUT_DIR/viewer-exit.txt"
  fi
else
  echo "viewer exit=na  # API $API_LEVEL < 33，无采样层" | tee "$OUT_DIR/viewer-exit.txt"
  VIEWER=0
fi

echo "=== API $API_LEVEL: 预览页探针 ==="
# 用 `sh -c` 执行整串：PROBE_CMD 可能带参数或引号（测试里就传
# `bash -c 'exit 1'`）。直接 `$PROBE_CMD` 会按空格拆开，引号被拆散。
sh -c "$PROBE_CMD"
PROBE=$?
echo "probe exit=$PROBE"

echo "=== 合并结论 ==="
echo "probe=$PROBE viewer=$VIEWER"
if [ "$PROBE" -eq 1 ] || [ "$VIEWER" -eq 1 ]; then
  echo "result=assertion_failed"
  exit 1
fi
if [ "$PROBE" -eq 2 ] || [ "$VIEWER" -eq 2 ]; then
  echo "result=environment_interrupted"
  exit 2
fi
echo "result=pass"
exit 0
