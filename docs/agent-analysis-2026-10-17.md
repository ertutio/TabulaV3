# TabulaV3：2026-10-17 agent 分析产物

现行唯一开发路线：[PR #9](https://github.com/ertutio/TabulaV3/pull/9)。旧 #8 关闭保留历史。

从 [agent 阅读入口](../.ua/AGENT_INDEX.md) 开始；本轮只完成分析和计划，业务逻辑未修改。默认每轮一个子任务、完成后停止，下一轮只做 T01a。

- [正式路线正文快照](../.ua/DEVELOPMENT_ROADMAP.md)
- [结构化分析](../.ua/agent-analysis.json) / [知识图谱](../.ua/knowledge-graph.json)
- [逐项重评](../.ua/PR8_REASSESSMENT.md)
- [覆盖](../.ua/coverage.json) / [CI 证据](../.ua/evidence/ci/ci-summary.md)
- [全部上传结果及 SHA-256](../.ua/PUBLICATION_MANIFEST.json)

结果来自固定应用 commit d2038b8e0dc8c99fbdf2c754e88dec0a8078a725；本分支新增分析资料不会改变已审查的应用源码。索引不等于逐行审计，静态结论不等于设备复现。临时输入、生成脚本、空日志、worker 状态和凭据不属于结果包。
