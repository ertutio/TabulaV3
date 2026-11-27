# 文字辅助配置弹层 · 大字体实测现场（2026-11-27）

PR #98 第 3 轮。本目录是**原始 instrumentation 输出**，未做编辑。

## 设备

| 项 | 值 |
| --- | --- |
| 模拟器 AVD | `intent94` |
| 型号 | `sdk_gphone64_x86_64`（`ro.hardware=ranchu`） |
| API | **36** |
| ABI | `x86_64` |
| 字体 | 大字体用例跑在 `font_scale=1.5`；两条回归跑在 `1.0` |

## 文件

| 文件 | 用例 | 结果 |
| --- | --- | --- |
| `lf.txt` | `IntentAssistDialogLargeFontDeviceTest`（font_scale=1.5） | **OK (3 tests)** |
| `sw5.txt` | `IntentAssistServiceSwitchDeviceTest` | **OK (5 tests)** |
| `pf-full.txt` | `IntentBatchPageFlowTest` | **OK (7 tests)** |

复现命令：

```bash
# 大字体（先设字体，instrumentation 起不来时先 force-stop 目标包）
adb shell settings put system font_scale 1.5
adb shell am force-stop com.tabula.v3.recyclebin.test
adb shell am instrument -w -r \
  -e class com.tabula.v3.IntentAssistDialogLargeFontDeviceTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner

# 回归
adb shell settings put system font_scale 1.0
adb shell am instrument -w -r -e class com.tabula.v3.IntentAssistServiceSwitchDeviceTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.tabula.v3.IntentBatchPageFlowTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner
```

## 截图

`docs/ui-shots/large-font/`（font_scale=1.5，1080×2400）：

| 文件 | 内容 |
| --- | --- |
| `01-dialog-deepseek.png` | DeepSeek 页，底栏 `关闭` / `保存` 完整可见 |
| `02-dialog-opencodego.png` | OpenCode Go 页（文案最长），底栏两个动作仍在 |
| `03-dialog-keyboard.png` | 输入框聚焦 + 键盘弹出，底栏贴在键盘上沿，仍可点 |

## 一个容易误判的覆盖盲区

`switchingServiceRevokesBatchAuthorization` 里目标服务是**未配置 Key** 的。
那种情况下"回页面后开关是关闭的"有退化解：`Switch` 的 `enabled = serviceKeyConfigured`，
没 Key 时它本来就不可能是开启态。所以那条用例只证明了「无 Key 所以开不了」。

本轮补了 `switchingServiceWithBothKeysConfiguredStillRevokesAuthorization`：
两套合成 Key 都写好，切换前后开关都可用；切完仍是「默认关闭」且**不是**「未配置 Key」，
才只能由 `revokeForServiceChange` → `onRebuild` 解释。
