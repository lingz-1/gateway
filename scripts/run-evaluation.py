#!/usr/bin/env python3
"""Run lightweight, repeatable evaluation suites from a YAML-compatible manifest."""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


def load_manifest(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as source:
        return json.load(source)


def run_command(
        command: list[str],
        cwd: Path,
        timeout_seconds: int,
        environment: dict[str, str] | None = None,
        capture_output: bool = True,
) -> dict[str, Any]:
    merged_environment = os.environ.copy()
    if environment:
        merged_environment.update(environment)
    started_at = time.perf_counter()
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            env=merged_environment,
            stdout=subprocess.PIPE if capture_output else subprocess.DEVNULL,
            stderr=subprocess.PIPE if capture_output else subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
        )
        return {
            "command": command,
            "exitCode": completed.returncode,
            "durationMs": round((time.perf_counter() - started_at) * 1000, 3),
            "stdout": completed.stdout or "",
            "stderr": completed.stderr or "",
            "timedOut": False,
        }
    except subprocess.TimeoutExpired as exception:
        return {
            "command": command,
            "exitCode": -1,
            "durationMs": round((time.perf_counter() - started_at) * 1000, 3),
            "stdout": decode_timeout_output(exception.stdout),
            "stderr": decode_timeout_output(exception.stderr),
            "timedOut": True,
        }


def decode_timeout_output(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def parse_json_output(stdout: str) -> dict[str, Any] | None:
    text = stdout.strip()
    if not text:
        return None
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def checks_passed(result: dict[str, Any] | None) -> bool:
    if result is None:
        return False
    checks = result.get("checks")
    return isinstance(checks, dict) and bool(checks) and all(checks.values())


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(percentile_value * len(ordered)) - 1)
    return round(ordered[index], 3)


def nested_number(result: dict[str, Any], path: str) -> float | None:
    current: Any = result
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    if isinstance(current, bool) or not isinstance(current, (int, float)):
        return None
    return float(current)


def summarize_suite(
        suite: dict[str, Any],
        cases: list[dict[str, Any]],
) -> dict[str, Any]:
    successful = sum(1 for case in cases if case["passed"])
    durations = [float(case["process"]["durationMs"]) for case in cases]
    metrics: dict[str, Any] = {}
    for path in suite.get("test", {}).get("metrics", []):
        values = [
            value
            for case in cases
            if case["result"] is not None
            for value in [nested_number(case["result"], path)]
            if value is not None
        ]
        metrics[path] = {
            "samples": len(values),
            "p50": percentile(values, 0.50),
            "p95": percentile(values, 0.95),
            "max": round(max(values), 3) if values else None,
        }
    return {
        "id": suite["id"],
        "description": suite.get("description", ""),
        "requested": suite["repeat"],
        "executed": len(cases),
        "successful": successful,
        "failed": len(cases) - successful,
        "successRate": round(successful / len(cases), 6) if cases else 0.0,
        "processDurationMs": {
            "p50": percentile(durations, 0.50),
            "p95": percentile(durations, 0.95),
            "max": round(max(durations), 3) if durations else None,
        },
        "metrics": metrics,
    }


def write_markdown(path: Path, run_id: str, summaries: list[dict[str, Any]]) -> None:
    lines = [
        "# LingShu 轻量批量测评报告",
        "",
        f"- Run ID：`{run_id}`",
        f"- 场景数：{len(summaries)}",
        "",
        "| 场景 | 执行 | 成功 | 失败 | 成功率 | 进程耗时 P50/P95 |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for summary in summaries:
        duration = summary["processDurationMs"]
        lines.append(
            f"| {summary['id']} | {summary['executed']} | {summary['successful']} | "
            f"{summary['failed']} | {summary['successRate']:.2%} | "
            f"{duration['p50']} / {duration['p95']} ms |"
        )
    for summary in summaries:
        if not summary["metrics"]:
            continue
        lines.extend(["", f"## {summary['id']} 指标", ""])
        for name, metric in summary["metrics"].items():
            lines.append(
                f"- `{name}`：samples={metric['samples']}，P50={metric['p50']} ms，"
                f"P95={metric['p95']} ms，max={metric['max']} ms"
            )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def powershell_command(repo: Path, script: str, arguments: list[str]) -> list[str]:
    executable = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
    if executable is None:
        raise RuntimeError("PowerShell executable was not found")
    return [
        executable,
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(repo / script),
        *arguments,
    ]


def git_commit(repo: Path) -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    return completed.stdout.strip() if completed.returncode == 0 else "unknown"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="evaluation-manifest.yaml")
    parser.add_argument("--suite", action="append", default=[])
    parser.add_argument("--output-root")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    repo = Path(__file__).resolve().parent.parent
    manifest_path = (repo / args.manifest).resolve()
    manifest = load_manifest(manifest_path)
    selected = [
        suite
        for suite in manifest["suites"]
        if not args.suite or suite["id"] in args.suite
    ]
    if not selected:
        raise SystemExit("No evaluation suites matched the selection")
    if args.dry_run:
        print(json.dumps({"manifest": str(manifest_path), "suites": selected}, indent=2))
        return 0

    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    output_root = Path(args.output_root or manifest["outputRoot"])
    run_directory = output_root / run_id
    run_directory.mkdir(parents=True, exist_ok=False)
    shutil.copy2(manifest_path, run_directory / "evaluation-manifest.yaml")
    (run_directory / "environment.json").write_text(json.dumps({
        "runId": run_id,
        "gitCommit": git_commit(repo),
        "platform": platform.platform(),
        "python": sys.version,
        "executable": sys.executable,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    summaries: list[dict[str, Any]] = []
    for suite in selected:
        suite_directory = run_directory / suite["id"]
        suite_directory.mkdir()
        manages_service = "service" in suite
        service = suite.get("service") or {}
        environment = {str(key): str(value) for key, value in service.get("environment", {}).items()}
        restart_each_iteration = bool(service.get("restartEachIteration", False))
        start_command = powershell_command(
            repo,
            service.get("startScript", "scripts/start-benchmark.ps1"),
            service.get("startArgs", []),
        )
        stop_command = powershell_command(
            repo,
            service.get("stopScript", "scripts/stop-local.ps1"),
            service.get("stopArgs", []),
        )
        starts: list[dict[str, Any]] = []
        stops: list[dict[str, Any]] = []
        if not manages_service:
            starts.append({
                "command": [],
                "exitCode": 0,
                "durationMs": 0.0,
                "stdout": "",
                "stderr": "",
                "timedOut": False,
            })
        elif not restart_each_iteration:
            starts.append(run_command(
                start_command,
                repo,
                int(service.get("startTimeoutSeconds", 90)),
                environment,
                capture_output=False,
            ))
        cases: list[dict[str, Any]] = []
        try:
            test = suite["test"]
            command = [sys.executable, str(repo / test["script"]), *test.get("args", [])]
            for index in range(1, int(suite["repeat"]) + 1):
                if manages_service and restart_each_iteration:
                    starts.append(run_command(
                        start_command,
                        repo,
                        int(service.get("startTimeoutSeconds", 90)),
                        environment,
                        capture_output=False,
                    ))
                start = starts[-1]
                if start["exitCode"] == 0:
                    process = run_command(
                        command,
                        repo,
                        int(test.get("timeoutSeconds", 30)),
                    )
                    result = parse_json_output(process["stdout"])
                    passed = process["exitCode"] == 0 and checks_passed(result)
                else:
                    process = {
                        "command": command,
                        "exitCode": -1,
                        "durationMs": 0.0,
                        "stdout": "",
                        "stderr": "Service setup failed",
                        "timedOut": False,
                    }
                    result = None
                    passed = False
                if manages_service and restart_each_iteration:
                    stops.append(run_command(
                        stop_command,
                        repo,
                        30,
                        capture_output=False,
                    ))
                case = {
                    "iteration": index,
                    "passed": passed,
                    "result": result,
                    "process": process,
                    "serviceStart": start if restart_each_iteration else None,
                    "serviceStop": stops[-1] if restart_each_iteration else None,
                }
                cases.append(case)
                (suite_directory / f"case-{index:03d}.json").write_text(
                    json.dumps(case, ensure_ascii=False, indent=2), encoding="utf-8")
                print(
                    f"[{suite['id']}] {index}/{suite['repeat']} "
                    f"{'PASS' if passed else 'FAIL'}",
                    flush=True,
                )
        finally:
            if manages_service and not restart_each_iteration:
                stops.append(run_command(
                    stop_command,
                    repo,
                    30,
                    capture_output=False,
                ))
            elif manages_service and len(stops) < len(starts):
                stops.append(run_command(
                    stop_command,
                    repo,
                    30,
                    capture_output=False,
                ))
            (suite_directory / "lifecycle.json").write_text(
                json.dumps({
                    "restartEachIteration": restart_each_iteration,
                    "starts": starts,
                    "stops": stops,
                }, ensure_ascii=False, indent=2), encoding="utf-8")
        summary = summarize_suite(suite, cases)
        summary["setupPassed"] = bool(starts) and all(
            start["exitCode"] == 0 for start in starts)
        (suite_directory / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        summaries.append(summary)

    report = {
        "runId": run_id,
        "runDirectory": str(run_directory),
        "allPassed": all(
            summary["setupPassed"]
            and summary["executed"] == summary["requested"]
            and summary["failed"] == 0
            for summary in summaries
        ),
        "suites": summaries,
    }
    (run_directory / "summary.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(run_directory / "report.md", run_id, summaries)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["allPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
