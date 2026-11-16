# LingShu 流式处理与专项测评

## 实现链路

```text
Client
  │ POST /v1/chat/completions, stream=true
  ▼
Gateway (Spring Cloud Gateway / WebFlux)
  │ 透传请求与 SSE 响应
  ▼
Core ChatCompletionController
  │ SseEmitter，2 分钟超时，每请求一个虚拟线程任务
  ▼
Processor Pipeline 前半段
  │ 租户策略 → PII → 精确缓存 → 语义缓存 → 路由
  ├─ 缓存命中：重建 SSE delta → usage/metadata 末帧 → [DONE]
  ▼
ModelProvider.stream
  │ Java HttpClient InputStream 逐行解析上游 data 帧
  │ content/tool_calls 增量立即下发，不等待完整响应
  ▼
Processor Pipeline 后半段
  │ 缓存写入 → 计费 → 指标
  ▼
usage/metadata 末帧 → data: [DONE]
```

## 关键工程边界

- DeepSeek和OpenAI兼容Provider请求 `stream=true` 与 `stream_options.include_usage=true`。
- 内容和工具调用参数分片按到达顺序转发，同时组装最终响应供缓存、计费和审计使用。
- 首个有效内容或工具调用增量记录TTFT。
- Provider缺失usage时使用确定性本地Token估算，并在API metadata、指标和账务记录中标记`ESTIMATED`；聚合数据来源混合时标记`MIXED`。
- Provider输出受最大累计字符数约束，默认每个响应1,000,000字符。
- 上游连接未发送`[DONE]`就关闭时判定为失败，不允许Core把截断响应包装为正常完成。
- 客户端写入失败、SSE超时或连接完成会设置取消标记，并关闭当前上游响应体。
- 首个增量发出前可以切换候选Provider；发出后失败立即终止，避免不同模型内容拼接。
- Fallback边界是第一个业务增量成功交给`SseEmitter`，不是HTTP响应头是否committed；SSE握手提交HTTP 200不应禁用首块前Fallback。
- 缓存命中仍返回OpenAI兼容chunk、usage/metadata末帧和`[DONE]`。
- 流终止指标区分`completed`、`client_cancel_requested`、`client_disconnected`、`timeout`、`upstream_truncated`、`upstream_error`、`downstream_write_failed`和`internal_error`，并提供全局活跃流Gauge。
- 客户端可调用`POST /v1/stream-cancellations/{traceId}`显式取消流；接口校验租户归属、重复调用幂等，并通过原子终态保证取消与正常完成只记录一个结果。
- 进程内注册表仅短期保留终态并将记录数限制在约10,000条；只淘汰终态，不淘汰仍在运行的流，避免高吞吐下形成无界trace缓存。
- 多Core模式使用Redis `SET NX + TTL`原子登记`traceId → instanceId/baseUrl/tenantId`；取消落到非owner实例时，经受内部共享密钥保护的HTTP接口转发到owner。
- Redis或owner转发失败统一返回503 `STREAM_ROUTING_UNAVAILABLE`，不会把未送达的取消请求报告为成功。分布式模式缺少唯一实例ID或内部密钥时启动失败。

## 当前证据

| 能力 | 证据类型 | 结论 |
|---|---|---|
| 首块在Provider完成前到达 | Provider测试 | 单测已验证 |
| OpenAI兼容chunk、usage末帧、`[DONE]` | Core端到端测试 | 单测已验证 |
| 工具调用增量组装 | Provider测试 | 单测已验证 |
| usage缺失本地估算 | Provider测试 | 单测已验证 |
| Usage来源进入API和虚拟账务 | DTO、计费与Core集成测试 | 单测已验证 |
| 客户端断连停止读取 | Provider测试 | 单测已验证 |
| 首块前Provider fallback | Core集成测试、Gateway→Core故障注入 | 单测和本地进程实测通过；只输出备用Provider内容，attempt顺序为`stub → stub-fast` |
| 50%流式百万请求 | 单机Stub压测 | 实测，但旧成功口径需严格复测 |
| 0%/50%/100%流式各三轮万级请求 | 单机Stub严格压测 | 共90,000请求，全部成功；所有流均收到`[DONE]` |
| 五分块固定10 ms块延迟 | 单机Stub严格压测 | 15,000流全部成功；ITL P95/P99中位数23.5/30.0 ms |
| 慢消费者与显式背压 | 2个8 MiB响应、客户端暂停3秒后限速读取 | 模拟实测通过；暂停时2条Core流仍活跃，最终完整收到256块和`[DONE]`，结束后活跃流归零 |
| 上游缺失`[DONE]` | Provider测试、Gateway→Core截断注入 | 单测和本地进程实测通过；收到2块后返回`STREAMING_ERROR`且无`[DONE]` |
| 流终止原因和活跃流指标 | Micrometer测试、截断与慢消费者注入 | 单测和本地进程实测；截断记录`upstream_truncated=1`，所有场景结束后活跃流归零 |
| 显式取消与重复取消 | 生命周期注册表、Controller测试、慢速Stub SSE | 单Core逻辑单测及本地双Core进程实测通过 |
| 显式取消时延与指标 | 单Core慢速Stub，重复5次 | 接口耗时中位数9.589 ms，流停止15.156 ms；活跃流归零 |
| 跨Core取消路由 | Redis owner索引、内部HTTP转发、双Core进程 | 本地容器Redis + 两个Core进程实测通过；首次202、重复200、无`[DONE]` |
| Redis/owner故障不伪报成功 | Redis与HTTP故障测试 | 单测验证返回路由异常；真实网络分区待演练 |
| Usage来源数据库迁移 | PostgreSQL/Flyway/JDBC集成测试 | 本地容器实测通过 |
| 首块后断流端到端判定 | Gateway→Core→Stub故障注入 | 本地进程实测通过；不fallback、不发送成功metadata、不发送`[DONE]` |
| 多实例滚动重启中的长连接 | 跨实例取消已验证 | 强制重启与连接排空仍未验证 |

双Core实测使用Core A `18081`持有慢速Stub SSE、Core B `18082`接收显式取消，运行：

```powershell
.\scripts\python.ps1 .\scripts\test-cluster-stream-cancel.py
```

验收脚本要求首次取消为202、重复取消为200、SSE包含`STREAM_CANCELLED`、不包含`[DONE]`且连接在10秒内结束。

流式故障和慢消费者复现命令：

```powershell
.\scripts\python.ps1 .\scripts\test-stream-faults.py --scenario fallback
.\scripts\python.ps1 .\scripts\test-stream-faults.py --scenario truncation
.\scripts\python.ps1 .\scripts\test-slow-stream-consumer.py
```

故障脚本要求首块前失败只能输出备用Provider内容并正常结束；首块后截断必须保留已发送增量、输出SSE错误且缺失`[DONE]`。慢消费者脚本默认并发2条约8 MiB响应，先暂停读取3秒，再按4 KiB/2 ms限速读取；它验证大响应下压力可传播到Core和最终协议完整性，但不证明堆内存存在严格常量上界。

## 压测输出

`scripts/load-test-large.py`现在分别输出总体、流式和非流式结果。流式部分包含：

- 严格成功率：必须收到`[DONE]`。
- 完成流吞吐量：每秒正常结束的SSE连接数。
- TTFT P50/P95/P99。
- 端到端延迟P50/P95/P99，以收到`[DONE]`为终点。
- ITL P50/P95/P99。
- 总数据块数、每个成功流平均数据块数和最大数据块数。
- 中途断流及SSE error归入错误类型，不再因HTTP 200被算作成功。

正式报告至少分别执行0%、50%、100%流式三组测试，每组重复三次并报告中位数。
