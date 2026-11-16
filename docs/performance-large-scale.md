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

历史运行使用的流式成功口径是“HTTP 2xx并读取响应至EOF”，没有独立强制校验`[DONE]`。Stub正常路径会发送`[DONE]`，但原始汇总无法逐请求回溯终止帧，因此100%成功率不能外推为“所有异常流均可识别”。压测器现已改为只有收到`[DONE]`才判定流式成功，并新增流式/非流式分组、ITL和完成流吞吐；严格口径复测结果见下节。

## 严格流式口径复测（2026-11-15）

测试链路仍为同机压测客户端 → Gateway → Core → Stub Provider，关闭精确缓存，100并发，每组10,000请求和1,000预热请求，各重复三次取中位数。成功流必须收到`[DONE]`且不能出现SSE error；三组共90,000个正式请求，错误数为0。

| SSE比例 | 总成功率 | 总QPS中位数 | 总P95/P99 | 完成流吞吐中位数 | 流E2E P95/P99 | TTFT P95/P99 |
|---:|---:|---:|---:|---:|---:|---:|
| 0% | 100% | 4,772.284 | 34.1 / 43.9 ms | 不适用 | 不适用 | 不适用 |
| 50% | 100% | 4,558.428 | 36.1 / 47.1 ms | 2,279.214 streams/s | 36.2 / 47.3 ms | 36.2 / 47.3 ms |
| 100% | 100% | 4,306.737 | 37.1 / 48.8 ms | 4,306.737 streams/s | 37.1 / 48.8 ms | 37.1 / 48.8 ms |

0%组第一轮为冷态，QPS 2,827.585、P95 58.9 ms；后两轮为4,813.388和4,772.284 QPS。表中仍按三轮中位数统计，没有删除冷态轮或选取最好结果。当前默认Stub每流只有一个业务增量，因此基础三组没有ITL样本，不能将ITL描述为0。

另执行5分块、每块固定延迟10 ms的100% SSE场景：100并发，每轮5,000请求和500预热请求，三轮共15,000个完整流，全部严格成功。中位数为1,063.346 streams/s、TTFT P95/P99 34.9/44.6 ms、ITL P95/P99 23.5/30.0 ms、端到端P95/P99 99.9/106.2 ms。每流实际收到5个业务增量，每轮产生20,000个ITL样本。该场景用于验证统计口径和分块链路，不代表真实模型Token生成速度。

原始`summary.json`与资源采样保存在`E:\LingShuData\benchmarks`下，运行目录时间范围为`20261115-221650`至`20261115-222033`。

## 显式取消时延（2026-11-15）

使用单Core、5分块、每块200 ms的慢速Stub，在首块前发起显式取消，共重复5次。五次均首次返回202、重复请求返回200，SSE均包含`STREAM_CANCELLED`且不包含`[DONE]`。取消接口端到端耗时中位数为9.589 ms，取消响应后流线程停止耗时中位数为15.156 ms。测评结束时Prometheus为`lingshu_chat_streams_active 0`、`lingshu_chat_streams_terminated_total{reason="client_cancel_requested"} 5`。

该时延来自同机单Core，不代表跨主机取消延迟。跨Core功能已使用本机双Core + Redis完成正确性实测，但当时没有采集可用于报告的重复时延分布。

## 流式故障与慢消费者演练（2026-11-16）

使用可配置Stub在本地Gateway→Core完整链路注入故障。首块前失败时，主Provider未发送业务增量即异常，Core切换到`stub-fast`；客户端只收到备用Provider内容，metadata中的尝试顺序为`["stub", "stub-fast"]`，正常收到`[DONE]`。该场景只完成一次正确性演练，不能作为fallback时延分布。

首块后截断时，主Provider发送2个增量后抛出截断异常。客户端保留已收到的2块，随后收到`STREAMING_ERROR`，没有成功metadata和`[DONE]`，Core没有切换到备用Provider。Prometheus记录`lingshu_chat_streams_terminated_total{reason="upstream_truncated"} 1`，活动流最终回到0。

慢消费者场景使用2个并发客户端，每条响应约8 MiB、256个业务增量；客户端收到响应头后暂停读取3秒，再以4 KiB/2 ms限速读取。暂停时Core活动流为2，说明该数据量已使下游压力传播到Core；两条流最终均完整收到256块、metadata和`[DONE]`，耗时分别9.913和9.877秒，结束后活动流为0。作为对照，4条约1 MiB响应在暂停2秒时Core已完成，说明较小响应可被Gateway/网络缓冲吸收。该结果验证协议完整性和大响应压力传播，不证明长期负载下堆内存具有严格上界。

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

1. 分别以0%、50%、100%流式比例复测，并单独报告TTFT、ITL、流式端到端延迟和严格成功率。
2. 对20、50、100、200并发各执行三轮，使用中位数确定本机拐点。
3. 在独立压测机和多实例部署下重复容量测试，排除客户端与单机资源竞争。
4. 使用可控延迟的Provider模拟器和真实模型分别测量缓存收益。
5. 继续补充Core强制重启、连接排空、Redis/owner网络故障，并对已完成的首块前失败、首块后断流和慢消费者场景重复采样恢复时间。
