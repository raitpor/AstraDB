# AstraDB 全链条测试计划

> 版本：v2.0 · 日期：2026-08-20 · 关联：[design.md](../design/design.md)、[scenario.md](../design/scenario.md)、[client-design.md](../design/client-design.md)、[optimization.md](../design/optimization.md)、[review-p0.md](../review/review-p0.md)、[defects.md](./defects.md)
> 变更：v2.0 新增第 7 节"P0 可靠性专项"（O-01/O-02/O-03 交付与 review-p0 修复验证）。

## 1. 目标

依据设计文档（design.md）、场景文档（scenario.md）、client 设计文档（client-design.md），对 AstraDB **全链条**（core 存储引擎 + server API/页面 + client SDK/二进制协议）做系统性测试，覆盖**功能、性能、安全**三大类型，产出独立测试文档并追踪缺陷。

## 2. 测试范围

### 2.1 功能（依据 design 全部功能域 + client-design）

| 域 | 内容 |
|---|---|
| F1 存储格式 | .seg/Chunk/编码器（Gorilla/DeltaVarint/Dictionary + valueAt/decodeRange）、点字典、zstd、**nullable v2（位图/有效值）**、格式版本 |
| F2 导入 | CSV（server 解析）、批量、异步、**二进制协议**、SnapshotData 直传、**任意时间戳回填/删除快照**、列数/类型/nullable 校验 |
| F3 查询 | 精确时间点匹配、分页区间解码、全量流式、单点历史（跨段并行/点消失）、**queryPointAt**、查询缓存 |
| F4 数据生命周期 | manifest 重建/两级校验/漂移纠正、崩溃截断恢复、保留期、时区分片 |
| F5 段管理 | 段列表/段内快照详情/删除段（confirm/路径穿越） |
| F6 API | 19+ 端点（表/数据/二进制/健康）+ 统一错误码 + 慢查询日志 |
| F7 管理页面 | 首页/表详情（导入同步/异步、浏览、搜索点、段弹窗、删除快照）、nullable 建表 |
| F8 client SDK | ingest / queryFullSnapshot（行对齐列名含 null）/ queryPointAt / 认证 / 错误码 / 二进制协议编解码 |
| F9 元数据 | 建表（nullable/主键第 0 位）、表信息/统计、删表 |

### 2.2 性能（依据 scenario 负载特征 + design 16 节）

| 项 | 说明 |
|---|---|
| P1 百万行 | 写入/读取/压缩率/内存（PerfBenchmarkTest + 真实数据） |
| P2 批量导入 | 批量 vs 逐条（fsync 次数） |
| P3 流式全量 | 10 万+ 行快照一次返回耗时/体积 |
| P4 单点历史 | 跨段并行查询耗时（288 快照场景） |
| P5 二进制协议 | 体积/编码耗时 vs CSV（数据密度收益） |

### 2.3 安全

| 项 | 说明 |
|---|---|
| S1 鉴权 | 未认证 401、正确凭证 200、错误凭证 401、表单登录、health/静态放行 |
| S2 路径穿越 | deleteSegment/listSegmentSnapshots 的 `../` 拒绝 |
| S3 输入校验 | 非法表名/列名、类型不符、nullable 冲突、重复时间戳、主键非空、批量乱序 |
| S4 上传限制 | 超限文件拒绝（multipart max-file-size） |
| S5 信息泄露 | 错误响应不含堆栈/内部路径；结构化错误码 |
| S6 传输 | Basic 认证明文提示（文档层面，生产需 TLS） |

## 3. 测试策略

1. **自动化**：全量 `mvn test`（core + client + server，基线 118 项）映射 F/P/S 域；
2. **真实端到端**：server jar + curl 覆盖全端点 + 二进制协议（client 程序）+ 页面关键路径 + 数据行为（回填/删除/nullable/时区/保留期）；
3. **性能**：既有 PerfBenchmarkTest + 端到端基准（流式/并行/二进制密度）；
4. **安全**：专项 curl（鉴权/穿越/校验/限制/信息泄露）；
5. **缺陷流程**：发现即登记 defects.md（延续 D-xx 编号）→ 修复 → 回归 → 关闭。

## 4. 准入/准出

- 准入：三份设计文档定稿；代码可构建（118 项基线）。
- 准出：功能/性能/安全用例执行完毕；无未关闭 P0/P1 缺陷；性能在基线范围内。

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 浏览器交互无法自动化 | curl 全 API + 页面静态核对覆盖，交互标注手工 |
| 测试污染数据目录 | 端到端用独立数据目录（/tmp） |
| 环境无 docker | Docker 不测（既有指示） |

## 6. 交付物

- `docs/test/chain-test-plan.md`（本文件）
- `docs/test/chain-test-cases.md`（用例 + 执行结果回填）
- `docs/test/defects.md`（缺陷记录，追加）
- `docs/test/chain-test-report.md`（测试报告）

## 7. P0 可靠性专项（2026-08-20 追加）

> 针对交付内容：`optimization.md` O-01/O-02/O-03（P0 可靠性）及其评审修复（`review-p0.md` B-1/B-2/S-1/S-2/S-3/O-2）。

### 7.1 专项范围

| 域 | 交付项 | 设计依据 |
|---|---|---|
| R1 批量导入原子化 | O-01：新段 staging 两阶段提交（`.staging/*.tmp` → `ATOMIC_MOVE` rename → manifest 一次保存） | optimization.md §2.1 O-01 |
| R2 幂等导入 | O-02：64 位内容哈希（FNV-1a）+ `idempotency.idx` 文件持久化 + 跨重启重放 + 损坏降级 | optimization.md §2.1 O-02 |
| R3 dataDir 文件锁 | O-03：`FileChannel.tryLock` 排他锁 + 异常路径释放 | optimization.md §2.1 O-03 |
| R4 评审修复回归 | B-1 锁序统一（表锁→幂等锁）、B-2 STRING 逐 char 哈希、S-1 open 锁泄漏、S-2 批内幂等 fsync、S-3 预写占位、O-2 降级告警 | review-p0.md §4 |

### 7.2 专项策略

1. **自动化**：`P0ReliabilityTest`（10 项）为核心资产，映射 R1~R4；全量 `mvn test` 回归（基线 70 项）；
2. **代码核查**：对 B-1 锁序、B-2 哈希实现、S-1 异常路径逐行核对修复是否真实落地（不依赖测试"已记录通过"）；
3. **缺陷验证**：D-10（锁序死锁）、D-11（STRING 哈希碰撞）按 defects.md 记录核对关闭证据，如有复现/新发现登记 D-12+；
4. **性能关注**：S-2 批内合并后批量导入 fsync 次数不回归（批量 vs 逐条基线）。

### 7.3 专项准入/准出

- 准入：P0 修复代码在库；`P0ReliabilityTest` 可编译执行。
- 准出：R1~R4 用例全部通过；全量 70 项回归全绿；D-10/D-11 关闭证据核实；无新增未关闭 P0/P1 缺陷。
