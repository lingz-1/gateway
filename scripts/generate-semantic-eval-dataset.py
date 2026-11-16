#!/usr/bin/env python3
"""Generate a deterministic synthetic semantic-cache evaluation dataset."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


INTENTS = [
    ("account-password", ["忘记密码后怎么重置", "如何找回账户密码", "登录密码忘了怎么办", "怎样重新设置密码", "无法登录时怎么改密码"]),
    ("account-email", ["怎么修改绑定邮箱", "如何更换账户邮箱", "绑定邮箱可以更新吗", "在哪里改登录邮箱", "想把账号邮箱换掉"]),
    ("account-phone", ["怎么修改绑定手机号", "如何更换账户手机号码", "旧手机号不用了怎么换绑", "在哪里更新登录手机", "账号能改成新手机号吗"]),
    ("account-close", ["如何注销账户", "怎么永久删除账号", "我想关闭当前账户", "在哪里申请销户", "账户注销流程是什么"]),
    ("account-mfa", ["如何开启两步验证", "怎么启用双因素认证", "账户怎样设置MFA", "在哪里打开二次验证", "如何提高登录验证安全性"]),
    ("order-track", ["怎么查询订单物流", "我的包裹到哪里了", "如何查看配送进度", "订单物流信息在哪看", "怎样跟踪快递"]),
    ("order-cancel", ["如何取消未发货订单", "订单还没发货怎么撤销", "在哪里取消购买", "未出库订单能退订吗", "怎么停止这个订单"]),
    ("order-refund", ["如何申请退款", "退款流程怎么走", "在哪里提交退费申请", "怎样把订单款退回来", "商品退款要怎么操作"]),
    ("order-invoice", ["如何申请电子发票", "订单发票在哪里开", "怎么获取购买凭证", "能给这个订单开发票吗", "电子票据如何下载"]),
    ("order-address", ["订单怎么修改收货地址", "发货前能更换配送地址吗", "如何更新订单地址", "收件地址填错了怎么办", "在哪里改包裹送达地点"]),
    ("cache-exact", ["什么是精确缓存", "精确缓存如何匹配请求", "完全相同的请求怎样命中缓存", "精确缓存键怎么生成", "Exact Cache的作用是什么"]),
    ("cache-semantic", ["什么是语义缓存", "语义缓存如何识别相似问题", "近似请求怎样复用回答", "Semantic Cache的作用是什么", "如何用向量相似度命中缓存"]),
    ("cache-redis-failure", ["Redis缓存不可用时怎么办", "缓存Redis宕机如何降级", "Redis连接失败会阻断请求吗", "精确缓存异常怎样fail-open", "Redis故障时主链路如何继续"]),
    ("cache-pgvector", ["pgvector如何存储语义缓存", "向量缓存怎样写入PostgreSQL", "pgvector相似度查询怎么做", "语义缓存的向量表如何设计", "Postgres向量索引如何配置"]),
    ("cache-embedding", ["Embedding服务超时怎么处理", "嵌入模型不可用如何降级", "向量生成失败会影响请求吗", "Embedding异常时怎样fail-open", "语义缓存调用嵌入失败怎么办"]),
    ("provider-timeout", ["Provider调用超时怎么处理", "上游模型响应太慢怎么办", "模型供应商超时策略是什么", "调用大模型超时如何终止", "Provider超时时间怎么配置"]),
    ("provider-retry", ["Provider失败后如何重试", "模型调用重试策略是什么", "上游临时错误会重试吗", "请求模型失败能自动再试吗", "Provider重试次数怎么设置"]),
    ("provider-circuit", ["Provider熔断如何工作", "模型供应商连续失败会怎样", "熔断器什么时候打开", "上游故障如何进入半开状态", "Provider circuit breaker怎么恢复"]),
    ("provider-fallback", ["如何切换备用Provider", "主模型失败后怎样降级", "Provider fallback的条件是什么", "什么时候调用候选供应商", "上游失败如何切换备选模型"]),
    ("provider-stream", ["流式响应中途截断怎么识别", "SSE没有DONE如何处理", "上游流中断会返回什么", "流式输出异常结束怎么统计", "如何区分正常结束和流截断"]),
    ("budget-reserve", ["Token预算如何预扣", "请求前怎样保留预算", "预算预授权流程是什么", "Redis如何原子预扣额度", "并发请求怎样避免预算超卖"]),
    ("budget-settle", ["实际Token如何差额结算", "预扣后怎样按真实用量扣费", "实际用量少于预扣怎么退款", "Token结算如何释放差额", "预算确认时怎样记录真实消耗"]),
    ("budget-idempotent", ["重复结算如何保证幂等", "同一reservation会重复扣费吗", "并发确认怎样避免多次扣款", "预算结算的唯一键是什么", "重复请求如何只结算一次"]),
    ("budget-outbox", ["预算Outbox事件如何投递", "账务事件怎样保证不丢失", "Outbox重复发布怎么去重", "结算事件如何发送Kafka", "账务消息的唯一性怎么保证"]),
    ("budget-usage", ["Provider不返回Usage怎么办", "缺失Token用量如何估算", "Usage来源怎样标记", "估算Token能否当作真实计费", "如何区分上报和估算用量"]),
    ("ops-postgres", ["PostgreSQL连接失败怎么排查", "数据库无法连接要检查什么", "Postgres连不上如何诊断", "数据库连接超时怎么办", "怎样定位PostgreSQL网络故障"]),
    ("ops-redis", ["Redis内存不足怎么处理", "缓存实例内存满了怎么办", "如何排查Redis OOM", "Redis淘汰策略怎样配置", "缓存内存压力如何缓解"]),
    ("ops-kafka", ["Kafka消息积压怎么处理", "消费者落后如何排查", "Kafka backlog过大怎么办", "如何提升消息消费速度", "账务Topic积压怎样恢复"]),
    ("ops-nacos", ["Nacos配置更新不一致怎么办", "多实例配置短暂不同步如何处理", "Nacos推送失败会怎样", "配置中心不可用如何保留旧配置", "热更新期间如何避免配置丢失"]),
    ("ops-prometheus", ["Prometheus如何统计P95", "请求延迟分位数怎么监控", "怎样在监控中查看P95", "Histogram如何计算延迟分位", "Prometheus延迟指标如何设计"]),
]


def samples() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for index, (intent, phrases) in enumerate(INTENTS):
        canonical = phrases[0]
        for variant in range(1, 5):
            rows.append({
                "id": f"pos-{index:02d}-{variant}",
                "intent": intent,
                "text_a": canonical,
                "text_b": phrases[variant],
                "label": 1,
                "sample_type": "synthetic-positive",
            })
            negative_index = (index + 1 + (variant - 1) * 7) % len(INTENTS)
            negative_intent, negative_phrases = INTENTS[negative_index]
            rows.append({
                "id": f"neg-{index:02d}-{variant}",
                "intent": f"{intent}!={negative_intent}",
                "text_a": canonical,
                "text_b": negative_phrases[variant],
                "label": 0,
                "sample_type": "synthetic-negative",
            })
    return rows


def main() -> int:
    data_root = Path(os.environ.get("LINGSHU_DATA_ROOT", r"E:\LingShuData"))
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        default=data_root / "datasets" / "semantic-eval-samples-240.jsonl",
    )
    args = parser.parse_args()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    rows = samples()
    output.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "output": str(output),
        "samples": len(rows),
        "positive": sum(row["label"] == 1 for row in rows),
        "negative": sum(row["label"] == 0 for row in rows),
        "checks": {"sample_count_is_240": len(rows) == 240},
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
