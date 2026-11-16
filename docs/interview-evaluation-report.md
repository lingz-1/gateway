# LingShu 面试关键能力测评报告

## 结论

本轮测评只验证实习面试最容易被追问的三个P0能力：流式故障处理、Provider容错和预算账务一致性。没有继续建设完整生产验收平台，也没有重复已有百万级容量测试。

| 能力 | 验证层级 | 结果 | 可使用结论 |
|---|---|---:|---|
| 首块前Fallback | Gateway→Core→Stub进程级 | 30/30通过 | 冷路由状态下主Provider首块前失败可切换备用Provider，不混入失败内容 |
| 首块后截断 | Gateway→Core→Stub进程级 | 30/30通过 | 已发送增量后不再Fallback，返回SSE错误且不发送`[DONE]` |
| 显式取消 | Gateway→Core→慢速Stub进程级 | 30/30通过 | 首次取消生效、重复取消幂等、流不发送`[DONE]` |
| Provider重试 | 本地HTTP桩组件级 | 30/30通过 | 瞬时500后按配置重试并成功，失败尝试usage被累计 |
| Provider熔断 | 本地HTTP桩组件级 | 30/30通过 | 达到阈值后熔断打开，后续请求不访问上游 |
| Provider恢复 | 本地HTTP桩组件级 | 30/30通过 | 冷却窗口结束后允许探测，成功后恢复UP状态 |
| Redis预算预扣 | 真实Redis集成级 | 10轮通过 | 并发预扣不超卖，重复reservation不重复扣减 |
| PostgreSQL结算状态 | 真实PostgreSQL集成级 | 确认10轮、释放10轮通过 | 30并发竞争时只有一次状态迁移和一条Outbox事件 |
| 虚拟余额扣费 | 真实PostgreSQL集成级 | 通过 | 并发扣费保持原子性，重复trace不重复扣费 |

以上结论是本机模拟或集成测试结论，不代表真实DeepSeek、跨主机集群或生产SLA。

## 流式故障批量结果

### 首块前Fallback

每轮均重启服务以清空自适应路由状态，然后让主Stub在第一个业务增量前失败。30轮均满足：

- HTTP握手成功；
- 只收到`stub-fast`内容；
- `providerAttempts`严格为`["stub", "stub-fast"]`；
- 没有SSE error；
- 正常收到`[DONE]`。

一次先导运行曾采用“单次启动后连续请求30次”的错误假设：第1次触发Fallback后，路由器对失败Provider增加惩罚，后29次直接选择健康Provider。业务请求全部成功，但严格的attempt顺序断言只通过1次。最终测评通过逐轮冷启动修正了口径；先导结果不计入30/30正式结论。

### 首块后截断

主Stub配置为5个增量，在发送2个增量后截断。连续30次均满足：

- 客户端保留已经收到的2个增量；
- 随后收到`STREAMING_ERROR`；
- 不切换到备用Provider；
- 不发送成功metadata和`[DONE]`；
- 请求不能被统计为成功流。

### 显式取消

慢速Stub配置为5个增量、每块200 ms。30次均满足首次取消返回202、重复取消返回200、SSE包含`STREAM_CANCELLED`且不包含`[DONE]`。

| 指标 | P50 | P95 | 最大值 |
|---|---:|---:|---:|
| 取消接口延迟 | 5.805 ms | 25.222 ms | 28.416 ms |
| 取消响应后流停止耗时 | 4.795 ms | 24.780 ms | 28.288 ms |

这些时延来自同机Gateway/Core/Stub，不应外推到跨主机集群。

## Provider容错结果

Provider测试使用本地HTTP桩，不调用真实DeepSeek API。三个场景各独立重复30次：

1. 首次返回临时500，第二次成功，验证重试次数和失败usage累计。
2. 失败阈值设置为1，首次失败后熔断变为DOWN；熔断期间调用直接拒绝且HTTP桩请求计数不增加。
3. 熔断冷却20 ms后将HTTP桩恢复为200，探测请求成功，Provider健康状态恢复为UP。

`DeepSeekModelProviderTest`本轮共执行102个用例，失败0、错误0，其中上述容错重复用例共90个。

## 预算账务结果

### Redis原子预扣与余额守恒

并发预扣场景重复10轮。每轮初始余额1,000单位，同时发起200个不同reservation、每个预扣10单位，结果严格为100个成功、100个拒绝，余额为0。随后对一半reservation执行重复确认、另一半执行重复释放，最终余额严格恢复到500。

重复reservation场景重复10轮。每轮64个并发请求使用相同reservation预扣10单位，只发生一次实际扣减；随后64个并发释放只退款一次，余额恢复到初始值。

测试同时发现并修复了一个真实问题：旧Lua脚本只检查reservation是否为`PENDING`，没有校验原租户和金额，导致不同租户或金额重放同一`reservationId`时可能被误报为幂等成功。现在只有tenant和units均一致时才接受幂等重放。

### Ledger与Outbox幂等

确认和释放分别重复10轮。每轮由30个线程竞争同一`reservationId`，只有一个线程成功把状态从`PENDING`转换为`CONFIRMED`或`RELEASED`，数据库中只生成一条对应Outbox事件，后续重复结算返回false。

现有虚拟余额测试还验证了20个并发唯一扣费和同一trace重复写入：余额只扣一次，最终余额与请求数一致。

## 证据与复现

轻量Runner读取根目录的`evaluation-manifest.yaml`，只负责运行现有脚本、保存逐轮JSON并生成摘要，不承担完整监控平台职责。

```powershell
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite stream-fallback-30
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite stream-truncation-30
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite stream-cancellation-30
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite provider-resilience-30
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite budget-consistency
```

正式原始结果位于：

- `E:\LingShuData\evaluations\20261116-152443`：Fallback 30次；
- `E:\LingShuData\evaluations\20261116-152052`：截断与取消各30次；
- `E:\LingShuData\evaluations\20261116-154242`：Provider容错；
- `E:\LingShuData\evaluations\20261116-154857`：预算账务。

## 面试表述边界

可以表述：

> 使用轻量批量Runner复现关键故障场景；流式Fallback、截断和取消各重复30次全部通过。Provider重试、熔断和恢复使用本地HTTP桩各验证30次。预算模块在真实Redis/PostgreSQL上验证并发原子预扣、幂等状态迁移和余额一致性，并修复了reservation跨租户/金额冲突重放问题。

不能表述：

- Provider容错已在真实DeepSeek故障或生产网络中压测；
- 当前预算已经支持按实际Token差额退款或补扣；
- Redis、PostgreSQL中途不可用时已经证明跨依赖事务一致性；
- 这些结果代表生产SLA或真实多机集群容量。

## 尚未纳入本轮的工作

- P1缓存四组同口径对照；
- P1语义缓存扩样与误命中评估；
- P2百万请求三轮、长稳、集群和依赖故障；
- 按实际Token进行差额释放或补扣；
- Redis reservation过期与Ledger扫描之间的故障窗口验证。
