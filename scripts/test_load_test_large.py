import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("load-test-large.py")
SPEC = importlib.util.spec_from_file_location("load_test_large", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class FakeResponse:

    def __init__(self, body: str) -> None:
        self.lines = iter(body.splitlines(keepends=True))

    def getheader(self, name: str, default: str | None = None) -> str | None:
        return default

    def readline(self) -> bytes:
        try:
            return next(self.lines).encode("utf-8")
        except StopIteration:
            return b""


class ParseStreamTest(unittest.TestCase):

    def test_requires_done_and_counts_content_chunks(self) -> None:
        response = FakeResponse(
            'data: {"choices":[{"delta":{"content":"hello"}}]}\n\n'
            'data: {"choices":[{"delta":{"content":" world"}}],'
            '"metadata":{"cacheStatus":"MISS"}}\n\n'
            'data: [DONE]\n\n'
        )

        result = MODULE.parse_stream(response, 1)

        self.assertIsNone(result.error)
        self.assertIsNotNone(result.ttft_us)
        self.assertIsNotNone(result.completion_us)
        self.assertEqual(2, result.chunks)
        self.assertEqual(1, len(result.inter_chunk_us))
        self.assertEqual("MISS", result.cache_status)

    def test_rejects_connection_closed_without_done(self) -> None:
        response = FakeResponse(
            'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'
        )

        result = MODULE.parse_stream(response, 1)

        self.assertEqual(
            "StreamIncomplete: connection closed before [DONE]",
            result.error,
        )

    def test_rejects_sse_error_event(self) -> None:
        response = FakeResponse(
            'event: error\n'
            'data: {"code":"STREAMING_ERROR","message":"failed"}\n\n'
            'data: [DONE]\n\n'
        )

        result = MODULE.parse_stream(response, 1)

        self.assertEqual("StreamError: failed", result.error)


if __name__ == "__main__":
    unittest.main()
