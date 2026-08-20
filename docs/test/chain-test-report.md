# AstraDB 全链条测试报告

> 版本：v2.0 · 日期：2026-08-20 · 关联：[chain-test-plan.md](./chain-test-plan.md)、[chain-test-cases.md](./chain-test-cases.md)、[defects.md](./defects.md)、[optimization.md](../design/optimization.md)、[review-p0.md](../review/review-p0.md)
> 变更：v2.0 追加第 8 节"P0 可靠性专项执行报告"（O-01/O-02/O-03 交付验证 + D-10/D-11 修复确认）。

## 1. 执行概述

依据 design.md / scenario.md / client-design.md 对 AstraDB 全链条（core + server + client + 二进制协议）执行功能、性能、安全三类测试。

**执行方式**：测试源码已恢复入库（core/client/server 的 src/test 共 **16 个测试类 / 56 项**，2026-08-20 全量 `mvn test` 实测全绿）；v1.0 的端到端基线（server jar + curl + client 程序 + 性能计时 + 安全专项）保留于第 2~4 节。

## 2. 功能测试结果（F1~F9，29 项用例）

| 域 | 结果 |
|---|---|
| F1 存储格式（编码器/nullable v2/恢复） | 通过（归档前自动化全绿；nullable v2 端到端验证：建表可空列 → 导入空值 → 查询返回 null） |
| F2 导入（CSV/批量/异步/二进制/回填/校验） | 通过：CSV 含 null 导入、批量（2 快照）、异步（SUCCESS）、**二进制导入含全 null 列**、中间空洞回填、重复时间戳拒绝（400）、类型不符拒绝（400） |
| F3 查询（精确/流式/单点/queryPointAt） | 通过：精确匹配、分页、全量流式、单点历史、**client queryFullSnapshot（行对齐列名含 null）**、**queryPointAt（值/null）** |
| F4 生命周期（manifest/保留期/时区） | 通过（归档前自动化 + 端到端时区行为） |
| F5 段管理 | 通过：段列表/段内快照详情/删除段 confirm |
| F6 API（19+ 端点 + 错误码 + 慢查询） | 通过：健康/建表/列表/信息/统计/导入/快照列表/分页/全量/单点历史/段列表/段详情/删除快照/批量/异步/状态/删表 全 200 或契约错误；统一错误码 {code,message,timestamp,path} |
| F7 管理页面 | 通过：首页/表详情/静态资源 200 + 关键元素 |
| F8 client SDK | 通过：协议 roundtrip、ingest/queryFullSnapshot/queryPointAt、认证、错误码 |
| F9 元数据 | 通过：建表（nullable/主键第 0 位校验）、信息/统计/删表 |

## 3. 性能测试结果（P1~P5，端到端实测）

| 项 | 结果 |
|---|---|
| P1 百万行 | 导入 **4.84s**（HTTP multipart + fsync）、全量读取 **0.48s**、存储 2.3KB（CSV 23.8MB → **压缩率 ~10130x**，重复模式与归档前基准 10170x 一致） |
| P2 批量导入 | 批量 2 快照成功（归档前基准：3×10 万行 90ms vs 逐条 224ms） |
| P3 流式全量 10 万行 | **0.17s / 3.9MB** 一次返回 |
| P4 单点历史 | **0.025s**（10 万点表单点） |
| P5 二进制 vs CSV | 10 万行二进制导入 **651ms** vs CSV 端到端 **785ms**（省 ~17%，含解析与传输密度收益） |

## 4. 安全测试结果（S1~S6）

| 项 | 结果 |
|---|---|
| S1 鉴权 | 通过：未认证 401、正确凭证 200、错误凭证 401、health/静态放行、表单登录（302 → 登录后 200） |
| S2 路径穿越 | 通过：listSegmentSnapshots/deleteSegment 的 `../` 拒绝（400） |
| S3 输入校验 | 通过：非法表名（路径分隔符）、主键非第 0 位、重复时间戳、列数不符、类型不符全部拒绝且报错明确 |
| S4 上传限制 | 配置存在（multipart max-file-size 200MB，环境变量可配） |
| S5 信息泄露 | 通过：错误响应仅 {code,message,timestamp,path}，无堆栈/内部路径 |
| S6 传输 | Basic 认证明文（生产需 TLS）已在 README 部署章节说明 |

## 5. 缺陷跟踪

**本次全链条测试未发现新缺陷**（功能/性能/安全用例全部通过）；缺陷追踪延续 [defects.md](./defects.md)：D-01~D-11 全部关闭，K-01~K-04 已解决；P0 专项缺陷 D-10/D-11 的关闭证据见第 8 节。

## 6. 结论

- **功能**：core/server/client 全链条（含二进制协议、nullable、回填/删除快照、异步导入）符合设计文档与场景文档；
- **性能**：百万行 4.84s 导入 / 0.48s 读取、10 万行流式 0.17s、单点 0.025s、二进制导入较 CSV 省 17%、压缩率 10k 级，满足场景"大数据量/压缩敏感/按时间点回查"要求；
- **安全**：鉴权/穿越/校验/信息泄露全部达标；
- **P0 可靠性（v2.0）**：O-01/O-02/O-03 交付验证通过（`P0ReliabilityTest` 10 项 + 全量 56 项全绿），评审缺陷 D-10/D-11 修复关闭确认；
- 未发现遗留严重缺陷。

## 7. 遗留与建议

| 项 | 说明 |
|---|---|
| 测试源码入库 | 16 类 56 项已恢复在库（git 待提交），建议纳入版本控制后补 CI |
| Docker 未测 | 环境无 docker（既有指示） |
| 浏览器交互 | 以 curl + 静态核对覆盖；异步导入轮询/段弹窗等交互建议人工补验 |
| 表单登录 CSRF | 验证通过（含 CSRF token 流程） |
| 性能专项（P1~P5） | 端到端基线为 v1.0 实测；`PerfBenchmarkTest` 等性能测试类未随当前资产恢复，后续可补充 |

## 8. P0 可靠性专项执行报告（2026-08-20 追加）

> 交付对象：`optimization.md` O-01/O-02/O-03；依据 `review-p0.md`（verdict block）修复项 B-1/B-2/S-1/S-2/S-3/O-2。
> 用例明细与映射见 [chain-test-cases.md](./chain-test-cases.md) R 专项（RT-R1-01 ~ RT-R4-03）。

### 8.1 执行方式

1. **全量回归**：`mvn test`（core + client + server）—— **56 项全绿，Failures=0，Errors=0**；
2. **专项自动化**：`P0ReliabilityTest` 10 项（含 O-01 staging、O-02 幂等 5 项、O-03 文件锁、B-1/B-2/S-1 回归 3 项）—— **10/10 全绿**；
3. **代码核查**（不依赖测试记录）：逐行确认修复落地：
   - B-1：`ingest`/`ingestBatch` 锁序统一为"表写锁 → 幂等锁"（原相反序已消除），快路径无锁嵌套；
   - B-2：`contentHash64` STRING 分支逐 char 喂 FNV-1a，弃用 `String.hashCode()`（32 位域）；
   - S-1：`open()` 异常路径 `release()` + `close()`，同 JVM 重试不再误报锁冲突；
   - S-2：`appendIdemBatch` 批内一次 fsync；
   - S-3：占位幂等（rowCount=-1）预写 + 崩溃后确认路径；
   - O-2：`loadIdem` 失败 WARN 日志（不再静默）。

### 8.2 结果

| 域 | 用例 | 结果 |
|---|---|---|
| R1 批量导入原子化 | RT-R1-01（跨天两新段 staging → 统一 rename → 两段可见；残留清理） | 通过 |
| R2 幂等导入 | RT-R2-01 ~ RT-R2-05（单快照/批量重放、跨重启、损坏降级、64 位哈希区分） | 通过（5/5） |
| R3 dataDir 文件锁 | RT-R3-01（第二实例拒绝、close 释放、数据保留） | 通过 |
| R4 评审修复回归 | RT-R4-01（同表 ingest+ingestBatch 并发不死锁）、RT-R4-02（STRING 冲突对区分）、RT-R4-03（open 失败重试） | 通过（3/3） |

### 8.3 缺陷验证

| 缺陷 | 验证结论 | 证据 |
|---|---|---|
| D-10（锁序反转可死锁） | **关闭（已验证）** | 锁序统一为表锁→幂等锁（`AstraDB.java` ingest L559-562 / ingestBatch L614-619）；`concurrentIngestAndIngestBatchNoDeadlock` 通过；全量 56 项回归全绿 |
| D-11（STRING 哈希 32 位域碰撞） | **关闭（已验证）** | `contentHash64` STRING 逐 char FNV-1a（`SnapshotData.java`）；`stringHashDistinguishesHashCodeCollisions` 通过 |

### 8.4 结论

P0 可靠性交付（O-01/O-02/O-03）**验收通过**：10 项专项用例 + 56 项全量回归全绿，评审期 2 个 blocking（D-10/D-11）与 4 个 should-fix（S-1~S-3/O-2）修复均经代码核查确认，**本次执行未发现新缺陷**。
