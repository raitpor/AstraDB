# AstraDB 设计文档

> 版本：v1.0（评审稿） · 语言：中文 · 状态：待评审
> 关联文档：[scenario.md](./scenario.md)（场景文档）
> 技术选型：Java 25 · Spring Boot 4.x · Maven 多模块
> 结构：`core`（存储引擎）+ `server`（Spring Boot 服务）+ 管理页面（Thymeleaf + 原生 JS）

---

## 1. 设计目标

在场景文档约束下，实现一套**大数据量时间分片快照高压缩数据库**：

| 维度 | 目标 |
|---|---|
| 压缩率 | 优先（CPU 换空间），利用"相邻快照间值接近/相同"做时序编码 |
| 存储 | 按天分片 `.seg` 文件，不可变，便于归档与迁移 |
| 写入 | 全量快照批量导入，20 万行编码 + 压缩 ≤ 5s，快照不可变 |
| 查询 | 按时间点取全量快照 ≤ 2s；按点取历史序列 |
| 内存 | 缓冲 2~4 个快照 ≤ 100MB，禁止装箱对象驻留 |
| 恢复 | 进程崩溃不产生半写可见数据，未完成快照可安全丢弃/重导 |

## 2. 已确认的设计决策（与用户评审结论）

1. **Chunk 内编码**：列式存储，每列独立编码——数值列用 Gorilla，整型用 Delta+Varint，低基数/字符串列用字典编码；
2. **主键策略**：表级点字典（key → pointId）+ 快照内按 pointId 排序后写入；
3. **管理页面**：Thymeleaf 服务端渲染 + 原生 JS（无前端构建）；
4. **导入格式**：CSV（UTF-8，行 = 一个点的全字段）；
5. **时间精度**：毫秒（8B 时间戳），快照时间戳由导入方显式指定（支持回填历史），缺省取当前时间；
6. **schema**：建表即冻结（列数、列类型、主键列不可改），`schema-registry.json` 记录版本历史（当前仅 v1），为未来演进预留字段；
7. **压缩**：直接采用 **zstd**（`zstd-jni`，com.github.luben），表级可配压缩等级（默认 3，范围 1~22），通过 `Compressor` 接口隔离实现；
8. **导入语义**：同步返回（预算内），返回快照时间戳 + 行数；同表写串行（每表一个写队列），查询与写入并发；
9. **崩溃恢复**：`.seg` Footer 缺失/损坏 → 顺序扫描 chunk 按 CRC 截断到最后一个完整 chunk；点字典走临时文件 + 原子 rename；
10. **保留期**：表级配置天数，定时任务整文件删除超期 `.seg`，同步更新 manifest。
11. **时区分片**：按天分片时区可配置（`astradb.timezone`，缺省系统时区），保证数据文件日期与页面/数据时间戳一致；
12. **导入入口解耦**：`SnapshotData`（列缓冲 record）为通用导入载体，CSV 与其他导入方式解析为同一 record 后复用同一落盘逻辑；
13. **段管理**：数据文件可查看段内快照时间戳（`listSegmentSnapshots`）与删除（`deleteSegment`，confirm + 路径穿越校验，不可恢复）；同段时间戳单调递增（乱序/重复拒绝，跨天回填允许）。

## 3. 总体架构

```
AstraDB (Maven 多模块)
├── core    存储引擎：纯 Java，零 Spring 依赖（可独立单测与复用）
│           元数据 / 列编码 / 压缩 / segment 读写 / 点字典 / manifest / 导入 / 查询 / 保留期
├── server  Spring Boot 3：REST API + 导入编排 + 后台任务（启动初始化、保留期清理）
└── ui      管理页面：Thymeleaf 模板 + 原生 JS（表管理、导入、查询、统计）
```

依赖方向：`server → core`，`ui` 由 `server` 托管渲染，无反向依赖。

### 3.1 core 模块职责（包结构草案）

```
com.astradb.core
├── meta        TableMeta、Schema、SchemaRegistry、TablesStore（tables.json 读写）
├── codec       列编码器：ColumnCodec 接口 + Gorilla / DeltaVarint / Dictionary 实现
├── compress    压缩器：Compressor 接口 + ZstdCompressor（zstd-jni）
├── segment     SegmentWriter / SegmentReader / Chunk / ChunkIndex / 文件头尾解析
├── points      PointDictionary（key → pointId，含落盘与内存态）
├── manifest    Manifest 读写与重建（扫描 segments/ 恢复）
├── ingest      CsvParser（流式）、SnapshotData（通用列缓冲载体）、SnapshotIngestor（校验→编码→落盘编排）
├── query       SnapshotQuery（按时间点全量）、PointSeriesQuery（单点历史）
└── retention   RetentionCleaner（超期段清理）
```

### 3.2 server 模块职责（包结构草案）

```
com.astradb.server
├── api         REST 控制器（表 / 快照 / 查询 / 统计）
├── service     业务编排：TableService、IngestService、QueryService、StatsService
├── task        StartupInitializer、RetentionTask（定时）
└── resources
    ├── templates/   Thymeleaf 页面
    └── static/      原生 JS / CSS
```

## 4. 概念模型

| 概念 | 说明 |
|---|---|
| 表（Table） | 独立命名空间，冻结 schema：主键列 + 若干值列；独立保留期与压缩等级 |
| 点（Point） | 一个实体，由主键唯一标识；全局 pointId 映射 |
| 快照（Snapshot） | 某一时刻全部点的一份状态；不可变；带毫秒时间戳 |
| Chunk | `.seg` 内的一个快照单元：列式编码 + 压缩后的数据块 |
| Segment | 按天分片的 `.seg` 文件，一天一个，追加写；不可变 |
| 段窗口 | manifest 中记录的段内主键 min/max，用于单点查询跳过 |

## 5. 数据文件组织

```
data/                                    # 根目录，路径可配置（astradb.data-dir）
├── tables.json                          # 表元数据（表名、schema、主键列、保留期、压缩等级）
└── tableName1/
    ├── manifest.json                    # 分片清单（可重建）
    ├── schema-registry.json             # schema 版本历史（当前仅 v1）
    ├── points.dict                      # 点字典：key → pointId（缓慢增长、追加式）
    └── segments/
        ├── 2026/
        │   ├── 2026-01-01.seg
        │   └── 2026-01-02.seg
        └── 2027/
            └── ...
```

## 6. 存储格式

### 6.1 .seg 容器布局（沿用场景文档定义）

```
┌────────────────────────────────────────────────┐
│ FileHeader: magic(4B) 格式版本(2B) 布局(1B)     │
│             段起始时间(8B) schemaVersion(2B)    │
│             列数(2B)                            │
├────────────────────────────────────────────────┤
│ Chunk 0   (快照0)                           │
│ Chunk 1   (快照1)                           │
│ ...                                             │
│ Chunk n (快照n)                           │
├────────────────────────────────────────────────┤
│ ChunkIndex: 每 chunk 一条                        │
│   offset(8B) length(4B) 时间戳(8B) 行数(4B)      │
│   schemaVersion(2B) CRC32C(4B)                  │
├────────────────────────────────────────────────┤
│ Footer: magic(4B) 索引偏移(8B) 索引条目数(4B)     │
│         文件校验和(8B) 结束magic(4B)             │
└────────────────────────────────────────────────┘
```

- Chunk 按时间戳升序追加；ChunkIndex 支持按时间戳二分定位；
- Footer 是"文件完整"的标记：崩溃时未写完 Footer 的文件在打开时触发恢复流程（见 8.3）。

### 6.2 Chunk 内部布局（本次设计的核心）

```
Chunk:
┌───────────────────────────────────────────────┐
│ ChunkHeader: 快照时间戳(8B) 行数(4B)            │
│              schemaVersion(2B) 列数(2B)        │
│              列偏移表: 每列 [offset(4B)        │
│                        length(4B) 编码器ID(1B)]│
├───────────────────────────────────────────────┤
│ 列 0 数据（编码字节流 + 压缩）                  │
│ 列 1 数据                                      │
│ ...                                           │
├───────────────────────────────────────────────┤
│ CRC32C(4B)                                     │
└───────────────────────────────────────────────┘
```

- **列式布局**：查询单列时按列偏移表只解压目标列；
- 每列数据 = 列编码器输出字节流 → 压缩器按表配置等级压缩；
- 列顺序与 schema 列序一致（第 0 列为主键列，其后为值列）。

### 6.3 列编码器

统一接口：

```java
interface ColumnCodec {
    byte typeId();                                  // 编码器 ID（写 ChunkHeader）
    byte[] encode(Column col, CodecContext ctx);    // 列 → 字节流（未压缩）
    Column decode(byte[] data, ColumnMeta meta, CodecContext ctx);
}
```

| 编码器 | 适用列 | 算法要点 |
|---|---|---|
| GorillaCodec | double / float 值列 | 相邻快照同列值做 XOR 差分：相同值 1 bit，接近值少 bit；压缩率主来源 |
| DeltaVarintCodec | long / int 值列、pointId 列 | 排序/序列化后增量 + varint，增量小则紧凑 |
| DictionaryCodec | 字符串列、低基数枚举列 | 表级字典（值 → int id），id 列走 DeltaVarint；字典随点字典追加维护 |

要点：Gorilla 对"相邻快照间大部分值相同"的全量快照流极其有效（相同值仅占 1 bit），等价于隐含的行级差分，同时按 chunk 解压即可随机读取，无需重放历史。

### 6.4 主键列与点字典

- **points.dict**：表级全局映射 `key → pointId`，点集缓慢增长（年增 ≤ 1 万）故采用追加式落盘（临时文件 + rename，原子提交）；
- 启动时载入内存（20 万~百万条 ≈ 几十 MB，在预算内），提供去重校验与 ID 分配；
- **快照内按 pointId 排序**后写入：主键列成为近似递增整数序列，Delta+Varint 后约 1~4 bit/点；
- 排序收益：① 主键列压缩率；② 单点查询可二分；③ manifest 记录每 chunk 主键 min/max 实现跨段跳过；
- 排序成本：20 万行约百 ms 级，远在 5s 写入预算内。

### 6.5 压缩层

- `Compressor` 接口：`byte[] compress(byte[])` / `byte[] decompress(byte[])`，块级（列级）压缩；
- **直接采用 zstd**：引入 `zstd-jni`（com.github.luben），压缩率与速度均衡优于 Deflater；
- 压缩等级表级可配（zstd 等级 1~22，默认 3），等级越高越省空间、CPU 开销越大（符合"CPU 换空间"定位）；
- 保留 `Compressor` 接口隔离实现，未来如需可替换其他算法而不影响调用方。

## 7. 写入路径

### 7.1 CSV 导入格式约定

- 编码 UTF-8，逗号分隔，标准引号转义（RFC 4180 风格）；
- **列序与 schema 列序一致**（含主键列）；
- 首行可选表头：若与 schema 列名完全一致则跳过，否则视为数据行；
- 每行 = 一个点的全字段；导入前校验：主键非空且快照内唯一、值列类型可解析。

### 7.2 导入流程（SnapshotIngestor）

```
1. 校验表存在、schema 冻结版本匹配、列数与列类型一致
2. 数据源解析 → SnapshotData（列缓冲 record）；CSV 由 CsvParser 产出，其他方式（JSON 等）解析为同一 record
3. 校验主键快照内唯一；与点字典求并集，新点分配 pointId（内存态）
4. 按 pointId 排序，值列按相同顺序重排
5. 逐列编码（6.3）→ 压缩（6.5）
6. 组装 Chunk，追加写当天 .seg，fsync
7. 更新 manifest（内存 + 落盘）
8. 返回：快照时间戳、行数
```

约束：

- **时间戳单调递增**：同一 `.seg` 内快照时间戳必须严格递增（`SnapshotData`/CSV 入口均强制校验），保证 ChunkIndex 二分正确；乱序或重复时间戳拒绝导入。跨天（不同段）回填历史时间仍允许；
- 导入入口解耦：`SnapshotIngestor.ingest(InputStream)`（CSV）与 `ingest(SnapshotData)`（通用）共用同一落盘逻辑，新增导入方式仅需实现"源 → SnapshotData"解析。

并发：同表写串行（每表一个写队列，`.seg` 追加写单写者）；跨表独立；查询与写并发（读不阻塞写）。

### 7.3 崩溃恢复

- **.seg**：打开时校验 Footer（magic + 文件校验和）；失败 → 顺序扫描 chunk 区，按 CRC32C 定位第一个不完整 chunk 并截断——未完成快照被丢弃，满足"可安全丢弃/重导"；
- **points.dict / tables.json / manifest.json**：写临时文件 + 原子 rename，进程崩溃不产生半写可见数据；
- **manifest**：任何时候可由扫描 `segments/` 重建（见 9）。

## 8. 查询路径

| 查询 | 路径 |
|---|---|
| 全量快照 @ts | manifest 定位日期 `.seg` → ChunkIndex 按时间戳二分 → 解压 chunk → 流式分页返回 |
| 单点历史 | 遍历 manifest，用段窗口（主键 min/max）跳过不含该点的段 → 解压候选 chunk 主键列二分得行号 → **只解压所需值列**的对应行 |

- 分页：快照查询按行区间分页（offset/limit），响应为行数组 + 总行数；
- 单点历史支持 from/to 时间范围与 limit 限制；
- 不建额外索引：贴合"牺牲通用检索能力换取极致压缩"的定位（对应场景文档 1.2 结论）。

## 9. manifest 与重建

```json
{
  "table": "tableName1",
  "segments": [
    {
      "path": "segments/2026/2026-01-01.seg",
      "startTime": 1767225600000,
      "endTime": 1767312000000,
      "chunkCount": 3,
      "rows": 600000,
      "minKey": 1,
      "maxKey": 200000,
      "sizeBytes": 12345678
    }
  ]
}
```

- 启动时校验；不一致或缺失 → 扫描 `segments/` 读各 `.seg` 的 Header + Footer 重建；
- 内存态供查询路由使用。

## 10. 保留期清理

- 表级配置"保留天数"；定时任务（每日）遍历 manifest，整文件删除段结束时间超出保留期的 `.seg`，同步更新 manifest 与磁盘目录；
- `.seg` 不可变，删除即整文件删，无部分删除风险。

## 11. server 层设计

### 11.1 API 清单（全部 POST，路径为明确操作）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/createTable` | 建表：schema（列名/类型）、主键列、保留期、压缩等级 |
| POST | `/api/listTables` | 表列表 |
| POST | `/api/getTableInfo` | 表详情（schema、段文件、统计），请求体：table |
| POST | `/api/deleteTable` | 删表，请求体：table + confirm=true（不可恢复） |
| POST | `/api/importSnapshot` | 导入快照：multipart CSV + table + 可选 timestamp |
| POST | `/api/listSnapshots` | 快照时间点列表，请求体：table |
| POST | `/api/getSnapshot` | 全量快照（分页），请求体：table + ts + offset/limit |
| POST | `/api/getPointSeries` | 单点历史序列，请求体：table + key + from/to/limit |
| POST | `/api/getTableStats` | 存储/压缩率统计，请求体：table |
| POST | `/api/listSegmentSnapshots` | 段内快照时间戳与行数（数据文件详情），请求体：table + path |
| POST | `/api/deleteSegment` | 删除数据文件（不可恢复），请求体：table + path + confirm=true |

说明：所有操作均使用 POST；参数（除 `importSnapshot` 的 CSV 文件外）统一放入 JSON 请求体。

### 11.2 后台任务

- 启动：加载 `tables.json` → 校验 schema-registry → 载入点字典 → 校验/重建 manifest；
- 定时：保留期清理（每日）、统计刷新。

### 11.3 配置（application.yml 草案）

```yaml
astradb:
  data-dir: ./data
  compression-level: 3        # 默认 zstd 压缩等级（1~22），建表时可表级覆盖
  timezone: Asia/Shanghai     # 按天分片时区（保证数据文件与页面时间戳一致）；缺省取系统时区
server:
  port: 8080
```

## 12. 管理页面（Thymeleaf + 原生 JS，参考 docs/admin.html 风格）

| 页面 | 功能 |
|---|---|
| 首页 / 表列表 | 表概览（统计式卡片）、建表（动态列编辑）、删除表（confirm） |
| 表详情 | 概览统计、快照导入（CSV + 日期时间/毫秒时间戳联动）、按时间戳浏览数据（分页）、在该时间戳搜索点、数据文件浏览（查看段内快照时间戳弹窗 / 删除段文件） |

页面数据全部经 REST API 获取（原生 JS fetch），无前端构建。

## 13. 内存与性能约束对照

| 场景文档约束 | 设计措施 |
|---|---|
| 单快照写入 ≤ 5s（20 万行） | 流式 CSV 解析；原始数组列缓冲；Gorilla/Delta 编码为 O(n) 流式；排序百 ms 级 |
| 单快照全量读取 ≤ 2s | 列式按需解压；ChunkIndex 二分；分页流式 |
| 缓冲 2~4 快照 ≤ 100MB | 原始类型数组（int[]/long[]/double[]/byte[]），禁止 `List<POJO>` 驻留 |
| 崩溃无半写可见 | Footer 校验 + CRC 截断；元数据原子 rename；manifest 可重建 |
| 压缩优先、等级可配 | 列编码 + 块压缩两层；zstd 1~22 表级可配（默认 3） |

## 14. 项目结构与构建

```
AstraDB/
├── pom.xml                      # parent：Java 25 目标、模块管理
├── core/pom.xml                 # 纯 Java 引擎（无 Spring 依赖）
└── server/pom.xml               # spring-boot-starter-web + Thymeleaf，依赖 core
```

- Java 25（环境 JDK 25 可编译）；Spring Boot 4.x；Maven 3.9；
- core 无框架依赖 → 可独立跑单元测试（编码器 roundtrip、segment 读写、崩溃恢复、保留期）；
- server 提供集成测试入口（MockMvc）。

## 15. 实施里程碑（草案）

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 core 引擎 | 编码器、压缩、segment 读写、点字典、manifest、导入、查询、保留期 | 单测覆盖编码 roundtrip / 崩溃截断 / 排序查询正确性 |
| M2 server | REST API、启动初始化、保留期定时任务、表管理 | 集成测试：导入→查询→清理全链路 |
| M3 管理页面 | Thymeleaf 页面 + 原生 JS | 手工验证建表/导入/查询/统计 |
| M4 打磨 | 性能基准（写入 5s / 读取 2s / 内存 100MB）、zstd 等级调优、异常边界 | 对照 13 节指标 |

---

## 附录 A：异常与边界约定

- 建表后 schema 冻结：导入列数/类型/主键列不匹配 → 400 拒绝，不产生任何文件写入；
- 快照内主键重复 → 400 拒绝整批导入（不做部分成功）；
- 时间戳回填历史：允许写任意历史日期（按天分片），但同一段内时间戳必须单调递增——乱序/重复 → 400 拒绝（防止 ChunkIndex 二分失效）；跨天回填不受影响；
- 单点查询 key 不存在 → 200 空序列（非错误）；
- 内存压力：点字典载入超预算时告警，不自动扩容策略（点集缓慢增长，预留余量）；
- 命名约束：表名/列名/主键值采用 UTF-8，禁止路径分隔符（`/`、`\`）与控制字符；
- 删表语义：直接删除 `data/<表>` 目录，不可恢复；`deleteTable` 需携带 `confirm=true` 二次确认；
- 段文件删除：`deleteSegment` 需 `confirm=true`；段路径仅允许 `segments/` 目录内相对路径（防路径穿越），删除后同步 manifest。
