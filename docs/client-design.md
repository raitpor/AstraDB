# AstraDB Client 设计与二进制数据流协议

> 版本：v1.0 · 状态：**规划中（待实施）** · 日期：2026-08-17 · 关联：[design.md](./design.md)、[README.md](../README.md)

## 1. 目标与范围

提供可**集成到其他 Java 应用**的客户端 SDK（`com.astradb.client`），以**专有二进制数据流**（非 CSV/JSON）与 server 交换数据，实现：
- **流式传输**：边编码边发送、边接收边解码（length-prefixed 帧）；
- **更高数据密度**：数值定长编码（INT 4B / LONG 8B / DOUBLE 8B），无文本/引号/分隔符开销；
- **更精确的解析**：列式类型化传输，无双精度损失、无文本歧义。

仅**数据路径**（导入 / 全量查询）走二进制；元数据 API（建表/表信息/统计/段管理）保持 JSON。

## 2. 模块结构

- 根 pom 新增 `<module>client</module>`；`client/pom.xml`（Java 25，依赖 `jackson-databind`，**不依赖 core/server**，可独立打包供外部引用）
- 包结构：
  - `AstraDbClient`：门面（构造 / ingest / queryFullSnapshot）
  - `QueryResult`：`record(String[] columns, List<Object[]> rows)`（行数据对齐列名）
  - `ClientException`：`(code, message)` 结构化错误
  - `protocol.BinaryProtocol`：列式编码/解码（含 `ColumnType` 枚举）
  - `io.BinaryWriter` / `io.BinaryReader`：流式读写原语

## 3. client API

```java
AstraDbClient(String baseUrl)                                     // 无认证（本地/无鉴权部署）
AstraDbClient(String baseUrl, String username, String password)   // Basic 认证（生产开启鉴权）
int ingest(String table, long timestamp, List<List<Object>> data) // 导入，返回 rowCount
QueryResult queryFullSnapshot(String table, long timestamp)       // columns + rows（对齐列名）
```

- **认证配置**：client 可集成到其他应用，构造时可选用户名/密码；内部 `Authorization: Basic base64(user:pass)` 附加**所有请求**（含二进制端点）；401 → `ClientException("UNAUTHORIZED", ...)`，提示检查凭证或部署是否开启鉴权。与 server 侧 `astradb.security.username/password`（或 `ASTRA_DB_SECURITY_USERNAME/PASSWORD`）对应。
- **ingest**：`List<List<Object>>`（行×列）→ 拉取 `getTableInfo` 按 schema 列类型校验（不匹配 → `TYPE_MISMATCH`）→ 列式二进制编码 → `POST /api/importBinary`（`table` query 参数 + `timestamp`）→ 返回 `rowCount`。
- **queryFullSnapshot**：`POST /api/queryFullSnapshotBinary`（JSON 请求体 `{table, ts}`）→ 二进制响应**自带列名/列类型/列数据**（client 无需再查 schema）→ 流式解码组装行（含主键列，主键按 schema 类型转换 INT/LONG→Number、DOUBLE→Double、STRING→String），行数据对齐 `columns`。

## 4. 二进制数据流协议（列式）

### 4.1 帧格式（length-prefixed，可流式）

```
magic(4B 'ASDB') + version(1B=1) + flags(1B: bit0=压缩标记, 预留) + columnCount(2B LE)
对每列: columnName(varint len + UTF-8) + columnType(1B: 1=INT, 2=LONG, 3=DOUBLE, 4=STRING)
rowCount(varint)
对每列数据（列式，与存储列缓冲对齐）:
  INT    : rowCount × int32（LE）
  LONG   : rowCount × int64（LE）
  DOUBLE : rowCount × float64（LE, IEEE754）
  STRING : 逐值 varint 字节长度 + UTF-8 字节
```

### 4.2 设计要点

- **流式**：编码器 `encode(OutputStream)` 增量写；解码器 `decode(InputStream)` 增量读；`rowCount` 前置声明（单次编码；未知行数的流式变体列为开放问题）；
- **精确性**：数值定长二进制，无精度/歧义；列式直通 server 列缓冲（解析即 `Column[]` → `SnapshotData`，跳过行级文本解析）；
- **密度**：INT 4B / LONG 8B / DOUBLE 8B vs 文本 5~15B；无引号/逗号/换行开销；
- **压缩扩展位**：`flags` 预留 bit0（默认 none；后续可挂 JDK Deflater；整数列后续可升级 delta/varint 与存储编码对齐）。

## 5. server 端点（专有新增，不改既有 CSV/JSON 端点）

| 端点 | 行为 |
|---|---|
| `POST /api/importBinary` | 请求体 = 二进制流（`table` 作 query 参数，`timestamp` 同 importSnapshot 语义，可回填任意时间戳）；`BinaryIngestParser` 列式解析 → `Column[]` → `SnapshotData` → core `ingest`（列数/列类型校验兜底）；返回 `{rowCount, newPoints, timestamp}` |
| `POST /api/queryFullSnapshotBinary` | 请求体 JSON `{table, ts}`；`FullSnapshot` → 列式二进制流写响应（含列名/类型/主键列），`Content-Type: application/octet-stream`；无匹配时间点返回空快照（rowCount=0，columns 完整） |

- server 侧组件：`com.astradb.server.ingest.BinaryIngestParser`、`com.astradb.server.api.BinaryDataController`（或并入 DataController）；
- 鉴权开启时同样受保护；错误响应仍回**结构化 JSON**（`{code,message,timestamp,path}`）便于诊断（不混入二进制流）。

## 6. 类型映射与错误处理

| Java 类型 | 协议类型 | 说明 |
|---|---|---|
| `Integer` | INT(1) | int32 |
| `Long` | LONG(2) | int64 |
| `Double`/`Float` | DOUBLE(3) | float64 IEEE754 |
| `String` | STRING(4) | varint 长度 + UTF-8 |

- 类型不匹配 → `ClientException("TYPE_MISMATCH", "第 c 列期望 X，实际 Y")`；
- 网络/超时 → `ClientException("NETWORK_ERROR")`；
- HTTP 非 2xx：优先解析结构化错误体 → `ClientException(code, message)`。

## 7. 测试计划

- **client 单测**：二进制 roundtrip（空/中文/INT 极值/LONG 超 INT/DOUBLE 精度/大行数）、流式分段读写、类型推断与校验、Basic 认证头构造、错误码映射（401/400/网络）；
- **server 单测**：`BinaryIngestParser`（列类型映射、与 CSV 路径导入结果一致、列数/类型不符拒绝、乱序/重复时间戳语义）；
- **server 集成**：MockMvc 二进制 body 的 importBinary / queryFullSnapshotBinary（成功、空快照、鉴权 401）；
- **端到端**：真实 server jar + client 往返（ingest → queryFullSnapshot 行对齐列名、数据与 CSV 路径一致）；**鉴权开启场景**（正确凭证 200 / 错误凭证 401）；
- 全量 `mvn clean test`（core + server + client）。

## 8. 开放问题（实施前确认）

1. 是否需要**未知行数流式导入**变体（当前 rowCount 前置声明、单次编码）；
2. 压缩初始版本是否启用（默认 none，仅留扩展位）；
3. 是否补充 client 批量导入（`ingestBatch`）与单点历史（`queryPointSeries`）的二进制变体——本版本范围外，后续迭代。

## 9. 实施后文档同步

- design.md：14 项目结构加 client 模块、11.1 补两个二进制端点、新增"二进制数据流协议"小节；
- README：client 使用示例（依赖引入、构造、认证配置、ingest/queryFullSnapshot 用法）。
