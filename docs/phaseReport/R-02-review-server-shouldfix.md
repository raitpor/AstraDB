# R-02 修复交付：review 5.1 should-fix（SS-1 ~ SS-10）

> 交付日期：2026-08-21 · 关联文档：[review.md](../review/review.md)（5.1 should-fix 清单）
> 说明：按项目规则，本交付记录 review 5.1 server/client should-fix 全部 10 项（SS-1~SS-10）的修复与验证；不修改 `docs/design` 与 `docs/test`。

---

## 1. 修复总览

| ID | 问题 | 修复方案 | 位置 | 状态 |
|---|---|---|---|---|
| SS-1 | createTable 缺 `columns` 字段 → NPE → 500 | `columns` 为空时抛 `IllegalArgumentException` → 400 INVALID_ARGUMENT | `TableController.createTable` | ✅ |
| SS-2 | 二进制协议解码 RuntimeException → 500 | `BinaryReader.readString` 校验 varint 长度区间（≤64MB，防负数强转/超大分配）；`BinaryIngestParser` catch `RuntimeException` 一并转 `IngestException` → 400 INGEST_REJECTED | `BinaryReader`、`BinaryIngestParser` | ✅ |
| SS-3 | 密码 `{noop}` 明文 + 默认弱口令 | 移除 `{noop}` 前缀，明文密码经 DelegatingPasswordEncoder（默认 BCrypt）编码存储，配置值带 `{prefix}` 则原样使用；默认口令 `admin123` 启动 WARN 告警 | `SecurityConfig` | ✅ |
| SS-4 | 管理台存储型 XSS（innerHTML 注入） | `app.js`/`table.html` 新增 `esc()`（HTML 实体转义）统一转义表名/段路径/主键/列值/列名；删除类操作改 `data-act` + 事件委托（消除 onclick 内联字符串参数注入面）；`AstraDB.validateName` 禁 `'` `"` `<` `>`（纵深防御） | `app.js`、`table.html`、`AstraDB.validateName` | ✅ |
| SS-5 | CSV 未闭合引号吞行 → 静默错数据 | 引号内遇到 `\n`/`\r` 或 EOF 时报 `IngestException`（未闭合引号 = 格式错误，RFC 4180） | `CsvParser` | ✅ |
| SS-6 | 无界队列 + trim 可能删除 RUNNING + 无关闭钩子 | `ThreadPoolExecutor` + `ArrayBlockingQueue(100)` 有界队列，满则 400；trim 只清理已结束任务、RUNNING 永不裁剪；新增 `@PreDestroy` 优雅停池 | `ImportTaskService` | ✅ |
| SS-7 | importAsync "大文件不阻塞请求"与实现不符 | CSV 解析移入后台线程：`submit` 改收 CSV 字节，后台 parse + ingest；请求线程仅读字节 + 表存在性前置校验 | `ImportTaskService`、`DataController.importAsync` | ✅ |
| SS-8 | HealthController 未认证泄露 dataDir | 移除 `dataDir` 字段，仅保留 `dataDirWritable` 布尔（不泄露文件系统布局） | `HealthController` | ✅ |
| SS-9 | 上传超限异常未映射为 4xx | `MaxUploadSizeExceededException` → **413** PAYLOAD_TOO_LARGE 结构化错误体 | `ApiExceptionHandler` | ✅ |
| SS-10 | client `escape()` 不完整 → 控制字符 key 生成非法 JSON | 4 处 JSON 拼接改用 `ClientJson.quote`（含控制字符 `\uXXXX` 转义），删除不完整的 `escape()` | `AstraDbClient` | ✅ |

## 2. 关键实现说明

### 2.1 SS-3 密码存储
- 配置值语义：带编码器前缀（如 `{bcrypt}$2a$...`）→ 原样使用；否则视为明文 → `PasswordEncoder.encode`（DelegatingPasswordEncoder 默认 BCrypt）后存储，不再出现 `{noop}` 明文；
- 默认口令 `admin123` 仅在配置未被覆盖时打印 WARN，不阻断本地开发默认关闭鉴权的场景。

### 2.2 SS-4 前端防御
- 文本插值（`${name}`、`${r.key}`、`${v}`、`${s.path}`、列名）全部经 `esc()`（`& < > " '` 五实体）；
- 按钮操作参数不再用 `onclick="fn('${x}')"`（单引号可逃逸），改为 `data-act` + 文档级事件委托，参数经 HTML 实体转义的 `data-*` 传递，浏览器读取 `dataset` 自动解码；
- `validateName` 新增禁用 `'` `"` `<` `>`（表名创建即拦截，纵深防御）。

### 2.3 SS-2 二进制帧防护
`readString` 先以 `long` 接收无符号 varint，校验 `0 ≤ len ≤ 64MB` 后再收窄为 `int`，从根上消除 `(int) varint` 负数 → `NegativeArraySizeException`；同时覆盖 SO-4 的字符串大分配面。

### 2.4 SS-6/SS-7 异步导入
- 有界队列上限 100，`submit` 在 `RejectedExecutionException` 时移除占位任务并抛 400 语义异常（客户端可重试）；
- 异步任务改为后台「解析 CSV → ingest」，请求线程仅 `file.getBytes()` 读取字节（multipart 临时文件在请求结束后会被容器清理，故字节读取须在请求线程内完成）。

## 3. 测试

| 项 | 结果 |
|---|---|
| `ApiContractTest` | ✅ 新增 createTable 缺 columns → 400 INVALID_ARGUMENT 断言；既有 batchAndAsyncImport（importAsync 提交/轮询）回归通过 |
| `BinaryEndpointTest` | ✅ 新增 varint 0xFFFFFFFF 帧 → 400 INGEST_REJECTED（旧实现为 NegativeArraySizeException → 500）；独立建表避免方法顺序依赖 |
| `CsvParserTest`（新建 4 项） | ✅ 未闭合引号（引号内换行/EOF）→ IngestException；含逗号/转义引号的引号字段正常解析；普通行解析 |
| `UploadLimitTest`（新建） | ✅ MaxUploadSizeExceededException → 413 + PAYLOAD_TOO_LARGE（MockMvc multipart 不经容器级大小限制，故直接单测 handler 映射） |
| `ClientContractTest` | ✅ 新增含换行/引号/反斜杠 key 与制表符表名的请求体 JSON 转义校验 |
| `SecurityContractTest` | ✅ BCrypt 编码后登录认证回归通过 |
| **全量 `mvn test`** | ✅ **70 项**（core 41 + client 16 + server 13）BUILD SUCCESS，0 失败 0 错误 |

## 4. 影响与边界

- 行为变更（预期内）：
  - 上传超过 `spring.servlet.multipart` 上限 → 413（原 500）；createTable 缺 columns → 400（原 500）；
  - 二进制损坏/恶意帧 → 400（原 500）；CSV 未闭合引号 → 400 格式错误（原静默吞行）；
  - 异步导入排队满 → 400 提示稍后重试；异步导入的 CSV 解析改在后台线程完成（请求响应更快，解析错误在任务状态中体现）；
  - 表名不再允许 `' " < >`（创建时拦截）；管理台渲染数据不再执行注入 HTML。
- 未改动：`docs/design`、`docs/test`；core should-fix（SF-1~SF-8）已于 R-01 交付，本报告仅含 5.1（SS）清单。
