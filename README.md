# AstraDB

**大数据量时间分片快照高压缩数据库**

专为"周期性全量快照 + 长期保留 + 回溯查询"负载设计：以时间分片组织快照、以时间序列编码（Gorilla）压缩值列、以主键列匹配检索点，用最小的空间保存任意历史时刻的全量状态。

> 场景与设计见 [docs/design/scenario.md](docs/design/scenario.md)、[docs/design/design.md](docs/design/design.md)。

## 特性

- **时间分片存储**：快照按天分片为不可变 `.seg` 文件，按时间戳二分定位，O(1) 定位 + 分页读取
- **极致压缩**：列式存储 + Gorilla XOR 差分（double）/ Delta+Varint（整型）/ 字典编码（字符串），再经 **zstd**（等级 1~22 表级可配）二次压缩
- **快照不可变**：写入即冻结，段文件可归档迁移
- **崩溃安全**：Footer + CRC 校验，未完成快照自动截断丢弃，可安全重导
- **点字典**：表级 key→pointId 映射，快照内按 pointId 排序，单点查询可二分与跨段跳过
- **保留期**：表级配置天数，定时整文件清理超期段
- **时区分片**：按天分片时区可配置（`astradb.timezone`），数据文件与页面时间戳一致
- **数据文件管理**：段文件可查看段内快照时间戳、可单独删除（confirm 防误删、防路径穿越）
- **性能优化**：查询按需解码（单点历史不构造整列数组）、批量导入（fsync 减少 ~60%）、百万行写入 1.5s、段句柄池化、跨段并行查询、全量快照流式输出
- **可观测与运维**：统一错误码（code/message）、慢查询日志（阈值可配）、全部配置支持环境变量覆盖、异步导入任务（大文件不阻塞）
- **schema 冻结**：建表即定列，杜绝历史数据漂移；每列可选**可空（nullable）**，主键列强制非空，空值以 null 位图存储（全非空零开销）

## 部署与安全

### 启用鉴权（生产）

```yaml
# application.yml 或环境变量
astradb:
  security:
    enabled: true
    username: admin
    password: change-me      # 生产用 ASTRA_DB_SECURITY_PASSWORD 注入
```

开启后 `/api/**` 与管理页面需登录（Basic 或表单）；`/api/health` 与静态资源放行。

### Docker

```bash
docker build -t astradb:0.1.0 .
docker compose up -d          # 数据卷 ./data、安全/时区经环境变量注入
```

### systemd

见 `deploy/astradb.service`（专用用户 + 环境变量注入 + 资源限制）。

### 健康检查

`GET /api/health` → `{"status":"UP","version":"0.1.0","tables":N,"dataDir":"./data","dataDirWritable":true,"uptimeMs":...}`

## 架构

```
AstraDB (Maven 多模块)
├── core    存储引擎：纯 Java，零框架依赖（编码/压缩/段读写/恢复/元数据/导入校验/查询/保留期；仅接收 SnapshotData/BatchSnapshot）
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

### Java Client SDK（二进制数据流）

独立 Maven 模块 `astradb-client`（**零第三方依赖**：仅 JDK HttpClient + 自含最小 JSON 与二进制协议，无 Spring/Jackson 依赖——可安全集成到任意 Spring Boot 应用，避免 Jackson 版本冲突），以专有列式二进制协议与 server 交换数据（流式/高密度/精确类型）。

```xml
<dependency>
  <groupId>com.astradb</groupId>
  <artifactId>astradb-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
import com.astradb.client.AstraDbClient;
import com.astradb.client.QueryResult;

// 无认证 / Basic 认证（与 server 鉴权账号对应）
AstraDbClient client = new AstraDbClient("http://localhost:8080");
// AstraDbClient client = new AstraDbClient("http://localhost:8080", "admin", "password");

// 导入：行×列数据（Integer/Long/Double/Float/String，可含 null），返回 rowCount
int rows = client.ingest("t1", System.currentTimeMillis(), List.of(
        List.of(1, 1.5, "华东"),
        List.of(2, null, null)));

// 全量快照：列名 + 数据行（行对齐列名，含主键列；null 值以 null 表示）
QueryResult r = client.queryFullSnapshot("t1", ts);
for (Object[] row : r.rows()) { /* ... */ }

// 指定时间点单点数据：值列数组（不含主键）；该时间点无数据返回 null
Object[] point = client.queryPointAt("t1", "42", ts);

// 元数据（JSON）
client.createTable("t1", List.of(
        Map.of("name", "id", "type", "INT"),
        Map.of("name", "v", "type", "DOUBLE", "nullable", true)), "id");
client.listTables();
```

二进制端点：`POST /api/importBinary`、`POST /api/queryFullSnapshotBinary`（协议见 [docs/design/client-design.md](docs/design/client-design.md)）。

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

# 按时间点取全量快照（分页；返回列式格式：pk=主键列名、columns=列名数组（主键在首位）、rows=二维数组（每行含主键值））
curl -X POST $BASE/api/getSnapshot -H 'Content-Type: application/json' \
  -d '{"table":"dev","ts":1767225600000,"offset":0,"limit":100}'
# 响应：{"pk":"id","columns":["id","c1","c2","c3"],"timestamp":...,"totalRows":...,"offset":0,"limit":100,
#        "rows":[[1,1,20.5,"华东"],[2,2,21.0,"华北"]]}

# 单点历史（返回 pk/columns/rows/timestamps，timestamps 与 rows 一一对齐）
curl -X POST $BASE/api/getPointSeries -H 'Content-Type: application/json' \
  -d '{"table":"dev","key":"1","from":0,"to":9999999999999,"limit":1000}'
# 响应：{"pk":"id","columns":["id","c1","c2","c3"],"rows":[[1,1,20.5,"华东"],...],"timestamps":[1767225600000,...]}
```

## API（全部 POST，路径为明确操作）

| 路径 | 说明 |
|---|---|
| `/api/createTable` | 建表：schema、主键列、保留期、压缩等级 |
| `/api/listTables` | 表列表 |
| `/api/getTableInfo` | 表详情 |
| `/api/deleteTable` | 删表（需 `confirm: true`，不可恢复） |
| `/api/importSnapshot` | 导入快照（multipart CSV + 可选 timestamp；可向段内任意不存在时间戳回填，段重写保持有序） |
| `/api/listSnapshots` | 快照时间点列表 |
| `/api/getSnapshot` | 全量快照（分页） |
| `/api/getFullSnapshot` | 全量快照（不分页，一次返回该时间点全部行） |
| `/api/getPointSeries` | 单点历史序列（from/to/limit） |
| `/api/getTableStats` | 存储/压缩率统计 |
| `/api/listSegmentSnapshots` | 段内快照时间戳与行数（数据文件详情） |
| `/api/deleteSegment` | 删除数据文件（需 `confirm: true`，不可恢复） |
| `/api/deleteSnapshot` | 删除指定时间点快照（需 `confirm: true`，段重写保持有序） |
| `/api/importSnapshots` | 批量导入：多 CSV + 严格递增 timestamps（减少 fsync） |
| `/api/importAsync` | 异步导入：立即返回 taskId，后台执行（适合大文件） |
| `/api/importStatus` | 查询异步导入任务状态（RUNNING/SUCCESS/FAILED） |
| `GET /api/health` | 健康检查（鉴权开启时放行） |

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
mvn test    # 70 项自动化测试（core 41 + client 16 + server 13），场景驱动/属性/契约组织（编码属性/生命周期/导入回填删除/并发/二进制协议/API/安全）
```

测试计划与用例、历史报告已归档；缺陷跟踪见 [docs/test/defects.md](docs/test/defects.md)（D-01~D-09 全部关闭）。

## 目录结构

```
├── pom.xml                 # parent（Java 25、模块管理）
├── core/                   # 存储引擎（纯 Java）
├── server/                 # Spring Boot 服务 + 管理页面
├── docs/
│   ├── design/             # 设计类文档（design、scenario、client-design、optimization）
│   ├── review/             # 评审报告（review-p0.md、review.md）
│   ├── phaseReport/        # 阶段交付报告（phase-report.md）
│   └── test/               # 测试文档（计划/用例/报告/缺陷跟踪 defects.md）
└── .mvn/maven.config       # Maven 本地仓库指向 .m2-repository
```

## 已知限制

- 不支持标签/维度过滤查询（无标签索引）；
- 不支持更新已写数据（快照不可变）；
- 点集需基本稳定（缓慢增长）；写为分钟级全量快照，非流式实时写入。
