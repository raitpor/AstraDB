# 测试文档索引（docs/test）

> 测试工程师维护。按类型组织：`plans/`（计划）、`cases/`（用例）、`execution/`（执行记录）、`reports/`（报告）、`defects/`（缺陷）、`archive/`（归档）。
> 测试代码位于 `<项目>/test/` 独立子项目（与业务代码隔离）。
> 缺陷总表沿用 `defects.md`（D-xx 编号，D-01~D-12 全部关闭）；既有全链条/黑盒文档保留于根目录。

## 活跃文档

| 文档 | 日期 | 状态 |
|---|---|---|
| [execution/2026-08-22-查询接口列式化执行记录.md](./execution/2026-08-22-查询接口列式化执行记录.md) | 2026-08-22 | 6/6 通过、黑盒 46 项全绿 |
| [reports/2026-08-22-查询接口列式化测试报告.md](./reports/2026-08-22-查询接口列式化测试报告.md) | 2026-08-22 | 通过 |
| [execution/2026-08-22-可空列UI与压缩等级默认值执行记录.md](./execution/2026-08-22-可空列UI与压缩等级默认值执行记录.md) | 2026-08-22 | 11/11 通过 |
| [reports/2026-08-22-可空列UI与压缩等级默认值测试报告.md](./reports/2026-08-22-可空列UI与压缩等级默认值测试报告.md) | 2026-08-22 | 通过 |
| [defects.md](./defects.md) | 2026-08-21 | D-01~D-12 全部关闭 |
| [blackbox-test-report.md](./blackbox-test-report.md) | 2026-08-21 | 31 项全绿 |
| [chain-test-plan.md](./chain-test-plan.md) / [chain-test-cases.md](./chain-test-cases.md) / [chain-test-report.md](./chain-test-report.md) | 2026-08-20 | 全链条基线 |

## 归档区（docs/test/archive/）

| 文档 | 归档日期 | 归档依据 |
|---|---|---|
| [plans/2026-08-22-查询接口列式化测试计划.md](./archive/plans/2026-08-22-查询接口列式化测试计划.md) | 2026-08-22 | 对应测试已执行完毕并出具报告 |
| [cases/2026-08-22-查询接口列式化测试用例.md](./archive/cases/2026-08-22-查询接口列式化测试用例.md) | 2026-08-22 | 对应版本测试已结束、报告已交付 |
| [plans/2026-08-22-可空列UI与压缩等级默认值测试计划.md](./archive/plans/2026-08-22-可空列UI与压缩等级默认值测试计划.md) | 2026-08-22 | 对应测试已执行完毕并出具报告 |
| [cases/2026-08-22-可空列UI与压缩等级默认值测试用例.md](./archive/cases/2026-08-22-可空列UI与压缩等级默认值测试用例.md) | 2026-08-22 | 对应版本测试已结束、报告已交付 |

## 关联

- 交付：`docs/phaseReport/`（应用工程师）；需求/规划/路线：`docs/design/`、`docs/roadmap/`（项目经理）
- 黑盒测试代码：`test/` 子项目（`NullableColumnDeliveryTest` 为 2026-08-22 交付新增）
