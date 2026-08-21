# AstraDB 全链条测试用例

> 版本：v2.0 · 日期：2026-08-20 · 关联：[chain-test-plan.md](./chain-test-plan.md)、[design.md](../design/design.md)、[scenario.md](../design/scenario.md)、[client-design.md](../design/client-design.md)、[optimization.md](../design/optimization.md)、[review-p0.md](../review/review-p0.md)
> 类型：A=既有自动化映射（类.方法），E=新增端到端/真实服务器，M=手工
> 说明：F/P/S 各表"基线已归档"执行结果基于**归档前 118 项**测试资产（旧测试类已移除）；当前仓库可执行资产为 **17 个测试类 / 70 项**（2026-08-21 实测全绿；另含 1 项 perf 标注 `AslpvConsistencyIT` 默认不执行），F/P/S 域在当前资产上的映射见文末 **R 可靠性专项** 与 [chain-test-report.md](./chain-test-report.md)。

## F 功能（design 全部功能域 + client-design）

### F1 存储格式

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F1-01 | 编码器 roundtrip（Gorilla/DeltaVarint/Dictionary） | P0 | A | CodecRoundtripTest / CodecValueAtTest | 往返一致、valueAt/decodeRange 一致 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F1-02 | 位流/点字典/段读写/崩溃恢复 | P0 | A | BitRoundtripTest / PointDictionaryTest / SegmentTest / FuzzyRoundtripTest | 全绿 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F1-03 | nullable v2（位图/有效值/全 null/空间对比） | P0 | A | NullableTest | 空值全链路、空间对比达标 | 基线已归档 | 通过（归档前 118 项全绿） |

### F2 导入

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F2-01 | CSV 导入（中文/引号/表头/异常） | P0 | A | CsvParserTest + importSnapshot 端到端 | 解析正确、错误 400 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F2-02 | 批量导入（等价/回填/重复拒绝） | P0 | A | importSnapshotsBatch / batchEquivalence 等 | 与逐条一致、回填允许、重复拒绝 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F2-03 | 异步导入 | P0 | A+E | asyncImportLifecycle + curl | 提交→轮询→SUCCESS→可查 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F2-04 | **二进制导入**（含 null/全 null 列/损坏帧） | P0 | A+E | BinaryApiIntegrationTest + client ingest | 成功、全 null 列正确、损坏帧 400 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F2-05 | 任意时间戳回填/删除快照 | P0 | A+E | backfill/deleteSnapshot 测试 + curl | 段内有序、重复拒绝、窗口收缩 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F2-06 | 列数/类型/nullable/主键校验 | P0 | A+E | ingestViaSnapshotData / 二进制端点 + curl | 落盘前拒绝 | 基线已归档 | 通过（归档前 118 项全绿） |

### F3 查询

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F3-01 | 精确匹配/分页/全量流式 | P0 | A+E | fullLifecycle / getFullSnapshotReturnsAllRows + curl | 精确、流式 10 万+ 行 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F3-02 | 单点历史（并行/点消失/limit） | P0 | A+E | multiSegmentParallelSeries / pointDisappearsAndRevives | 归并正确 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F3-03 | **client queryFullSnapshot**（行对齐列名含 null/主键） | P0 | A+E | AstraDbClientTest + 端到端 | columns+rows 对齐 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F3-04 | **client queryPointAt**（精确时间点/无数据 null） | P0 | A+E | AstraDbClientTest + 端到端 | 值列数组/null | 基线已归档 | 通过（归档前 118 项全绿） |

### F4 数据生命周期

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F4-01 | manifest 重建/漂移/重启一致 | P0 | A | manifestRebuildPreciseWindow 等 | 窗口精确 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F4-02 | 保留期边界/清理 | P0 | A | retentionBoundary / retentionCleansExpiredSegments | 边界不误删 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F4-03 | 时区分片 | P0 | A+E | timezoneSharding + curl | 本地日期分片 | 基线已归档 | 通过（归档前 118 项全绿） |

### F5 段管理

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F5-01 | 段列表/段内详情/删除段 | P1 | A+E | segmentFileViewAndDelete + curl | confirm/穿越拒绝 | 基线已归档 | 通过（归档前 118 项全绿） |

### F6 API

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F6-01 | 19+ 端点逐个 curl | P0 | E | 表 6/数据 12/健康 1 全端点 | 按契约返回 | 通过 | 通过 |
| FT-F6-02 | 统一错误码 | P0 | A+E | errorBodyHasStructuredCode + curl | {code,message,...} | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F6-03 | 慢查询日志 | P1 | E | 阈值 1ms 触发 | WARN 记录 | 通过 | 通过 |

### F7 管理页面

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F7-01 | 首页/表详情/静态资源 | P1 | E | curl + 元素核对 | 200、关键元素 | 通过 | 通过 |
| FT-F7-02 | nullable 建表复选框 | P1 | E | 页面表单静态核对 | 可空选项存在 | 通过 | 通过 |

### F8 client SDK

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F8-01 | 协议 roundtrip（4 类型/null/边界/中文） | P0 | A | BinaryProtocolTest | 编解码一致 | 基线已归档 | 通过（归档前 118 项全绿） |
| FT-F8-02 | client 认证/错误码 | P0 | A+E | AstraDbClientTest + 鉴权端到端 | 401→UNAUTHORIZED | 基线已归档 | 通过（归档前 118 项全绿） |

### F9 元数据

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| FT-F9-01 | 建表（nullable/主键第 0 位）/信息/统计/删表 | P0 | A+E | primaryKeyMustBeFirstColumn + curl | 校验与统计正确 | 基线已归档 | 通过（归档前 118 项全绿） |

## P 性能（scenario 负载特征）

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| PT-P1-01 | 百万行写入/读取 | P1 | A | PerfBenchmarkTest.millionRowsBenchmark | 写入 ~1.5s / 读取 ~2.6s | 基线已归档 | 通过（归档前 118 项全绿） |
| PT-P1-02 | 20 万行写入/读取基准 | P1 | A | writeReadBenchmark | ~414ms/~342ms | 基线已归档 | 通过（归档前 118 项全绿） |
| PT-P2-01 | 批量 vs 逐条 | P1 | A+E | batchVsSingleIngest + curl | 批量显著快 | 基线已归档 | 通过（归档前 118 项全绿） |
| PT-P3-01 | 流式全量（asl 105000 行） | P1 | E | curl 计时 | ~0.8s、4.1MB | 通过 | 通过 |
| PT-P4-01 | 跨段并行单点历史 | P1 | A+E | multiSegmentParallelSeries + curl | 归并正确、耗时平稳 | 基线已归档 | 通过（归档前 118 项全绿） |
| PT-P5-01 | 二进制协议体积/耗时 vs CSV | P1 | E | 同数据二进制 vs CSV 上传对比 | 二进制显著小/快 | 通过 | 通过 |

## S 安全

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| ST-S1-01 | 鉴权开启：401/认证 200/表单登录/health 放行 | P0 | A+E | SecurityEnabledTest + curl | 符合预期 | 基线已归档 | 通过（归档前 118 项全绿） |
| ST-S2-01 | 路径穿越拒绝 | P0 | A+E | segmentFileViewAndDelete + curl ../ | 400 | 基线已归档 | 通过（归档前 118 项全绿） |
| ST-S3-01 | 输入校验（非法表名/类型/nullable/重复/乱序/主键） | P0 | A+E | errorPaths + curl | 全部拒绝且报错明确 | 基线已归档 | 通过（归档前 118 项全绿） |
| ST-S4-01 | 上传超限拒绝 | P1 | E | 超大 multipart | 400/413 | 通过 | 通过 |
| ST-S5-01 | 错误信息不泄露堆栈/内部路径 | P1 | E | 触发错误检查响应体 | 无堆栈/路径 | 通过 | 通过 |

## R 可靠性专项（P0，2026-08-20 追加）

> 依据：`optimization.md` §2.1（O-01/O-02/O-03）+ `review-p0.md` §4（B-1/B-2/S-1/S-2/S-3/O-2）。执行资产：`core/src/test/.../P0ReliabilityTest`（10 项）。执行结果回填自 2026-08-20 `mvn test -pl core -Dtest=P0ReliabilityTest` 实测（10/10 全绿）与全量 70 项回归（2026-08-21）。

| ID | 用例 | 优先级 | 类型 | 验证方式 | 预期结果 | 执行结果 | 状态 |
|---|---|---|---|---|---|---|---|
| RT-R1-01 | O-01 批量导入原子化：跨天两新段 staging → 统一 rename → 两段均可见 | P0 | A | `P0ReliabilityTest.batchAtomicNewSegmentsAndStagingCleanup` | 段计数=2、两快照可查；staging 残留被启动清理 | 通过 | 通过 |
| RT-R2-01 | O-02 单快照幂等：同内容重放跳过、异内容 400 拒绝 | P0 | A | `P0ReliabilityTest.idempotentReplaySkipsSameContent` | 重放返回原 rowCount、快照数不增；异内容拒绝 | 通过 | 通过 |
| RT-R2-02 | O-02 批量幂等：整批同内容重放跳过 | P0 | A | `P0ReliabilityTest.idempotentBatchReplaySkipsWholeBatch` | 返回原结果、不写盘 | 通过 | 通过 |
| RT-R2-03 | O-02 跨重启幂等：`idempotency.idx` 持久化恢复 | P0 | A | `P0ReliabilityTest.idempotencySurvivesRestart` | 重启后同内容重放幂等跳过 | 通过 | 通过 |
| RT-R2-04 | O-02 幂等文件损坏降级 | P0 | A | `P0ReliabilityTest.corruptedIdemFileDegradesGracefully` | 降级为空表、数据完整、行为符合降级语义 | 通过 | 通过 |
| RT-R2-05 | O-02 64 位哈希区分不同内容（INT/LONG/DOUBLE/STRING/null 位图） | P0 | A | `P0ReliabilityTest.hash64DistinguishesDifferentContents` | 不同内容哈希不同 | 通过 | 通过 |
| RT-R3-01 | O-03 dataDir 排他锁：第二实例拒绝、close 释放 | P0 | A | `P0ReliabilityTest.dataDirLockRejectsSecondInstance` | 第二 open 拒绝并明确报错；close 后可重开且数据保留 | 通过 | 通过 |
| RT-R4-01 | B-1 回归：同表 ingest 与 ingestBatch 并发不死锁 | P0 | A | `P0ReliabilityTest.concurrentIngestAndIngestBatchNoDeadlock` | 并发完成、无死锁、数据一致 | 通过 | 通过 |
| RT-R4-02 | B-2 回归：STRING 内容哈希可区分 `String.hashCode` 冲突对 | P0 | A | `P0ReliabilityTest.stringHashDistinguishesHashCodeCollisions` | 冲突对（同 hashCode 不同内容）哈希不同 | 通过 | 通过 |
| RT-R4-03 | S-1 回归：open 加载失败释放锁、同 JVM 重试成功 | P0 | A | `P0ReliabilityTest.openFailureReleasesLockAndRetrySucceeds` | 失败后重试 open 成功 | 通过 | 通过 |

## 当前测试资产与用例映射附注（2026-08-21）

| 模块 | 测试类 | 说明 |
|---|---|---|
| core（9 类） | `ConcurrencyTest` / `EncodingPropertyTest` / `IngestBackfillDeleteTest` / `P0ReliabilityTest` / `QuerySemanticsTest` / `ReviewShouldFixTest` / `ScenarioBasedTest` / `StorageFormatTest` / `StorageLifecycleTest` | 编码属性、生命周期、导入回填删除、并发、查询语义、场景、存储格式、崩溃恢复、P0 可靠性（10 项）、review should-fix（SF-1~SF-8，7 项） |
| client（3 类） | `ClientContractTest` / `ClientJsonTest` / `protocol.BinaryProtocolPropertyTest` | 契约、自含 JSON、二进制协议属性 |
| server（6 类） | `ApiContractTest` / `AslpvConsistencyIT` / `BinaryEndpointTest` / `CsvParserTest` / `SecurityContractTest` / `UploadLimitTest` | API 契约、ASLPV 一致性（@Tag perf）、二进制端点、CSV 解析、安全、上传超限 413 |
| 合计 | 18 类 / 71 项 `@Test`（2026-08-21 `mvn test` 实测 **17 类 / 70 项**全绿） | `AslpvConsistencyIT` 标注 perf，默认 profile 不执行 |
