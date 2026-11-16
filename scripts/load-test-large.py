import argparse
import http.client
import json
import math
import os
import ssl
import sys
import threading
import time
import urllib.request
import uuid
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Sample:
    status: int
    latency_us: int
    ttft_us: int | None
    cache_status: str
    error: str | None
    stream: bool = False
    stream_chunks: int = 0
    inter_chunk_us: tuple[int, ...] = ()


@dataclass(frozen=True)
class StreamParseResult:
    ttft_us: int | None
    completion_us: int | None
    cache_status: str
    chunks: int
    inter_chunk_us: tuple[int, ...]
    error: str | None


@dataclass
class Histogram:
    bucket_width_us: int = 100
    buckets: Counter[int] = field(default_factory=Counter)
    count: int = 0
    total_us: int = 0
    maximum_us: int = 0

    def add(self, value_us: int) -> None:
        value_us = max(0, value_us)
        self.buckets[value_us // self.bucket_width_us] += 1
        self.count += 1
        self.total_us += value_us
        self.maximum_us = max(self.maximum_us, value_us)

    def merge(self, other: "Histogram") -> None:
        self.buckets.update(other.buckets)
        self.count += other.count
        self.total_us += other.total_us
        self.maximum_us = max(self.maximum_us, other.maximum_us)

    def percentile_ms(self, quantile: float) -> float | None:
        if self.count == 0:
            return None
        target = max(1, math.ceil(self.count * quantile))
        observed = 0
        for bucket, bucket_count in sorted(self.buckets.items()):
            observed += bucket_count
            if observed >= target:
                return round(((bucket + 1) * self.bucket_width_us) / 1000, 3)
        return round(self.maximum_us / 1000, 3)

    def summary(self) -> dict[str, float | int | None]:
        return {
            "count": self.count,
            "average": round(self.total_us / self.count / 1000, 3) if self.count else None,
            "p50": self.percentile_ms(0.50),
            "p95": self.percentile_ms(0.95),
            "p99": self.percentile_ms(0.99),
            "max": round(self.maximum_us / 1000, 3) if self.count else None,
            "resolutionMs": round(self.bucket_width_us / 1000, 3),
        }


@dataclass
class WorkerStats:
    successes: int = 0
    errors: int = 0
    latency: Histogram = field(default_factory=Histogram)
    ttft: Histogram = field(default_factory=Histogram)
    stream_latency: Histogram = field(default_factory=Histogram)
    non_stream_latency: Histogram = field(default_factory=Histogram)
    inter_chunk_latency: Histogram = field(default_factory=Histogram)
    stream_successes: int = 0
    stream_errors: int = 0
    non_stream_successes: int = 0
    non_stream_errors: int = 0
    stream_chunks: int = 0
    max_stream_chunks: int = 0
    status_codes: Counter[str] = field(default_factory=Counter)
    cache_statuses: Counter[str] = field(default_factory=Counter)
    error_types: Counter[str] = field(default_factory=Counter)
    error_samples: list[str] = field(default_factory=list)

    def record(self, sample: Sample) -> None:
        self.status_codes[str(sample.status)] += 1
        self.cache_statuses[sample.cache_status] += 1
        if sample.error is None:
            self.successes += 1
            self.latency.add(sample.latency_us)
            if sample.stream:
                self.stream_successes += 1
                self.stream_latency.add(sample.latency_us)
                self.stream_chunks += sample.stream_chunks
                self.max_stream_chunks = max(self.max_stream_chunks, sample.stream_chunks)
                for value_us in sample.inter_chunk_us:
                    self.inter_chunk_latency.add(value_us)
            else:
                self.non_stream_successes += 1
                self.non_stream_latency.add(sample.latency_us)
            if sample.ttft_us is not None:
                self.ttft.add(sample.ttft_us)
            return
        self.errors += 1
        if sample.stream:
            self.stream_errors += 1
        else:
            self.non_stream_errors += 1
        error_type = sample.error.split(":", 1)[0]
        self.error_types[error_type] += 1
        if len(self.error_samples) < 10:
            self.error_samples.append(sample.error)

    def merge(self, other: "WorkerStats") -> None:
        self.successes += other.successes
        self.errors += other.errors
        self.latency.merge(other.latency)
        self.ttft.merge(other.ttft)
        self.stream_latency.merge(other.stream_latency)
        self.non_stream_latency.merge(other.non_stream_latency)
        self.inter_chunk_latency.merge(other.inter_chunk_latency)
        self.stream_successes += other.stream_successes
        self.stream_errors += other.stream_errors
        self.non_stream_successes += other.non_stream_successes
        self.non_stream_errors += other.non_stream_errors
        self.stream_chunks += other.stream_chunks
        self.max_stream_chunks = max(self.max_stream_chunks, other.max_stream_chunks)
        self.status_codes.update(other.status_codes)
        self.cache_statuses.update(other.cache_statuses)
        self.error_types.update(other.error_types)
        remaining = max(0, 10 - len(self.error_samples))
        self.error_samples.extend(other.error_samples[:remaining])


class PersistentHttpClient:

    def __init__(self, url: str, timeout: float) -> None:
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError(f"Unsupported URL: {url}")
        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.path = parsed.path or "/"
        if parsed.query:
            self.path += "?" + parsed.query
        self.timeout = timeout
        self.connection: http.client.HTTPConnection | None = None

    def connect(self) -> http.client.HTTPConnection:
        if self.connection is None:
            if self.scheme == "https":
                self.connection = http.client.HTTPSConnection(
                    self.host,
                    self.port,
                    timeout=self.timeout,
                    context=ssl.create_default_context(),
                )
            else:
                self.connection = http.client.HTTPConnection(
                    self.host,
                    self.port,
                    timeout=self.timeout,
                )
        return self.connection

    def reset(self) -> None:
        if self.connection is not None:
            self.connection.close()
        self.connection = None

    def close(self) -> None:
        self.reset()


def request_headers(tenant_id: str, trace_id: str, api_key: str | None) -> dict[str, str]:
    headers = {
        "Accept": "text/event-stream, application/json",
        "Connection": "keep-alive",
        "Content-Type": "application/json",
        "X-Tenant-Id": tenant_id,
        "X-Trace-Id": trace_id,
    }
    if api_key:
        headers["X-API-Key"] = api_key
    return headers


def parse_stream(response: http.client.HTTPResponse, started_ns: int) -> StreamParseResult:
    ttft_us: int | None = None
    completion_us: int | None = None
    cache_status = str(response.getheader("X-LingShu-Cache", "UNKNOWN")).upper()
    chunks = 0
    inter_chunk_us: list[int] = []
    previous_chunk_ns: int | None = None
    stream_error: str | None = None
    event_name: str | None = None
    while True:
        raw_line = response.readline()
        if not raw_line:
            break
        line = raw_line.decode("utf-8").strip()
        if line.startswith("event:"):
            event_name = line[len("event:"):].strip()
            continue
        if not line.startswith("data:"):
            continue
        data = line[len("data:"):].strip()
        if not data:
            continue
        if data == "[DONE]":
            completion_us = (time.perf_counter_ns() - started_ns) // 1000
            event_name = None
            continue
        event = json.loads(data)
        if event_name == "error" or event.get("error"):
            message = event.get("message") or event.get("error") or "stream returned an error event"
            stream_error = f"StreamError: {message}"
            event_name = None
            continue
        choices = event.get("choices", [])
        if choices:
            delta = choices[0].get("delta", {})
            if delta.get("content") or delta.get("tool_calls"):
                observed_ns = time.perf_counter_ns()
                if ttft_us is None:
                    ttft_us = (observed_ns - started_ns) // 1000
                if previous_chunk_ns is not None:
                    inter_chunk_us.append((observed_ns - previous_chunk_ns) // 1000)
                previous_chunk_ns = observed_ns
                chunks += 1
        metadata = event.get("metadata")
        if metadata:
            cache_status = str(metadata.get("cacheStatus", cache_status)).upper()
        event_name = None
    if stream_error is None and completion_us is None:
        stream_error = "StreamIncomplete: connection closed before [DONE]"
    return StreamParseResult(
        ttft_us,
        completion_us,
        cache_status,
        chunks,
        tuple(inter_chunk_us),
        stream_error,
    )


def execute_request(
        client: PersistentHttpClient,
        model: str,
        tenant_id: str,
        prompt: str,
        stream: bool,
        api_key: str | None,
) -> Sample:
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": stream,
        "temperature": 0.0,
    }, separators=(",", ":")).encode("utf-8")
    trace_id = f"benchmark-{uuid.uuid4().hex}"
    started_ns = time.perf_counter_ns()
    status = 0
    try:
        connection = client.connect()
        connection.request(
            "POST",
            client.path,
            body=body,
            headers=request_headers(tenant_id, trace_id, api_key),
        )
        response = connection.getresponse()
        status = response.status
        if status < 200 or status >= 300:
            response.read()
            return Sample(
                status,
                (time.perf_counter_ns() - started_ns) // 1000,
                None,
                "UNKNOWN",
                f"HTTP {status}",
                stream=stream,
            )
        if stream:
            parsed = parse_stream(response, started_ns)
            latency_us = parsed.completion_us or (time.perf_counter_ns() - started_ns) // 1000
            return Sample(
                status,
                latency_us,
                parsed.ttft_us,
                parsed.cache_status,
                parsed.error,
                stream=True,
                stream_chunks=parsed.chunks,
                inter_chunk_us=parsed.inter_chunk_us,
            )
        else:
            cache_status = str(response.getheader("X-LingShu-Cache", "UNKNOWN")).upper()
            payload = json.loads(response.read().decode("utf-8"))
            cache_status = str(payload.get("metadata", {}).get("cacheStatus", cache_status)).upper()
            ttft_us = None
        return Sample(
            status,
            (time.perf_counter_ns() - started_ns) // 1000,
            ttft_us,
            cache_status,
            None,
            stream=False,
        )
    except Exception as exception:
        client.reset()
        return Sample(
            status,
            (time.perf_counter_ns() - started_ns) // 1000,
            None,
            "UNKNOWN",
            f"{type(exception).__name__}: {exception}",
            stream=stream,
        )


def dimensions(index: int, concurrency: int, prompt_cardinality: int, tenant_prefix: str, run_id: str) -> tuple[str, str]:
    key = index % prompt_cardinality
    tenant_id = f"{tenant_prefix}-{key % concurrency}"
    prompt = f"large-load-test {run_id} prompt-{key}"
    return tenant_id, prompt


def worker(
        worker_id: int,
        args: argparse.Namespace,
        run_id: str,
        stop_event: threading.Event,
        progress: list[int],
) -> WorkerStats:
    stats = WorkerStats()
    client = PersistentHttpClient(args.url, args.timeout)
    try:
        for index in range(worker_id, args.requests, args.concurrency):
            if stop_event.is_set():
                break
            tenant_id, prompt = dimensions(
                index, args.concurrency, args.prompt_cardinality, args.tenant_prefix, run_id
            )
            stream = (index % 100) < round(args.stream_ratio * 100)
            stats.record(execute_request(client, args.model, tenant_id, prompt, stream, args.api_key))
            progress[worker_id] += 1
    finally:
        client.close()
    return stats


def execute_parallel(
        args: argparse.Namespace,
        run_id: str,
        stop_event: threading.Event,
) -> tuple[WorkerStats, float, bool]:
    results: list[WorkerStats | None] = [None] * args.concurrency
    progress = [0] * args.concurrency
    started_at = time.perf_counter()

    def run(worker_id: int) -> None:
        try:
            results[worker_id] = worker(worker_id, args, run_id, stop_event, progress)
        except Exception as exception:
            failed = WorkerStats()
            failed.record(Sample(
                0,
                0,
                None,
                "UNKNOWN",
                f"WorkerFailure: {type(exception).__name__}: {exception}",
            ))
            results[worker_id] = failed
            stop_event.set()

    threads = [
        threading.Thread(target=run, args=(worker_id,), name=f"load-worker-{worker_id}")
        for worker_id in range(args.concurrency)
    ]
    for thread in threads:
        thread.start()

    interrupted = False
    try:
        while any(thread.is_alive() for thread in threads):
            for thread in threads:
                thread.join(timeout=0.1)
            elapsed = time.perf_counter() - started_at
            if elapsed >= args.progress_interval:
                completed = sum(progress)
                if not hasattr(execute_parallel, "last_report"):
                    execute_parallel.last_report = 0.0
                if elapsed - execute_parallel.last_report >= args.progress_interval:
                    current_qps = completed / elapsed if elapsed else 0.0
                    print(json.dumps({
                        "progress": completed,
                        "total": args.requests,
                        "percent": round(completed * 100 / args.requests, 2),
                        "elapsedSeconds": round(elapsed, 1),
                        "averageQps": round(current_qps, 3),
                    }), file=sys.stderr, flush=True)
                    execute_parallel.last_report = elapsed
    except KeyboardInterrupt:
        interrupted = True
        stop_event.set()
    finally:
        for thread in threads:
            thread.join()

    merged = WorkerStats()
    for result in results:
        if result is not None:
            merged.merge(result)
    return merged, time.perf_counter() - started_at, interrupted


def warmup(args: argparse.Namespace, run_id: str) -> None:
    if args.warmup_requests == 0:
        return
    client = PersistentHttpClient(args.url, args.timeout)
    try:
        for index in range(args.warmup_requests):
            stream = index % 2 == 0 and args.stream_ratio > 0
            sample = execute_request(
                client,
                args.model,
                f"{args.tenant_prefix}-warmup-{index % max(1, args.concurrency)}",
                f"large-load-test warmup {run_id} prompt-{index}",
                stream,
                args.api_key,
            )
            if sample.error:
                raise RuntimeError(f"Warmup failed: {sample.error}")
    finally:
        client.close()


def warm_cache(args: argparse.Namespace, run_id: str) -> None:
    if not args.warm_cache:
        return
    modes = [False]
    if args.stream_ratio > 0:
        modes.append(True)
    client = PersistentHttpClient(args.url, args.timeout)
    try:
        for index in range(args.prompt_cardinality):
            tenant_id, prompt = dimensions(
                index, args.concurrency, args.prompt_cardinality, args.tenant_prefix, run_id
            )
            for stream in modes:
                sample = execute_request(client, args.model, tenant_id, prompt, stream, args.api_key)
                if sample.error:
                    raise RuntimeError(f"Cache warmup failed: {sample.error}")
    finally:
        client.close()


def prometheus_metrics(url: str, timeout: float) -> dict[str, float | str | None]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            text = response.read().decode("utf-8")
    except Exception as exception:
        return {"error": f"{type(exception).__name__}: {exception}"}

    values: dict[str, list[tuple[str, float]]] = {}
    for line in text.splitlines():
        if not line or line.startswith("#") or " " not in line:
            continue
        metric, raw_value = line.rsplit(" ", 1)
        name = metric.split("{", 1)[0]
        try:
            value = float(raw_value)
        except ValueError:
            continue
        values.setdefault(name, []).append((metric, value))

    def first(name: str) -> float | None:
        entries = values.get(name, [])
        return entries[0][1] if entries else None

    heap_used = sum(
        value for metric, value in values.get("jvm_memory_used_bytes", []) if 'area="heap"' in metric
    )
    return {
        "processCpuUsage": first("process_cpu_usage"),
        "systemCpuUsage": first("system_cpu_usage"),
        "heapUsedBytes": heap_used or None,
        "liveThreads": first("jvm_threads_live_threads"),
        "gcPauseCount": sum(value for _, value in values.get("jvm_gc_pause_seconds_count", [])) or None,
        "gcPauseSeconds": sum(value for _, value in values.get("jvm_gc_pause_seconds_sum", [])) or None,
    }


def resource_sampler(
        url: str,
        timeout: float,
        interval: float,
        stop_event: threading.Event,
        samples: list[dict[str, Any]],
        started_at: float,
) -> None:
    while not stop_event.is_set():
        samples.append({
            "elapsedSeconds": round(time.perf_counter() - started_at, 3),
            "metrics": prometheus_metrics(url, timeout),
        })
        stop_event.wait(interval)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a bounded-memory, keep-alive LingShu load test.")
    parser.add_argument("--url", default="http://127.0.0.1:8080/v1/chat/completions")
    parser.add_argument("--model", default="stub-echo-v1")
    parser.add_argument("--requests", type=int, default=10_000)
    parser.add_argument("--concurrency", type=int, default=100)
    parser.add_argument("--prompt-cardinality", type=int, default=1_000)
    parser.add_argument("--stream-ratio", type=float, default=0.0)
    parser.add_argument("--warmup-requests", type=int, default=1_000)
    parser.add_argument("--warm-cache", action="store_true")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--tenant-prefix", default="large-perf")
    parser.add_argument("--run-id", default=time.strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--label", default="benchmark")
    parser.add_argument("--api-key", default=os.environ.get("LINGSHU_GATEWAY_API_KEY"))
    parser.add_argument("--progress-interval", type=float, default=10.0)
    parser.add_argument("--metrics-interval", type=float, default=1.0)
    parser.add_argument("--metrics-timeout", type=float, default=5.0)
    parser.add_argument("--metrics-cooldown", type=float, default=2.0)
    parser.add_argument("--gateway-metrics-url", default="http://127.0.0.1:8080/actuator/prometheus")
    parser.add_argument("--core-metrics-url", default="http://127.0.0.1:8081/actuator/prometheus")
    parser.add_argument("--no-metrics", action="store_true")
    default_output = Path(os.environ.get("LINGSHU_DATA_ROOT", r"E:\LingShuData")) / "benchmarks"
    parser.add_argument("--output-dir", type=Path, default=default_output)
    args = parser.parse_args()
    if args.requests < 1 or args.concurrency < 1 or args.prompt_cardinality < 1:
        parser.error("requests, concurrency, and prompt-cardinality must be positive")
    if args.warmup_requests < 0:
        parser.error("warmup-requests cannot be negative")
    if not 0.0 <= args.stream_ratio <= 1.0:
        parser.error("stream-ratio must be between 0 and 1")
    if (args.timeout <= 0 or args.progress_interval <= 0 or args.metrics_interval <= 0
            or args.metrics_timeout <= 0 or args.metrics_cooldown < 0):
        parser.error("timeout and reporting intervals must be positive")
    return args


def safe_label(value: str) -> str:
    cleaned = "".join(character if character.isalnum() or character in "-_" else "-" for character in value)
    return cleaned.strip("-") or "benchmark"


def main() -> None:
    args = parse_args()
    run_id = args.run_id
    warmup(args, run_id)
    warm_cache(args, run_id)

    directory_name = f"{time.strftime('%Y%m%d-%H%M%S')}-{safe_label(args.label)}-{safe_label(run_id)}"
    run_directory = args.output_dir / directory_name
    if run_directory.exists():
        run_directory = args.output_dir / f"{directory_name}-{uuid.uuid4().hex[:8]}"
    run_directory.mkdir(parents=True, exist_ok=False)

    stop_event = threading.Event()
    metric_stop = threading.Event()
    resource_samples: dict[str, list[dict[str, Any]]] = {"gateway": [], "core": []}
    test_started_at = time.perf_counter()
    samplers: list[threading.Thread] = []
    if not args.no_metrics:
        samplers = [threading.Thread(
            target=resource_sampler,
            args=(url, args.metrics_timeout, args.metrics_interval, metric_stop,
                  resource_samples[name], test_started_at),
            name=f"resource-sampler-{name}",
        ) for name, url in (
            ("gateway", args.gateway_metrics_url),
            ("core", args.core_metrics_url),
        )]
        for sampler in samplers:
            sampler.start()

    stats, wall_seconds, interrupted = execute_parallel(args, run_id, stop_event)
    if samplers and args.metrics_cooldown > 0:
        time.sleep(args.metrics_cooldown)
    metric_stop.set()
    for sampler in samplers:
        sampler.join()

    attempted = stats.successes + stats.errors
    stream_attempted = stats.stream_successes + stats.stream_errors
    non_stream_attempted = stats.non_stream_successes + stats.non_stream_errors
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
            "timeoutSeconds": args.timeout,
            "runId": run_id,
            "label": args.label,
            "apiKeyConfigured": bool(args.api_key),
        },
        "summary": {
            "attempted": attempted,
            "successes": stats.successes,
            "errors": stats.errors,
            "successRatePercent": round(stats.successes * 100 / attempted, 5) if attempted else 0.0,
            "interrupted": interrupted,
            "wallSeconds": round(wall_seconds, 3),
            "throughputRps": round(stats.successes / wall_seconds, 3) if wall_seconds else None,
            "latencyMs": stats.latency.summary(),
            "ttftMs": stats.ttft.summary(),
            "streaming": {
                "attempted": stream_attempted,
                "successes": stats.stream_successes,
                "errors": stats.stream_errors,
                "successRatePercent": round(
                    stats.stream_successes * 100 / stream_attempted, 5
                ) if stream_attempted else None,
                "completedRps": round(
                    stats.stream_successes / wall_seconds, 3
                ) if wall_seconds else None,
                "endToEndLatencyMs": stats.stream_latency.summary(),
                "ttftMs": stats.ttft.summary(),
                "missingTtft": stats.stream_successes - stats.ttft.count,
                "interChunkLatencyMs": stats.inter_chunk_latency.summary(),
                "chunks": {
                    "total": stats.stream_chunks,
                    "averagePerSuccessfulStream": round(
                        stats.stream_chunks / stats.stream_successes, 3
                    ) if stats.stream_successes else None,
                    "maxPerStream": stats.max_stream_chunks,
                },
            },
            "nonStreaming": {
                "attempted": non_stream_attempted,
                "successes": stats.non_stream_successes,
                "errors": stats.non_stream_errors,
                "successRatePercent": round(
                    stats.non_stream_successes * 100 / non_stream_attempted, 5
                ) if non_stream_attempted else None,
                "completedRps": round(
                    stats.non_stream_successes / wall_seconds, 3
                ) if wall_seconds else None,
                "endToEndLatencyMs": stats.non_stream_latency.summary(),
            },
            "cacheStatuses": dict(sorted(stats.cache_statuses.items())),
            "statusCodes": dict(sorted(stats.status_codes.items())),
            "errorTypes": dict(sorted(stats.error_types.items())),
            "errorSamples": stats.error_samples,
        },
        "artifacts": {
            "resourceSamples": "resource-samples.json" if samplers else None,
        },
    }

    (run_directory / "summary.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    if samplers:
        (run_directory / "resource-samples.json").write_text(
            json.dumps(resource_samples, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    print(json.dumps({"output": str(run_directory), **result}, ensure_ascii=False, indent=2))
    if interrupted:
        raise SystemExit(130)
    if stats.errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
