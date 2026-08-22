# AstraDB 文档库（docs）

> 文档按性质分为五个板块：`design/`（设计）、`review/`（评审）、`roadmap/`（路线图）、`phaseReport/`（阶段报告）、`test/`（测试）。
> 约定：**新文档默认在 `design/` 编写**，仅在内容明显属于评审、路线图、阶段报告或测试时才写入对应板块。
> 维护：项目经理（文档整理/评审/路线图）；`phaseReport/` 归应用工程师、`test/` 归测试工程师。

## 板块结构

| 板块 | 定位 | 内容 |
| --- | --- | --- |
| [`design/`](./design/) | **设计类文档（主要编写区）** | 场景、总体设计、客户端设计、优化提案 |
| [`review/`](./review/) | 评审报告 | 总体现状评价与 P0 专项评审（review-p0.md）、全项目评审（review.md） |
| [`roadmap/`](./roadmap/) | 路线图 | 开发路线与里程碑/任务状态（ROADMAP.md） |
| [`phaseReport/`](./phaseReport/) | 阶段交付报告 | 阶段核对报告（phase-report.md）+ 修复交付（R-01/R-02/D-12） |
| [`test/`](./test/) | 测试文档 | 测试计划/用例/报告、缺陷跟踪（defects.md）、黑盒测试报告 |

## design/ 文件清单

- `design.md` — 总体设计文档（核心，版本/状态见文首）
- `scenario.md` — 场景文档（要解决的问题与适用场景）
- `client-design.md` — 客户端设计与二进制数据流协议
- `optimization.md` — 评价与优化方案（v1.2 正式；P0 的 O-01~O-03 已实施归档见第 6 章；O-14/O-15 部分实施；其余候选；问题清单 G-01~G-11 见 review/）

## review/ 文件清单

- `review-p0.md` — 总体现状评价与 P0 可靠性优化评审报告（第 1 章总体问题清单 G-01~G-11，第 2 章起 P0 专项评审；v1.2 复审通过）
- `review.md` — 全项目评审报告（v1.2：should-fix 全部清零，core/server/client 观察项清单、测试覆盖核对、P2 观察项）

## roadmap/ 文件清单

- `ROADMAP.md` — 开发路线图（M1~M8 已完成、P0 已归档；M9 格式演进为下一优先窗口，含任务清单与状态）

## 编写约定

- 新增设计类文档一律放入 `design/`，并更新本 README 的文件清单；
- 文档间相互引用使用**相对路径链接**（移动文件后需同步修正）；
- 状态标记统一放在文首引用块（版本 / 状态 / 日期 / 关联文档）；
- 已完成/已关闭的文档事项归档至 `docs/archive/` 对应类型子目录（如 `docs/archive/design/`），并从本索引移除或标注「已归档」。

## 归档区（docs/archive/）

| 文档 | 原位置 | 归档日期 | 归档依据 |
|---|---|---|---|
| [design/ui-table-nullable.md](./archive/design/ui-table-nullable.md) | design/ | 2026-08-22 | 可空列 UI 修复已实现并验收（交付 2026-08-22 + 测试 11/11 通过） |
| [design/api-query-columnar-format.md](./archive/design/api-query-columnar-format.md) | design/ | 2026-08-22 | 接口列式化已实现并验收（交付 + 测试 6/6、黑盒 46 项通过） |
| [phaseReport/2026-08-22-查询接口列式化交付文档.md](./archive/phaseReport/2026-08-22-查询接口列式化交付文档.md) | phaseReport/ | 2026-08-22 | 测试 6/6 + 黑盒 46 项通过、缺陷闭环 |
| [phaseReport/2026-08-22-可空列与压缩等级UI修复交付文档.md](./archive/phaseReport/2026-08-22-可空列与压缩等级UI修复交付文档.md) | phaseReport/ | 2026-08-22 | 测试 11/11 通过、缺陷闭环 |
| [phaseReport/R-02-review-server-shouldfix.md](./archive/phaseReport/R-02-review-server-shouldfix.md) | phaseReport/ | 2026-08-22 | SS-1~SS-10 全量 70 项 + 黑盒通过、缺陷闭环 |
| [phaseReport/R-01-review-shouldfix.md](./archive/phaseReport/R-01-review-shouldfix.md) | phaseReport/ | 2026-08-22 | SF-1~SF-8 全量 63 项 + 黑盒 LD 验证、缺陷闭环 |
| [phaseReport/D-12-unknown-endpoint-404.md](./archive/phaseReport/D-12-unknown-endpoint-404.md) | phaseReport/ | 2026-08-22 | 黑盒 LD-01/LD-02 通过、D-12 缺陷关闭 |
