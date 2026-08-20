# AstraDB 缺陷记录与跟踪

> 版本：v1.0 · 日期：2026-08-16 · 说明：测试计划/用例/报告见 [chain-test-plan.md](./chain-test-plan.md)、[chain-test-cases.md](./chain-test-cases.md)、[chain-test-report.md](./chain-test-report.md)；历史 test-cases.md / test-report.md 已归档。
> 编号规则：`D-xx` 本次测试执行发现的缺陷；`K-xx` 已知问题（开发期遗留，持续跟踪）。

---

## 1. 缺陷状态图例

| 状态 | 说明 |
|---|---|
| 新建 | 已登记，待处理 |
| 修复中 | 正在定位/修改 |
| 已修复 | 代码已修改，待回归 |
| 已验证 | 回归通过，缺陷关闭 |
| 挂起 | 接受为已知边界，不修复（记录原因） |
| 未执行 | 用例未执行，无缺陷信息 |

## 2. 缺陷列表

### D-01：CSV 引号转义对非包裹字段（`a""b`）不还原引号

| 项 | 内容 |
|---|---|
| 发现阶段 | 执行 C-15（CsvParserTest.quotedEscapeAndCommaInQuotes 首轮失败） |
| 严重级 | 低（L3）——仅影响**非 RFC4180 标准**输入；标准输入（字段整体引号包裹）解析正确 |
| 所属模块 | core · ingest · CsvParser |
| 现象 | 输入 `a""b,"x,y",1` 时首字段解析为 4 个字段/引号丢失（`a"b` 期望值未还原） |
| 根因 | 引号状态机仅在"字段起始且内容为空"时进入引号模式，字段中间的 `"` 被当作普通字符直接追加，未与转义逻辑（afterQuote）联动 |
| 修复 | CsvParser 引号分支改为"引号外遇 `"` 一律进入引号模式"，与现有 `""` 转义、afterQuote 关闭逻辑配合；测试输入统一为 RFC4180 标准形态（`"a""b","x,y",1`） |
| 验证 | CsvParserTest 7 项通过；全量回归 37 项通过（`mvn test` BUILD SUCCESS） |
| 状态 | **已验证（关闭）** |
| 备注 | 非标准输入（含引号但未整体包裹的字段）不在支持范围，行为为宽松降级（引号可能被吸收），已在设计边界注明 |

### D-02：管理页面脚本因内联 tableName 缺引号中断，导入功能失效

| 项 | 内容 |
|---|---|
| 发现阶段 | 用户实际使用管理页面（/table?name=asl）点导入时报告（U-02 手工验证） |
| 严重级 | 高（P1）——管理页面脚本整体中断，快照导入/概览/段文件等 JS 功能全部失效 |
| 所属模块 | server · ui · templates/table.html |
| 现象 | 选择 CSV 文件、时间戳缺省、点击导入，提示 `内部错误: Required request parameter 'name' for method parameter type String is not present` |
| 根因 | `const tableName = [[${tableName}]];` 中 Thymeleaf 内联输出无引号（渲染为 `asl`），被 JS 当作未定义标识符 → ReferenceError → 脚本中断 → submit 监听器未注册 → 表单默认提交路径 → 服务端 GET /table 缺少 name 参数，被统一异常处理包装为 500"内部错误" |
| 修复 | 内联表达式加字符串引号：`const tableName = '[[${tableName}]]';`（渲染为 `'asl'`） |
| 验证 | 渲染 HTML 检查 `const tableName = 'asl';`；curl 模拟页面导入请求（multipart，时间戳缺省）成功导入 20 万行；快照查询/统计正确；server 集成测试回归通过 |
| 状态 | **已验证（关闭）** |
| 备注 | 防御性改进（后续）：无 name 的表单提交应返回友好 400 而非 500 |

### D-03：删除表前端误报 JSON 解析错误

| 项 | 内容 |
|---|---|
| 发现阶段 | 用户实际操作管理页面删除表时报告（U-02） |
| 严重级 | 中（P2）——删除实际成功，但前端误报"删除失败: SyntaxError: JSON.parse..."且列表不刷新 |
| 所属模块 | server · api/TableController + ui · static/js/app.js |
| 现象 | 删除表后提示 `删除失败: SyntaxError: JSON.parse: unexpected end of data at line 1 column 1 of the JSON data` |
| 根因 | `deleteTable` 返回 void（200 空响应体），前端 `api()` 对成功响应执行 `resp.json()` 解析空字符串抛 SyntaxError；表已删除但列表未刷新 |
| 修复 | 服务端 `deleteTable` 返回 `{"deleted":true,"table":name}`；前端 `api()`/`apiUpload()` 改为先 `resp.text()`，空体返回 null、非空再 `JSON.parse`（通用容错） |
| 验证 | curl：无 confirm 400、删除成功返回 `{"deleted":true,...}`、表已删；server 集成测试回归通过（BUILD SUCCESS） |
| 状态 | **已验证（关闭）** |

### D-04：同段乱序时间戳导入导致 ChunkIndex 二分失效、查询返回空

| 项 | 内容 |
|---|---|
| 发现阶段 | 用户报告：按时间戳 ts=1786850700000 导入数据后，`getSnapshot` 查询该时间点返回 0 行 |
| 严重级 | 高（P1）——一旦同一段内导入乱序时间戳的快照，该段所有时间点查询结果不可信（二分失效） |
| 所属模块 | core · segment/SegmentWriter |
| 现象 | 先导入 08:48 快照、再导入 08:45 快照（同一天），段内 chunk 时间戳降序；`findChunkAtOrBefore` 升序二分失效，查询返回空页 |
| 根因 | 导入未校验时间戳单调性：同一 .seg 内 chunk 按到达顺序追加，无时间顺序约束；乱序后 ChunkIndex 二分的前提（升序）被破坏 |
| 修复 | `SegmentWriter.append` 强制时间戳严格递增（`timestamp <= 段内最后快照` 即拒绝），保证段内单调；同段回填更早时间戳被拒绝，跨天（不同段）回填历史仍允许 |
| 验证 | SegmentTest.appendRejectsOutOfOrderTimestamps、AstraDBIntegrationTest.outOfOrderSnapshotRejected 通过；真实数据重建后查询 ts=1786850700000 返回 200000 行、乱序导入 400 拒绝；core 37 项 + server 2 项回归全绿 |
| 状态 | **已验证（关闭）** |
| 备注 | 用户侧需按时间升序导入（正常使用即满足）；已存在的乱序数据需删除重建 |

### D-05：STRING 主键空值未被拒绝（违反"主键非空"约定）

| 项 | 内容 |
|---|---|
| 发现阶段 | 存储核心完整测试执行 CT-CSV04（CoreEdgeCasesTest.emptyPrimaryKeyRejected 首轮失败） |
| 严重级 | 中（P2）——空主键行会被注册为 key="" 的点，破坏主键唯一语义 |
| 所属模块 | core · ingest/SnapshotIngestor |
| 现象 | STRING 主键表导入 CSV 首字段为空（`,1.0`）→ 空主键被接受并注册进点字典 |
| 根因 | 导入仅校验主键"快照内唯一"，未校验"非空"（design 7.1 约定"主键非空且快照内唯一"） |
| 修复 | SnapshotIngestor 主键校验前置：空/空白主键 → `IngestException("主键不能为空（第 N 行）")` 整批拒绝 |
| 验证 | emptyPrimaryKeyRejected 通过（拒绝且点计数为 0）；core 54 + server 2 全量回归通过 |
| 状态 | **已验证（关闭）** |

### D-06：DeltaVarintCodec.valueAt 对 INT 列返回 Long（装箱类型错误）

| 项 | 内容 |
|---|---|
| 发现阶段 | 新增优化专项测试执行 O-A2-01（CodecValueAtTest.deltaIntValueAtMatchesDecode 首轮失败） |
| 严重级 | 中（P2）——按需解码路径 INT 列返回 `Long` 而非 `Integer`，与整列解码的 Java 类型契约不一致（JSON 数值序列化无差异，但类型契约错误） |
| 所属模块 | core · codec/DeltaVarintCodec |
| 现象 | `valueAt` 对 INT 列返回 `Long`，调用方按 `Integer` 断言/强转抛 ClassCastException |
| 根因 | 三元表达式 `type == LONG ? v : (int) v` 因 long 与 int 二元数值提升，整体装箱为 `Long` |
| 修复 | 改为显式分支：LONG → 返回 `long`（装箱 Long），否则返回 `(int) v`（装箱 Integer） |
| 验证 | CodecValueAtTest 6 项全过（含 deltaInt/deltaLong valueAt 一致性）；全量 75 项回归通过 |
| 状态 | **已验证（关闭）** |
| 备注 | 测试环境需显式声明 `astradb.security.enabled=false`（ApiIntegrationTest 已加）——测试配置改进，非产品缺陷 |

### D-07：application.yml 中 security.enabled 被置为 true（配置回归）

| 项 | 内容 |
|---|---|
| 发现阶段 | 精确匹配查询端到端验证时，服务器返回 401（本应默认无鉴权） |
| 严重级 | 低（L3）——导致本地/默认启动需认证，与设计"默认关闭（false）"不符 |
| 所属模块 | server · resources/application.yml |
| 现象 | jar 与源码中 `astradb.security.enabled: true`，本地启动 `/api/**` 需登录 |
| 根因 | 配置值被误置为 true（与设计 16.2/README"默认 false"不一致） |
| 修复 | 改回 `enabled: false`；重建后 jar 内确认为 false |
| 验证 | 重建后无鉴权启动正常；精确匹配端到端验证通过；全量回归通过 |
| 状态 | **已验证（关闭）** |
| 备注 | 这也解释了此前 ApiIntegrationTest 401 的根因（测试环境隐式继承 yml 的 true） |

### D-08（全量测试发现，新建）

| 项 | 内容 |
|---|---|
| 标题 | CSV 格式错误（列数不符/非法数值）返回 HTTP 500，应为 400 客户端错误 |
| 发现 | 全量测试 FT-F8-04 错误路径：批量导入传 2 列文件到 4 列 schema 表，返回 `STORAGE_ERROR`（500）；非法数值走 `INTERNAL_ERROR`（500） |
| 严重度 | P1 |
| 根因 | server `CsvParser` 列数不符抛 `IOException`（→ 500 STORAGE_ERROR）；`ColumnBuilder` 数值解析抛 `NumberFormatException`（→ 500 INTERNAL_ERROR）。均为"请求数据不符合格式"的客户端错误，应返回 400 |
| 修复 | `CsvParser` 列数不符与 `ColumnBuilder` 数值解析改为抛 `SnapshotIngestor.IngestException`（→ 400 INGEST_REJECTED，结构化错误码） |
| 验证 | CsvParserTest 断言更新（IOException→IngestException）+ 全量回归 + curl 重验：列数不符/非法数值均返回 400 `INGEST_REJECTED`，正常导入 200 |
| 状态 | **已验证（关闭）** |

### D-09（独立审查发现，新建）

| 项 | 内容 |
|---|---|
| 标题 | 段中间重写插入后 manifest 双重计数（chunkCount/rows 翻倍） |
| 发现 | 独立审查（review 子代理）发现：单/批量中间重写分支把 `SegmentRewriter.RewriteResult`（段内**总量**）传入按"追加累加"语义的 `mergeSegmentInfo`，N 旧 + k 新被记成 2N+k；`getTableStats`/manifest 行数统计运行期错误，仅重启时被启动校验修正 |
| 严重度 | P1（数据统计错误，非数据损坏） |
| 根因 | 重写结果与"追加增量"两种语义混淆 |
| 修复 | 中间重写分支改传**新增量**（单快照 `1, p.rowCount()`；批量 `chunks.size(), rows`）；同步修复重写时 reader 未关闭即原子替换（Windows 兼容）与 `.tmp` 残留启动清理 |
| 验证 | 新增 stats 断言（回填后 chunkCount=2/rows=4）+ `batchBackfillIntoMiddleGap`（chunkCount=4/rows=8）+ 全量 99 项回归通过 |
| 状态 | **已验证（关闭）** |

## 3. 已知问题（K-xx，持续跟踪）

| ID | 描述 | 影响 | 处置 | 状态 |
|---|---|---|---|---|
| K-01 | manifest 重建时段窗口 minKey/maxKey 使用保守值（1..pointCount） | 单点查询的段跳过效率下降（需解压更多候选段） | 已解决：`describeSegment` 重建时解码主键列首尾精确计算 minKey/maxKey；验证用例 `manifestRebuildPreciseWindow`（跨天两段场景，重建后段1 maxKey=100 而非当前 pointCount=150） | **已解决** |
| K-02 | 跨表写入使用全局写锁串行 | 多表并发导入无法并行，吞吐受限 | 已解决：改为表级读写锁（同表写串行、查询与写并发、跨表写并行；锁序 global→table 防死锁，dropTable 等待在途写完成）；验证用例 `concurrentIngestDifferentTables`（并发 20 万行 + 小表，小表先完成 369ms = 并行） | **已解决** |
| K-03 | 管理页面浏览器交互（U-02）未人工执行 | UI 交互层可用性未完全确认 | 已解决：真实使用中验证导入/浏览/搜索/删除等交互，并触发修复 D-02/D-03 | **已解决** |
| K-04 | 百万行级数据规模未实测 | 场景假设 N=20 万~百万的扩展性未验证 | 已解决：百万行压测（PerfBenchmarkTest.millionRowsBenchmark）——100 万点写入 1485ms、全量读取 2558ms、压缩率 10170x（重复模式理想值）、JVM 已用内存约 569MB（含测试堆） | **已解决** |

## 4. 缺陷跟踪汇总

| 阶段 | 数量 |
|---|---|
| 发现缺陷（本次执行） | 11（D-01~D-11；D-08 全量测试新增、D-09 独立审查新增、D-10/D-11 评审报告 review-p0.md 登记） |
| 已修复并验证 | 11 |
| 未解决缺陷 | 0 |
| 已知问题 | 4（K-01~K-04 全部已解决） |

## 5. 过程记录

- 2026-08-16：C-15 首轮执行失败 → 登记 D-01（新建）；
- 2026-08-16：修复 CsvParser 引号分支 + 校正测试输入为标准 RFC4180（已修复）；
- 2026-08-16：CsvParserTest 7 项 + 全量 37 项回归通过 → D-01 关闭（已验证）；
- 2026-08-16：K-01~K-04 登记为挂起项，纳入后续版本跟踪；
- 2026-08-16：用户真实使用管理页面报告导入报错 → 登记 D-02（新建，P1）；
- 2026-08-16：修复 table.html 内联 tableName 缺引号（已修复）；渲染检查 + curl 模拟导入 20 万行成功 + server 回归通过 → D-02 关闭（已验证）。
- 2026-08-17：全量测试 FT-F8-04 发现 CSV 格式错误返回 500 → 登记 D-08；修复 CsvParser/ColumnBuilder 抛 `IngestException`（→ 400 INGEST_REJECTED）；CsvParserTest 断言更新 + 95 项回归 + curl 重验通过 → D-08 关闭（已验证）。
- 2026-08-19：全链条测试（功能/性能/安全，端到端）执行完毕，**未发现新缺陷**（D-01~D-09 维持关闭）；测试计划/用例/报告见 chain-test-*.md。
- 2026-08-17：实现任意时间戳回填与删除指定快照（段重写）后，独立审查发现 manifest 双重计数 → 登记 D-09；修复（重写分支传新增量 + reader 关闭顺序 + tmp 清理）+ stats 断言与批量回填测试 + 99 项回归通过 → D-09 关闭（已验证）。
- 2026-08-20：P0 可靠性优化评审（review-p0.md，verdict block）发现：① `ingest` 与 `ingestBatch` 锁序相反（幂等锁→表锁 vs 表锁→幂等锁）同表并发可死锁 → 登记 D-10；② `contentHash64` STRING 分支用 `String.hashCode()`（32 位域）可构造碰撞致幂等误判静默丢数据 → 登记 D-11。修复：D-10 统一锁序为先表写锁后幂等锁（含并发回归 `concurrentIngestAndIngestBatchNoDeadlock`）；D-11 STRING 逐 char 喂 FNV-1a（含冲突对回归 `stringHashDistinguishesHashCodeCollisions`）；另修复 S-1（open 锁异常路径泄漏，`openFailureReleasesLockAndRetrySucceeds`）、S-2（幂等 fsync 批内合并）、S-3（预写占位幂等消除提交后崩溃窗口）、O-2（降级 WARN 日志）→ P0 测试 10 项 + 全量 56 项回归通过 → D-10/D-11 关闭（已验证）。
