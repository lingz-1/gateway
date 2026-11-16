#!/usr/bin/env python3
"""Measure cache hit rate, latency, and provider-call reduction on one request set."""

from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from pathlib import Path


def percentile(values: list[float], ratio: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * ratio) - 1)
    return round(ordered[index], 3)


def distribution(values: list[float]) -> dict[str, float | None]:
    return {
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "average": round(sum(values) / len(values), 3) if values else None,
    }


def request(url: str, tenant_id: str, prompt: str) -> dict[str, object]:
    body = json.dumps({
        "model": "stub-echo-v1",
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
        "temperature": 0.0,
    }, ensure_ascii=False).encode("utf-8")
    call = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": tenant_id,
            "X-Trace-Id": f"cache-eval-{uuid.uuid4().hex}",
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(call, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
            return {
                "status": response.status,
                "latencyMs": round((time.perf_counter() - started) * 1000, 3),
                "cacheStatus": str(payload.get("metadata", {}).get(
                    "cacheStatus", response.headers.get("X-LingShu-Cache", "UNKNOWN")
                )).upper(),
            }
    except urllib.error.HTTPError as exception:
        return {
            "status": exception.code,
            "latencyMs": round((time.perf_counter() - started) * 1000, 3),
            "cacheStatus": "ERROR",
        }


def request_set(dataset: Path) -> list[dict[str, str]]:
    rows = [
        json.loads(line)
        for line in dataset.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    positives: dict[str, dict[str, object]] = {}
    negatives: dict[str, dict[str, object]] = {}
    for row in rows:
        base_intent = str(row["intent"]).split("!=", 1)[0]
        if int(row["label"]) == 1:
            positives.setdefault(base_intent, row)
        elif str(row["id"]).endswith("-4"):
            negatives[base_intent] = row
    requests: list[dict[str, str]] = []
    for intent in sorted(positives):
        positive = positives[intent]
        negative = negatives[intent]
        canonical = str(positive["text_a"])
        requests.extend([
            {"kind": "prime", "scope": intent, "prompt": canonical},
            {"kind": "exact-repeat", "scope": intent, "prompt": canonical},
            {"kind": "positive-paraphrase", "scope": intent, "prompt": str(positive["text_b"])},
            {"kind": "negative-control", "scope": intent, "prompt": str(negative["text_b"])},
        ])
    return requests


def main() -> int:
    data_root = Path(os.environ.get("LINGSHU_DATA_ROOT", r"E:\LingShuData"))
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("off", "exact", "semantic"), required=True)
    parser.add_argument("--url", default="http://127.0.0.1:8080/v1/chat/completions")
    parser.add_argument(
        "--dataset",
        default=data_root / "datasets" / "semantic-eval-samples-240.jsonl",
    )
    parser.add_argument(
        "--raw-output-root",
        default=data_root / "evaluations" / "cache-effect-raw",
    )
    args = parser.parse_args()

    requests = request_set(Path(args.dataset))
    tenant_root = f"cache-eval-{args.mode}-{uuid.uuid4().hex}"
    samples = []
    for item in requests:
        tenant_id = f"{tenant_root}-{item['scope']}"
        result = request(args.url, tenant_id, item["prompt"])
        samples.append({**item, **result})

    output_root = Path(args.raw_output_root)
    output_root.mkdir(parents=True, exist_ok=True)
    raw_output = output_root / f"{args.mode}-{uuid.uuid4().hex}.json"
    raw_output.write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding="utf-8")

    successes = [sample for sample in samples if sample["status"] == 200]
    statuses = Counter(str(sample["cacheStatus"]) for sample in successes)
    hits = statuses["EXACT"] + statuses["SEMANTIC"]
    misses = statuses["MISS"]
    all_latencies = [float(sample["latencyMs"]) for sample in successes]
    hit_latencies = [
        float(sample["latencyMs"])
        for sample in successes
        if sample["cacheStatus"] in ("EXACT", "SEMANTIC")
    ]
    miss_latencies = [
        float(sample["latencyMs"])
        for sample in successes
        if sample["cacheStatus"] == "MISS"
    ]
    positive_samples = [
        sample for sample in successes if sample["kind"] == "positive-paraphrase"
    ]
    negative_samples = [
        sample for sample in successes if sample["kind"] == "negative-control"
    ]
    semantic_true_hits = sum(
        sample["cacheStatus"] == "SEMANTIC" for sample in positive_samples
    )
    semantic_false_hits = sum(
        sample["cacheStatus"] == "SEMANTIC" for sample in negative_samples
    )
    result = {
        "mode": args.mode,
        "dataset": str(Path(args.dataset)),
        "rawOutput": str(raw_output),
        "requests": len(samples),
        "successful": len(successes),
        "cacheStatuses": dict(sorted(statuses.items())),
        "hitRate": round(hits / len(successes), 6) if successes else 0.0,
        "providerCalls": misses,
        "providerCallReduction": round(1 - misses / len(successes), 6) if successes else 0.0,
        "latencyMs": distribution(all_latencies),
        "cacheHitLatencyMs": distribution(hit_latencies),
        "providerPathLatencyMs": distribution(miss_latencies),
        "semanticPositiveHitRate": round(
            semantic_true_hits / len(positive_samples), 6
        ) if positive_samples else 0.0,
        "semanticFalseHitRate": round(
            semantic_false_hits / len(negative_samples), 6
        ) if negative_samples else 0.0,
        "checks": {
            "all_requests_succeeded": len(successes) == len(samples),
            "request_set_has_120_requests": len(samples) == 120,
            "off_mode_has_no_hits": args.mode != "off" or hits == 0,
            "exact_mode_hits_all_repeats": args.mode == "off" or statuses["EXACT"] >= 30,
            "semantic_mode_exercises_semantic_cache": (
                args.mode != "semantic" or statuses["SEMANTIC"] > 0
            ),
            "semantic_false_hit_rate_at_most_5_percent": (
                args.mode != "semantic"
                or semantic_false_hits / len(negative_samples) <= 0.05
            ),
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(result["checks"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
