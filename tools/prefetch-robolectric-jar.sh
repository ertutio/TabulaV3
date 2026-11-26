#!/usr/bin/env bash
# Robolectric android-all 预取（尽力执行，永不让 CI 失败）
#
# 背景：五个工作流原来各自内联了一段预取，结尾用 `test -s` 硬断言。
# Maven Central 对 GitHub runner 会返回 429，于是预取失败 → 整步失败 →
# build 红，而真正要跑的 Gradle 测试根本没开始。这类红是基础设施噪声，
# 不是代码问题（本仓库已多次被它绊住：45766362618、45783005456 等）。
#
# 本脚本的契约：
#   1. **已有有效缓存就直接复用**，不重复下载；
#   2. 下载先写临时文件，校验通过后才移入缓存目录（不会留下半截文件）；
#   3. 任何失败都只打 ::warning:: 并以 0 退出，让后续 Gradle 正常跑；
#   4. 真正的失败判定留给 Gradle：依赖解析失败、测试失败、设备断言失败
#      仍然会让 CI 红——本脚本不吞这些。
#
# 用法：bash tools/prefetch-robolectric-jar.sh
set -uo pipefail

JAR=android-all-instrumented-14-robolectric-10818077-i6.jar
GROUP_PATH="org/robolectric/android-all-instrumented/14-robolectric-10818077-i6"
DIR="${HOME}/.m2/repository/${GROUP_PATH}"
TARGET="${DIR}/${JAR}"

warn() {
  # GitHub Actions 注解：在 UI 上显示为警告，不改变作业结论
  echo "::warning::$*"
}

# 已有缓存：非空且是 zip（jar 就是 zip）→ 直接复用
if [ -s "$TARGET" ] && head -c 2 "$TARGET" | grep -q 'PK'; then
  echo "android-all cache hit: $TARGET ($(wc -c <"$TARGET") bytes)"
  exit 0
fi

mkdir -p "$DIR" || { warn "cannot create $DIR; Robolectric will fetch at test time"; exit 0; }

MIRRORS=(
  "https://repo.maven.apache.org/maven2"
  "https://maven.aliyun.com/repository/central"
  "https://repo1.maven.org/maven2"
)

for BASE in "${MIRRORS[@]}"; do
  URL="${BASE}/${GROUP_PATH}/${JAR}"
  TMP="$(mktemp "${DIR}/.${JAR}.XXXXXX")" || continue
  if curl -fsSL --retry 2 --retry-delay 3 --max-time 120 -o "$TMP" "$URL"; then
    # 校验：非空 + zip 魔数，防止把 429 页面或截断文件当成 jar
    if [ -s "$TMP" ] && head -c 2 "$TMP" | grep -q 'PK'; then
      mv -f "$TMP" "$TARGET"
      echo "android-all fetched from $BASE ($(wc -c <"$TARGET") bytes)"
      exit 0
    fi
    echo "rejected malformed payload from $BASE"
  else
    echo "miss $BASE"
  fi
  rm -f "$TMP"
done

warn "Robolectric android-all prefetch exhausted (Maven 429/network). Gradle will retry at test time; this step does not fail the build."
exit 0
