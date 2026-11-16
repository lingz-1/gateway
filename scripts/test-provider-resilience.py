#!/usr/bin/env python3
"""Run and summarize the repeated Provider resilience component tests."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from xml.etree import ElementTree


def count_method(test_cases: list[ElementTree.Element], method: str) -> int:
    return sum(
        1
        for case in test_cases
        if case.attrib.get("name", "").startswith(f"{method}()")
    )


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    powershell = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
    if powershell is None:
        raise RuntimeError("PowerShell executable was not found")
    command = [
        powershell,
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(repo / "scripts" / "mvn.ps1"),
        "verify",
    ]
    completed = subprocess.run(
        command,
        cwd=repo,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    report_path = (
        repo
        / "lingshu-core"
        / "target"
        / "surefire-reports"
        / "TEST-com.lingshu.core.provider.DeepSeekModelProviderTest.xml"
    )
    if report_path.exists():
        root = ElementTree.parse(report_path).getroot()
        test_cases = list(root.findall("testcase"))
        retry_count = count_method(
            test_cases, "retriesTransientFailureAndAccumulatesReportedUsage")
        circuit_open_count = count_method(
            test_cases, "opensCircuitAfterConfiguredFailureThreshold")
        recovery_count = count_method(test_cases, "recoversAfterCircuitOpenDuration")
        failures = int(root.attrib.get("failures", "0"))
        errors = int(root.attrib.get("errors", "0"))
        total = int(root.attrib.get("tests", "0"))
    else:
        retry_count = 0
        circuit_open_count = 0
        recovery_count = 0
        failures = -1
        errors = -1
        total = 0
    checks = {
        "maven_verify_succeeded": completed.returncode == 0,
        "retry_repeated_30_times": retry_count == 30,
        "circuit_open_repeated_30_times": circuit_open_count == 30,
        "circuit_recovery_repeated_30_times": recovery_count == 30,
        "provider_test_class_has_no_failures": failures == 0 and errors == 0,
    }
    result = {
        "mavenExitCode": completed.returncode,
        "providerTestCases": total,
        "retryRepetitions": retry_count,
        "circuitOpenRepetitions": circuit_open_count,
        "circuitRecoveryRepetitions": recovery_count,
        "failures": failures,
        "errors": errors,
        "checks": checks,
    }
    if completed.returncode != 0:
        result["mavenOutputTail"] = (completed.stdout + completed.stderr)[-4000:]
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
