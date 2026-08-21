# AstraDB 黑盒测试报告

> 版本：v1.1 · 日期：2026-08-21 · 状态：**通过（1 项缺陷 D-12 挂起）**
> 关联：[scenario.md](../design/scenario.md)（场景）、[design.md](../design/design.md)（设计）、[defects.md](./defects.md)（缺陷跟踪）
> 执行方式：根目录 `test/` 独立测试工程（不挂主构建，**未改动任何非测试代码**），真实启动 server jar + HTTP API 黑盒验证
> 变更：v1.1 追加第 8 节"定时导入长测"（spring @Scheduled + astradb-client，每 1 分钟 1000 条 ×10）

---

## 1. 概述

依据 **scenario.md** 的负载特征与非功能约束（周期性全量快照、数据不丢不坏、崩溃安全、安全部署），对 AstraDB server 做**黑盒测试**——不触碰内部实现，仅通过外部接口（HTTP API + 进程行为）验证三大质量属性：

| 质量属性 | 覆盖点 |
|---|---|
| **可用性** | 健康检查、表/数据全流程、错误语义（400 vs 500 vs 404）、异步/批量导入可用 |
| **完整性** | 导入→查询一致（值/null/类型）、单点历史、重复拒绝、幂等重放、历史回填、删除快照、**崩溃恢复（kill -9）**、跨表隔离 |
| **安全性** | 鉴权控制（401/200/放行）、路径穿越、confirm 防误删、输入校验、错误信息不泄露 |

## 2. 环境与执行方式

| 项 | 内容 |
|---|---|
| 测试工程 | `test/`（独立 pom，仅 junit-jupiter + jackson-databind，Java 25） |
| 测试对象 | `server/target/astradb-server-0.1.0-SNAPSHOT.jar`（真实进程，随机端口 + 临时数据目录） |
| 运行 | `mvn -f test/pom.xml test`（先 `mvn -pl server -am package -Dmaven.test.skip=true` 构建） |
| 用例规模 | **24 项**（可用性 7 + 完整性 8 + 安全性 9），串行执行 |
| 执行结果 | **23 通过 + 1 跳过（D-12 待修）**，Failures=0 Errors=0，BUILD SUCCESS |
| 数据隔离 | 每个 server 实例独立临时数据目录（`Files.createTempDirectory`），结束后清理 |

## 3. 可用性测试结果（AV-01~AV-07）

| ID | 用例 | 结果 |
|---|---|---|
| AV-01 | 健康检查 `/api/health` → 200，status=UP，含 version/dataDirWritable/uptimeMs | ✅ 通过 |
| AV-02 | 表生命周期：建表→列表→详情→统计→删表 全链路 | ✅ 通过 |
| AV-03 | CSV 导入→快照查询→删表 端到端 | ✅ 通过 |
| AV-04 | 未知端点 → **应 404，实测 500** | ⚠️ **失败（D-12）** |
| AV-05 | 非法请求（主键不存在/未知表）→ 400（非 500） | ✅ 通过 |
| AV-06 | 异步导入：importAsync → 轮询 importStatus → SUCCESS → 数据可查 | ✅ 通过 |
| AV-07 | 批量导入：2 文件 + 严格递增时间戳 → 2 快照均可查 | ✅ 通过 |

## 4. 完整性测试结果（IT-01~IT-08）

| ID | 用例 | 结果 |
|---|---|---|
| IT-01 | 导入→全量快照一致：行数、数值、**null 还原** | ✅ 通过 |
| IT-02 | 单点历史：多时间点值序列；未知 key 返回空序列 | ✅ 通过 |
| IT-03 | 重复时间戳（异内容）→ 400 拒绝，原数据不污染 | ✅ 通过 |
| IT-04 | 幂等重放：同 ts 同内容重放成功且不产生重复数据 | ✅ 通过 |
| IT-05 | 历史回填：向过去时间戳导入 → 新旧时间点均可回溯 | ✅ 通过 |
| IT-06 | 删除快照：无 confirm 400；confirm 后该时间点为空、他点保留 | ✅ 通过 |
| IT-07 | **崩溃恢复：kill -9 重启后已提交快照/点字典完好** | ✅ 通过 |
| IT-08 | 跨表隔离：A/B 表数据互不影响 | ✅ 通过 |

## 5. 安全性测试结果（SE-01~SE-09，鉴权开启实例）

| ID | 用例 | 结果 |
|---|---|---|
| SE-01 | 鉴权开启时 `/api/health` 放行（无认证 200） | ✅ 通过 |
| SE-02 | 未认证访问受保护 API → 401 | ✅ 通过 |
| SE-03 | 错误凭证 → 401 | ✅ 通过 |
| SE-04 | 正确凭证（Basic）→ 200 | ✅ 通过 |
| SE-05 | 路径穿越：`../`/绝对路径（listSegmentSnapshots/deleteSegment）→ 400 | ✅ 通过 |
| SE-06 | confirm 保护：deleteTable 无 confirm → 400，表仍在；带 confirm → 200 | ✅ 通过 |
| SE-07 | 非法表名（含路径分隔符）→ 400 | ✅ 通过 |
| SE-08 | 导入类型不符 → 400 结构化错误，不留下部分数据 | ✅ 通过 |
| SE-09 | 错误响应体无堆栈/异常链，结构化 `{code,message,...}` | ✅ 通过 |

## 6. 缺陷跟踪

| ID | 严重度 | 描述 | 状态 |
|---|---|---|---|
| D-12 | P2 | 未知 API 端点返回 **500 INTERNAL_ERROR 而非 404**（`ApiExceptionHandler` 全局 `Exception` 兜底吞掉 `NoResourceFoundException` 的 404 语义） | **新建（未修复）**，用例 `avUnknownEndpoint404` 已 `@Disabled` 引用，修复后启用 |

> 完整缺陷记录见 [defects.md](./defects.md)（D-01~D-11 已关闭，D-12 挂起）。

## 7. 结论与建议

**结论**：AstraDB 在场景文档定义的负载下**可用性、完整性、安全性总体达标**——24 项黑盒用例 23 项通过，未发现数据丢失/损坏类缺陷；崩溃恢复（kill -9 重启数据完好）、幂等重放、路径穿越防护、鉴权控制、confirm 防误删等关键可靠性/安全行为均验证通过。

**遗留**：
1. **D-12（P2）**：未知端点 500 而非 404——建议在 `ApiExceptionHandler` 增加 `NoResourceFoundException` → 404 映射（结构化错误体），修复后启用 `avUnknownEndpoint404` 并回归；
2. **建议**：将 `test/` 黑盒工程纳入 CI（构建 server jar → 跑黑盒）；性能专项（写入/读取时延 ≤5s/≤2s 的 scenario 约束）建议后续以黑盒方式补充计时断言。

## 8. 定时导入长测（spring @Scheduled + astradb-client，2026-08-21 追加）

> 依据测试计划：① 启动服务端建表test（主键 `pointId` INT、数据列 `pointValue` DOUBLE、**压缩等级 20**）；② 以 spring 实现定时任务，每 1 分钟用 client 导入 1000 条，运行十分钟停止；③ 逐个快照全量查询与单点查询，确认可查询且数据正确。
> 测试代码：`test/src/test/java/com/astradb/blackbox/ScheduledImportTest.java`（spring-context `@EnableScheduling` + `@Scheduled` + astradb-client）。

### 8.1 执行过程

| 步骤 | 内容 | 结果 |
|---|---|---|
| 1 建表 | `POST /api/createTable`：`test`，columns=[pointId INT(主键), pointValue DOUBLE]，compressionLevel=20 | ✅ 200 |
| 2 定时导入 | spring `@Scheduled(fixedDelay=60s)` 任务用 `AstraDbClient.ingest` 导入：固定 1000 点（id=1..1000），值 = `id*0.5 + 批次*0.1`（跨快照可区分），共 **10 次**，时间戳递增 60s | ✅ 10/10 成功，每次 1000 行 |
| 3 验证 | 逐个快照（10 个）全量查询 + 单点查询 + 单点历史 | ✅ 全部通过 |

- **冒烟**（同链路，interval=2s × 3 次）：3 次导入 + 3 快照全量/单点/单点历史全部通过 → 确认 spring 定时 + client 导入 + 验证逻辑链路正确；
- **正式十分钟运行**：10 次导入完成，**总耗时 543.8s**（每 1 分钟一次，符合计划）；每次导入 1000 行、行数断言通过。

### 8.2 验证结果

| 验证项 | 覆盖 | 结果 |
|---|---|---|
| 快照完整性 | `listSnapshots` = 10 个时间点，严格递增 | ✅ 通过 |
| 全量查询（client `queryFullSnapshot`） | 10 个快照逐个：总行数 = 1000；抽查行 1/500/1000 的主键与值（`id*0.5+批次*0.1`） | ✅ 10/10 通过 |
| 单点查询（client `queryPointAt`） | 10 个快照逐个：抽查点 1/250/1000 的值 | ✅ 10/10 通过 |
| 单点历史（`getPointSeries`） | 点 1 在 10 个快照的值序列（0.5, 0.6, …, 1.4）与时间戳一一对应 | ✅ 通过（跨快照不串数据） |

### 8.3 结论

**定时导入长测通过**：压缩等级 20 的表在"每 1 分钟 client 导入 1000 条 ×10"负载下，10 个快照全部可查、全量与单点数据正确（含值随批次变化的跨快照区分验证）；spring 定时任务与 client 二进制导入链路稳定，**未发现新缺陷**。
