#!/usr/bin/env python3
"""Evaluate semantic-cache thresholds against a labelled JSONL dataset."""

from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.request
from pathlib import Path


def embed(endpoint: str, text: str) -> tuple[list[float], float]:
    body = json.dumps({"inputs": text}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.load(response)
    latency_ms = (time.perf_counter() - started) * 1000
    vector = payload[0]
    if not isinstance(vector, list) or not vector:
        raise ValueError("TEI response does not contain a vector")
    return [float(value) for value in vector], latency_ms


def cosine(left: list[float], right: list[float]) -> float:
    if len(left) != len(right):
        raise ValueError("Embedding dimensions do not match")
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        raise ValueError("Embedding norm must be positive")
    return dot / (left_norm * right_norm)


def percentile(values: list[float], ratio: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * ratio) - 1)
    return round(ordered[index], 3)


def threshold_metrics(rows: list[dict[str, float]], threshold: float) -> dict[str, float | int]:
    tp = sum(row["label"] == 1 and row["similarity"] >= threshold for row in rows)
    fp = sum(row["label"] == 0 and row["similarity"] >= threshold for row in rows)
    fn = sum(row["label"] == 1 and row["similarity"] < threshold for row in rows)
    tn = sum(row["label"] == 0 and row["similarity"] < threshold for row in rows)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    false_positive_rate = fp / (fp + tn) if fp + tn else 0.0
    false_hit_rate = fp / (tp + fp) if tp + fp else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "threshold": round(threshold, 4),
        "precision": round(precision, 6),
        "recall": round(recall, 6),
        "falsePositiveRate": round(false_positive_rate, 6),
        "falseHitRate": round(false_hit_rate, 6),
        "f1": round(f1, 6),
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "tn": tn,
    }


def main() -> int:
    data_root = Path(os.environ.get("LINGSHU_DATA_ROOT", r"E:\LingShuData"))
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:8090/embed")
    parser.add_argument(
        "--dataset",
        default=data_root / "datasets" / "semantic-eval-samples-240.jsonl",
    )
    parser.add_argument(
        "--raw-output",
        default=data_root / "logs" / "semantic-threshold-raw.json",
    )
    parser.add_argument(
        "--report",
        default=data_root / "logs" / "semantic-threshold-report.json",
    )
    parser.add_argument("--threshold-min", type=float, default=0.75)
    parser.add_argument("--threshold-max", type=float, default=0.98)
    parser.add_argument("--threshold-step", type=float, default=0.01)
    parser.add_argument("--required-precision", type=float, default=0.95)
    args = parser.parse_args()

    samples = [
        json.loads(line)
        for line in Path(args.dataset).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    vectors: dict[str, list[float]] = {}
    embedding_latencies: list[float] = []
    texts = dict.fromkeys(
        str(sample[key]) for sample in samples for key in ("text_a", "text_b")
    )
    for text in texts:
        vectors[text], latency = embed(args.endpoint, text)
        embedding_latencies.append(latency)

    rows = [{
        "id": sample.get("id"),
        "label": int(sample["label"]),
        "similarity": cosine(vectors[sample["text_a"]], vectors[sample["text_b"]]),
        "text_a": sample["text_a"],
        "text_b": sample["text_b"],
    } for sample in samples]
    raw_output = Path(args.raw_output)
    raw_output.parent.mkdir(parents=True, exist_ok=True)
    raw_output.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")

    count = int(round((args.threshold_max - args.threshold_min) / args.threshold_step))
    thresholds = [args.threshold_min + index * args.threshold_step for index in range(count + 1)]
    scans = [threshold_metrics(rows, threshold) for threshold in thresholds]
    eligible = [row for row in scans if row["precision"] >= args.required_precision]
    recommended = max(
        eligible or scans,
        key=lambda row: (row["recall"], row["f1"], row["threshold"]),
    )
    result = {
        "dataset": str(Path(args.dataset)),
        "rawOutput": str(raw_output),
        "sampleCount": len(samples),
        "positiveCount": sum(int(sample["label"]) == 1 for sample in samples),
        "negativeCount": sum(int(sample["label"]) == 0 for sample in samples),
        "uniqueTexts": len(vectors),
        "embeddingLatencyMs": {
            "p50": percentile(embedding_latencies, 0.50),
            "p95": percentile(embedding_latencies, 0.95),
        },
        "requiredPrecision": args.required_precision,
        "recommended": recommended,
        "thresholds": scans,
        "checks": {
            "sample_count_between_200_and_300": 200 <= len(samples) <= 300,
            "contains_positive_and_negative_samples": (
                any(int(sample["label"]) == 1 for sample in samples)
                and any(int(sample["label"]) == 0 for sample in samples)
            ),
            "recommended_precision_meets_requirement": (
                recommended["precision"] >= args.required_precision
            ),
        },
    }
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(result["checks"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
