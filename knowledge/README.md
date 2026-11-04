# 知识文档状态约定

本目录中的 `dataConfidence` 描述事实的证据状态，`status` 描述文档的编辑、审核和发布状态，两者不能互相替代。

| 值 | 条件 | 使用规则 |
| --- | --- | --- |
| `DEMO` | 没有可引用外部证据，或仅由项目组基于现有 DEMO 数据编排 | 不得作为开放时间、票价、实时状态等硬事实的独立依据 |
| `CITED` | `evidence` 至少有一条，且 reviewer 为空 | 已有 locator 和 quote，可进入检索和评测，但尚未完成人工复核 |
| `VERIFIED` | `evidence` 至少有一条，且 `reviewer` 非空 | 事实已经人工核实并留下复核人；仍须符合 `validTo` 和来源限制 |

统一约束：

1. `VERIFIED` 必须同时满足 `evidence` 非空和 `reviewer` 非空。
2. 有 evidence 但 reviewer 为空时使用 `CITED`，不得使用 `VERIFIED`。
3. 内部区域、路线和餐饮编排使用 `DEMO`，并通过交叉引用指向对应 POI 文档。
4. `status` 为 `DRAFT` 的文档可以拥有证据，但不能进入正式发布索引。
5. 动态事实仍以 `validTo` 和实时工具结果为准，`VERIFIED` 不等于永久有效。
