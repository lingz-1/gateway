# LingShu 验证目标与统计口径

## 目标

本验证包用于复现 LingShu 在本地单实例和多实例逻辑集群中的功能、性能与故障行为，不用于证明生产 SLA 或真实多机 Linux 集群容量。每项结论必须标注为“实测、模拟、单测、未验证”之一。

## 核心指标口径

| 指标 | 统一定义 | 采集方式 | 当前状态 |
|---|---|---|---|
| 总 QPS | 在测试墙钟时间内正常完成的请求数/秒；SSE 按完整流计一次，不按数据块计数 | `load-test-large.py` | 实测 |
| 非流式成功 | HTTP 2xx，响应体可解析为合法 JSON | 压测客户端 | 实测 |
| 流式成功 | HTTP 2xx、未收到 SSE error，且收到终止帧 `data: [DONE]` | 压测客户端 | 0%/50%/100%矩阵实测 |
| 成功率 | 成功请求数/实际发起请求数；4xx、5xx、超时、解析错误、中途断流均为失败 | 压测客户端 | 严格口径实测 |
| TTFT | 客户端发出请求至收到第一个非空 `delta.content` 或 `delta.tool_calls` 的时间 | 客户端时钟；Core 同时记录 Micrometer timer | 实测 |
| 流式端到端延迟 | 客户端发出请求至收到 `[DONE]` 的时间 | 压测客户端 | 万级三轮实测 |
| 非流式端到端延迟 | 客户端发出请求至完整 JSON 响应读取完毕的时间 | 压测客户端 | 实测 |
| ITL | 同一响应相邻有效内容/工具调用数据块到达客户端的间隔 | 压测客户端 | 五分块慢速Stub三轮实测 |
| 流式完成吞吐 | 正常收到 `[DONE]` 的流数量/测试墙钟时间 | 压测客户端 | 万级三轮实测 |
| 缓存命中率 | `EXACT` 或 `SEMANTIC` 完成请求数/可参与缓存的完成请求数 | 响应 metadata 与服务端指标 | 部分实测 |
| Provider 尝试失败率 | 失败的 Provider 尝试次数/全部 Provider 尝试次数，重试和 fallback 分别计次 | Provider 尝试级指标 | 未实现 |
| Usage来源 | `PROVIDER_REPORTED`、`ESTIMATED`、`MIXED`或`UNKNOWN`；估算值不得表述为Provider实际账单 | API metadata、账务记录与`lingshu_chat_usage_requests_total` | 单测已验证 |
| 流终止原因 | 完成、显式取消、连接断开、超时、上游截断/错误、下游写失败、内部错误分别计数 | `lingshu_chat_streams_terminated_total{reason}` | 单测已验证；显式取消和上游截断已完成进程实测 |
| 跨Core显式取消 | 任意Core接收取消，经Redis owner索引转发到持有上游连接的Core | `test-cluster-stream-cancel.py`、Redis、双Core进程 | 本机双进程实测通过 |
| Usage账务迁移 | 旧库升级后新增`usage_source`且新账务可持久化来源 | PostgreSQL 16、Flyway V2→V3→V4、JDBC集成测试 | 本地容器实测 |
| 故障恢复时间 | 从故障注入时刻到连续成功率、延迟恢复至验收阈值的时间 | 故障脚本、压测时间线与 Prometheus | 未验证 |
| 流式P0批量故障 | Fallback、截断、显式取消各重复30次 | 轻量Runner、逐轮JSON | 三组均30/30通过 |
| Provider P0容错 | 重试、熔断打开、冷却恢复各重复30次 | 本地HTTP桩、Surefire报告 | 三组均30/30通过；非真实DeepSeek |
| 预算P0一致性 | Redis并发预扣/重复reservation、JDBC并发结算/Outbox、虚拟余额 | 真实Redis/PostgreSQL集成测试 | 全部通过；不包含实际Token差额结算 |

## 流式专项矩阵

| 场景 | 关键变量 | 主要观察项 | 验收重点 |
|---|---|---|---|
| 流式比例对照 | 0%、50%、100% SSE | 总 QPS、流式完成吞吐、TTFT、端到端 P95/P99 | 三组数据分别报告，不混合解释 |
| Provider 首块延迟 | 固定 0/50/200/1000 ms | TTFT、超时率 | TTFT 随注入延迟变化且口径稳定 |
| Provider 块间延迟 | 固定/抖动分布 | ITL P50/P95/P99、端到端延迟 | 能区分首块慢与生成过程慢 |
| 首块前失败 | 429、5xx、超时、断连 | 重试、熔断、fallback、失败 usage | Stub异常注入实测通过；可切换 Provider，不向客户端混入失败 Provider 内容 |
| 首块后断流 | 输出若干块后断连 | 中途断流率、终止事件、是否错误 fallback | Stub截断注入实测通过；不跨 Provider 拼接内容，不得计为成功 |
| 客户端取消 | 首块前/后主动关闭 | 上游连接关闭、许可释放、取消耗时 | 不继续读取和累计无界响应 |
| 显式取消接口 | 首次、重复、跨租户、取消/完成竞态 | HTTP状态、上游cancel、唯一终态 | 首次202、重复幂等、跨租户404、CAS只保留一个终态；慢流5次实测通过 |
| 跨实例显式取消 | 流在Core A、取消落到Core B | Redis owner、内部转发、SSE终止帧 | 首次202、重复200、出现取消事件且不得出现`[DONE]` |
| 慢消费者 | 暂停后限制客户端读取速度 | Core 堆、活跃流、线程、超时 | 2条8 MiB响应模拟实测压力传播与完整结束；堆长期稳定性仍未验证 |
| 长响应 | 不同块数和内容长度 | 内存、ITL、最大响应限制 | 超过字符上限前拒绝继续下发 |
| usage 缺失 | Provider 不返回 usage | 估算 Token、账单标记 | 使用确定性本地估算并明确误差边界 |
| 流式缓存命中 | 内存/Redis/语义缓存 | TTFT、SSE协议、metadata、`[DONE]` | 命中后仍保持 OpenAI 兼容终止帧 |
| 滚动重启 | 多实例逐个下线 | 新流分配、已有流中断率、恢复时间 | 区分连接排空与强制终止 |

## 报告规则

- 混合负载报告必须同时给出总体、流式、非流式三个分组，不能只给总体 P95。
- SSE 的 HTTP 200 只代表握手成功；没有 `[DONE]` 的响应必须计为失败。
- TTFT 只统计第一个有效内容或工具调用增量，不把空 role、usage 或 metadata 帧算作首 Token。
- Provider未返回usage时，响应metadata和账务记录必须保留`ESTIMATED`来源；聚合数据包含不同来源时标记为`MIXED`。
- 历史百万级数据用于描述旧口径下的核心转发上限；严格口径目前已完成万级三轮复测，百万级仍需按新口径复测。
- Stub、故障模拟器、真实 Provider 的结果分开报告，不互相外推。
