# AstraDB 评价与优化方案

> 版本：v1.1（评审稿） · 日期：2026-08-20 · 状态：待评审
> 关联文档：[design.md](./design.md)（设计）、[scenario.md](./scenario.md)（场景）、[phase-report.md](../phaseReport/phase-report.md)（阶段报告）、[test/defects.md](../test/defects.md)（缺陷跟踪）
> 说明：本文档为**优化提案**，所有优化项均标注状态（`候选` = 未实施；`已实施` = 代码已落地，实施情况与遗留局限见各节，待并入 design.md 16 节）。不含代码实现，仅描述动机、方案要点、预期收益、验收标准与实施情况。

---

## 1. 现状评价

### 1.1 定位与完成度

AstraDB 是一套**定位清晰、完成度高的自研存储引擎**：以"周期性全量快照 + 长期保留 + 回溯查询"这一窄场景为锚点，技术选型（列式 + Gorilla 时序编码 + 按天分片不可变段 + zstd 二次压缩）高度自洽，代码（约 9.4k 行 Java，75 个文件）实现了"场景约束 → 存储格式 → 崩溃语义 → 运维手段"的完整闭环，并配套了设计、场景、阶段报告、缺陷跟踪四类文档。

关键能力核对（实现 vs design.md）：

| 维度 | 结论 |
|---|---|
| 存储格式 | `.seg` 容器（FileHeader + Chunk + ChunkIndex + Footer CRC64）、列偏移表、CRC32C、崩溃截断恢复——与 design 6.1/6.2 一致 |
| 编码/压缩 | Gorilla / DeltaVarint / Dictionary + zstd（1~22 表级可配），含按需解码（valueAt/decodeRange） |
| 并发模型 | 全局锁只管表集合；表级读写锁：同表写串行、查询与写并发、跨表写并行；锁序 global→table 防死锁 |
| 崩溃安全 | points.dict 先落盘再写 chunk（孤儿点无害）、元数据临时文件 + rename、manifest 可重建 |
| 模块边界 | core 零框架依赖、client 零第三方依赖（JDK HttpClient + 自含 JSON/二进制协议）、server 为薄编排层 |
| 测试 | `mvn test` 实测 70 项全绿（core 41 + client 16 + server 13）；缺陷 D-01~D-12 全部关闭 |

### 1.2 值得肯定的设计点

1. **快照内按 pointId 排序**：一个决策同时服务三项收益——主键列近似递增（Delta+Varint 高压缩）、单点查询二分定位、manifest 段窗口 minKey/maxKey 跨段跳过（`SnapshotIngestor` / `PointSeriesQuery`）；
2. **null 位图"有效值序列"**：只编码非 null 值 + 位图标记，压缩不因占位退化；全非空列零开销（`ChunkCodec` v2 格式）；
3. **类型字节置于 zstd 流外**：避免破坏块内压缩（否则触发跨块边界、压缩率暴跌）——此类细节表明对压缩原理有真实理解；
4. **性能路径打磨**：原始类型排序（int[] 快排替代装箱）、区间解码、LRU 列解压缓存、段句柄池化、跨段 ForkJoin 并行、全量快照流式 JSON 输出；
5. **工程纪律**：缺陷记录含根因/修复/验证三要素；属性测试（编码 roundtrip）、契约测试、安全测试分层合理。

> **问题清单**（G-01~G-11，含各问题严重度与解决现状）已移入 [review-p0.md](../review/review-p0.md)（总体现状评价）第 1 章；本文第 2 章各优化项"动机"中的 G-xx 编号与之对应。

---

## 2. 优化方案

> 优先级定义：**P0** = 正确性与可靠性缺口（先补）；**P1** = 核心竞争力的内存/性能（中期）；**P2** = 功能与查询能力延伸（产品力）；**P3** = 运维与工程卫生（长期）。每项含验收标准（可量化）。

### 2.1 P0 · 可靠性与正确性

#### O-01 批量导入原子化（staging 两阶段提交）`已实施`

- **动机**：消除 G-01 的"部分提交"窗口。当前批量导入逐段落盘，中途崩溃后磁盘上是一组不一致的段集合。
- **方案要点**：批量快照先编码到表目录下 staging 临时段（`segments/.staging/*.tmp`），全部完成后统一 rename 到 `segments/` 正式路径 + 一次 manifest 更新。同文件系统 rename 为 O(1) 元数据操作；与现有"临时文件 + rename"哲学一致。启动校验已清理 `*.tmp` 残留（`AstraDB.validateManifest`），可复用。
- **预期收益**：跨段批量导入从"部分提交"变为"全有或全无"。
- **涉及模块**：core · ingest/SnapshotIngestor、core · segment/SegmentWriter。
- **验收标准**：注入崩溃点（kill -9）于批量导入中段，重启后要么全量可见、要么全量不可见（manifest 与磁盘一致）；既有批量导入测试（`ingestBatch` 90ms vs 224ms）不回归。
- **实施情况**（2026-08-20 代码核查）：`SnapshotIngestor.writeSegmentsBatch` 已实现新段 staging——先写 `segments/.staging/*.tmp`，全部完成后 `Files.move(ATOMIC_MOVE)` 统一 rename 到正式路径 + manifest 一次保存；`P0ReliabilityTest.batchAtomicNewSegmentsAndStagingCleanup` 覆盖跨天两新段与 staging 残留启动清理，P0 测试 10 项全绿。
- **遗留局限**：
  1. 仅"新段"走 staging；已有段 append 仍为直接追加（依赖崩溃截断兜底，单文件语义本就"全有或全无"），中间回填仍为整段重写（`SegmentRewriter` 的 tmp+rename）；
  2. rename 循环逐个执行，循环中途崩溃（微秒级窗口 × 段数）仍可能出现部分提交——语义收敛为**"近似原子"**（staging 全部 fsync 后 rename，窗口极小且实践可接受；多文件无原子 rename，不做严格两阶段）；
  3. staging 残留依赖启动校验 `*.tmp` 清理兜底（正常路径不显式清理，可接受）。

#### O-02 幂等导入语义 `已实施`

- **动机**：消除 G-02。文档声称"崩溃后可安全重导"，但重复时间戳 400 拒绝，调用方实际无法安全重放。
- **方案要点**：在"重复时间戳 400 拒绝"之外提供可选幂等模式（请求携带 `idempotencyKey` 或 `table+ts+内容哈希`）：相同则跳过并返回原结果；不同则 400。仅内存比对（manifest 已有段内时间戳列表），成本极低。
- **预期收益**：崩溃恢复后重放导入变为安全操作。
- **涉及模块**：core · ingest、server · api/DataController（importSnapshot / importSnapshots / importAsync）。
- **验收标准**：同 key 同内容重放返回原 rowCount 且不产生新数据；同 key 异内容返回 400。
- **实施情况**（2026-08-20 评审修订）：`AstraDB.ingest` / `ingestBatch` 已实现幂等——`SnapshotData.contentHash64()`（**64 位 FNV-1a**，覆盖列类型/数组内容/null 位图）判定同内容则跳过并返回原结果，异内容走正常路径触发"时间戳已存在"拒绝；**幂等记录持久化到表目录 `idempotency.idx`（24B/条，追加 + fsync，200k 超限截断重写，启动加载）**，崩溃重启后重放同内容仍可幂等跳过（G-02 闭环）。`P0ReliabilityTest` 覆盖单条/批量/跨重启/损坏降级/哈希区分。
- **遗留局限**：**已全部解决**（2026-08-20 评审修订）——1) 幂等记录持久化至 `idempotency.idx`（崩溃重启后重放安全）；2) 哈希升级 64 位 FNV-1a（碰撞误判跳过风险显著降低，`hash64DistinguishesDifferentContents` 属性验证）。剩余理论风险：64 位哈希仍非零碰撞概率（工程可接受）、幂等文件损坏时降级为进程内幂等（重放需显式处理）。

#### O-03 dataDir 文件锁 `已实施`

- **动机**：消除 G-03。多进程共享数据目录是静默数据损坏来源，当前无任何防护。
- **方案要点**：启动时对 `dataDir` 内锁文件（如 `data/.lock`）加 `FileChannel.lock()` 排他锁，持有至进程退出；锁失败则启动报错退出（明确提示已有实例运行）。
- **预期收益**：杜绝双写。
- **涉及模块**：core · AstraDB.open。
- **验收标准**：两进程同时 open 同一目录，第二个启动失败并给出明确错误；正常单进程启动/关闭无影响。
- **实施情况**（2026-08-20 代码核查）：`AstraDB.open` 已对 `dataDir/.lock` 加 `FileChannel.tryLock()` 排他锁（含 `OverlappingFileLockException` 处理），失败抛"数据目录已被其他进程锁定"；`close()` 释放锁与句柄。`P0ReliabilityTest.dataDirLockRejectsSecondInstance` 覆盖（同 JVM 第二实例拒绝、close 后重开数据保留），P0 测试 10 项全绿。**无遗留问题**。

### 2.2 P1 · 内存与性能

#### O-04 点字典去字符串化（primitive 键路径）`候选`

- **动机**：消除 G-06。百万点场景下主键 String 驻留是内存大头，且 `primaryKeyString` 每行转换有 CPU 开销。
- **方案要点**：INT/LONG 主键改用原始类型键路径（`long → pointId` 的 primitive 开放寻址哈希），仅 STRING 主键走字符串表。落盘格式 points.dict 保持不变或升格式版本（v1 兼容读、v2 写）。
- **预期收益**：百万点内存降一个数量级（String 对象 + ArrayList 开销消失）；导入省去逐行字符串转换。
- **涉及模块**：core · points/PointDictionary、core · ingest/SnapshotIngestor.primaryKeyString。
- **验收标准**：百万点表内存占用显著下降（对照 G-05 目标）；既有编码/导入/查询测试全绿。

#### O-05 Gorilla 分块 checkpoint `候选`

- **动机**：消除 G-08。`valueAt` 从块头重放是 Gorilla 固有限制，单点历史查询每快照每列 O(行)；288 快照 × 10 万点实测 1.8s 即受此限制。
- **方案要点**：列内分块（每 1024 行存 64bit 前值 + leading/trailing checkpoint），`valueAt`/`decodeRange` 从 checkpoint 起跳而非块头。需要格式版本升级（FORMAT_VERSION 2 → 3，旧段不兼容读取需重导，与 v1→v2 先例一致）。
- **预期收益**：单点历史查询由 O(行) 降为 O(行/1024 + 1024)；20 万行快照场景查询时延量级下降。
- **涉及模块**：core · codec/GorillaCodec、core · segment/SegmentFormat。
- **验收标准**：valueAt/decodeRange 与整列解码结果一致（属性测试）；单点历史查询在 20 万行快照上提速 ≥ 3 倍；全量回归通过。
- **前提提示**：趁数据量可控时做格式演进（当前仅 v2）比 5 年后便宜得多，建议与 O-04 的格式升级合并一次性做。

#### O-06 导入/查询内存峰值回落 `候选`

- **动机**：消除 G-05/G-07。百万行压测 569MB 超出 100MB 约束，核心是原始列缓冲驻留 + 查询行对象装箱。
- **方案要点**：① 编码完成后显式释放原始 `Column[]` 缓冲（`prepare` 产出 chunkBytes 后置空引用）；② `fullSnapshot` 在 core 层改行回调/迭代式输出，避免 `List<Row>` 全量装箱驻留；③ 查询行值改用原始类型数组 + 类型分派，避免逐值 `Object` 装箱。
- **预期收益**：百万行导入/查询内存峰值显著回落，向约束（2~4 快照 ≤ 100MB）收敛。
- **涉及模块**：core · ingest、core · query/SnapshotQuery。
- **验收标准**：百万行压测内存峰值下降 ≥ 40%；功能与性能回归通过。

#### O-07 写入 fsync 策略可配（延迟 fsync / group commit）`候选`

- **动机**：把"每次 close 即 fsync"的固定策略变为可配取舍。批量导入已证明 fsync 次数是写入吞吐主变量（3×10 万行 90ms vs 224ms）。
- **方案要点**：配置项 `astradb.write.fsync-mode`（`immediate`（默认，现状）/ `batch-window`（按窗口合并 fsync）/ `none`（仅测试/临时目录）），同步更新崩溃语义文档。
- **预期收益**：吞吐与崩溃窗口由用户显式取舍，适配"归档重导可容忍少量丢失"的场景。
- **涉及模块**：core · segment/SegmentWriter、server · 配置。
- **验收标准**：三种模式行为符合文档；默认 `immediate` 全量回归通过。

### 2.3 P2 · 功能与查询能力

#### O-08 统一"精确 / 最近一次 ≤ ts"查询语义 `候选`

- **动机**：消除 G-09 的语义矛盾，并贴合场景真实用法（"某时刻点的状态"通常取最近一次快照）。`findChunkAtOrBefore` 已具备能力，仅被 `==` 精确判定挡住。
- **方案要点**：`getSnapshot`/`getFullSnapshot` 增加模式参数（`EXACT`（默认，向后兼容）/ `LATEST`）；README/design 同步修订语义描述。
- **预期收益**：文档与实现一致；新增查询能力成本极低。
- **涉及模块**：core · query/SnapshotQuery、server · api、README/design。
- **验收标准**：LATEST 模式在无精确快照时间点返回最近一次（≤ ts）；EXACT 行为不变；既有测试全绿。

#### O-09 快照 diff 查询 `候选`

- **动机**：场景"某时刻 vs 另一时刻状态变化"（设备变化盘点、合规追溯）是列式存储天然优势区间，当前无查询入口。
- **方案要点**：两个 chunk 按 pointId 归并比较（主键列均已排序，归并 O(n+m)），输出新增/删除/变更（列级差异）三组。
- **预期收益**：差异化盘点能力，产品力提升明显，代码量小。
- **涉及模块**：core · query（新增 DiffQuery）、server · api、管理页面。
- **验收标准**：与手工逐行比较结果一致（含新增点/消失点/值变化/null 变化）；20 万行双快照 diff 时延在秒级内。

#### O-10 段导出工具（CSV / Parquet）`候选`

- **动机**：消除 G-11。打破专有格式锁定，同时为基于不可变段的增量备份（段级复制 + manifest 快照）铺路。
- **方案要点**：core 提供按段/按时间范围的解码导出接口；server 提供 `POST /api/exportSnapshot`（CSV 流式响应，复用现有 `getFullSnapshot` 流式路径）。
- **预期收益**：数据可迁移、可审计；备份策略获得"段级"粒度。
- **涉及模块**：core · query、server · api/DataController。
- **验收标准**：导出 CSV 与 `getFullSnapshot` 数据一致；10 万行导出时延 < 2s；导出文件可重新导入（roundtrip 测试）。

#### O-11 粗粒度流式聚合（count/sum/avg）`候选`

- **动机**：低成本的统计能力补充；在解码过程中累加，不构造行对象。
- **方案要点**：`getSnapshot` 增加聚合参数（列级 count/sum/avg/min/max，null 跳过），解码循环内累加原始类型累加器。
- **预期收益**：报表类查询免全量传输。
- **涉及模块**：core · query/SnapshotQuery、server · api。
- **验收标准**：聚合结果与逐行计算一致；20 万行聚合查询内存占用近零。

### 2.4 P3 · 运维与工程

#### O-12 分片粒度可配（小时/天/周）`候选`

- **动机**：段重写成本 O(段大小) 与分片粒度直接挂钩；小时分片把中间回填/删快照代价降一个量级。
- **方案要点**：`astradb.segment-granularity`（默认 DAY 不变）；`SegmentPaths` 按粒度生成路径；时间戳→段定位逻辑参数化。
- **预期收益**：回填/删除成本与粒度成反比；细粒度对"高频快照"更友好。
- **涉及模块**：core · segment/SegmentPaths、core · AstraDB、server · 配置。
- **验收标准**：三种粒度下导入/回填/查询/保留期全链路正确；既有测试全绿。

#### O-13 metrics 端点 `候选`

- **动机**：当前仅有 WARN 慢查询日志，缺可观测性闭环。
- **方案要点**：`GET /api/metrics`（Prometheus 文本格式）：表级行数/段数/字节数、查询缓存命中率、慢查询计数、导入吞吐/时延直方图、句柄池活跃数。
- **预期收益**：容量规划与问题定位有数据支撑。
- **涉及模块**：server · api（新增 MetricsController）、core · AstraDB（暴露计数）。
- **验收标准**：指标可被 Prometheus 抓取；关键计数与 stats API 一致。

#### O-14 安全加固 `候选`

- **动机**：消除 G-10。默认密码明文、无传输加密说明。
- **方案要点**：密码改 BCrypt 存储（去掉 `{noop}`）；README 补 TLS 部署说明（前置反代终止 TLS）；可选请求限流（每表导入速率）。
- **预期收益**：生产默认配置不再暴露弱凭据。
- **涉及模块**：server · config/SecurityConfig、README。
- **验收标准**：security.enabled=true 时登录/鉴权测试通过（正确 200 / 错误 401）；密码以加密形式配置。

#### O-15 CI 与文档卫生 `候选`

- **动机**：消除 G-09 残留；`@Tag("perf")` 基准测试与常规单测混跑，文档测试数量滞后。
- **方案要点**：GitHub Actions（build + test + 产物上传）；surefire 按 tag 分离 perf 测试（默认跳过，profile 启用）；README/phase-report 测试数量与实测同步；design.md 16 节登记本文档已实施项。
- **预期收益**：回归自动化、基准可复现、文档可信。
- **涉及模块**：仓库根（.github/workflows）、pom.xml、文档。
- **验收标准**：CI 全绿；`mvn test` 与 `mvn test -Pperf` 分离可执行。

#### O-16 schema 演进预留（追加列）`候选`

- **动机**：列偏移表按列索引存储，天然支持"追加列"向后兼容（旧段缺列补 null）；schema-registry 版本机制已铺路，趁格式年轻时落地最便宜。
- **方案要点**：schema v2 允许在表尾部追加可空列；读取时旧段（columnCount 较小）缺列视为全 null；禁止删除/改序/改类型（保持冻结核心语义）。
- **预期收益**：字段演进能力，避免"建表即定死"在长期使用中的痛点。
- **涉及模块**：core · meta/SchemaRegistry、core · segment/ChunkCodec（缺列兼容）、server · api/createTable。
- **验收标准**：旧段 + 新 schema 混合读取正确；追加列后旧数据查询不回归。

---

## 3. 路线图

| 阶段 | 范围 | 目标 | 对应优化项 |
|---|---|---|---|
| 短期（正确性优先） | P0 | 补"单机单副本 + 无 WAL"的可靠性缺口 | O-01、O-02、O-03 |
| 中期（核心竞争力） | P1 + P2 高价值项 | 百万级 × 多年规模的**内存与查询表现**（点字典去字符串化、Gorilla 分块）与**产品力**（查询语义统一、快照 diff） | O-04、O-05、O-06、O-08、O-09 |
| 长期（生态与运维） | P2 剩余 + P3 | 数据可迁移（导出/备份）、分片粒度、可观测性、CI 与文档卫生 | O-10、O-11、O-12、O-13、O-14、O-15、O-16 |

**建议执行顺序**：

1. **先做 O-04 + O-05 + O-16 的格式合并升级**：三者都涉及格式版本变更，合并为一次 FORMAT_VERSION 升级（3），避免多次"旧数据需重导"；
2. P0（O-01/O-02/O-03）**已实施并评审修订**（2026-08-20）：O-02 跨重启幂等闭环（64 位哈希 + idempotency.idx 持久化）；O-01 新段 staging 原子化（append 崩溃语义与 rename 微秒窗口已文档化接受）；O-07（fsync 策略）与 O-01 存在交互，宜一并设计；
3. O-09（快照 diff）与 O-11（聚合）共用解码路径，可合并实施；
4. 文档同步（O-15）应随每次实施即时进行，避免再次累积漂移。

## 4. 战略取舍与风险

1. **护城河押注明确**：项目牺牲通用检索换取"压缩率 + 快照回溯效率"。资源投入应持续押在压缩率（O-05 分块、字典、delta 加强）与单点历史查询（O-05）上——这是别人难以复制的深度；不应向通用数据库能力（标签索引、事务、流式写入）分散，后者会稀释定位且无法与成熟产品竞争。
2. **性能基准数字要严谨**：569MB vs 100MB 约束、2274x/10170x 压缩率标注"理想值"，此类自报数据在评审时最易被挑战；O-06 落地后应重测并同步约束表述。
3. **格式升级窗口**：当前存储格式仅 v2、schema-registry 仅 v1，是格式演进的最后低成本窗口；建议在 O-04/O-05/O-16 合并升级后，冻结格式并补充"版本迁移工具"（旧段重导脚本），此后不再轻易升格式。
4. **Docker 未实测**（环境无 docker）：O-13/O-14 涉及部署形态时，需在有 docker 环境完成构建/运行验证后再宣称支持。

## 5. 优化项总览

| ID | 标题 | 优先级 | 状态 | 验收可量化 |
|---|---|---|---|---|
| O-01 | 批量导入原子化（staging 两阶段提交） | P0 | **已实施**（新段路径；遗留局限见 2.1） | 崩溃注入后全有或全无；批量导入性能不回归 |
| O-02 | 幂等导入语义 | P0 | **已实施**（64 位哈希 + 持久化，跨重启闭环） | 同 key 重放安全 |
| O-03 | dataDir 文件锁 | P0 | **已实施**（无遗留） | 双进程第二实例拒绝启动 |
| O-04 | 点字典去字符串化 | P1 | 候选 | 百万点内存降一个数量级 |
| O-05 | Gorilla 分块 checkpoint | P1 | 候选 | 单点历史查询提速 ≥ 3 倍 |
| O-06 | 导入/查询内存峰值回落 | P1 | 候选 | 百万行内存峰值降 ≥ 40% |
| O-07 | 写入 fsync 策略可配 | P1 | 候选 | 三种模式行为符合文档 |
| O-08 | 统一精确/最近一次查询语义 | P2 | 候选 | LATEST 模式可用、EXACT 不回归 |
| O-09 | 快照 diff 查询 | P2 | 候选 | 与逐行比较一致；秒级时延 |
| O-10 | 段导出工具（CSV/Parquet） | P2 | 候选 | roundtrip 一致；10 万行 < 2s |
| O-11 | 粗粒度流式聚合 | P2 | 候选 | 与逐行计算一致；近零额外内存 |
| O-12 | 分片粒度可配 | P3 | 候选 | 三种粒度全链路正确 |
| O-13 | metrics 端点 | P3 | 候选 | Prometheus 可抓取、与 stats 一致 |
| O-14 | 安全加固 | P3 | 候选 | 加密密码 + 鉴权测试通过 |
| O-15 | CI 与文档卫生 | P3 | 候选 | CI 全绿、perf 测试分离 |
| O-16 | schema 演进预留（追加列） | P3 | 候选 | 旧段混合读取正确 |
