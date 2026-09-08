# LingShu 开发计划

## 1. 实施策略

采用“Conda 工具链 + Docker Compose 基础设施”的组合：

- Conda `lingshu-dev`：OpenJDK 21、Maven 3.9、Python 3.12。
- Docker Compose：Redis、PostgreSQL/pgvector、Kafka、Nacos、Prometheus、Grafana。
- Maven 多模块：`lingshu-common`、`lingshu-gateway`、`lingshu-core`。
- 默认测试使用 Stub Provider，不依赖真实模型和密钥。

## 2. 阶段计划

### 阶段 0：工程基线

- 创建父 POM 和三个 Maven 模块。
- 固定 Java、Spring Boot、Spring Cloud、Spring AI 版本。
- 建立格式检查、单元测试、错误契约和配置分层。
- 建立 Stub Provider 与集成测试基线。

验收：`mvn verify` 在无 API Key、无外部服务条件下通过。

### 阶段 1：最小纵向链路

- Gateway：TraceId、API Key 鉴权骨架、基础路由。
- Core：兼容 `/v1/chat/completions` 的非流式端点。
- Processor Engine：Trace、Router、ProviderInvoke。
- Provider：Stub 基线已完成；DeepSeek OpenAI 兼容 Provider 已接入，支持 `.env` 配置并将 `deepseek-v4flash` 兼容转换为 API 支持的 `deepseek-v4-flash`。
- Router：实现确定性的规则路由及失败错误契约。

验收：同一端点可根据输入切换 Provider；调用顺序、路由结果、耗时和错误均可追踪。

### 阶段 2：语义缓存

- 精确缓存：规范化请求后计算 SHA-256，按租户和 Prompt 版本隔离。
- 精确缓存进度：内存与 Redis 两种存储已实现；Redis 使用 TTL、故障开放策略和租户隔离键。
- 语义缓存：PostgreSQL 16 + pgvector，使用元数据过滤和相似度阈值。
- 语义缓存进度：pgvector 存储、TTL、租户/模型/采样参数隔离和开发桩嵌入器已实现。
- 已绑定本地 Qwen3-Embedding-4B（BF16，2560 维）和 Hugging Face TEI CUDA 服务；模型目录固定为 `E:\LingShuData\models\Qwen3-Embedding-4B`，服务监听 `127.0.0.1:8090`。
- 已实现 OpenAI 兼容 Embeddings HTTP 适配器和 TEI `/embed` 适配器，包含超时/维度校验和故障开放；不需要真实 API Key。
- 已完成 DeepSeek 真实请求验收：`deepseek-v4flash` 配置经兼容转换后返回 HTTP 200；本地 Qwen 嵌入、pgvector 语义缓存与 Gateway 路由均已联通。
- 已完成 2560 维语义缓存迁移：新表使用 `halfvec(2560)`，建立 HNSW 余弦索引；旧的混合维度表保留作回退，不参与新模型查询。
- 嵌入提供者和向量维度纳入缓存作用域；固定生产模型维度后再建立 HNSW 索引。
- 异步回写、TTL、缓存失效和防误命中测试。
- Reranker 作为可选增强，不阻塞基础语义缓存。

验收：精确命中、语义命中、未命中三条路径可重复验证，并输出命中率和误命中率。

当前进度：阶段 0、阶段 1、阶段 2 和阶段 3 已完成；阶段 4 已进入开发，已打通内存、PostgreSQL 和 Nacos 三种租户策略源、运行时策略更新、租户级 PII/缓存/模型/限流控制，以及带租户标签的 Micrometer 请求、延迟、Token、费用和失败指标。

阶段 3 进展：Redis Lua 预扣/确认/释放、PostgreSQL `lingshu_budget_ledger` 账本、带认领机制的 `lingshu_budget_outbox`、reservation 幂等状态转换、虚拟计费 Processor、PostgreSQL 虚拟租户账户/用量表、余额与用量查询、Kafka 发布器、Outbox Relay、幂等消费表和超时回收扫描器已完成。Kafka Compose 服务已绑定 `E:\LingShuData\kafka` 并启动；真实预算与 Kafka Relay 默认关闭，避免开发环境误启用真实预算扣减。

开发计费进展：已加入本地虚拟账本，每租户默认 CNY 10.00；按 provider 返回的输入/输出 token 计算费用，缓存命中不计费；失败响应若携带 usage 也计费，无 usage 时记录 0。虚拟账本不调用外部扣款接口，也不影响 DeepSeek API 配额。

注意：下载嵌入模型、Reranker 或实验数据集前，必须先取得用户提供的数据存储目录。

### 阶段 3：预算与结算

- Redis Lua 原子预扣、确认和释放。
- PostgreSQL 账本作为最终事实来源。
- Kafka 结算事件、唯一 `reservation_id` 幂等消费。
- 增加 Outbox 或等价可靠投递机制，避免数据库提交与消息发送不一致。
- 超时预扣扫描和自动回滚。

验收：并发超额被拒绝；重复事件不重复扣费；失败和超时均释放额度；账本可审计。

### 阶段 4：动态策略与可观测性

- 抽象 `TenantPolicySource`，先实现本地/数据库版本。
- 本地动态策略进度：默认零依赖模式已使用线程安全内存存储，`PUT /internal/tenants/{tenantId}/policy` 更新后对下一请求立即生效；开启持久化后自动切换到 PostgreSQL 存储。
- 已完成租户启停、模型白名单、PII 脱敏、精确/语义缓存开关、RPM/并发限制和虚拟计费单价策略。
- Nacos HTTP 适配器已完成：按 MD5 轮询更新，远端策略优先，本地/数据库策略回退；拉取或解析失败时保留最后一次有效快照，且配置中心实现未侵入 Processor Engine。
- Micrometer 请求、延迟、Token、虚拟费用和失败指标已补齐租户标签；Prometheus 抓取已具备，Grafana 租户面板和结构化日志仍待完善。
- 完善多租户策略、告警和错误契约。

验收：策略变更可动态生效；Trace、延迟、错误率、Token 和费用可按租户查询。

### 阶段 5：可靠性与性能

- Resilience4j 超时、重试、熔断和降级。
- SSE 流式输出与 usage 末帧计费；缺失 usage 时使用本地计数回退。
- 压测、容量基线、故障注入和恢复测试。

验收：故障切换行为确定；流式计费可核对；形成吞吐、P95/P99 和资源占用报告。

## 3. 关键调整与风险

- 多 Provider 不依赖单一自动配置 Bean，采用显式 Provider Bean/客户端配置，避免多模型自动配置冲突。
- 缓存键不建议继续使用 MD5，改用 SHA-256，并包含租户、模型族、任务类型、系统 Prompt 版本及关键采样参数。
- SSE 不应无上限拼接完整响应，回退计数采用增量缓冲或设置最大响应上限。
- Nacos 与 Spring Boot 4.1.x / Spring Cloud 2025.1.2 的生态兼容性需要单独验证；核心策略接口保持与 Nacos 解耦。
- Kafka 幂等消费只解决重复事件，不能单独解决事件丢失，需配合 Outbox 或可靠投递设计。

## 4. 数据与密钥边界

- 代码仓库只保存小型、可公开、可复现的测试夹具。
- 真实 API Key 不落盘、不提交。
- 模型、数据集、压测语料、数据库持久化文件和大日志统一放到 `E:\LingShuData` 的对应子目录。
- `D:\Docker` 仅为 Docker 安装位置，不作为项目数据目录。
- LingShu PostgreSQL 与 Redis 分别使用本机端口 `54320` 和 `63790`，避免影响已有容器。
