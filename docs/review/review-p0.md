# AstraDB 总体现状评价与 P0 可靠性优化评审报告

> 版本：v1.2（复审）· 日期：2026-08-20 · 状态：**通过（放行）**
> 定位：第 1 章为**总体现状评价**（问题清单 G-01~G-11，迁移自 optimization.md 原 1.3 节）；第 2 章起为 **P0 可靠性优化专项评审**（O-01 / O-02 / O-03 的当前工作区实现）。
> 评审历程：v1.0（2026-08-20 初评，verdict block）→ v1.1（并入总体评价，仍 block）→ **v1.2（复审通过，本版）**
> 关联文档：[optimization.md](../design/optimization.md)（优化提案）、[review.md](./review.md)（全项目评审）、[design.md](../design/design.md)（设计）、[test/defects.md](../test/defects.md)（缺陷跟踪）
> 评审方法：独立 review 子代理 + 人工代码逐行核查 + 测试实跑（`P0ReliabilityTest` 10 项全绿 + 全量 70 项回归）

---

## 1. 总体现状评价（问题清单）

> 本节为总体现状评价的问题清单，迁移自 optimization.md 原 1.3 节（2026-08-20）；严重度与解决现状随优化实施更新。优化项编号 O-xx 见 [optimization.md](../design/optimization.md) 第 2 章。

| ID | 问题 | 严重度 | 现状 |
|---|---|---|---|
| G-01 | **跨段批量导入无原子性**：`ingestBatch` 逐段 fsync 提交，中途崩溃出现"部分段已提交" | 高 | **已解决（部分）**：O-01 新段 staging 原子化；既有段 append/重写路径与 rename 循环中途崩溃仍属遗留（见 optimization.md 6.1 O-01；S-4 已文档化接受） |
| G-02 | **无幂等重导语义**：重复时间戳直接 400 拒绝，崩溃后调用方无法安全重放导入 | 高 | **已解决**：O-02 64 位哈希 + 幂等记录持久化（跨重启重放安全） |
| G-03 | **无 dataDir 文件锁**：双进程打开同一数据目录会互相破坏（tables.json rename 竞争、段追加竞争） | 高 | **已解决（O-03 排他锁）** |
| G-04 | **中间回填/删快照 = 整段重写**：O(段大小) IO + 重编码，且持表写锁阻塞该表全部读写；D-09 双重计数 bug 即发生在此路径 | 中高 | 候选 |
| G-05 | **内存模型与约束不符**：文档约束"缓冲 2~4 快照 ≤ 100MB"，百万行压测自报 569MB（phase-report 3 节） | 中 | 候选 |
| G-06 | **点字典全字符串驻留**：INT/LONG 主键经 `primaryKeyString` 转 String，`idToKey` ArrayList + pending 列表是百万级 key 内存大头 | 中 | 候选 |
| G-07 | **查询路径装箱**：`Row(key, List<Object> values)` 每行每值装箱；`fullSnapshot` core 层全量驻留再组装（"流式"仅到 JSON 层） | 中 | 候选 |
| G-08 | **Gorilla 随机访问退化**：`valueAt` 必须从块头重放（O(行)），单点历史查询每快照每列重放 | 中 | 候选 |
| G-09 | **文档与实现漂移**：README curl 注释写 `getSnapshot`"最近一次 ≤ ts"，实现为精确匹配（`timestampAt(idx) != ts` 返回空）；测试数量 README 声称 40/99 项，实际 70 项 | 低 | **部分解决**：测试数量已于 2026-08-21 同步为 70 项；README curl 注释"最近一次 ≤ ts"与精确匹配实现仍不一致（对应 O-08 候选） |
| G-10 | **安全默认值弱**：密码 `admin123` 明文（`{noop}`）；无 TLS/限流说明 | 低 | **部分解决**：口令已改 BCrypt 编码存储（R-02/SS-3，默认口令保留但启动 WARN 告警）；TLS/限流说明仍候选 |
| G-11 | **专有格式无逃生通道**：无段导出工具（CSV/Parquet），5 年数据仅存于专有格式 | 低 | 候选 |

---

## 2. 复审结论（v1.2）

**verdict: 通过（放行）** —— v1.1 的 2 个 blocking + 4 个 should-fix + 2 个观察项**全部处理完毕**，P0 可靠性优化达到放行条件。

- **B-1（锁序反转死锁）已修复**：`ingest` / `ingestBatch` 统一为先表写锁、后幂等锁，无任何"幂等锁 → 表锁"嵌套路径；新增回归 `concurrentIngestAndIngestBatchNoDeadlock`；
- **B-2（STRING 32 位哈希域）已修复**：`contentHash64` STRING 分支逐 char 直接喂 FNV-1a（64 位区分度），弃用 `String.hashCode()`；新增冲突对回归 `stringHashDistinguishesHashCodeCollisions`；
- **S-1/S-2/S-3/O-2 已修复**，**S-4 已文档化接受**（"近似原子"，见下）；
- **测试证据更新**：`P0ReliabilityTest` 由评审当日 7 项扩至 **10 项**，实跑全绿；全量回归 **70 项全绿**（core 41 + client 16 + server 13，2026-08-21 `mvn test` BUILD SUCCESS）；
- **缺陷闭环**：D-10 / D-11 已按本报告登记至 defects.md 并验证关闭。

| v1.1 问题 | 修复内容 | 回归测试 | 状态 |
|---|---|---|---|
| B-1 锁序反转（ingest 与 ingestBatch 同表并发死锁） | 统一两条路径锁序为先表写锁、后幂等锁 | `concurrentIngestAndIngestBatchNoDeadlock` | ✅ 已修复 |
| B-2 STRING 列哈希碰撞域 32 位 | STRING 逐 char 喂 FNV-1a，弃用 `String.hashCode()` | `stringHashDistinguishesHashCodeCollisions` | ✅ 已修复 |
| S-1 FileLock 异常路径泄漏句柄与锁 | `open()` 失败路径 finally 释放 `FileLock` + `FileChannel` | `openFailureReleasesLockAndRetrySucceeds` | ✅ 已修复 |
| S-2 幂等文件 fsync 在表写锁内执行 | 新增 `appendIdemBatch`，批内一次 open+write+`force(true)` | P0 批量幂等用例覆盖 | ✅ 已修复 |
| S-3 "段提交后、幂等记录前"崩溃窗口 | 预写占位幂等记录（先落盘幂等、后提交段；残留占位无害） | — | ✅ 已修复 |
| S-4 O-01 rename 循环非严格原子 | 文档措辞收敛为"近似原子"（staging 全 fsync 后 rename，微秒级窗口，实践可接受；多文件无原子 rename，不做严格两阶段） | — | ✅ 已文档化接受 |
| O-1 测试代码未纳入版本控制 | `.gitignore` 移除 `**/test/`，测试目录已入未跟踪列表 | `git status` 可见 | ✅ 已处理 |
| O-2 幂等文件 IO 异常静默降级 | 降级路径补 WARN 日志 | — | ✅ 已修复 |

---

## 3. P0 评审范围

| 项 | 内容 | 涉及代码 |
|---|---|---|
| O-01 | 批量导入原子化（staging 两阶段提交） | `core/.../ingest/SnapshotIngestor.java`（`writeSegmentsBatch`） |
| O-02 | 幂等导入语义（64 位内容哈希 + 文件持久化） | `core/.../AstraDB.java`（`ingest`/`ingestBatch`/`appendIdem`/`loadIdem`/`rewriteIdem`）、`core/.../ingest/SnapshotData.java`（`contentHash64`） |
| O-03 | dataDir 文件锁 | `core/.../AstraDB.java`（`open`/`close`） |
| 测试 | P0 专项测试 | `core/src/test/java/com/astradb/core/P0ReliabilityTest.java` |

评审基准为评审当日工作区未提交改动（`AstraDB.java` / `SnapshotData.java` / `SnapshotIngestor.java` / `.gitignore`）。

## 4. 原评审结论（v1.0/v1.1，历史）

**verdict: block（历史）** —— 2 个 blocking + 4 个 should-fix + 2 个观察项。本结论保留为评审历史，处理结果见第 2 章。

- O-03、O-01（新段路径）、O-02（64 位哈希 + 跨重启持久化）主体实现**正确且质量良好**，且 O-02 已按上一轮评审意见完成升级（32 位 int 哈希 → 64 位 FNV-1a；内存态 → `idempotency.idx` 文件持久化）；
- 但 **B-1 锁序反转可致死锁**、**B-2 STRING 列哈希碰撞可致静默丢数据**两项须修复后再复审；
- 观察项 O-1（测试未纳入版本控制）已由 `.gitignore` 移除 `**/test/` 处理，测试目录待 `git add`。

## 5. 已确认正确项

| 项 | 结论 | 证据 |
|---|---|---|
| O-03 文件锁正常路径 | ✅ | `open()` 对 `dataDir/.lock` 加 `FileChannel.tryLock()` 排他锁，`OverlappingFileLockException` 处理为 null；失败抛"数据目录已被其他进程锁定"；`close()` 释放锁与句柄；测试 `dataDirLockRejectsSecondInstance`（同 JVM 第二实例拒绝、close 后重开数据保留） |
| O-01 新段 staging | ✅ | 新段先写 `segments/.staging/*.tmp`，全部完成后 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 统一 rename + manifest 一次保存；启动校验 `*.tmp` 清理覆盖 staging 残留；测试 `batchAtomicNewSegmentsAndStagingCleanup`（跨天两新段 + 残留清理） |
| O-02 64 位哈希 | ✅ | `contentHash64()` 采用 FNV-1a（64 位），INT/LONG/DOUBLE 均以原始 64 位值参与，null 位图纳入；STRING 逐 char 喂 FNV-1a（v1.2 确认）；测试 `hash64DistinguishesDifferentContents` / `stringHashDistinguishesHashCodeCollisions` |
| O-02 跨重启持久化 | ✅ | `idempotency.idx`（24B/条：ts+hash64+rowCount+newPoints），fsync 追加，超限（20 万条）裁剪保留尾部，启动 `loadIdem` 恢复，文件损坏降级为空表；测试 `idempotencySurvivesRestart` / `corruptedIdemFileDegradesGracefully` |
| 测试执行 | ✅ | `P0ReliabilityTest` 10 项实跑全绿（`mvn test -pl core -Dtest=P0ReliabilityTest`，Failures=0 Errors=0） |

## 6. 问题清单（原 v1.1 评审，历史）

> 各问题处理状态见第 2 章；本节保留原始描述。

### 6.1 Blocking（v1.1：必须修复后再复审）—— **均已修复**

#### B-1 锁序反转：ingest 与 ingestBatch 同表并发可死锁（→ D-10，已修复）

| 项 | 内容 |
|---|---|
| 位置 | `AstraDB.java`：`ingest` 于 `synchronized (idempotency)`（L493）**内**获取表写锁（L500）；`ingestBatch` 于持有表写锁（L536）后进入 `synchronized (idempotency)`（L540） |
| 根因 | 两条写入路径加锁顺序相反：`ingest` 为"幂等锁 → 表锁"，`ingestBatch` 为"表锁 → 幂等锁"。`idempotency` 为**全局共享** `LinkedHashMap`（非每表），同表并发 `ingest` + `ingestBatch` 时形成环形等待：线程 A 持幂等锁等表锁，线程 B 持表锁等幂等锁 → 死锁 |
| 严重度 | 高（确定性可构造，非概率性；死锁后该表与幂等锁永久卡死，无超时兜底） |
| 修复 | 统一两条路径锁序（先表写锁、后幂等锁）；新增并发测试 `concurrentIngestAndIngestBatchNoDeadlock`（v1.2 确认） |

#### B-2 STRING 列哈希碰撞域仍为 32 位，可致静默丢数据（→ D-11，已修复）

| 项 | 内容 |
|---|---|
| 位置 | `SnapshotData.java` `contentHash64()` STRING 分支（L58）：`h = fnv(h, v == null ? 0 : v.hashCode())` |
| 根因 | 64 位 FNV 的输入值之一为 `String.hashCode()`（32 位）——STRING 内容区分度被压缩到 32 位，碰撞可由 `String.hashCode` 的已知冲突（如 `"Aa"`/`"BB"`）有意或无意构造 |
| 影响 | 幂等判定为"同内容重放"的前提是"同 ts + 其余列全同 + STRING 哈希序列全同"；一旦碰撞命中，**不同内容被误判为幂等重放而跳过导入（静默数据丢失方向）**——可靠性机制不应依赖 32 位摘要 |
| 修复 | STRING 逐 char 直接喂 FNV-1a，弃用 `String.hashCode()`；新增冲突对属性测试 `stringHashDistinguishesHashCodeCollisions`（v1.2 确认） |

### 6.2 Should-fix（v1.1：建议随修复迭代处理）—— **均已处理**

#### S-1 FileLock 异常路径泄漏句柄与锁（已修复）

| 项 | 内容 |
|---|---|
| 位置 | `AstraDB.java` `open()`（L220-244）：`FileChannel`/`FileLock` 成功后，`TablesStore.load` / `loadTable` / `loadIdem` 任一抛 IOException 即跳出，锁与句柄不释放；`catch (IOException e) { throw e; }` 为冗余空 catch |
| 影响 | 启动加载失败（如表损坏）后：句柄泄漏；**同 JVM 内重试 `open` 必失败**（旧锁仍持有 → 误报"数据目录已被其他进程锁定"），需重启 JVM 才能恢复 |
| 修复 | 失败路径 finally 释放 `FileLock` + `FileChannel`；回归 `openFailureReleasesLockAndRetrySucceeds` |

#### S-2 幂等文件 fsync 在表写锁内执行，稀释批量导入收益（已修复）

| 项 | 内容 |
|---|---|
| 位置 | `AstraDB.java`：`ingest` L506、`ingestBatch` L546 在表写锁内调用 `appendIdem`（open+write+`force(true)`） |
| 影响 | 单快照导入每次多一次 fsync；批量导入每个快照各一次幂等 fsync（N 次），与"批量导入减少 fsync 次数"的目标（3 次 vs 9 次）部分抵消，且写路径被 fsync 串行化 |
| 修复 | 新增 `appendIdemBatch`：批内一次 open + write + `force(true)`（v1.2 确认） |

#### S-3 "段提交后、幂等记录前"崩溃窗口仍存在（已修复）

| 项 | 内容 |
|---|---|
| 位置 | `AstraDB.java` `ingest`：`SnapshotIngestor.ingest`（提交段+manifest，L502）→ `appendIdem`（L506） |
| 影响 | 崩溃落在两者之间时：重启后快照已存在、幂等无记录 → 重放仍 400 拒绝（跨重启幂等在此窗口失效） |
| 修复 | 预写占位幂等记录（先落盘幂等、后提交段；残留幂等记录无害）（v1.2 确认） |

#### S-4 O-01 rename 循环非严格原子（已文档化接受）

| 项 | 内容 |
|---|---|
| 位置 | `SnapshotIngestor.java` `writeSegmentsBatch`：新段逐个 `Files.move`（L310-321） |
| 影响 | 多新段 rename 循环中途崩溃仍可能部分提交（窗口为微秒级 × 段数），与"全有或全无"的严格表述存在理论差距（实践可接受） |
| 处置 | 文档措辞收敛为"近似原子（rename 循环窗口极小）"；不做严格两阶段（多文件无原子 rename）（v1.2 确认） |

### 6.3 观察项

#### O-1 测试代码未纳入版本控制 —— **已处理**

- 原状：`.gitignore` 含 `**/test/`，全部测试代码（含 `P0ReliabilityTest.java`）不在 git 中，clone 后无法运行测试，CI（O-15）前置条件缺失；
- 现状：`.gitignore` 已移除该规则，测试目录已入未跟踪列表，**待 `git add` 入库**。

#### O-2 幂等文件 IO 异常静默降级 —— **已修复**

- `appendIdem` / `rewriteIdem` / `loadIdem` 的 IOException 降级路径已补 WARN 日志（v1.2 确认）；降级语义（进程内幂等/空表）保留。

## 7. 测试证据（v1.2 复审）

| 命令 | 结果 |
|---|---|
| `mvn test`（2026-08-21 复审实跑） | **BUILD SUCCESS，17 类 / 70 项全绿**（core 41 + client 16 + server 13） |
| `mvn test -pl core -Dtest=P0ReliabilityTest` | `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` |
| `P0ReliabilityTest` 用例清单（10 项） | `dataDirLockRejectsSecondInstance`、`idempotentReplaySkipsSameContent`、`idempotentBatchReplaySkipsWholeBatch`、`idempotencySurvivesRestart`、`corruptedIdemFileDegradesGracefully`、`hash64DistinguishesDifferentContents`、`concurrentIngestAndIngestBatchNoDeadlock`（B-1 回归）、`stringHashDistinguishesHashCodeCollisions`（B-2 回归）、`openFailureReleasesLockAndRetrySucceeds`（S-1 回归）、`batchAtomicNewSegmentsAndStagingCleanup` |
| 覆盖缺口（v1.2） | ① 删除数据后幂等记录清理（review.md SF-1）无测试；② 损坏段拒启动的隔离/自愈（review.md SF-3）无测试；③ 幂等锁全局串行化（review.md SF-4）无针对性测试 |

## 8. 结论与后续建议（v1.2）

1. **P0 可靠性优化放行**：O-01/O-02/O-03 达到设计目标，D-10/D-11 关闭，全量回归通过；
2. **新增问题移交全项目评审**：本报告之外发现的幂等残留（SF-1）、损坏段拒启动（SF-3）、幂等锁全局串行（SF-4）等已列入 [review.md](./review.md)，并于 **2026-08-21 经 R-01 交付全部修复**（`ReviewShouldFixTest` 7 项 + 黑盒 LD-03~LD-06 验证）；剩余 server/client 问题（SS-1~SS-10）留待后续迭代；
3. **文档同步**：README（40 项）、optimization.md（48 项）中的测试数量声明已过时，实测为 70 项（17 类），需随本报告更新；
4. **遗留优化项**：G-04~G-11 对应 O-04~O-16 按 [optimization.md](../design/optimization.md) 路线图推进。
