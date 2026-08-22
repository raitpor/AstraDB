# AstraDB 黑盒测试报告

> 版本：v1.3 · 日期：2026-08-21 · 状态：**通过（缺陷全部关闭）**
> 关联：[scenario.md](../design/scenario.md)（场景）、[design.md](../design/design.md)（设计）、[defects.md](./defects.md)（缺陷跟踪）
> 执行方式：根目录 `test/` 独立测试工程（不挂主构建，**未改动任何非测试代码**），真实启动 server jar + HTTP API 黑盒验证
> 变更：v1.1 追加第 8 节"定时导入长测"；v1.2 追加第 9 节"最新交付可用性测试"（R-01 + D-12）；v1.3 追加第 10 节"R-02 server/client should-fix 测试"

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
| D-12 | P2 | 未知 API 端点返回 **500 INTERNAL_ERROR 而非 404**（`ApiExceptionHandler` 全局 `Exception` 兜底吞掉 `NoResourceFoundException` 的 404 语义） | **已验证（关闭）**：修复交付（`NoResourceFoundException`→404 NOT_FOUND、`HttpRequestMethodNotSupportedException`→405 METHOD_NOT_ALLOWED）后，`avUnknownEndpoint404` 解除 `@Disabled` 通过，新增 405 用例通过（见第 9 节） |

> 完整缺陷记录见 [defects.md](./defects.md)：D-01~D-12 **全部关闭**，K-01~K-04 已解决。

## 7. 结论与建议

**结论**：AstraDB 在场景文档定义的负载下**可用性、完整性、安全性总体达标**——24 项黑盒用例 23 项通过，未发现数据丢失/损坏类缺陷；崩溃恢复（kill -9 重启数据完好）、幂等重放、路径穿越防护、鉴权控制、confirm 防误删等关键可靠性/安全行为均验证通过。

**遗留**：
1. **建议**：将 `test/` 黑盒工程纳入 CI（构建 server jar → 跑黑盒）；性能专项（写入/读取时延 ≤5s/≤2s 的 scenario 约束）建议后续以黑盒方式补充计时断言；
2. R-01（SF-1~SF-8）中的 SF-5/SF-6/SF-7/SF-8 为内部语义/持久化细节，黑盒不可直接观测，由 core 层 `ReviewShouldFixTest`（7 项）覆盖。

## 9. 最新交付可用性测试（R-01 SF-1~SF-8 + D-12，2026-08-21 追加）

> 针对交付文档：[D-12 修复交付](../archive/phaseReport/D-12-unknown-endpoint-404.md)、[R-01 修复交付](../archive/phaseReport/R-01-review-shouldfix.md)。
> 测试代码：`test/src/test/java/com/astradb/blackbox/LatestDeliveryAvailabilityTest.java`（7 项，黑盒：HTTP + 文件系统行为）。
> 执行结果：`mvn -f test/pom.xml test -Dtest=LatestDeliveryAvailabilityTest` → **Tests run: 7, Failures: 0, Errors: 0**；黑盒全量 31 项 BUILD SUCCESS。

| ID | 用例 | 验证的交付 | 结果 |
|---|---|---|---|
| LD-01 | 未知端点 → 404 + 结构化错误码 `NOT_FOUND`（无堆栈泄露） | D-12 | ✅ 通过 |
| LD-02 | 方法不支持（GET 打 POST 端点）→ 405 + `METHOD_NOT_ALLOWED` | D-12（顺带补充） | ✅ 通过 |
| LD-03 | **损坏段隔离**：篡改段文件 → 重启 → 库仍启动（health UP）、好表数据完好、坏段从 manifest 剔除且隔离至 `segments/.quarantine/*.corrupt` | R-01 SF-3 | ✅ 通过 |
| LD-04 | **删除快照后同内容重放真正写入**（幂等记录随删除清理，快照可恢复） | R-01 SF-1 | ✅ 通过 |
| LD-05 | **混合批导入**：部分命中正式记录 → 重放 + 其余新增，不抛"时间戳已存在"，3 快照数据正确 | R-01 SF-2 | ✅ 通过 |
| LD-06 | **跨表并发导入**：两表同时导入均成功、数据正确（互不阻塞） | R-01 SF-4 | ✅ 通过 |
| LD-07 | 常规回归：健康/建表/导入/查询/删表全流程可用 | 回归 | ✅ 通过 |

### 结论

**最新交付可用性验证通过**：D-12 错误语义修复（404/405）与 R-01 可靠性修复（SF-1 幂等清理、SF-2 混合批、SF-3 损坏段隔离、SF-4 跨表并行）经黑盒验证全部生效且无回归——特别是 **SF-3**：单段损坏不再阻塞整个库启动，好数据可用、坏段隔离留证，可用性显著提升；D-12 关闭。缺陷全部清零（D-01~D-12 关闭，K-01~K-04 解决）。

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

## 10. R-02 server/client should-fix 测试（SS-1~SS-10，2026-08-21 追加）

> 针对交付文档：[R-02 修复交付](../archive/phaseReport/R-02-review-server-shouldfix.md)（review.md 5.1 SS-1~SS-10）。
> 测试代码：`test/src/test/java/com/astradb/blackbox/LatestServerDeliveryTest.java`（8 项，黑盒：HTTP + client SDK）。
> 执行结果：`mvn -f test/pom.xml test -Dtest=LatestServerDeliveryTest` → **Tests run: 8, Failures: 0, Errors: 0**；黑盒全量 39 项 BUILD SUCCESS。

| ID | 用例 | 验证的交付 | 结果 |
|---|---|---|---|
| RS-01 | createTable 缺 `columns` → 400 `INVALID_ARGUMENT`（原 500 NPE） | SS-1 | ✅ 通过 |
| RS-02 | 损坏二进制帧 → 400 `INGEST_REJECTED`（原 500 `NegativeArraySizeException`） | SS-2 | ✅ 通过 |
| RS-03 | health 不再泄露 `dataDir`（仅保留 `dataDirWritable` 布尔） | SS-8 | ✅ 通过 |
| RS-04 | 表名含 `' " < >` → 400 拒绝（XSS 纵深防御） | SS-4 | ✅ 通过 |
| RS-05 | CSV 未闭合引号 → 400 格式错误且不产生数据（原静默吞行） | SS-5 | ✅ 通过 |
| RS-06 | importAsync 坏 CSV：请求 200 返回 taskId，解析错误进任务状态 FAILED（解析移后台） | SS-7 | ✅ 通过 |
| RS-07 | client 含引号/换行/制表符 key 的 JSON 转义 → 导入成功且单点可查 | SS-10 | ✅ 通过 |
| RS-08 | 常规回归：健康/建表/导入/查询全流程 | 回归 | ✅ 通过 |

> 说明：SS-3（BCrypt 密码）行为由既有 `SecurityTest`（鉴权 401/200）覆盖；SS-6（异步队列有界/不裁剪 RUNNING）、SS-9（上传超限 413，需 >200MB 文件）为单测覆盖（`ImportTaskService`/`UploadLimitTest`），黑盒不可低成本构造，本报告不再重复。

### 结论

**R-02 server/client should-fix 交付验证通过**：SS-1/SS-2/SS-4/SS-5/SS-7/SS-8/SS-10 经黑盒验证全部生效（错误语义 400 化、信息泄露消除、XSS 纵深防御、CSV 格式校验、异步解析后台化、client JSON 转义完整），无回归；黑盒全量 39 项 0 失败 0 错误，**未发现新缺陷**。
