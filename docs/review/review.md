# AstraDB 全项目评审报告

> 版本：v1.1（复审）· 日期：2026-08-21 · 状态：正式
> 评审历程：v1.0（2026-08-20 初评，无 blocking / 8+10 项 should-fix）→ **v1.1（2026-08-21 复审：core should-fix 全部修复、D-12 修复关闭，本版）**
> 评审基准：工作区 HEAD + 未提交的修复（R-01：SF-1~SF-8；D-12：404/405 错误语义）
> 关联文档：[review-p0.md](./review-p0.md)（P0 专项评审）、[optimization.md](../design/optimization.md)（优化提案）、[design.md](../design/design.md)（设计）、[R-01 修复交付](../phaseReport/R-01-review-shouldfix.md)、[D-12 修复交付](../phaseReport/D-12-unknown-endpoint-404.md)、[blackbox-test-report.md](../test/blackbox-test-report.md)（黑盒测试）、[defects.md](../test/defects.md)（缺陷跟踪）

---

## 1. 评审范围与方法

| 维度 | 范围 |
|---|---|
| 代码 | `core/`（存储/编码/压缩/段/点字典/导入/查询/保留期）、`server/`（API/安全/异步导入/UI）、`client/`（二进制协议 + 自含 JSON）、`test/`（黑盒测试工程） |
| 文档对照 | design.md、optimization.md、chain-test-*、blackbox-test-report.md、defects.md、R-01/D-12 交付报告 |
| 实测（v1.1） | `mvn test`：**BUILD SUCCESS，63 项全绿**（core 41 + client 15 + server 7）；黑盒工程 31 项 BUILD SUCCESS |
| 原则 | 只读评审，不修改任何 Java 代码；结论均附 file:line 证据 |

## 2. 总体结论（v1.1 复审）

**verdict: 有条件放行（较 v1.0 显著收敛）** —— 无 **blocking**；v1.0 报告的 **core 8 项 should-fix（SF-1~SF-8）已全部修复并验证**（R-01 交付：`ReviewShouldFixTest` 7 项 + core 41 项 + 全量 63 项 + 黑盒 LD-01~LD-07 验证）；黑盒测试发现的 **D-12（未知端点 404）已修复关闭**。缺陷全部清零（D-01~D-12 关闭，K-01~K-04 解决）。

**v1.1 已解决**：
1. ~~SF-1 幂等记录不随删除清理（静默丢数据方向）~~ → `removeIdem` + `rewriteIdemExcluding`（临时文件 + fsync + ATOMIC_MOVE + 目录 fsync），删除/保留期清理同步枚举段内 ts；
2. ~~SF-3 损坏段使整个库无法 open~~ → 启动隔离至 `segments/.quarantine/*.corrupt` + WARN + manifest 剔除，好数据可用（黑盒 LD-03 验证）；
3. ~~SF-4 全局幂等锁串行化跨表导入~~ → 幂等 map/锁按表拆分（`TableState.idemLock`），跨表导入恢复并行（黑盒 LD-06 验证）；
4. ~~D-12 未知端点 500~~ → `NoResourceFoundException`→404、`HttpRequestMethodNotSupportedException`→405（黑盒 LD-01/LD-02 验证）。

**剩余主要风险（v1.1，均属 server/client，留待后续迭代）**：
1. **SS-4 管理台存储型 XSS**（表名/数据进 innerHTML）；
2. **SS-3 `{noop}` 明文弱口令**（默认 `admin123`）；
3. **SS-5 CSV 未闭合引号吞行 → 静默错数据**；
4. 其余 SS-1/SS-2/SS-6~SS-10（客户端错误 500 映射、任务队列、client escape 等）。

**亮点（v1.0 确认，复审未发现回退）**：锁序一致无死锁；`contentHash64` 64 位 FNV-1a（STRING 逐 char）；dataDir 锁异常路径释放；幂等批内合并 fsync；预写占位幂等；错误码映射与路径安全；二进制协议自洽；UI 模板转义。

## 3. 已验证正确项

| 项 | 证据 |
|---|---|
| 锁序一致、无死锁 | `AstraDB.java` ingest/ingestBatch 均为「表写锁 → 幂等锁」；SF-4 后为「global read → table write → idemLock」，无反向嵌套（R-01 2.2） |
| 64 位内容哈希 | `SnapshotData.java` STRING 逐 char 喂 FNV-1a（弃用 `String.hashCode()`）；INT/LONG/DOUBLE 原始 64 位参与 |
| dataDir 锁异常路径释放 | `AstraDB.java` 加载失败时 release+close；成功路径 `close()` 释放 |
| 幂等批内合并 fsync | `appendIdemBatch` 一次 open + write + `force(true)` |
| 预写占位幂等 | 占位先落盘（fsync）→ 段提交 → 正式记录 |
| 目录 fsync（SF-7） | `core/util/FsUtil.fsyncDir` 接入 JsonFiles/SegmentRewriter/writeSegmentsBatch/RetentionCleaner/delete*；平台不支持降级 WARN |
| 损坏段隔离（SF-3） | `quarantineCorruptSegment` → `segments/.quarantine/*.corrupt`，启动校验不重复命中 |
| 客户端错误 → 400 / 404 / 405 | `IngestException`/`IllegalArgumentException`→400；`NoResourceFoundException`→404；`HttpRequestMethodNotSupportedException`→405（D-12） |
| 表名/路径安全 | `AstraDB.validateName` 禁 `/`、`\`、控制字符；`resolveSegment` 目录越界拒绝 |
| 二进制协议自洽 | `BinaryProtocol` encode/decode 对称（列数上限 1024、varint 溢出检查） |
| UI 模板转义 | `UiController` 注入经 `th:text`/`[[…]]` 转义，无模板注入 |

## 4. core 问题清单

### 4.1 should-fix（v1.0 清单；**v1.1 全部已修复**，交付见 [R-01](../phaseReport/R-01-review-shouldfix.md)）

| ID | 问题（v1.0 描述） | 修复方案（R-01） | 验证 | 状态 |
|---|---|---|---|---|
| SF-1 | **幂等记录不随数据删除清理**：删除快照/段/保留期清理后，同 ts 同内容重放命中正式记录 → 静默跳过（静默丢数据方向） | `removeIdem`：内存移除 + `rewriteIdemExcluding` 原子重写（临时文件 + fsync + ATOMIC_MOVE + 目录 fsync）；deleteSnapshot/deleteSegment/RetentionCleaner 枚举段内 ts 一并清理 | `ReviewShouldFixTest`（delete 后重放真正写入 + 重启仍有效）；黑盒 LD-04 | ✅ 已修复 |
| SF-2 | **混合批导入（部分快照已提交）必抛"时间戳已存在"** | 慢路径改混合批：正式命中重放、占位先确认（已提交→精确返回，未提交→作废重导）、仅未命中进入 `SnapshotIngestor.ingestBatch`，结果按原顺序合并 | `ReviewShouldFixTest`；黑盒 LD-05 | ✅ 已修复 |
| SF-3 | **损坏段使整个库无法 open，无隔离/自愈** | 启动 `validateManifest` 对损坏段隔离至 `segments/.quarantine/*.corrupt` + WARN + manifest 剔除；隔离失败→删除告警；均失败→抛错维持原语义 | `ReviewShouldFixTest`（隔离后 open 成功、好段可查）；黑盒 LD-03 | ✅ 已修复 |
| SF-4 | **全局幂等锁把跨表导入串行化**（与 K-02"跨表写并行"注释矛盾） | 幂等 map/锁按表拆分（`TableState` 持 per-table map + `idemLock`）；锁序 global read → table write → idem，无死锁 | `ReviewShouldFixTest`（跨表写真正并行）；黑盒 LD-06 | ✅ 已修复 |
| SF-5 | **`writeSegmentsBatch` rename 循环非"全有或全无"**（注释与实际不符） | 注释收敛为"近似原子"（staging 全量 fsync 后逐个 rename，崩溃时部分段生效，幂等重放可覆盖；与 review-p0 S-4 接受结论一致） | 文档语义收敛 | ✅ 已修复 |
| SF-6 | **占位命中确认返回值不精确且 int 强转可溢出** | `timestampExists` → `timestampRowCount` 返回精确 chunk 行数（-1 不存在）；占位确认返回 `(ts, 精确行数, 0)`，消除整段行数误用与 long→int 溢出（newPoints 置 0 并注释语义） | `ReviewShouldFixTest`（手工构造占位，确认返回精确行数） | ✅ 已修复 |
| SF-7 | **删除/rename 后无目录 fsync**（断电后目录项可能不持久） | 新增 `core/util/FsUtil.fsyncDir`，接入 JsonFiles.write、SegmentRewriter.rewrite、writeSegmentsBatch、RetentionCleaner.clean、deleteSegment/deleteSnapshot；平台不支持降级 WARN | core 41 项回归 | ✅ 已修复 |
| SF-8 | **`SegmentWriter.close` 异常路径泄漏 RAF 句柄** | `close()` 改 try/finally：`closed` 置位与 `raf.close()` 无论成败必然执行 | core 41 项回归 | ✅ 已修复 |

### 4.2 观察项（v1.0 清单，v1.1 状态）

| ID | 内容 | v1.1 状态 |
|---|---|---|
| O-1 | `contentHash64` null 与 `"\u0000"` 哨兵哈希不可区分（碰撞点，概率极低） | 未修 |
| O-2 | `open()` 异常路径不关闭 `segmentChannels` 池中已 acquire 句柄（仅加载失败场景） | 未修 |
| O-3 | `appendIdemBatch` 无尾部占位截断（仅冗余占位记录，语义正确） | 未修 |
| O-4 | `rewriteIdem` 非原子（truncate+write），中途崩溃幂等文件变空（概率低） | 未修 |
| O-5 | >2GB 段不支持（int 截断） | 未修 |
| O-6 | 正常查询路径无逐 chunk CRC 校验（依赖文件级 Crc64） | 未修 |
| O-7 | `ingest` timestamp=null 时"双 now"窗口 | 未修 |
| O-8 | 描述信息 light 模式 minKey/maxKey 占位 1,1（不写回，安全） | 未修 |
| O-9 | 损坏数据下 `BitReader.fill` 越界抛 AIOOBE（有 Crc64 兜底） | 未修 |
| O-10 | `ChunkCache` 字节度量只计 raw 数组，上限偏松 | 未修 |
| O-11 | 查询对"段缺失"行为不一致（PointSeriesQuery 跳过 vs SnapshotQuery 抛异常） | 未修 |
| O-12 | 单快照整块内存驻留，超大快照内存峰值高 | 未修 |
| O-13 | `RetentionCleaner` 整段粒度删除，跨期段整体保留（保守） | 未修 |
| O-14 | 幂等锁内做文件 IO 放大锁竞争 | **随 SF-4 缓解**（锁按表拆分，范围缩小；快速路径哈希仍在锁内） |
| O-15 | `SegmentChannelCache` 活跃句柄无上限 | 未修 |
| O-16 | `appendIdem` IO 失败降级后幂等不可恢复（有 WARN 日志） | 未修 |

## 5. server / client 问题清单

### 5.1 should-fix（v1.0 清单 SS-1~SS-10：**未修复，留待后续迭代**；新增 SS-11 已修复）

| ID | 问题 | 位置 | 影响 / 建议 | 状态 |
|---|---|---|---|---|
| SS-1 | **createTable 缺 `columns` 字段 → NPE → 500** | `TableController.java:47` | 客户端参数缺失应为 400；判空抛 `IllegalArgumentException` | 未修 |
| SS-2 | **二进制协议解码 RuntimeException → 500** | `BinaryIngestParser.java:28-33`、`BinaryReader.java:61` | 恶意/损坏帧穿透为 500；校验长度并映射 400 | 未修 |
| SS-3 | **密码 `{noop}` 明文 + 默认弱口令 `admin123`** | `SecurityConfig.java:42,44`、`application.yml:17` | 开启鉴权后仍可被默认口令全权操作（对应 G-10/O-14）；建议 BCrypt + 强制改密 | **未修（P0 候选）** |
| SS-4 | **管理台存储型 XSS** | `app.js:50-53`、`table.html:231-234` | 表名含 `'` 可注入 JS；STRING 列值可为 `<img onerror=...>`；innerHTML 路径统一转义 + validateName 限制特殊字符 | **未修（P0 候选）** |
| SS-5 | **CSV 未闭合引号吞行 → 静默错数据** | `CsvParser.java:48-55,92-94` | 未闭合引号把后续行并入同一字段，列数恰好匹配时不报错；RFC 4180 应报格式错误 | **未修（P0 候选）** |
| SS-6 | **ImportTaskService 无界队列 + trim 可能删除 RUNNING + 无关闭钩子** | `ImportTaskService.java:34-40,74-83` | 大文件任务内存无限堆积；RUNNING 被 trim 后状态查询失败；有界队列 + trim 排除 RUNNING + `@PreDestroy` | 未修 |
| SS-7 | **importAsync"大文件不阻塞请求"与实现不符** | `DataController.java:80-91` | 解析仍在请求线程同步完成；注释/实现二选一对齐 | 未修 |
| SS-8 | **HealthController 未认证泄露 dataDir 绝对路径** | `HealthController.java:32`、`SecurityConfig.java:32` | 未认证者可获服务器文件系统布局；脱敏或鉴权 | 未修 |
| SS-9 | **上传超限异常未映射为 4xx** | `application.yml:24-26`、`ApiExceptionHandler` | 超限上传落入默认 500；映射 413/400 | 未修 |
| SS-10 | **client `escape()` 不完整 → 控制字符 key 生成非法 JSON** | `AstraDbClient.java:351-353,134-135` | STRING 主键含换行/制表符时产生裸控制字符；改用 `ClientJson.quote` | 未修 |
| SS-11 | **未知 API 端点返回 500 而非 404**（黑盒 AV-04 发现，D-12） | `ApiExceptionHandler` 全局 `Exception` 兜底吞掉 `NoResourceFoundException` | 未知端点 404 会被监控误判为服务故障；`NoResourceFoundException`→404 NOT_FOUND、`HttpRequestMethodNotSupportedException`→405 METHOD_NOT_ALLOWED，错误体结构化 | ✅ 已修复（D-12 交付，黑盒 LD-01/LD-02 验证） |

### 5.2 观察项（v1.0 清单，v1.1 状态）

| ID | 内容 | 状态 |
|---|---|---|
| SO-1 | `security.enabled` 默认 false（docker-compose/systemd 已强制 true；直接 `java -jar` 时 API 全开放） | 未处理 |
| SO-2 | `resolveSegment` 前缀匹配越界（利用面低），建议边界判断 | 未处理 |
| SO-3 | `BinaryProtocol.encode` nullable 且 nullBitmap==null → NPE（公共 API 无保护） | 未处理 |
| SO-4 | `readString/readBytes` 无长度上限，恶意帧可触发大数组分配（OOM 面） | 未处理 |
| SO-5 | `getFullSnapshot`/`queryFullSnapshotBinary` 先全量载入内存再流式写，注释不成立 | 未处理 |
| SO-6 | `SlowQueryInterceptor` 对 JSON body 请求 `getParameter("table")` 恒为 null | 未处理 |
| SO-7 | `CsvParser` 中间空行报 400（方向安全）；首行与列名全同时被当表头吞掉 | 未处理 |
| SO-8 | Jackson 2（core 传递依赖）与 Spring Boot 4 默认 Jackson 3 双栈并存 | 未处理 |
| SO-9 | 错误消息可能泄露段文件绝对路径（`ApiExceptionHandler` 包装 `e.getMessage()`） | 未处理 |
| SO-10 | `AstraDbService` 注释"缺省取系统时区"与 `application.yml` 默认 `Asia/Shanghai` 不符 | 未处理 |
| SO-11 | `getSnapshot`/`series` 的 limit/offset 无上限校验（core 有 clamp） | 未处理 |
| SO-12 | `ImportTaskService` 任务失败仅取 `t.getMessage()`，message 为 null 时前端显示 null | 未处理 |
| SO-13 | 黑盒工程 `test/` 未纳入 CI；性能专项（写入 ≤5s / 读取 ≤2s）无黑盒计时断言（blackbox-test-report 7 节遗留） | 未处理 |

## 6. 测试覆盖与文档一致性核对（v1.1 更新）

| 项目 | 文档声称 | 实测 | 结论 |
|---|---|---|---|
| 全量测试数量 | README 40 项 / optimization.md 48 项 | **63 项**（core 41 + client 15 + server 7，`mvn test` BUILD SUCCESS，2026-08-21 实测） | README/optimization 过时，需更新为 63 项 |
| P0ReliabilityTest | review-p0 v1.1 写 7 项 | 10 项（v1.2 已更新） | 一致 |
| core 回归 | R-01 交付写 41 项 | 41 项（34 原 + 7 ReviewShouldFixTest） | 一致 |
| 黑盒测试 | blackbox-test-report v1.2 | **31 项**（可用性 7 + 完整性 8 + 安全性 9 + 最新交付 7）BUILD SUCCESS；另有定时导入长测（第 8 节） | 一致 |
| 测试类数量 | chain-test-cases 声称"合计 16 类/56 项" | 15 类 / 58 个 `@Test`（默认执行 15 类 / 57 项，`AslpvConsistencyIT` 因 `*IT` 命名被 surefire 排除） | chain-test-cases 仍未更新（16 类自相矛盾） |
| chain-test-cases F/P/S 表 A 用例 | "通过（归档前 118 项）" | 引用的测试类（CodecRoundtripTest、CsvParserTest 等）仍不存在 | 属"基线已归档"声明，无当前对应代码 |
| defects 引用测试 | `manifestRebuildPreciseWindow`、`concurrentIngestDifferentTables` 等 | 类/方法不存在；由改名测试覆盖 | 文档测试名仍过时 |
| 缺陷状态 | — | D-01~D-12 全部关闭、K-01~K-04 解决（defects.md 2026-08-21） | 一致 |

## 7. 修复建议优先级（v1.1 更新）

| 优先级 | 项 | 理由 |
|---|---|---|
| **P0（下迭代必做）** | SS-4 管理台 XSS 转义；SS-3 `{noop}` → BCrypt + 强制改密；SS-5 CSV 未闭合引号报错 | 可被直接利用的安全面 / 静默错数据（SF-1、SF-3 已解决） |
| **P1** | SS-1 createTable NPE→400；SS-2 二进制解码 500→400；SS-9 上传超限 413；SS-10 client escape 补全；SS-6 任务队列有界化；SS-7 importAsync 注释对齐；SS-8 health 路径脱敏 | 错误语义 / 资源边界 |
| **P2** | 文档同步（README/optimization.md 测试数量 → 63 项；chain-test-cases 16 类自相矛盾；defects 测试名）；黑盒工程纳入 CI；性能黑盒断言；观察项 O-1~O-16、SO-1~SO-13 逐项评估 | 工程卫生与文档一致性 |

## 8. 结论（v1.1）

1. **core 可靠性问题清零**：v1.0 的 8 项 core should-fix（SF-1~SF-8）经 R-01 交付全部修复，覆盖单测（ReviewShouldFixTest 7 项）与黑盒（LD-03~LD-06）双验证，跨表导入恢复并行、损坏段隔离、幂等随删除清理均生效；
2. **D-12 关闭**：未知端点 404 / 方法不支持 405 错误语义修复，缺陷清零（D-01~D-12 关闭，K-01~K-04 解决）；
3. **剩余风险集中在 server/client**：SS-3（弱口令）、SS-4（XSS）、SS-5（CSV 引号）为下一迭代 P0，SS-1/SS-2/SS-6~SS-10 为 P1；
4. **文档漂移待治理**：README（40）、optimization.md（48）测试数量声明过时（实测 63），chain-test-cases"16 类"自相矛盾，建议随 O-15（CI 与文档卫生）一并更新。

## 9. 复审记录（2026-08-21）

| 事件 | 内容 | 验证 |
|---|---|---|
| R-01 交付核对 | SF-1~SF-8 修复落地（代码抽查：`FsUtil.fsyncDir`、`removeIdem`/`rewriteIdemExcluding`、`quarantineCorruptSegment`、`TableState.idemLock`、`timestampRowCount` 均在位） | `ReviewShouldFixTest` 7 项；core 41 项；全量 63 项 BUILD SUCCESS；黑盒 LD-03~LD-06 |
| D-12 交付核对 | `ApiExceptionHandler` 新增 404/405 映射（`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`），错误体结构化 `{code,message,timestamp,path}` | 黑盒 LD-01/LD-02；黑盒全量 31 项 |
| 黑盒最新交付验证 | LD-01~LD-07 全部通过（404/405、损坏段隔离、幂等清理重放、混合批、跨表并行、常规回归） | `LatestDeliveryAvailabilityTest` Tests run: 7, Failures: 0 |
| 定时导入长测 | 压缩 20 表 × 每 1 分钟 client 导入 1000 条 ×10：10 快照全量/单点/历史全部正确，无新缺陷 | `ScheduledImportTest`（正式 543.8s） |
| 测试数量演进 | 56（v1.0 实测）→ **63**（core 41 + client 15 + server 7）；黑盒 24 → 31 | `mvn test` BUILD SUCCESS |
