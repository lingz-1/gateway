# LingShu 两级缓存与预算差额结算测评报告

## 结论

本轮只评测两级缓存和 Token 预算差额结算，复用现有 Docker Compose、Maven 集成测试和轻量 Runner，没有引入新基础设施。

| 能力 | 验证方式 | 结果 |
|---|---|---:|
| 缓存适用性、租户及参数隔离 | 10 个定向单元/故障注入用例 | 通过 |
| Redis、Embedding、pgvector fail-open | 依赖异常与启动异常注入 | 通过 |
| 语义阈值扫描 | 240 对合成标注样本 | 通过 |
| 三种缓存模式对照 | 同一 120 请求，分别重复 3 轮 | 9/9 通过 |
| 预算差额结算 | 真实 Redis + PostgreSQL | 通过 |
| 并发结算幂等 | 32 路重复结算 | 只结算一次、只生成一条 Outbox |
| Usage 来源保留 | `PROVIDER_REPORTED`、`ESTIMATED`、`MIXED` | 通过 |

## 两级缓存结果

### 测试口径

- 每轮 120 个顺序请求：30 个 canonical 预热、30 个精确重复、30 个正向改写、30 个负向对照。
- 30 个意图使用独立租户命名空间，避免其他已预热意图污染正负样本标注。
- Provider 使用本地 Stub，并固定注入 40 ms 调用延迟；Embedding 使用本地 Qwen3-Embedding-4B，语义存储使用 PostgreSQL/pgvector。
- 三种模式使用完全相同的请求、租户、模型和采样参数，各重复 3 轮。

### 阈值扫描

数据集包含 120 对正样本和 120 对负样本，共 240 对合成中文样本。扫描范围为 0.75～0.98。

| 工作点 | Precision | Recall | FPR | 误命中占预测命中比例 |
|---|---:|---:|---:|---:|
| 0.75，满足 Precision ≥ 95% 时最大召回 | 97.06% | 55.00% | 1.67% | 2.94% |
| 0.79，本轮端到端保守工作点 | 100.00% | 40.00% | 0.00% | 0.00% |

Embedding 延迟 P50 为 45.379 ms，P95 为 58.409 ms。端到端对照采用 0.79；在每个意图选取的固定正负样本上，语义正样本命中率为 50%，负样本误命中率为 0%。这些是合成样本结果，不是生产业务 Prompt 的误命中率结论。

### 三组对照

下表为三轮指标的中位数：

| 模式 | 命中率 | Provider 调用减少 | 总体 P50 | 总体 P95 | 命中请求 P50 | 未命中路径 P50 |
|---|---:|---:|---:|---:|---:|---:|
| 无缓存 | 0% | 0% | 60.963 ms | 63.789 ms | — | 60.963 ms |
| Redis 精确缓存 | 25.0% | 25.0% | 61.147 ms | 72.361 ms | 6.778 ms | 62.168 ms |
| Redis + pgvector 语义缓存 | 37.5% | 37.5% | 94.242 ms | 126.810 ms | 25.437 ms | 106.201 ms |

相对无缓存 P50，Redis 精确命中请求降低 88.9%，语义模式命中请求降低 58.3%。Redis 精确缓存未命中路径额外开销约 1.205 ms；语义缓存未命中路径额外开销约 45.238 ms，主要来自 Embedding 和向量查询。

由于本轮 Stub Provider 只有 40 ms 固定延迟，且命中率不足 50%，两种缓存模式的总体 P50/P95 均没有优于无缓存。可使用的结论是“命中请求显著降低延迟并减少 Provider 调用”，不能表述为“当前两级缓存降低了全部请求的总体 P95”。

### Fail-open

定向测试验证了以下行为：

- Redis 精确缓存读写失败时继续调用 Provider；
- Embedding 请求失败时跳过语义缓存；
- pgvector 查询或写入失败时继续主链路；
- pgvector 在 Core 启动时不可用不会阻止应用启动，依赖恢复后会再次尝试初始化表结构。

以上属于单元/组件故障注入，不等同于真实网络分区演练。

## Token 预算差额结算结果

### 已实现

- Redis Lua 原子预扣；
- 按实际 Token 总量确认，少于预扣时释放差额，超过预扣时补扣并允许余额进入负值；
- Ledger 保存 `reserved_units`、`actual_units`、`released_units` 和 `usage_source`；
- Outbox 的 `(reservation_id, event_type)` 唯一约束阻止重复事件；
- `PROVIDER_REPORTED`、`ESTIMATED`、`MIXED` 原样保留；
- PostgreSQL 已成功、Redis 更新失败时，相同结算可安全重试并收敛。

### 已验证

- 预扣 100、实际 60：释放 40，余额守恒；
- 预扣 80、实际 130：补扣 50，余额可进入负值；
- 32 路并发提交相同结算：余额只变化一次，Ledger 只有一个最终状态，Outbox 只有一条事件；
- Redis 差额结算、Ledger 实际结算各重复 10 轮；
- 数据库写入失败不会提前修改 Redis，数据库成功但 Redis 失败可用同一参数重试；
- Usage 缺失使用 `ESTIMATED`，混合上报使用 `MIXED`，不会伪装成 Provider 实际账单。

### 未完成

- 预算预扣/结算服务尚未自动接入聊天 Processor；默认请求链仍使用虚拟计费，不能声称“每次模型请求已执行预算预扣”。
- PostgreSQL 成功而 Redis 持续失败时依赖调用方重试，尚无自动对账/补偿 Worker。
- 估算 Token 不等于 Provider 最终账单，尚无账单回补对账。

## 证据与复现

```powershell
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite semantic-dataset-240
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite semantic-threshold-scan
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite cache-off-3 --suite cache-redis-exact-3 --suite cache-redis-semantic-3
.\scripts\python.ps1 .\scripts\run-evaluation.py --suite cache-fail-open --suite budget-consistency
```

原始结果：

- `E:\LingShuData\evaluations\20261116-222956`：数据集生成、缓存 fail-open、预算一致性；
- `E:\LingShuData\evaluations\20261116-223941`：240 对样本阈值扫描；
- `E:\LingShuData\evaluations\20261116-224417`：最终三种缓存模式各 3 轮；
- `E:\LingShuData\evaluations\20261116-224905`：带误命中率不超过 5% 验收门槛的语义缓存最终回归；
- `E:\LingShuData\datasets\semantic-eval-samples-240.jsonl`：固定标注数据；
- `E:\LingShuData\logs\semantic-threshold-raw.json`：逐样本相似度原始数据；
- `E:\LingShuData\evaluations\cache-effect-raw`：逐请求缓存状态和延迟。

## 简历安全表述

> 基于 Redis 精确缓存与 Qwen3-Embedding-4B/pgvector 语义缓存构建 240 对合成样本阈值扫描，并使用同一 120 请求集完成三种缓存模式各 3 轮对照；在保守阈值 0.79 下，离线 Precision 100%、Recall 40%、FPR 0%，端到端两级缓存命中率 37.5%、Provider 调用减少 37.5%，命中请求 P50 相对无缓存降低 58.3%。补齐 Redis Lua 预算差额结算，验证 32 路并发重复结算只扣费一次且 Outbox 事件唯一。

必须同时说明：样本是合成数据、Provider 是 40 ms Stub、预算服务尚未自动接入聊天 Processor，以上结果不代表生产 SLA。
