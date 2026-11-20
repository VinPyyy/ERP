# 异常与 HTTP 状态码语义

> 2026-11-21 修正落地。此前兜底异常返回 HTTP 200 + code 500，服务器故障在网关/监控/重试策略眼里与业务成功无异——本次把传输层状态码还给传输层，业务码封套约定不变。

## 1. 动机

响应封套 `R{code,msg,data}` 承担业务语义，HTTP 状态码承担传输语义，两者分工而非互相吞并。原实现把"未处理异常"也压进 200，导致：

- nginx/负载均衡错误率统计、告警全部失明；
- 客户端重试/熔断策略无法区分"业务拒绝"（不该重试）与"服务器故障"（可重试）；
- 冒烟里 `id="null"` 这类客户端错误也被记成 500 语义（本次一并细分）。

## 2. 契约（GlobalExceptionHandler，spec §5.1 修订）

| 异常 | HTTP | 封套 code | 说明 |
|---|---|---|---|
| `BizException` | **200** | 业务码（10001/10002/40001…） | 业务结果是正常应答，不是传输失败；前端拦截器按 code≠0 toast |
| `MethodArgumentNotValidException` | 200 | 10001 + 首条字段错误 | 参数校验属业务交互反馈 |
| `MethodArgumentTypeMismatchException` | **400** | 10001 | 路径/参数类型错是客户端错误，不记服务器故障 |
| `NoHandlerFound/NoResourceFoundException` | **404** | 60003 | 路由不存在是客户端错，此前落兜底被记成 500 |
| `NotLoginException` | 401 | 10401 | 前端清会话跳登录 |
| `NotPermission/NotRoleException` | 403 | 10403 | toast 无权限 |
| 其余 `Exception` 兜底 | **500** | 500 | msg 固定"服务器内部错误，请联系管理员"；堆栈只进日志 |

前端（`frontend/src/api/http.ts`）无需改动：非 2xx 分支已读 `response.data.msg` 展示封套文案，401/403 处理不变。

## 3. 代码地图

- `common/exception/GlobalExceptionHandler.java` — 六个 handler
- `src/test/java/com/wille/erp/common/exception/GlobalExceptionHandlerTest.java` — standalone MockMvc 契约 6 例（不起上下文、不依赖库）

## 4. 验证记录（2026-11-21）

- `mvn test` **221/221**（原 220 + 类型不匹配契约 1 例；兜底契约断言从 `status().isOk()` 改为 `isInternalServerError()`）
- 冒烟回归：auth 12/12、inventory 15/15、purchase 10/10 全过（raw fetch 解析封套，不受状态码影响）
- 线上抽验：`GET /api/purchase/inbounds/abc`（带 token）→ HTTP 400 + code 10001

## 5. 已知边界

- 冒烟脚本用原生 fetch 不看 HTTP 状态、只看封套 code——语义上正确（封套才是契约面）。
- `HttpMessageNotReadableException`（请求体 JSON 语法错）目前落入兜底 500；语义应为 400，M5 若前端联调踩到再补 handler。
- Sa-Token 过滤器层抛出的 401/403 不经本 advice 的路径（过滤器先于 MVC）——现网行为与 handler 语义一致，未动。
