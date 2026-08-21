# AstraDB 全项目评审报告

> 版本：v1.2（复审）· 日期：2026-08-21 · 状态：正式
> 评审历程：v1.0（2026-08-20 初评，无 blocking / 8+10 项 should-fix）→ v1.1（2026-08-21：R-01 修复 core SF-1~SF-8、D-12 关闭）→ **v1.2（2026-08-21：R-02 修复 server/client SS-1~SS-10，should-fix 全部清零，本版）**
> 评审基准：工作区 HEAD + 未提交的修复（R-01：SF-1~SF-8；R-02：SS-1~SS-10；D-12：404/405 错误语义）
> 关联文档：[review-p0.md](./review-p0.md)（P0 专项评审）、[optimization.md](../design/optimization.md)（优化提案）、[design.md](../design/design.md)（设计）、[R-01 修复交付](../phaseReport/R-01-review-shouldfix.md)、[R-02 修复交付](../phaseReport/R-02-review-server-shouldfix.md)、[D-12 修复交付](../phaseReport/D-12-unknown-endpoint-404.md)、[blackbox-test-report.md](../test/blackbox-test-report.md)（黑盒测试）、[defects.md](../test/defects.md)（缺陷跟踪）

---

## 1. 评审范围与方法

| 维度 | 范围 |
|---|---|
| 代码 | `core/`（存储/编码/压缩/段/点字典/导入/查询/保留期）、`server/`（API/安全/异步导入/UI）、`client/`（二进制协议 + 自含 JSON）、`test/`（黑盒测试工程） |
| 文档对照 | design.md、optimization.md、chain-test-*、blackbox-test-report.md、defects.md、R-01/D-12 交付报告 |
| 实测（v1.2） | `mvn test`：**BUILD SUCCESS，70 项全绿**（core 41 + client 16 + server 13）；黑盒工程 31 项 BUILD SUCCESS |
| 原则 | 只读评审，不修改任何 Java 代码；结论均附 file:line 证据 |

## 2. 总体结论（v1.2 复审）

**verdict: 放行（should-fix 全部清零）** —— 无 **blocking**；v1.0 报告的 **8（core）+ 10（server/client）项 should-fix 已全部修复并验证**：R-01 交付 SF-1~SF-8（`ReviewShouldFixTest` 7 项 + 黑盒 LD-03~LD-06）、R-02 交付 SS-1~SS-10（server/client 新增回归 14 项 + 全量 70 项）；黑盒发现的 D-12（未知端点 404）已修复关闭。缺陷全部清零（D-01~D-12 关闭，K-01~K-04 解决）。

**v1.1 已解决（R-01）**：
1. ~~SF-1 幂等记录不随删除清理（静默丢数据方向）~~ → `removeIdem` + `rewriteIdemExcluding`（临时文件 + fsync + ATOMIC_MOVE + 目录 fsync），删除/保留期清理同步枚举段内 ts；
2. ~~SF-3 损坏段使整个库无法 open~~ → 启动隔离至 `segments/.quarantine/*.corrupt` + WARN + manifest 剔除，好数据可用（黑盒 LD-03 验证）；
3. ~~SF-4 全局幂等锁串行化跨表导入~~ → 幂等 map/锁按表拆分（`TableState.idemLock`），跨表导入恢复并行（黑盒 LD-06 验证）；
4. ~~D-12 未知端点 500~~ → `NoResourceFoundException`→404、`HttpRequestMethodNotSupportedException`→405（黑盒 LD-01/LD-02 验证）。

**v1.2 已解决（R-02）**：
1. ~~SS-3 `{noop}` 明文弱口令~~ → 移除 `{noop}`，DelegatingPasswordEncoder（默认 BCrypt）编码存储；默认口令启动 WARN 告警；
2. ~~SS-4 管理台存储型 XSS~~ → `app.js`/`table.html` 统一 `esc()` HTML 实体转义 + `data-act` 事件委托（消除 onclick 内联注入面）+ `validateName` 禁 `' " < >` 纵深防御；
3. ~~SS-5 CSV 未闭合引号吞行~~ → 引号内遇换行/EOF 报 `IngestException`（RFC 4180 格式错误）；
4. ~~SS-1/SS-2/SS-6~SS-10~~ → createTable 缺 columns 400、二进制帧长度校验（≤64MB）+ RuntimeException→400、异步队列有界（100）+ trim 不裁剪 RUNNING + `@PreDestroy`、importAsync 解析移入后台线程、health 脱敏（仅 `dataDirWritable`）、上传超限 413、client JSON 拼接改用 `ClientJson.quote`。

**剩余（观察项为主，无 should-fix）**：core 观察项 O-1~O-16 与 server/client 观察项 SO-1~SO-13 中，除 SO-4（随 SS-2 修复）、SO-7 部分（随 SS-5）外均为低风险工程卫生项，详见第 4.2 / 5.2 节。

**亮点（v1.0 确认，两轮复审未发现回退）**：锁序一致无死锁；`contentHash64` 64 位 FNV-1a（STRING 逐 char）；dataDir 锁异常路径释放；幂等批内合并 fsync；预写占位幂等；错误码映射（400/404/405/413）与路径安全；二进制协议自洽；UI 模板转义。

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

### 5.1 should-fix（v1.0 清单 SS-1~SS-11：**v1.2 全部已修复**，交付见 [R-02](../phaseReport/R-02-review-server-shouldfix.md)、SS-11 见 [D-12](../phaseReport/D-12-unknown-endpoint-404.md)）

| ID | 问题（v1.0 描述） | 修复方案（R-02） | 验证 | 状态 |
|---|---|---|---|---|
| SS-1 | **createTable 缺 `columns` 字段 → NPE → 500** | `columns` 为空抛 `IllegalArgumentException` → 400 INVALID_ARGUMENT | `ApiContractTest` 新增断言 | ✅ 已修复 |
| SS-2 | **二进制协议解码 RuntimeException → 500**（含恶意帧 OOM 面） | `readString` 校验 varint 长度区间（≤64MB，防负数/超大分配）；`BinaryIngestParser` catch RuntimeException → 400 INGEST_REJECTED | `BinaryEndpointTest` 新增 varint 0xFFFFFFFF 帧断言 | ✅ 已修复 |
| SS-3 | **密码 `{noop}` 明文 + 默认弱口令 `admin123`** | 移除 `{noop}`，DelegatingPasswordEncoder（默认 BCrypt）编码存储；配置值带 `{prefix}` 原样使用；默认口令启动 WARN 告警 | `SecurityContractTest` BCrypt 登录回归 | ✅ 已修复 |
| SS-4 | **管理台存储型 XSS**（表名/数据进 innerHTML） | `app.js`/`table.html` 统一 `esc()`（`& < > " '`）转义；删除类操作改 `data-act` + 事件委托（消除 onclick 内联注入面）；`validateName` 禁 `' " < >` 纵深防御 | 前端渲染回归 | ✅ 已修复 |
| SS-5 | **CSV 未闭合引号吞行 → 静默错数据** | 引号内遇 `\n`/`\r` 或 EOF 报 `IngestException`（RFC 4180 格式错误） | `CsvParserTest`（新建 4 项） | ✅ 已修复 |
| SS-6 | **ImportTaskService 无界队列 + trim 可能删除 RUNNING + 无关闭钩子** | `ThreadPoolExecutor` + `ArrayBlockingQueue(100)` 有界，满则 400；trim 只清理已结束任务；新增 `@PreDestroy` 优雅停池 | `batchAndAsyncImport` 回归 | ✅ 已修复 |
| SS-7 | **importAsync"大文件不阻塞请求"与实现不符** | CSV 解析移入后台线程（`submit` 收 CSV 字节，后台 parse + ingest）；请求线程仅读字节 + 表存在性前置校验 | `batchAndAsyncImport` 回归 | ✅ 已修复 |
| SS-8 | **HealthController 未认证泄露 dataDir 绝对路径** | 移除 `dataDir` 字段，仅保留 `dataDirWritable` 布尔 | 黑盒 AV-01 回归 | ✅ 已修复 |
| SS-9 | **上传超限异常未映射为 4xx** | `MaxUploadSizeExceededException` → **413** PAYLOAD_TOO_LARGE 结构化错误体 | `UploadLimitTest`（新建） | ✅ 已修复 |
| SS-10 | **client `escape()` 不完整 → 控制字符 key 生成非法 JSON** | 4 处 JSON 拼接改用 `ClientJson.quote`（含 `\uXXXX` 转义），删除不完整的 `escape()` | `ClientContractTest` 新增换行/引号/反斜杠 key 断言 | ✅ 已修复 |
| SS-11 | **未知 API 端点返回 500 而非 404**（黑盒 AV-04 发现，D-12） | `NoResourceFoundException`→404 NOT_FOUND、`HttpRequestMethodNotSupportedException`→405 METHOD_NOT_ALLOWED，错误体结构化 | 黑盒 LD-01/LD-02 | ✅ 已修复（D-12） |

### 5.2 观察项（v1.0 清单，v1.1 状态）

| ID | 内容 | 状态 |
|---|---|---|
| SO-1 | `security.enabled` 默认 false（docker-compose/systemd 已强制 true；直接 `java -jar` 时 API 全开放） | 未处理 |
| SO-2 | `resolveSegment` 前缀匹配越界（利用面低），建议边界判断 | 未处理 |
| SO-3 | `BinaryProtocol.encode` nullable 且 nullBitmap==null → NPE（公共 API 无保护） | 未处理 |
| SO-4 | `readString/readBytes` 无长度上限，恶意帧可触发大数组分配（OOM 面） | ✅ 随 SS-2 修复（varint 长度 ≤64MB 校验） |
| SO-5 | `getFullSnapshot`/`queryFullSnapshotBinary` 先全量载入内存再流式写，注释不成立 | 未处理 |
| SO-6 | `SlowQueryInterceptor` 对 JSON body 请求 `getParameter("table")` 恒为 null | 未处理 |
| SO-7 | `CsvParser` 中间空行报 400（方向安全）；首行与列名全同时被当表头吞掉 | 部分缓解（未闭合引号已报错；空行/表头行为未改） |
| SO-8 | Jackson 2（core 传递依赖）与 Spring Boot 4 默认 Jackson 3 双栈并存 | 未处理 |
| SO-9 | 错误消息可能泄露段文件绝对路径（`ApiExceptionHandler` 包装 `e.getMessage()`） | 未处理 |
| SO-10 | `AstraDbService` 注释"缺省取系统时区"与 `application.yml` 默认 `Asia/Shanghai` 不符 | 未处理 |
| SO-11 | `getSnapshot`/`series` 的 limit/offset 无上限校验（core 有 clamp） | 未处理 |
| SO-12 | `ImportTaskService` 任务失败仅取 `t.getMessage()`，message 为 null 时前端显示 null | 未处理 |
| SO-13 | 黑盒工程 `test/` 未纳入 CI；性能专项（写入 ≤5s / 读取 ≤2s）无黑盒计时断言（blackbox-test-report 7 节遗留） | 未处理 |

## 6. 测试覆盖与文档一致性核对（v1.1 更新）

| 项目 | 文档声称 | 实测 | 结论 |
|---|---|---|---|
| 全量测试数量 | README 40 项 / optimization.md 48 项 | **70 项**（core 41 + client 16 + server 13，`mvn test` BUILD SUCCESS，2026-08-21 实测） | 本次已同步为 70 项（README / optimization / phase-report / chain-test-* / review-p0） |
| P0ReliabilityTest | review-p0 v1.1 写 7 项 | 10 项（v1.2 已更新） | 一致 |
| core 回归 | R-01 交付写 41 项 | 41 项（34 原 + 7 ReviewShouldFixTest） | 一致 |
| 黑盒测试 | blackbox-test-report v1.2 | **31 项**（可用性 7 + 完整性 8 + 安全性 9 + 最新交付 7）BUILD SUCCESS；另有定时导入长测（第 8 节） | 一致 |
| 测试类数量 | chain-test-cases 原声称"合计 16 类/56 项"（自相矛盾） | 源码 18 类 / 71 个 `@Test`；默认执行 17 类 / 70 项（`AslpvConsistencyIT` 因 `*IT` 命名被 surefire 排除） | 已同步（chain-test-cases 附注更新为 18 类/71 项、执行 17 类/70 项） |
| chain-test-cases F/P/S 表 A 用例 | "通过（归档前 118 项）" | 引用的测试类（CodecRoundtripTest、CsvParserTest 等）仍不存在 | 属"基线已归档"声明，无当前对应代码（既有说明保留） |
| defects 引用测试 | `manifestRebuildPreciseWindow`、`concurrentIngestDifferentTables` 等 | 类/方法不存在；由改名测试覆盖（`StorageLifecycleTest.manifestRebuildAfterDeletionAndDrift`、`ConcurrencyTest.crossTableWritesAreParallel`） | 已同步（K-01/K-02 更新为现存测试名；D-xx 历史引用保留） |
| 缺陷状态 | — | D-01~D-12 全部关闭、K-01~K-04 解决（defects.md 2026-08-21） | 一致 |

## 7. 修复建议优先级（v1.2 更新）

| 优先级 | 项 | 理由 |
|---|---|---|
| ~~P0~~ | ~~SS-4 XSS / SS-3 BCrypt / SS-5 CSV 引号~~ | **已随 R-02 全部修复**（v1.2） |
| ~~P1~~ | ~~SS-1/SS-2/SS-6~SS-10~~ | **已随 R-02 全部修复**（v1.2） |
| **P2（观察项评估）** | 黑盒工程纳入 CI；性能黑盒断言（写入 ≤5s / 读取 ≤2s）；观察项逐项评估：O-1~O-16（core）、SO-1~SO-3/SO-5~SO-13（server/client，SO-4/SO-7 已部分缓解）；文档同步后续随 O-15 治理 | 工程卫生与文档一致性 |

## 8. 结论（v1.2）

1. **全部 should-fix 清零**：v1.0 的 18 项 should-fix（core SF-1~SF-8 + server/client SS-1~SS-10）经 R-01/R-02 交付全部修复，单测（ReviewShouldFixTest 7 项、server/client 新增回归 14 项）与黑盒（LD-01~LD-07）双验证，缺陷清零（D-01~D-12 关闭，K-01~K-04 解决）；
2. **全量 70 项 + 黑盒 31 项全绿**（2026-08-21 实测），错误语义（400/404/405/413）与安全默认值（BCrypt、XSS 转义、health 脱敏）达标；
3. **剩余为观察项**（core O-1~O-16、server/client SO-1~SO-3/SO-5~SO-13），均为低风险工程卫生项，建议按 P2 逐项评估、随 O-15（CI 与文档卫生）治理；
4. **文档漂移本次已治理**：README（70 项）、optimization.md（70 项）、phase-report.md（70 项、D-12 关闭）、chain-test-cases（18 类/71 项、执行 17 类/70 项）、chain-test-report（17 类/70 项）、review-p0（70 项）、defects K-01/K-02 测试名均已与实测同步。

## 9. 复审记录（2026-08-21）

| 事件 | 内容 | 验证 |
|---|---|---|
| R-01 交付核对 | SF-1~SF-8 修复落地（代码抽查：`FsUtil.fsyncDir`、`removeIdem`/`rewriteIdemExcluding`、`quarantineCorruptSegment`、`TableState.idemLock`、`timestampRowCount` 均在位） | `ReviewShouldFixTest` 7 项；core 41 项；全量 63 项 BUILD SUCCESS；黑盒 LD-03~LD-06 |
| R-02 交付核对 | SS-1~SS-10 修复落地（代码抽查：`SecurityConfig` DelegatingPasswordEncoder、`esc()`/`data-act` 前端转义、`CsvParser` 未闭合引号报错、`ImportTaskService` 有界队列、`HealthController` 脱敏、`ApiExceptionHandler` 413、`ClientJson.quote`） | server 13 项（含 `CsvParserTest` 4、`UploadLimitTest` 1）；client 16 项；全量 **70 项** BUILD SUCCESS |
| D-12 交付核对 | `ApiExceptionHandler` 新增 404/405 映射（`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`），错误体结构化 `{code,message,timestamp,path}` | 黑盒 LD-01/LD-02；黑盒全量 31 项 |
| 黑盒最新交付验证 | LD-01~LD-07 全部通过（404/405、损坏段隔离、幂等清理重放、混合批、跨表并行、常规回归） | `LatestDeliveryAvailabilityTest` Tests run: 7, Failures: 0 |
| 定时导入长测 | 压缩 20 表 × 每 1 分钟 client 导入 1000 条 ×10：10 快照全量/单点/历史全部正确，无新缺陷 | `ScheduledImportTest`（正式 543.8s） |
| 测试数量演进 | 56（v1.0 实测）→ 63（R-01）→ **70**（R-02：core 41 + client 16 + server 13）；黑盒 24 → 31 | `mvn test` BUILD SUCCESS |
| 文档漂移治理 | README/optimization/phase-report 测试数量同步为 70 项；chain-test-cases 附注更新（18 类/71 项、执行 17 类/70 项）；chain-test-report 17 类/70 项；review-p0 数字同步；defects K-01/K-02 测试名更新 | 链接校验 0 损坏 |
