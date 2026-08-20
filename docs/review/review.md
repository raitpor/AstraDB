# AstraDB 全项目评审报告

> 版本：v1.0 · 日期：2026-08-20 · 状态：正式
> 评审基准：工作区 HEAD + 未提交的 P0 可靠性修复（`AstraDB.java` / `SnapshotData.java` / `SnapshotIngestor.java` / 测试代码）
> 评审方法：三个独立 review 子代理（core 41 文件 / server+client / 测试覆盖核对）+ 人工逐项抽查关键结论 + `mvn test` 全量实跑
> 关联文档：[review-p0.md](./review-p0.md)（P0 专项评审）、[optimization.md](../design/optimization.md)（优化提案）、[design.md](../design/design.md)（设计）、[test/defects.md](../test/defects.md)（缺陷跟踪）、[test/chain-test-report.md](../test/chain-test-report.md)（测试报告）

---

## 1. 评审范围与方法

| 维度 | 范围 |
|---|---|
| 代码 | `core/`（41 个源文件，存储/编码/压缩/段/点字典/导入/查询/保留期）、`server/`（API/安全/异步导入/UI）、`client/`（二进制协议 + 自含 JSON） |
| 文档对照 | design.md、optimization.md、chain-test-plan/cases/report、defects.md |
| 实测 | `mvn test`：**BUILD SUCCESS，14 测试类 / 56 项全绿**（core 34 + client 15 + server 7） |
| 原则 | 只读评审，不修改任何 Java 代码；结论均附 file:line 证据 |

## 2. 总体结论

**verdict: 有条件放行** —— 无 **blocking** 级问题（无死锁、无常规路径数据损坏、无未修复的 P0 可靠性缺口），但存在 **8（core）+ 10（server/client）项 should-fix** 与一批观察项，其中多项属"静默丢数据 / 损坏后不可恢复 / 安全默认值弱"方向，建议在下一迭代优先处理。

**亮点（已确认正确）**：锁序一致无死锁；`contentHash64` 为 64 位 FNV-1a（STRING 逐 char 喂入）；dataDir 文件锁异常路径正确释放；幂等批内合并 fsync；预写占位幂等消除崩溃窗口；错误码映射（`IngestException`→400）与路径安全（`validateName`、`resolveSegment` 越界拒绝）正确；二进制协议 encode/decode 自洽；管理页模板转义（Thymeleaf）正确。

**主要风险**：
1. **SF-1 幂等记录不随数据删除清理** → 删除后重放同 ts 同内容被静默跳过（静默丢数据方向）；
2. **SF-3 任一损坏段使整个库无法 open**，且无隔离/自愈手段；
3. **存储型 XSS**（管理台表名/数据进 innerHTML）与 **`{noop}` 明文弱口令**；
4. **CSV 未闭合引号吞行** → 静默导入错误数据。

## 3. 已验证正确项

| 项 | 证据 |
|---|---|
| 锁序一致、无死锁 | `AstraDB.java:560-562, 615-619` 均为「表写锁 → 幂等锁」；`dropTable` 为「global → table」；无「幂等锁 → 表锁」嵌套路径 |
| 64 位内容哈希 | `SnapshotData.java:61-64` STRING 逐 char 喂 FNV-1a（弃用 `String.hashCode()`）；INT/LONG/DOUBLE 原始 64 位参与，null 位图纳入 |
| dataDir 锁异常路径释放 | `AstraDB.java:297-310` 加载失败时 release+close；成功路径 `close()` 释放 |
| 幂等批内合并 fsync | `AstraDB.java:171-193` `appendIdemBatch` 一次 open + 一次 write + 一次 `force(true)` |
| 预写占位幂等 | `AstraDB.java:577-584, 618-645` 占位先落盘（fsync）→ 段提交 → 正式记录 |
| 段格式与编解码 | SegmentFormat/ChunkCodec/三编码器/Crc64/BitWriter 逐行核对无确认级错误；Gorilla 窗口复用与标准实现一致 |
| 客户端错误 → 400 | `IngestException`/`IllegalArgumentException` → 400；`IOException` → 500（映射正确） |
| 表名/路径安全 | `AstraDB.validateName` 禁 `/`、`\`、控制字符；`resolveSegment` 有目录越界拒绝；段文件路径由固定格式生成 |
| 二进制协议自洽 | `BinaryProtocol` encode/decode 对称（列数上限 1024、行数上限 Integer.MAX、varint 溢出检查） |
| UI 模板转义 | `UiController` 注入经 `th:text`/`[[…]]` 转义，无模板注入 |

## 4. core 问题清单

### 4.1 should-fix

| ID | 问题 | 位置 | 影响 / 建议 |
|---|---|---|---|
| SF-1 | **幂等记录不随数据删除清理** | `AstraDB.java:756-826`（deleteSegment/deleteSnapshot）、`RetentionCleaner.java:30-37` 只删段/manifest，不清理 `idempotency.idx` | 删除某快照/段后，同 ts 同内容重放命中正式记录 → 直接返回成功、数据实际未写入（**静默丢数据方向**）；建议删除路径同步清理对应幂等记录 |
| SF-2 | **混合批导入（部分快照已提交）必抛"时间戳已存在"** | `AstraDB.java:633-634` 慢路径把全部快照传给 `SnapshotIngestor.ingestBatch`；`SnapshotIngestor.java:277-281` 批内查重抛异常 | 快速路径要求"全部命中"、慢路径不能跳过已命中条目 → "批内部分重放 + 部分新增"无法成功，用户只能拆批；建议慢路径跳过已命中条目 |
| SF-3 | **损坏段使整个库无法 open，无隔离/自愈** | `AstraDB.java:404-418` → `SegmentReader.open` 抛 IOException → `open()` 失败 | 任一 `.seg` 头部损坏（bit rot）→ 全部表加载失败、库打不开，且无 API 可删除损坏段（deleteSegment 依赖 manifest 且须先 open）；建议启动隔离损坏段或提供修复工具 |
| SF-4 | **全局幂等锁把跨表导入串行化** | `AstraDB.java:97-102` 单实例全局 `LinkedHashMap`；`562-586` 整个 `SnapshotIngestor.ingest`（编码/压缩/写段/fsync）在 `synchronized(idempotency)` 内 | 表级写锁是 per-table，但幂等锁全局覆盖整个导入，跨表导入实际完全串行（与 K-02"跨表写并行"注释矛盾），且锁内含文件 IO；建议幂等表按表拆分或移出大锁区间 |
| SF-5 | **`writeSegmentsBatch` rename 循环并非"全有或全无"** | `SnapshotIngestor.java:310-322` 逐个 `Files.move(ATOMIC_MOVE)`；注释 243-244 声称"全有或全无" | 崩溃后已 rename 段生效、未 rename 段留 `.staging`，`validateManifest` 将其纳入 → 实际"部分成功"（幂等重放可覆盖，安全但语义与注释不符）；建议注释收敛为"近似原子"（review-p0 S-4 已接受） |
| SF-6 | **占位命中确认路径返回值不精确且 int 强转可溢出** | `AstraDB.java:570-573` `return new IngestResult(ts, (int) si.rows(), (int) si.rows())` | `si.rows()` 是整段行数而非该快照行数；`long`→`int` 在段行数 > 2^31 时溢出为负；建议占位确认返回精确 rowCount 或标注语义 |
| SF-7 | **删除/rename 后无目录 fsync** | `JsonFiles.java:42`、`SegmentRewriter.java:78`、`SnapshotIngestor.java:314-315`、`RetentionCleaner.java:35` | 只 fsync 文件内容不落目录项，断电后 rename/delete 可能不持久（manifest 与磁盘靠启动校验校正，段删除可能复活）；建议 rename/delete 后 fsync 父目录 |
| SF-8 | **`SegmentWriter.close` 异常路径泄漏 RAF 句柄** | `SegmentWriter.java:150-152` `raf.getFD().sync()` 抛 IOException 时 `raf.close()` 不执行 | sync 失败后 `RandomAccessFile` 未关闭；建议 try-with-resources |

### 4.2 观察项（精简）

| ID | 内容 |
|---|---|
| O-1 | `contentHash64` null 与 `"\u0000"` 哨兵均喂 `fnv(h,0L)`，哈希不可区分（碰撞点，概率极低） |
| O-2 | `open()` 异常路径不关闭 `segmentChannels` 池中已 acquire 的句柄（仅泄漏于加载失败场景） |
| O-3 | `appendIdemBatch` 无尾部占位截断（与单条 `appendIdem` 不对称），仅残留冗余占位记录，语义正确 |
| O-4 | `rewriteIdem` 非原子（truncate(0)+write），中途崩溃幂等文件变空（触发概率低） |
| O-5 | >2GB 段不支持（`SegmentReader.java:55`/`SegmentWriter.java:76,88` int 截断） |
| O-6 | 正常查询路径无逐 chunk CRC 校验（依赖文件级 Crc64） |
| O-7 | `ingest` 在 timestamp=null 时存在"双 now"窗口（`AstraDB.java:556` 与 `SnapshotIngestor.java:50` 各取一次时钟） |
| O-8 | 描述信息 light 模式 minKey/maxKey 占位 1,1（不写回，安全） |
| O-9 | 损坏数据下 `BitReader.fill` 越界抛 AIOOBE 而非受控异常（有 Crc64 兜底） |
| O-10 | `ChunkCache` 字节度量只计 raw 数组（不含 nullBitmap/对象头），上限偏松 |
| O-11 | 查询对"段缺失"行为不一致：`PointSeriesQuery.java:55-57` 跳过 vs `SnapshotQuery.java:152-153` 抛 IOException |
| O-12 | 单快照整块内存驻留（`ChunkCodec.encode` 一次整 chunk），超大快照内存峰值高 |
| O-13 | `RetentionCleaner` 按整段粒度删除，跨期段整体保留（保守不误删） |
| O-14 | 幂等锁内做文件 IO（`timestampExists` 在锁内 `SegmentReader.open`），放大锁竞争 |
| O-15 | `SegmentChannelCache` 活跃句柄无上限（并发 reader 超限时超出池上限打开 FD） |
| O-16 | `appendIdem` IO 失败仅降级进程内幂等，重启后不可恢复（有 WARN 日志） |

## 5. server / client 问题清单

### 5.1 should-fix

| ID | 问题 | 位置 | 影响 / 建议 |
|---|---|---|---|
| SS-1 | **createTable 缺 `columns` 字段 → NPE → 500** | `TableController.java:47` `req.columns().stream()` 未判空 | 客户端参数缺失应为 400；建议判空并抛 `IllegalArgumentException` |
| SS-2 | **二进制协议解码 RuntimeException → 500** | `BinaryIngestParser.java:28-33` 仅捕获 `IOException`；`BinaryReader.java:61` 无符号 varint 强转 int 可为负 → `NegativeArraySizeException` | 恶意/损坏帧穿透为 500；建议校验长度并把运行时异常映射 400 |
| SS-3 | **密码 `{noop}` 明文 + 默认弱口令 `admin123`** | `SecurityConfig.java:42,44`、`application.yml:17` | 开启鉴权后仍可被默认口令全权操作（对应 G-10/O-14 候选）；建议 BCrypt 编码 + 强制改密 |
| SS-4 | **管理台存储型 XSS** | `app.js:50-53` 表名拼 `innerHTML`（含 `onclick="dropTable('${name}')"`）；`table.html:231-234` `${r.key}`/`${v}` 进 innerHTML；`AstraDB.validateName` 不限制 `'`/`<`/`>` | 表名含 `'` 可注入 JS；STRING 列值可为 `<img onerror=...>`；建议 innerHTML 路径统一转义（textContent 或转义函数），validateName 限制特殊字符 |
| SS-5 | **CSV 未闭合引号吞行 → 静默错数据** | `CsvParser.java:48-55` `inQuotes` 分支把 `\n` 当普通字符 append；`92-94` 单独 `\r` 被忽略 | 未闭合引号把后续行并入同一字段，列数恰好匹配时不报错、静默导入错误数据；RFC 4180 应报格式错误 |
| SS-6 | **ImportTaskService 无界队列 + trim 可能删除 RUNNING 任务 + 无关闭钩子** | `ImportTaskService.java:34-40`（无界 `LinkedBlockingQueue`）、`74-83`（按 id 删最旧含 RUNNING） | 大文件任务连续提交内存无限堆积；RUNNING 任务被 trim 后状态查询返回"任务不存在"；建议有界队列、trim 排除 RUNNING、`@PreDestroy` 关闭线程池 |
| SS-7 | **importAsync"大文件不阻塞请求"与实现不符** | `DataController.java:80-91` `CsvParser.parse` 在请求线程同步完成 | 仅 `ingest` 落盘异步，大文件解析仍阻塞 HTTP 线程并占请求内存；建议注释/实现二选一对齐 |
| SS-8 | **HealthController 未认证泄露 dataDir 绝对路径** | `HealthController.java:32` 返回 `dataDir`；`SecurityConfig.java:32` 对 `/api/health` permitAll | 未认证者可获服务器文件系统布局；建议脱敏或鉴权 |
| SS-9 | **上传超限异常未映射为 4xx** | `application.yml:24-26` 限制 200MB；`ApiExceptionHandler` 未处理 `MaxUploadSizeExceededException` 等 | 超限上传落入默认 500 或非统一错误格式；建议映射 413/400 |
| SS-10 | **client `escape()` 不完整 → 控制字符 key 生成非法 JSON** | `AstraDbClient.java:351-353` 仅转义 `\` 与 `"`；`134-135` 拼入 JSON | STRING 主键含换行/制表符时产生裸控制字符，server 400，客户端无法查询此类点；建议改用 `ClientJson.quote` |

### 5.2 观察项（精简）

| ID | 内容 |
|---|---|
| SO-1 | `security.enabled` 默认 false（docker-compose/systemd 已强制 true；直接 `java -jar` 时 API 全开放），建议启动告警或默认开启 |
| SO-2 | `resolveSegment` 前缀匹配越界（`segments2/x` 同前缀可通过校验，实际利用面低），建议边界判断 |
| SO-3 | `BinaryProtocol.encode` nullable 且 nullBitmap==null → NPE（公共 API 构造帧时无保护） |
| SO-4 | `readString/readBytes` 无长度上限，恶意帧可触发大数组分配（OOM 面） |
| SO-5 | `getFullSnapshot`/`queryFullSnapshotBinary` 先全量载入内存再流式写，注释"避免整页内存缓冲"不成立 |
| SO-6 | `SlowQueryInterceptor` 对 JSON body 请求 `getParameter("table")` 恒为 null，日志 `table=-` |
| SO-7 | `CsvParser` 中间空行报 400（方向安全但可改进为忽略）；首行与列名全同时被当表头吞掉 |
| SO-8 | Jackson 2（core 传递依赖）与 Spring Boot 4 默认 Jackson 3 双栈并存，序列化行为可能不一致 |
| SO-9 | 错误消息可能泄露段文件绝对路径（`ApiExceptionHandler` 包装 `e.getMessage()`） |
| SO-10 | `AstraDbService` 注释"缺省取系统时区"与 `application.yml` 默认 `Asia/Shanghai` 不符 |
| SO-11 | `getSnapshot`/`series` 的 limit/offset 无上限校验（core 有 clamp，行为可接受但语义隐蔽） |
| SO-12 | `ImportTaskService` 任务失败仅取 `t.getMessage()`，message 为 null 时前端显示 null |

## 6. 测试覆盖与文档一致性核对

| 项目 | 文档声称 | 实测 | 结论 |
|---|---|---|---|
| 全量测试数量 | README 40 项 / optimization.md 48 项 | **14 类 / 56 项**（core 34 + client 15 + server 7，`mvn test` BUILD SUCCESS） | README/optimization 过时，需更新为 56 项 |
| P0ReliabilityTest | review-p0 v1.1 写 7 项 | **10 项**（含 B-1/B-2/S-1 三个回归） | review-p0 v1.2 已更新 |
| 测试类数量 | chain-test-cases 声称"合计 16 类/56 项" | **15 类 / 57 个 `@Test`**（默认执行口径 14 类/56 项，`AslpvConsistencyIT` 因 `*IT` 命名被 surefire 默认排除） | 文档自相矛盾（子表求和 15 类）；perf 排除实际靠命名约定而非 pom 配置 |
| chain-test-cases F/P/S 表 A 用例 | "通过（归档前 118 项）" | 引用的测试类（CodecRoundtripTest、CsvParserTest、PerfBenchmarkTest 等）**均不存在** | 属"基线已归档"有意声明，但当前无对应可执行代码 |
| defects K-01/K-02/D-xx 引用测试 | `manifestRebuildPreciseWindow`、`concurrentIngestDifferentTables`、`CsvParserTest` 等 | 类/方法不存在；功能由改名测试覆盖（`manifestRebuildAfterDeletionAndDrift`、`crossTableWritesAreParallel` 等） | 文档测试名过时 |
| 性能基准 | chain-test-report 自认 PerfBenchmarkTest 未恢复 | `AslpvConsistencyIT`（perf 标注）存在但默认不执行 | 百万行性能基准当前无可执行回归 |

## 7. 修复建议优先级

| 优先级 | 项 | 理由 |
|---|---|---|
| **P0（下迭代必做）** | SF-1 幂等记录随删除清理；SF-3 损坏段隔离/自愈；SS-4 管理台 XSS 转义；SS-3 `{noop}` → BCrypt；SS-5 CSV 未闭合引号报错 | 静默丢数据 / 损坏后不可恢复 / 可被直接利用的安全面 |
| **P1** | SS-1 createTable NPE→400；SS-2 二进制解码 500→400；SS-9 上传超限 413；SF-4 幂等锁按表拆分；SF-7 目录 fsync；SF-8 RAF 释放；SS-10 client escape 补全 | 错误语义 / 并发与持久化边界 |
| **P2** | SS-8 health 路径脱敏；SS-6 任务队列有界化；SS-7 importAsync 注释对齐；SF-2 混合批；SF-5/SF-6 语义收敛；文档测试数量与测试名同步（README/optimization/chain-test-cases/defects） | 工程卫生与文档一致性 |

## 8. 结论

1. 项目整体质量良好：核心存储语义正确、P0 可靠性闭环（O-01/O-02/O-03）达标、56 项测试全绿、缺陷与已知问题全部关闭；
2. 本报告未发现 blocking，但有 18 项 should-fix 与一批观察项，核心风险集中在**数据删除后的幂等残留（SF-1）**、**损坏段拒启动（SF-3）**、**管理台 XSS（SS-4）**与**弱口令（SS-3）**；
3. 建议按第 7 章优先级排入下一迭代，修复后由本报告复审关闭；同时将 README/optimization.md 的测试数量声明同步为 56 项，并回填 defects.md（登记为 D-12 起）；
4. 文档漂移（chain-test-cases 16 类/56 项自相矛盾、已归档测试类引用）建议随 O-15（CI 与文档卫生）一并治理。
