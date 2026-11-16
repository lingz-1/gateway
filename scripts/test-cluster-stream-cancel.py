#!/usr/bin/env python3
"""Verify that a cancellation received by one Core instance stops a stream on another."""

from __future__ import annotations

import argparse
import http.client
import json
import threading
import time
import uuid
from urllib.parse import urlparse


def post(url: str, headers: dict[str, str], body: bytes | None = None) -> tuple[int, bytes]:
    parsed = urlparse(url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=10)
    try:
        connection.request("POST", parsed.path, body=body, headers=headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stream-core", default="http://127.0.0.1:18081")
    parser.add_argument("--cancel-core", default="http://127.0.0.1:18082")
    parser.add_argument("--tenant", default="cluster-cancel-test")
    args = parser.parse_args()

    trace_id = f"trace-cluster-{uuid.uuid4().hex}"
    headers = {
        "Content-Type": "application/json",
        "X-Tenant-Id": args.tenant,
        "X-Trace-Id": trace_id,
    }
    request_body = json.dumps({
        "model": "stub-echo-v1",
        "messages": [{"role": "user", "content": "cluster cancellation test"}],
        "stream": True,
    }).encode("utf-8")
    stream_result: dict[str, object] = {}

    def consume_stream() -> None:
        status, body = post(
            f"{args.stream_core}/v1/chat/completions",
            headers,
            request_body,
        )
        stream_result["status"] = status
        stream_result["body"] = body.decode("utf-8", errors="replace")

    stream_thread = threading.Thread(target=consume_stream, daemon=True)
    stream_thread.start()

    cancel_url = f"{args.cancel_core}/v1/stream-cancellations/{trace_id}"
    cancel_headers = {"X-Tenant-Id": args.tenant}
    deadline = time.monotonic() + 5
    first_status = 404
    first_body = b""
    cancel_request_ms = None
    while time.monotonic() < deadline and first_status == 404:
        time.sleep(0.1)
        cancel_started = time.perf_counter()
        first_status, first_body = post(cancel_url, cancel_headers)
        cancel_request_ms = (time.perf_counter() - cancel_started) * 1000

    accepted_at = time.perf_counter()
    second_status, second_body = post(cancel_url, cancel_headers)
    stream_thread.join(timeout=10)
    stream_stopped_after_cancel_ms = (time.perf_counter() - accepted_at) * 1000
    if stream_thread.is_alive():
        raise RuntimeError("The remote stream did not stop after cancellation")

    stream_body = str(stream_result.get("body", ""))
    first_payload = json.loads(first_body or b"{}")
    second_payload = json.loads(second_body or b"{}")
    checks = {
        "first_cancel_accepted": first_status == 202
        and first_payload.get("cancellationAccepted") is True,
        "duplicate_is_idempotent": second_status == 200
        and second_payload.get("cancellationAccepted") is False,
        "stream_returned_http_200": stream_result.get("status") == 200,
        "stream_has_cancel_event": "STREAM_CANCELLED" in stream_body,
        "stream_has_no_done": "[DONE]" not in stream_body,
    }
    print(json.dumps({
        "traceId": trace_id,
        "firstCancelStatus": first_status,
        "secondCancelStatus": second_status,
        "cancelRequestLatencyMs": round(cancel_request_ms or 0, 3),
        "streamStoppedAfterCancelMs": round(stream_stopped_after_cancel_ms, 3),
        "checks": checks,
    }, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
