# LingShu 工程验证已知限制

## 部署与容量

- 当前公开压测数据来自单台 Windows 开发工作站、单 Gateway、单 Core 和 Stub Provider，不代表真实多机 Linux 集群容量或生产 SLA。
- 本机多实例会竞争同一 CPU、内存和网络，只适合验证相对变化、负载均衡与故障行为。
- 真实 Provider 网络延迟、限额、排队和计费成本未包含在现有百万请求数据中。

## 流式链路

- Gateway 使用 Spring Cloud Gateway WebFlux 转发；Core 使用 Spring MVC `SseEmitter`、虚拟线程以及 Java HttpClient 阻塞式上游输入流，不是全链路 Reactive 实现。
- Provider 增量能够边读边发，客户端断连会触发上游响应体关闭；首块前允许 fallback，首块后禁止跨 Provider 混流。
- Core 设置两分钟 SSE 超时和单响应累计字符上限。2条8 MiB慢消费者模拟已观察到压力传播至Core并完整结束，但4条1 MiB响应可被中间缓冲吸收；尚未证明缓冲上限、长期堆稳定性或生产网络下的背压行为。
- Provider 上游连接在没有`[DONE]`时关闭会被判为失败；Provider级回归测试和Core/Gateway端到端Stub截断注入均已通过，但尚未对真实Provider和网络代理执行断流演练。
- 历史压测器记录 TTFT，但旧版流式成功判定没有强制校验 `[DONE]`。新版本已经采用严格终止判定，历史结果需要同参数复测。
- 只有调用显式取消接口的请求才标记为`client_cancel_requested`；普通连接提前关闭标记为`client_disconnected`或`downstream_write_failed`，不推断用户主观意图。
- 多Core显式取消已实现Redis `traceId → owner`原子登记和实例间HTTP转发，并在本机双Core进程中实测通过；这证明路由与取消链路，不代表真实跨主机网络分区下的生产SLA。
- 分布式流路由必须显式配置唯一实例ID、可达的实例Base URL和内部共享密钥。Redis查询失败或owner不可达时返回503，不会伪报取消成功。
- owner实例崩溃时原SSE连接本身已经丢失；Redis归属记录会保留到TTL，期间取消请求可能收到503或404。当前没有把“owner已死亡”包装成“取消成功”。

## 缓存与语义评估

- 29.6% 的 P95 降幅来自本地内存精确缓存和 Stub Provider，不代表 Redis或语义缓存收益。
- 语义缓存目前只有小样本离线阈值评估，不能给出生产误命中率结论。
- 语义缓存收益取决于Embedding延迟、Provider延迟、命中率和误命中成本，需要单独对照测试。

## 预算、消息与配置

- 当前预算链路尚未实现按实际 Token 的差额释放/补扣，不能描述为完整实际用量结算。
- Redis原子预扣、重复reservation、Ledger并发确认/释放与Outbox唯一事件已完成真实Redis/PostgreSQL集成测试；尚未验证Redis或PostgreSQL在两阶段操作中途故障时的跨依赖一致性。
- Redis reservation TTL与Ledger到期扫描使用相同到期基准，依赖故障或扫描延迟下是否能可靠返还过期预扣仍需专项验证。
- Provider缺失usage时会保留`ESTIMATED`来源，但估算Token仍不等于Provider实际账单，后续结算需要账单对账或Provider usage回补。
- `usage_source`已在本地PostgreSQL 16完成Flyway V2→V4升级和JDBC读写验证，但尚未在生产快照或大数据量迁移场景验证锁等待与迁移耗时。
- Outbox 与消费者幂等已有实现基础，但重复投递、积压和依赖故障仍需系统演练。
- Nacos 使用周期刷新，多实例可能短暂读取不同版本；本地测试只能验证该窗口内的行为，不能证明生产一致性。

## 结论使用范围

- 文档中的结论应标注为实测、模拟、单测或未验证。
- 模拟故障用于验证代码路径，不等价于真实网络分区、云负载均衡或生产依赖故障。
- 真实业务 Prompt 分布、生产资源规格及历史个人贡献归属不在本验证包的证明范围内。
