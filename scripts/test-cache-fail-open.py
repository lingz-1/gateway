#!/usr/bin/env python3
"""Run cache isolation and fail-open fault-injection tests."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from xml.etree import ElementTree


TEST_CLASSES = (
    "com.lingshu.core.cache.CacheEligibilityTest",
    "com.lingshu.core.cache.ExactCacheKeyFactoryTest",
    "com.lingshu.core.cache.ExactCacheServiceTest",
    "com.lingshu.core.cache.SemanticCacheServiceTest",
    "com.lingshu.core.cache.PgVectorSemanticCacheStoreTest",
)


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    powershell = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
    if powershell is None:
        raise RuntimeError("PowerShell is required")
    completed = subprocess.run([
        powershell,
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", str(repo / "scripts" / "mvn.ps1"),
        "-pl", "lingshu-core",
        "-Dtest=" + ",".join(name.rsplit(".", 1)[-1] for name in TEST_CLASSES),
        "test",
    ], cwd=repo, capture_output=True, text=True, encoding="utf-8",
        errors="replace", check=False)

    reports = []
    method_names: set[str] = set()
    for class_name in TEST_CLASSES:
        path = repo / "lingshu-core" / "target" / "surefire-reports" / f"TEST-{class_name}.xml"
        if not path.exists():
            continue
        root = ElementTree.parse(path).getroot()
        reports.append(root)
        method_names.update(case.attrib.get("name", "") for case in root.findall("testcase"))
    checks = {
        "maven_tests_succeeded": completed.returncode == 0,
        "redis_exact_failure_fails_open": "failsOpenWhenStoreIsUnavailable" in method_names,
        "embedding_failure_fails_open": "failsOpenWhenEmbeddingProviderIsUnavailable" in method_names,
        "pgvector_runtime_failure_fails_open": "failsOpenWhenStoreIsUnavailable" in method_names,
        "pgvector_startup_failure_fails_open": "startupFailsOpenWhenDatabaseIsUnavailable" in method_names,
        "cache_applicability_verified": "allowsPlainTextAndRejectsEveryToolContext" in method_names,
        "tenant_and_parameter_isolation_verified": all(name in method_names for name in (
            "createsStableSha256KeyAndIsolatesTenantAndParameters",
            "isolatesCacheWhenEmbeddingDimensionChanges",
            "isolatesCacheByExtendedSamplingParameters",
        )),
        "reports_have_no_failures": bool(reports) and all(
            int(root.attrib.get("failures", "0")) == 0
            and int(root.attrib.get("errors", "0")) == 0
            for root in reports
        ),
    }
    result = {
        "testClasses": len(reports),
        "testMethods": len(method_names),
        "checks": checks,
    }
    if completed.returncode != 0:
        result["mavenOutputTail"] = (completed.stdout + completed.stderr)[-4000:]
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
