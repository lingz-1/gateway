# LingShu 大规模压测记录

## 测试边界

- 日期：2026-09-16
- 环境：单机 Windows 开发工作站
- 链路：压测客户端 → Gateway → Core → Stub Provider
- 请求模型：100 并发、50% SSE、50% 非流式、唯一请求 traceId
- 基础设施：无 Docker，内存租户策略，关闭精确缓存的容量场景使用唯一 Prompt
- 日志：关闭 Core/Gateway 逐请求 INFO 日志，保留错误日志
- 统计：100 微秒分辨率的有界延迟直方图；Gateway/Core Prometheus 独立采样

这些数据验证压测工具和本地开发链路，不代表真实模型、独立压测机、多实例部署或生产容量。每个大规模场景目前只执行一轮；正式容量结论仍需每档至少重复三次并取中位数。

## 容量结果

| 场景 | 请求量 | 成功率 | QPS | 平均延迟 | P95 | P99 | TTFT P95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 有界 trace 缓存验证 | 200,000 | 100% | 4,236.905 | 22.884 ms | 43.7 ms | 58.5 ms | 44.3 ms |
| 有界 trace 缓存最终复测 | 1,000,000 | 100% | 3,984.989 | 24.803 ms | 46.5 ms | 60.9 ms | 46.8 ms |

百万级最终复测全部返回 HTTP 200，错误数为 0，所有请求均为缓存 MISS。运行 250.942 秒，流式 TTFT P99 为 61.4 ms，端到端最大延迟为 121.732 ms。

## 压测发现并修复的问题

首次百万请求测试虽然也是 100% 成功，但吞吐从短测约 4,100 QPS 持续下降到 1,818.350 QPS，P95 升至 147.7 ms。资源曲线显示 Core 堆从 167.55 MiB 增长到 1,578.43 MiB，Core 平均 CPU 达 75.25%。

根因是 `VirtualBillingService` 为方便同一请求的错误响应回查，按唯一 traceId 将虚拟计费结果永久保存在无界内存 Map 中。现已改为线程安全的有界 LRU，默认最多保留 10,000 条，可通过 `LINGSHU_VIRTUAL_MAX_TRACE_ENTRIES` 调整，并增加淘汰单元测试。

相同百万请求参数复测后，QPS 提升 119.2%，平均延迟降低 44.2%，P95 降低 68.5%，P99 降低 76.3%，Core 堆峰值从 1,578.43 MiB 降至 218.81 MiB。

## 百万级资源结果

| 服务 | CPU 平均/峰值 | 堆首值/末值/峰值 | 线程峰值 | GC 暂停增量 |
|---|---:|---:|---:|---:|
| Gateway | 7.47% / 9.61% | 73.01 / 72.99 / 194.04 MiB | 38 | 0.62 s |
| Core | 5.20% / 7.53% | 150.71 / 163.42 / 218.81 MiB | 75 | 0.41 s |

Core 四阶段平均堆占用依次为 132.34、135.39、126.73、125.44 MiB，未随累计请求量增长；Gateway/Core 资源采样均无错误。CPU 数值来自 JVM `process_cpu_usage`，表示当前进程相对整机 CPU 容量的采样值。

## 万级缓存对照

| 场景 | 成功率 | QPS | 平均延迟 | P95 | P99 | TTFT P95 | 缓存结果 |
|---|---:|---:|---:|---:|---:|---:|---|
| 关闭精确缓存 | 100% | 5,751.522 | 13.613 ms | 28.7 ms | 37.2 ms | 28.7 ms | 10,000 MISS |
| 精确缓存预热命中 | 100% | 4,753.025 | 9.994 ms | 20.2 ms | 36.0 ms | 18.1 ms | 10,000 EXACT |

热 JVM 的精确缓存场景平均延迟降低约 26.6%，P95 降低约 29.6%，但吞吐量低约 17.4%。Stub Provider 几乎没有推理成本，因此不能据此推断真实模型缓存的收益。

## 可复现命令

无缓存：

```powershell
.\scripts\start-benchmark.ps1 -Cache Off
.\scripts\python.ps1 .\scripts\load-test-large.py --requests 10000 --concurrency 100 --prompt-cardinality 10000 --warmup-requests 1000 --stream-ratio 0.5 --label uncached-10k-c100
```

精确缓存命中：

```powershell
.\scripts\start-benchmark.ps1 -Cache Exact
.\scripts\python.ps1 .\scripts\load-test-large.py --requests 10000 --concurrency 100 --prompt-cardinality 100 --warmup-requests 1000 --stream-ratio 0.5 --warm-cache --label exact-10k-c100
```

原始 `summary.json` 和 `resource-samples.json` 保存在 `${LINGSHU_DATA_ROOT}/benchmarks/<run>`。原始运行数据不提交到 Git。

## 下一阶段

1. 对 20、50、100、200 并发各执行三轮，使用中位数确定本机拐点。
2. 在独立压测机和多实例部署下重复容量测试，排除客户端与单机资源竞争。
3. 使用可控延迟的 Provider 模拟器和真实模型分别测量缓存收益。
4. 增加 Provider 429/5xx/超时和 Core 重启故障注入，记录切换与恢复时间。
