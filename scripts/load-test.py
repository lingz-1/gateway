import argparse
import json
import math
import os
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from typing import Any


@dataclass(frozen=True)
class Sample:
    status: int
    latency_ms: float
    ttft_ms: float | None
    cache_status: str
    error: str | None


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return round(ordered[index], 3)


def distribution(values: list[float]) -> dict[str, float | None]:
    if not values:
        return {"average": None, "p50": None, "p95": None, "p99": None, "max": None}
    return {
        "average": round(sum(values) / len(values), 3),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": round(max(values), 3),
    }


def request_headers(tenant_id: str, api_key: str | None) -> dict[str, str]:
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream, application/json",
        "X-Tenant-Id": tenant_id,
        "X-Trace-Id": f"perf-{uuid.uuid4().hex}",
    }
    if api_key:
        headers["X-API-Key"] = api_key
    return headers


def parse_stream(response: Any, started_at: float) -> tuple[float | None, str]:
    ttft_ms: float | None = None
    cache_status = "UNKNOWN"
    for raw_line in response:
        line = raw_line.decode("utf-8").strip()
        if not line.startswith("data:"):
            continue
        data = line[len("data:"):].strip()
        if not data or data == "[DONE]":
            continue
        event = json.loads(data)
        choices = event.get("choices", [])
        if choices:
            content = choices[0].get("delta", {}).get("content")
            if content and ttft_ms is None:
                ttft_ms = (time.perf_counter() - started_at) * 1000
        metadata = event.get("metadata")
        if metadata:
            cache_status = str(metadata.get("cacheStatus", cache_status)).upper()
    return ttft_ms, cache_status


def execute_request(
        url: str,
        model: str,
        tenant_id: str,
        prompt: str,
        stream: bool,
        timeout: float,
        api_key: str | None,
) -> Sample:
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": stream,
        "temperature": 0.0,
    }).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers=request_headers(tenant_id, api_key),
        method="POST",
    )
    started_at = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if stream:
                ttft_ms, cache_status = parse_stream(response, started_at)
            else:
                payload = json.loads(response.read().decode("utf-8"))
                ttft_ms = None
                cache_status = str(
                    payload.get("metadata", {}).get(
                        "cacheStatus", response.headers.get("X-LingShu-Cache", "UNKNOWN")
                    )
                ).upper()
            return Sample(
                response.status,
                (time.perf_counter() - started_at) * 1000,
                ttft_ms,
                cache_status,
                None,
            )
    except urllib.error.HTTPError as exception:
        return Sample(
            exception.code,
            (time.perf_counter() - started_at) * 1000,
            None,
            "UNKNOWN",
            f"HTTP {exception.code}",
        )
    except Exception as exception:
        return Sample(
            0,
            (time.perf_counter() - started_at) * 1000,
            None,
            "UNKNOWN",
            f"{type(exception).__name__}: {exception}",
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Load-test the LingShu chat completion endpoint.")
    parser.add_argument("--url", default="http://127.0.0.1:8080/v1/chat/completions")
    parser.add_argument("--model", default="stub-echo-v1")
    parser.add_argument("--requests", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--prompt-cardinality", type=int, default=20)
    parser.add_argument("--stream-ratio", type=float, default=0.5)
    parser.add_argument("--warmup-requests", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--tenant-prefix", default="perf")
    parser.add_argument("--run-id", default=time.strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--warm-cache", action="store_true")
    parser.add_argument("--api-key", default=os.environ.get("LINGSHU_GATEWAY_API_KEY"))
    args = parser.parse_args()
    if (
        args.requests < 1
        or args.concurrency < 1
        or args.prompt_cardinality < 1
        or args.warmup_requests < 0
    ):
        parser.error(
            "requests, concurrency, and prompt-cardinality must be positive; "
            "warmup-requests cannot be negative"
        )
    if not 0.0 <= args.stream_ratio <= 1.0:
        parser.error("stream-ratio must be between 0 and 1")
    return args


def main() -> None:
    args = parse_args()

    warmup_errors = []
    for index in range(args.warmup_requests):
        sample = execute_request(
            args.url,
            args.model,
            f"{args.tenant_prefix}-warmup-{index % args.concurrency}",
            f"load-test warmup {args.run_id} prompt-{index}",
            index % 2 == 0 and args.stream_ratio > 0,
            args.timeout,
            args.api_key,
        )
        if sample.error:
            warmup_errors.append(asdict(sample))
    if warmup_errors:
        raise RuntimeError(json.dumps({"warmupErrors": warmup_errors}, ensure_ascii=False))

    def dimensions(index: int) -> tuple[str, str]:
        key = index % args.prompt_cardinality
        tenant = f"{args.tenant_prefix}-{key % args.concurrency}"
        prompt = f"load-test {args.run_id} prompt-{key}"
        return tenant, prompt

    if args.warm_cache:
        cache_warmup_errors = []
        warmup_modes = [False]
        if args.stream_ratio > 0:
            warmup_modes.append(True)
        for index in range(args.prompt_cardinality):
            tenant, prompt = dimensions(index)
            for stream in warmup_modes:
                sample = execute_request(
                    args.url, args.model, tenant, prompt, stream, args.timeout, args.api_key
                )
                if sample.error:
                    cache_warmup_errors.append(asdict(sample))
        if cache_warmup_errors:
            raise RuntimeError(
                json.dumps({"cacheWarmupErrors": cache_warmup_errors}, ensure_ascii=False)
            )

    def run(index: int) -> Sample:
        tenant, prompt = dimensions(index)
        stream = (index % 100) < round(args.stream_ratio * 100)
        return execute_request(
            args.url, args.model, tenant, prompt, stream, args.timeout, args.api_key
        )

    wall_started_at = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        samples = list(executor.map(run, range(args.requests)))
    wall_seconds = time.perf_counter() - wall_started_at

    successes = [sample for sample in samples if sample.error is None]
    latency_values = [sample.latency_ms for sample in successes]
    ttft_values = [sample.ttft_ms for sample in successes if sample.ttft_ms is not None]
    errors = [sample.error for sample in samples if sample.error]
    result = {
        "configuration": {
            "url": args.url,
            "model": args.model,
            "requests": args.requests,
            "concurrency": args.concurrency,
            "promptCardinality": args.prompt_cardinality,
            "streamRatio": args.stream_ratio,
            "warmupRequests": args.warmup_requests,
            "warmCache": args.warm_cache,
            "runId": args.run_id,
        },
        "summary": {
            "successes": len(successes),
            "errors": len(errors),
            "wallSeconds": round(wall_seconds, 3),
            "throughputRps": round(len(successes) / wall_seconds, 3) if wall_seconds else None,
            "latencyMs": distribution(latency_values),
            "ttftMs": distribution(ttft_values),
            "cacheStatuses": dict(sorted(Counter(sample.cache_status for sample in successes).items())),
            "statusCodes": dict(sorted(Counter(str(sample.status) for sample in samples).items())),
            "errorSamples": errors[:10],
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
