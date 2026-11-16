#!/usr/bin/env python3
"""Exercise streaming fallback and post-first-chunk failure contracts."""

from __future__ import annotations

import argparse
import http.client
import json
import uuid
from urllib.parse import urlparse


def run_stream(url: str, model: str) -> dict[str, object]:
    parsed = urlparse(url)
    trace_id = f"trace-stream-fault-{uuid.uuid4().hex}"
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": "stream fault injection"}],
        "stream": True,
    }).encode("utf-8")
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=10)
    contents: list[str] = []
    errors: list[dict[str, object]] = []
    metadata: dict[str, object] | None = None
    done = False
    try:
        connection.request("POST", parsed.path, body=body, headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": "stream-fault-test",
            "X-Trace-Id": trace_id,
        })
        response = connection.getresponse()
        for raw_line in response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                done = True
                continue
            try:
                event = json.loads(payload)
            except json.JSONDecodeError:
                continue
            if event.get("code"):
                errors.append(event)
                continue
            choices = event.get("choices") or []
            if choices:
                delta = choices[0].get("delta") or {}
                content = delta.get("content")
                if content:
                    contents.append(content)
            if event.get("metadata"):
                metadata = event["metadata"]
        return {
            "traceId": trace_id,
            "httpStatus": response.status,
            "done": done,
            "contentChunks": contents,
            "errors": errors,
            "metadata": metadata,
        }
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8080/v1/chat/completions")
    parser.add_argument("--model", default="stream-fault-model")
    parser.add_argument("--scenario", choices=("fallback", "truncation"), required=True)
    args = parser.parse_args()

    result = run_stream(args.url, args.model)
    metadata = result.get("metadata") or {}
    if args.scenario == "fallback":
        checks = {
            "http_200": result["httpStatus"] == 200,
            "received_done": result["done"] is True,
            "no_sse_error": not result["errors"],
            "fallback_content_only": "".join(result["contentChunks"]).startswith("stub-fast:"),
            "provider_is_fallback": metadata.get("provider") == "stub-fast",
            "attempt_order_preserved": metadata.get("providerAttempts") == ["stub", "stub-fast"],
        }
    else:
        checks = {
            "http_200_handshake": result["httpStatus"] == 200,
            "partial_content_received": len(result["contentChunks"]) > 0,
            "received_streaming_error": any(
                item.get("code") == "STREAMING_ERROR" for item in result["errors"]
            ),
            "missing_done_is_failure": result["done"] is False,
            "no_success_metadata": result["metadata"] is None,
        }
    result["checks"] = checks
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
