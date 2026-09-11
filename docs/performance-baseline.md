# LingShu 本地性能基线

## 测试范围

- 日期：2026-09-11
- 链路：Python 压测客户端 → Gateway `:8080` → Core `:8081` → Stub Provider
- 存储：内存租户策略、内存精确缓存
- 请求数：每组 200
- 并发数：20
- 流式请求占比：50%
- 独立预热请求：20
- 运行方式：单机 Windows 开发环境

本报告用于建立可重复的开发基线，不代表真实模型、生产基础设施或多实例部署的容量上限。

## 测试结果

| 场景 | 成功率 | 吞吐量 | 平均延迟 | P95 延迟 | P99 延迟 | 平均 TTFT | P95 TTFT | P99 TTFT | 缓存结果 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 冷缓存 | 100% | 722.285 RPS | 25.779 ms | 42.357 ms | 49.290 ms | 5.895 ms | 23.949 ms | 26.598 ms | 200 MISS |
| 热缓存 | 100% | 783.403 RPS | 24.188 ms | 41.565 ms | 44.922 ms | 15.654 ms | 33.028 ms | 36.211 ms | 200 EXACT |

热缓存场景吞吐量比冷缓存高约 8.5%，平均总延迟降低约 6.2%，P99 总延迟降低约 8.9%。但热缓存的 P95 TTFT 反而高约 37.9%。这是因为 Stub Provider 几乎没有推理成本，缓存查找、响应重建和本机线程调度的固定成本足以覆盖其收益；该结果不能外推到真实模型。首个未预热测试曾出现明显的 JVM/JIT 尾延迟，因此正式数据均在 20 个不计入统计的独立请求后采集。

## 可复现命令

冷缓存：

```powershell
.\scripts\python.ps1 .\scripts\load-test.py --requests 200 --concurrency 20 --prompt-cardinality 200 --stream-ratio 0.5 --warmup-requests 20 --tenant-prefix perf-cold-v3 --run-id baseline-cold-v3-20260911
```

热缓存：

```powershell
.\scripts\python.ps1 .\scripts\load-test.py --requests 200 --concurrency 20 --prompt-cardinality 20 --stream-ratio 0.5 --warmup-requests 20 --tenant-prefix perf-warm-v3 --run-id baseline-warm-v3-20260911 --warm-cache
```

## 指标与看板

- `lingshu_chat_duration_seconds`：端到端请求耗时，已启用 Prometheus histogram。
- `lingshu_chat_ttft_seconds`：流式请求从进入 Core 到首个内容增量成功发出的耗时，已启用 Prometheus histogram。
- Grafana `LingShu Overview`：支持租户筛选，并展示延迟 P95/P99、TTFT P95/P99、请求速率、Token、费用和缓存命中率。

## 后续实验

1. 增加 1、10、20、50、100 并发阶梯测试，每档执行多轮并提供中位数。
2. 使用 Redis 精确缓存和 PostgreSQL 语义缓存重复测试。
3. 使用 DeepSeek/OpenAI 真实流式响应测量网络 TTFT，但必须设置明确的调用预算。
4. 注入 Provider 超时、HTTP 429/5xx、Redis 中断和 Core 实例退出，记录切换与恢复时间。
5. 同步采集 CPU、堆内存、GC 和连接池占用，确定容量拐点。
