# 普通查看器玻璃采样 · 设备取证（2026-11-27，PR #99 第 3 轮）

## 设备

| 项 | 值 |
| --- | --- |
| 模拟器 AVD | `intent94` |
| 型号 | `sdk_gphone64_x86_64`（`ro.hardware=ranchu`） |
| **API** | **36** |
| ABI | `x86_64` |
| 显示 | 1080×2400，density 420，font_scale 1.0 |

## 文件

| 文件 | 内容 | 结果 |
| --- | --- | --- |
| `render-evidence.txt` | `ViewerGlassRenderEvidenceDeviceTest` 原始输出 | **OK (3 tests)** |
| `01-first-sampled.png` | 查看器现场：照片上下黑边 + 按钮在黑边内 | — |
| `bindirect-master.txt` | master `9f28927` 上跑 `RecycleBinViewerBackdropDeviceTest` | **OK (1 test)** |
| `bindirect-pr99.txt` | #99 HEAD 上跑**同一条**用例（同设备、同启动参数） | **OK (1 test)** |

## 采样坐标：这张图能说明什么

`01-first-sampled.png` 对应的 AUDIT 行（从设备无障碍树原样读出）：

```
AUDIT sampled=true reason=NONE path=glass page=1 ready=true
      plan=SAMPLED planreason=NONE
      glass=126x126 layer=148x148 host=1080x2400
      hostdraw=13 layerdraw=4 buttondraw=4 drawrev=7 rev=7
      effect=Ok drawplan=true
      photorect=27,516,1026x1368
      sync=ok scale=1.0 pan=(0.0,0.0) pager=true
```

- `photorect=27,516,1026x1368` 是**照片实际矩形**：宽 1026 = 1080×95%，
  高 1368 = 1026×4/3（受宽度限制），上下各留 516px 黑边；
- `host=1080x2400` 是整个宿主。**两者不相等**，正是本轮修掉的东西：
  修复前采样层按 `host` 做 Fit，照片会被放大到 1080×2400 并贴满，
  按钮下方本该是黑边的位置会采到照片；
- 截图里能看到照片上下确实是黑边，而关闭按钮落在黑边区域内——
  视觉与读数一致。

## 同一张图的局限

`01-first-sampled.png` 是唯一保留的一张现场图。另外两次 `screencap`
（`02-` / `04-`）与本张**逐字节相同**（md5 一致），因为三次都停在
「缩放已复原、采样已就绪」这一同一稳定态。保留重复文件会让"三处不同现场"
经不起核对，所以只留一张。

## 回收站用例的定向对比

CI 上 `emulator-viewer-evidence (35)` 报 `timed out waiting for 项待处理`。
本轮在同一台设备（API 36）、同一启动参数
（`-e allowSyntheticMediaProbe true -e class ...RecycleBinViewerBackdropDeviceTest`）下：

| 代码版本 | 结果 |
| --- | --- |
| master `9f28927` | **OK (1 test)** |
| #99 HEAD | **OK (1 test)** |

两边都通过，说明这条用例在本机**没有稳定复现**，CI 上的失败更可能是
API 35 模拟器上「进入回收站后计数行没渲染出来」的环境性抖动
（同一条用例在 CI 的 API 33 上一直通过）。本轮没有据此改动该用例。

## 复现命令

```bash
# 渲染证据（普通查看器）
adb shell am force-stop com.tabula.v3.recyclebin.test
adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
  -e class com.tabula.v3.ViewerGlassRenderEvidenceDeviceTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner

# 回收站查看器（定向对比）
adb shell am instrument -w -r -e allowSyntheticMediaProbe true \
  -e class com.tabula.v3.RecycleBinViewerBackdropDeviceTest \
  com.tabula.v3.recyclebin.test.test/androidx.test.runner.AndroidJUnitRunner
```
