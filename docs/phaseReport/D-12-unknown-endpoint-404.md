# D-12 修复交付：未知 API 端点返回 404

> 交付日期：2026-08-21 · 关联缺陷：[defects.md D-12](../test/defects.md)（新建，未修复→本交付关闭） · 关联文档：[design.md](../design/design.md)
> 说明：按项目规则，本交付记录 D-12 修复与验证；`docs/test/` 缺陷文档状态由测试流程维护，本报告仅作交付记录。

---

## 1. 缺陷回顾

| 项 | 内容 |
|---|---|
| 标题 | 未知 API 端点返回 HTTP 500（INTERNAL_ERROR），应为 404 |
| 严重度 | P2（错误语义错误：HTTP 规范要求未知端点 404；500 会被监控误判为服务故障） |
| 现象 | 黑盒用例 `avUnknownEndpoint404`：`POST /api/notExistEndpoint` 实测返回 **500** 而非 404 |
| 根因 | `ApiExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 全局兜底把 Spring 的 `NoResourceFoundException`（未知端点 404 语义）也映射为 `INTERNAL_ERROR` 500；缺少对 `NoResourceFoundException`（及方法不支持类异常）的显式映射 |

## 2. 修复方案

`server/src/main/java/com/astradb/server/api/ApiExceptionHandler.java`：

- 新增 `NoResourceFoundException` → **404 NOT_FOUND**，错误体沿用结构化格式 `{code, message, timestamp, path}`，新增错误码 `NOT_FOUND`，message 含资源路径（`接口不存在: <path>`）；
- 顺带补充同类错误语义：`HttpRequestMethodNotSupportedException` → **405 METHOD_NOT_ALLOWED**（新增错误码 `METHOD_NOT_ALLOWED`）——同属客户端契约错误，不应落入全局 500 兜底。

```java
@ExceptionHandler(NoResourceFoundException.class)
public ResponseEntity<ApiError> notFound(NoResourceFoundException e, HttpServletRequest req) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError.of(CODE_NOT_FOUND, "接口不存在: " + e.getResourcePath(), req));
}

@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiError.of(CODE_METHOD_NOT_ALLOWED, "方法不支持: " + e.getMessage(), req));
}
```

## 3. 验证

| 项 | 结果 |
|---|---|
| 黑盒用例 `avUnknownEndpoint404`（解除 @Disabled 启用） | ✅ `POST /api/notExistEndpoint` 返回 **404**（含结构化错误体 NOT_FOUND） |
| 黑盒全量 24 项（Availability/Integrity/Security） | ✅ BUILD SUCCESS（0 失败 0 错误） |
| server 集成全量（core+client+server） | ✅ 56 项 BUILD SUCCESS |

## 4. 影响与边界

- 仅影响异常映射层：未知端点/方法不支持的响应码与错误体；正常端点行为不变；
- 结构化错误体兼容既有客户端（`code`/`message` 字段不变，新增 `NOT_FOUND`/`METHOD_NOT_ALLOWED` 两个稳定错误码）；
- 全局 `Exception` 兜底（500 INTERNAL_ERROR）保留，仅不再吞掉 404/405 语义。
