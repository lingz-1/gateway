#!/usr/bin/env python3
"""Verify SSE integrity and lifecycle cleanup with deliberately slow clients."""

from __future__ import annotations

import argparse
import http.client
import json
import socket
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse
from urllib.request import urlopen


def metric_value(metrics_url: str, name: str) -> float:
    with urlopen(metrics_url, timeout=5) as response:
        body = response.read().decode("utf-8")
    for line in body.splitlines():
        if line.startswith(f"{name} "):
            return float(line.rsplit(" ", 1)[1])
    raise RuntimeError(f"Metric not found: {name}")


def consume_stream(
        index: int,
        url: str,
        model: str,
        prompt_bytes: int,
        read_bytes: int,
        read_delay: float,
        receive_buffer: int,
        ready: threading.Event,
        resume: threading.Event,
) -> dict[str, object]:
    parsed = urlparse(url)
    trace_id = f"trace-slow-consumer-{index}-{uuid.uuid4().hex}"
    prompt = "x" * prompt_bytes
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": True,
    }).encode("utf-8")
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=60)
    started_at = time.perf_counter()
    try:
        connection.connect()
        if connection.sock is None:
            raise RuntimeError("HTTP connection did not expose a socket")
        connection.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, receive_buffer)
        connection.request("POST", parsed.path, body=body, headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": f"slow-consumer-{index}",
            "X-Trace-Id": trace_id,
        })
        response = connection.getresponse()
        ready.set()
        if not resume.wait(timeout=20):
            raise TimeoutError("Timed out waiting to resume slow consumer")
        chunks: list[bytes] = []
        while True:
            chunk = response.read(read_bytes)
            if not chunk:
                break
            chunks.append(chunk)
            time.sleep(read_delay)
        payload = b"".join(chunks).decode("utf-8", errors="replace")
        data_lines = [
            line[5:].strip()
            for line in payload.splitlines()
            if line.startswith("data:")
        ]
        errors = []
        content_chunks = 0
        metadata_seen = False
        for line in data_lines:
            if line == "[DONE]":
                continue
            event = json.loads(line)
            if event.get("code"):
                errors.append(event.get("code"))
            choices = event.get("choices") or []
            if choices and (choices[0].get("delta") or {}).get("content"):
                content_chunks += 1
            metadata_seen = metadata_seen or bool(event.get("metadata"))
        return {
            "traceId": trace_id,
            "httpStatus": response.status,
            "done": "[DONE]" in data_lines,
            "errors": errors,
            "contentChunks": content_chunks,
            "metadataSeen": metadata_seen,
            "responseBytes": len(payload.encode("utf-8")),
            "elapsedSeconds": round(time.perf_counter() - started_at, 3),
        }
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8080/v1/chat/completions")
    parser.add_argument("--metrics-url", default="http://127.0.0.1:8081/actuator/prometheus")
    parser.add_argument("--model", default="stream-slow-model")
    parser.add_argument("--clients", type=int, default=2)
    parser.add_argument("--prompt-bytes", type=int, default=8388608)
    parser.add_argument("--initial-pause", type=float, default=3.0)
    parser.add_argument("--read-bytes", type=int, default=4096)
    parser.add_argument("--read-delay", type=float, default=0.002)
    parser.add_argument("--receive-buffer", type=int, default=4096)
    args = parser.parse_args()

    ready_events = [threading.Event() for _ in range(args.clients)]
    resume = threading.Event()
    with ThreadPoolExecutor(max_workers=args.clients) as executor:
        futures = [
            executor.submit(
                consume_stream,
                index,
                args.url,
                args.model,
                args.prompt_bytes,
                args.read_bytes,
                args.read_delay,
                args.receive_buffer,
                ready_events[index],
                resume,
            )
            for index in range(args.clients)
        ]
        ready = all(event.wait(timeout=15) for event in ready_events)
        if ready:
            time.sleep(args.initial_pause)
            active_during_pause = metric_value(args.metrics_url, "lingshu_chat_streams_active")
        else:
            active_during_pause = -1.0
        resume.set()
        results = [future.result(timeout=60) for future in futures]

    time.sleep(0.2)
    active_after_completion = metric_value(args.metrics_url, "lingshu_chat_streams_active")
    checks = {
        "all_clients_reached_stream": ready,
        "streams_remained_active_while_clients_paused": active_during_pause >= args.clients,
        "all_http_200": all(item["httpStatus"] == 200 for item in results),
        "all_received_done": all(item["done"] is True for item in results),
        "no_sse_errors": all(not item["errors"] for item in results),
        "all_received_content_and_metadata": all(
            item["contentChunks"] > 0 and item["metadataSeen"] is True
            for item in results
        ),
        "active_streams_returned_to_zero": active_after_completion == 0.0,
    }
    report = {
        "configuration": vars(args),
        "activeDuringPause": active_during_pause,
        "activeAfterCompletion": active_after_completion,
        "clients": results,
        "checks": checks,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
