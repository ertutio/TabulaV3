# 智谱启动与续接

复制下段给实际执行者。当前游标、队列及唯一下一动作只在 [DEVELOPMENT_PROGRESS.md](DEVELOPMENT_PROGRESS.md)，执行过程和升级规则只在 [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md)，T/TC 安全与验收规格在 [docs/acceptance.md](../docs/acceptance.md)。

> 执行 ertutio/TabulaV3 北京时间 2026-10-31 夜至 11-01 早的连续开发方案。先获取实际 refs，读取计划分支 `docs/continuous-development-2026-10-28` 的 `AGENTS.md`、`.ua/DEVELOPMENT_ROADMAP.md`、`.ua/DEVELOPMENT_PROGRESS.md`、`docs/acceptance.md`，核对实际最新 master、PR、未提交文件与所有权及运行中 CI；从进度文件唯一 next_action 开始。生产实现从实际最新 master 建范围明确的分支，计划 PR #12 保持开放且不能整体合入代码 PR。按路线图逐行为单元完成有效反例 FAIL、最小修复、同门禁 PASS 与相邻回归、未参与实现的新会话独立只读审计及闭环、冻结 SHA 和按授权保存 commit/PR，然后继续最早 ready 项。共享契约验证或审计未清时暂停依赖，推进独立项。复用有效证据，保留安全保护与跨会话失败计数；按规则升级，遇到中断先落盘，晨间交付已实现待复核、未验证、阻塞及恢复点。智谱没有另一平台同名技能或模型时，使用已验证的平台能力并记录缺口；没有独立会话标 review_pending，自查不能称独立 PASS。实际提交、推送、开 PR 和 CI 以你收到的启动授权为准；本计划不授予自动合并、默认分支推送、签名发布、真实媒体操作、提权或付费调用。

本次文档更新尚未启动智谱、自动化或生产执行。恢复时核对进度记录与实际状态；计划资料若不在 master，显式读取对应 plan ref，保留版本引用，避免混入旧实现代码。工具和技能按当前平台实际能力选择；能力缺失按路线图保存 blocker 并转可独立工作。
