import argparse
import json
import math
import urllib.request
from pathlib import Path


def embed(endpoint: str, text: str) -> list[float]:
    body = json.dumps({"inputs": text}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    vector = payload[0]
    if not isinstance(vector, list) or not vector:
        raise ValueError("TEI response does not contain a vector")
    return [float(value) for value in vector]


def cosine(left: list[float], right: list[float]) -> float:
    if len(left) != len(right):
        raise ValueError("Embedding dimensions do not match")
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        raise ValueError("Embedding norm must be positive")
    return dot / (left_norm * right_norm)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:8090/embed")
    parser.add_argument(
        "--dataset",
        default=r"E:\LingShuData\datasets\semantic-eval-samples.jsonl",
    )
    parser.add_argument(
        "--report",
        default=r"E:\LingShuData\logs\semantic-threshold-report.txt",
    )
    args = parser.parse_args()

    samples = [
        json.loads(line)
        for line in Path(args.dataset).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    rows = []
    for sample in samples:
        left = embed(args.endpoint, sample["text_a"])
        right = embed(args.endpoint, sample["text_b"])
        rows.append({"label": int(sample["label"]), "similarity": cosine(left, right)})

    print("label\tsimilarity\ttext_a\ttext_b")
    for sample, row in zip(samples, rows):
        print(
            f"{row['label']}\t{row['similarity']:.6f}\t"
            f"{sample['text_a']}\t{sample['text_b']}"
        )

    lines = ["threshold precision recall f1 tp fp fn tn"]
    results = []
    for threshold in (0.80, 0.85, 0.88, 0.90, 0.92, 0.94, 0.96):
        tp = sum(row["label"] == 1 and row["similarity"] >= threshold for row in rows)
        fp = sum(row["label"] == 0 and row["similarity"] >= threshold for row in rows)
        fn = sum(row["label"] == 1 and row["similarity"] < threshold for row in rows)
        tn = sum(row["label"] == 0 and row["similarity"] < threshold for row in rows)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        result = (f1, precision, threshold, recall, tp, fp, fn, tn)
        results.append(result)
        lines.append(
            f"{threshold:.2f} {precision:.4f} {recall:.4f} {f1:.4f} "
            f"{tp} {fp} {fn} {tn}"
        )

    best = max(results)
    lines.append(
        f"recommended_threshold={best[2]:.2f} f1={best[0]:.4f} "
        f"precision={best[1]:.4f} recall={best[3]:.4f}"
    )
    report = "\n".join(lines) + "\n"
    Path(args.report).write_text(report, encoding="utf-8")
    print("\n" + report, end="")


if __name__ == "__main__":
    main()
