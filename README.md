# AstraDB

**大数据量时间分片快照高压缩数据库**

专为"周期性全量快照 + 长期保留 + 回溯查询"负载设计：以时间分片组织快照、以时间序列编码（Gorilla）压缩值列、以主键列匹配检索点，用最小的空间保存任意历史时刻的全量状态。

> 场景与设计见 [docs/scenario.md](docs/scenario.md)、[docs/design.md](docs/design.md)。

## 特性

- **时间分片存储**：快照按天分片为不可变 `.seg` 文件，按时间戳二分定位，O(1) 定位 + 分页读取
- **极致压缩**：列式存储 + Gorilla XOR 差分（double）/ Delta+Varint（整型）/ 字典编码（字符串），再经 **zstd**（等级 1~22 表级可配）二次压缩
- **快照不可变**：写入即冻结，段文件可归档迁移
- **崩溃安全**：Footer + CRC 校验，未完成快照自动截断丢弃，可安全重导
- **点字典**：表级 key→pointId 映射，快照内按 pointId 排序，单点查询可二分与跨段跳过
- **保留期**：表级配置天数，定时整文件清理超期段
- **时区分片**：按天分片时区可配置（`astradb.timezone`），数据文件与页面时间戳一致
- **数据文件管理**：段文件可查看段内快照时间戳、可单独删除（confirm 防误删、防路径穿越）
- **schema 冻结**：建表即定列，杜绝历史数据漂移

## 架构

```
AstraDB (Maven 多模块)
├── core    存储引擎：纯 Java，零框架依赖（编码/压缩/段读写/恢复/元数据/导入/查询/保留期）
├── server  Spring Boot 4 服务：REST API + 启动初始化 + 保留期定时任务
└── ui      管理页面：Thymeleaf + 原生 JS（表管理/导入/查询/统计）
```

## 环境要求

- JDK 25+，Maven 3.9+
- 依赖：zstd-jni、Jackson（core）；Spring Boot 4.0（server）

## 快速开始

```bash
# 构建
mvn clean package

# 启动（默认数据目录 ./data，端口 8080）
java -jar server/target/astradb-server-0.1.0-SNAPSHOT.jar

# 自定义数据目录 / 端口 / 时区
java -jar server/target/astradb-server-0.1.0-SNAPSHOT.jar \
  --astradb.data-dir=/path/to/data --server.port=9000 \
  --astradb.timezone=Asia/Shanghai
```

打开管理台：<http://localhost:8080>

### 使用示例（curl）

```bash
BASE=http://localhost:8080

# 建表（schema 冻结：列名/类型/主键列不可改）
curl -X POST $BASE/api/createTable -H 'Content-Type: application/json' -d '{
  "name":"dev","primaryKey":"id","retentionDays":1825,"compressionLevel":3,
  "columns":[{"name":"id","type":"INT"},{"name":"status","type":"INT"},
             {"name":"temp","type":"DOUBLE"},{"name":"region","type":"STRING"}]}'

# 导入快照（CSV：UTF-8、列序与 schema 一致、首行可含表头；timestamp 可回填历史）
printf '1,1,20.5,华东\n2,2,21.0,华北\n' > snap.csv
curl -X POST $BASE/api/importSnapshot -F table=dev -F timestamp=1767225600000 -F file=@snap.csv

# 按时间点取全量快照（最近一次 ≤ ts）
curl -X POST $BASE/api/getSnapshot -H 'Content-Type: application/json' \
  -d '{"table":"dev","ts":1767225600000,"offset":0,"limit":100}'

# 单点历史
curl -X POST $BASE/api/getPointSeries -H 'Content-Type: application/json' \
  -d '{"table":"dev","key":"1","from":0,"to":9999999999999,"limit":1000}'
```

## API（全部 POST，路径为明确操作）

| 路径 | 说明 |
|---|---|
| `/api/createTable` | 建表：schema、主键列、保留期、压缩等级 |
| `/api/listTables` | 表列表 |
| `/api/getTableInfo` | 表详情 |
| `/api/deleteTable` | 删表（需 `confirm: true`，不可恢复） |
| `/api/importSnapshot` | 导入快照（multipart CSV + 可选 timestamp） |
| `/api/listSnapshots` | 快照时间点列表 |
| `/api/getSnapshot` | 全量快照（分页） |
| `/api/getPointSeries` | 单点历史序列（from/to/limit） |
| `/api/getTableStats` | 存储/压缩率统计 |
| `/api/listSegmentSnapshots` | 段内快照时间戳与行数（数据文件详情） |
| `/api/deleteSegment` | 删除数据文件（需 `confirm: true`，不可恢复） |

## 存储格式要点

- 数据目录：`data/tables.json` + `data/<表>/`（manifest、schema-registry、points.dict、segments/YYYY/YYYY-MM-DD.seg）
- `.seg` 容器：FileHeader + 按时间戳升序的 Chunk（列式 + 列偏移表 + CRC32C）+ ChunkIndex + Footer（CRC64 文件校验）
- 崩溃恢复：Footer 缺失/损坏 → 顺序扫描按 CRC 截断到最后一个完整 chunk

## 性能基准（实测，20 万行/快照）

| 指标 | 实测 | 约束（scenario） |
|---|---|---|
| 快照写入（编码+压缩+落盘） | 414 ms | ≤ 5s |
| 单快照全量读取（分页） | 342 ms | ≤ 2s |
| 压缩率（高度重复数据，理想值） | 2274x | 压缩优先 |

## 测试

```bash
mvn test    # 42 项自动化测试（core 40 + server 2），含性能基准
```

测试计划、用例、缺陷跟踪与报告见 [docs/test/](docs/test/)。

## 目录结构

```
├── pom.xml                 # parent（Java 25、模块管理）
├── core/                   # 存储引擎（纯 Java）
├── server/                 # Spring Boot 服务 + 管理页面
├── docs/
│   ├── scenario.md         # 场景文档
│   ├── design.md           # 设计文档
│   └── test/               # 测试计划/用例/缺陷/报告
└── .mvn/maven.config       # Maven 本地仓库指向 .m2-repository
```

## 已知限制

- 不支持标签/维度过滤查询（无标签索引）；
- 不支持更新已写数据（快照不可变）；
- 点集需基本稳定（缓慢增长）；写为分钟级全量快照，非流式实时写入。
