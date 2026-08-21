# R-01 修复交付：review 4.1 should-fix（SF-1 ~ SF-8）

> 交付日期：2026-08-21 · 关联文档：[review.md](../review/review.md)（4.1 should-fix 清单）、[review-p0.md](../review/review-p0.md)（S-4 已文档化接受）
> 说明：按项目规则，本交付记录 review 4.1 core should-fix 全部 8 项（SF-1~SF-8）的修复与验证；不修改 `docs/design` 与 `docs/test`。

---

## 1. 修复总览

| ID | 问题 | 修复方案 | 状态 |
|---|---|---|---|
| SF-1 | 幂等记录不随数据删除清理（静默丢数据方向） | 删除路径同步清理幂等：新增 `removeIdem`（内存移除 + `idempotency.idx` 原子重写：临时文件 + fsync + ATOMIC_MOVE + 目录 fsync）；`deleteSnapshot` 删 ts、`deleteSegment` 与 `RetentionCleaner` 删段前枚举段内 ts 一并清理 | ✅ |
| SF-2 | 混合批导入（部分快照已提交）必抛"时间戳已存在" | `ingestBatch` 慢路径改为混合批：正式命中重放、占位记录先确认（已提交→精确返回，未提交→作废重导）、仅未命中快照进入 `SnapshotIngestor.ingestBatch`，结果按原顺序合并 | ✅ |
| SF-3 | 损坏段使整个库无法 open，无隔离/自愈 | 启动 `validateManifest` 对损坏段（light/precise 描述抛异常）隔离至 `segments/.quarantine/*.corrupt` + WARN + 目录 fsync，从 manifest 剔除；隔离失败→删除并告警（已确认决策），均失败→抛错维持原语义 | ✅ |
| SF-4 | 全局幂等锁把跨表导入串行化 | 幂等 map 与锁按表拆分（`TableState` 持有 per-table map + `idemLock`）；`ingest`/`ingestBatch` 快速与慢路径、`loadIdem` 全部改表级；锁序 `global read → table write → idem` 无死锁，跨表导入恢复并行 | ✅ |
| SF-5 | `writeSegmentsBatch` rename 循环非"全有或全无" | 注释收敛为"近似原子"（staging 全量 fsync 后逐个 rename，崩溃时部分段生效，幂等重放可覆盖；与 review-p0 S-4 接受结论一致） | ✅ |
| SF-6 | 占位命中确认返回值不精确且 int 强转可溢出 | `timestampExists` 改为 `timestampRowCount` 返回精确 chunk 行数（-1 不存在）；占位确认返回 `(ts, 精确行数, 0)`，消除整段行数误用与 long→int 溢出（newPoints 无法从已合并点字典恢复，置 0 并注释语义） | ✅ |
| SF-7 | 删除/rename 后无目录 fsync | 新增 `core/util/FsUtil.fsyncDir`；接入 `JsonFiles.write`、`SegmentRewriter.rewrite`（move 后）、`writeSegmentsBatch`（rename 后）、`RetentionCleaner.clean`（delete 后）、`AstraDB.deleteSegment/deleteSnapshot`（delete 后） | ✅ |
| SF-8 | `SegmentWriter.close` 异常路径泄漏 RAF 句柄 | `close()` 改 try/finally：`closed` 置位与 `raf.close()` 无论成败必然执行 | ✅ |

## 2. 关键实现说明

### 2.1 SF-1 幂等清理的降级语义
`rewriteIdemExcluding` 写盘失败仅 WARN：进程内 map 已移除，磁盘残留会在重启后被 `loadIdem` 重新加载，同 ts 同内容重放仍可能被跳过——与幂等写入失败的既有降级语义一致（不引入新的正确性承诺）。

### 2.2 SF-4 锁序（无死锁）
- 快速路径：`global read（stateOf）→ idemLock`；
- 慢路径：`global read → table write → idemLock`；
- 无 `idemLock → table write` 或 `idemLock → global` 反向路径；同表幂等操作仍由表写锁 + 表幂等锁双重串行。

### 2.3 SF-3 隔离策略（按确认决策）
损坏段优先移动隔离（数据保留待人工处置）；隔离失败（如权限）则删除并告警（数据已不可读，避免每次启动重复隔离）；隔离与删除均失败则抛 `IllegalStateException`（维持原"库打不开"语义并留痕）。隔离文件以 `.corrupt` 后缀命名，启动校验（只扫 `.seg`/`.tmp`）不会再次命中。

## 3. 测试

| 项 | 结果 |
|---|---|
| 新增 `ReviewShouldFixTest` 7 项 | ✅ SF-1（deleteSnapshot/deleteSegment 后重放真正写入 + 重启后仍有效）、SF-2（混合批部分重放+部分新增）、SF-3（损坏段隔离后 open 成功、好段可查、隔离产物在 `.quarantine`）、SF-4（跨表写真正并行：small 完成时 big 未完成）、SF-6（手工构造占位记录，确认返回精确行数 2、newPoints=0） |
| `IngestBackfillDeleteTest` 适配 | ✅ "与已有快照重复拒绝"断言拆分不同 ts：单条/批量导入失败会残留占位记录，之后同内容重试命中占位确认（ts 已提交→确认成功）为 SF-2/SF-6 预期新语义 |
| core 全量 | ✅ 41 项（34 原 + 7 新）BUILD SUCCESS |
| **全量 `mvn test`** | ✅ **63 项**（core 41 + client 15 + server 7）BUILD SUCCESS，0 失败 0 错误 |

## 4. 影响与边界

- 行为变更（预期内）：
  - 批量导入批内部分命中正式幂等记录时不再抛"时间戳已存在"，而是重放命中项 + 导入未命中项（SF-2）；
  - 占位记录残留且 ts 已提交时，重放确认返回精确行数（SF-6，与单条 ingest 既有确认语义一致）；
  - 删除快照/段/保留期清理后，同 ts 同内容重放会真正重新写入（SF-1）；
  - 任一损坏段不再阻塞整个库启动，改为隔离 + 告警（SF-3）。
- 新增目录：`segments/.quarantine/`（损坏段隔离区）；目录 fsync 为 best-effort（平台不支持时降级 WARN，不阻断主流程）。
- 未改动：`docs/design`、`docs/test`；server/client 的 should-fix（SS-1~SS-10）不在本次范围，留待后续迭代。
