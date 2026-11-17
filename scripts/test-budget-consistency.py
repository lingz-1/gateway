#!/usr/bin/env python3
"""Run and summarize Redis/PostgreSQL budget consistency integration tests."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path
from xml.etree import ElementTree


def load_dotenv(path: Path, environment: dict[str, str]) -> None:
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        environment[key.strip()] = value.strip()


def read_report(repo: Path, class_name: str) -> tuple[ElementTree.Element, list[str]]:
    path = (
        repo
        / "lingshu-core"
        / "target"
        / "surefire-reports"
        / f"TEST-{class_name}.xml"
    )
    root = ElementTree.parse(path).getroot()
    return root, [case.attrib.get("name", "") for case in root.findall("testcase")]


def count_method(names: list[str], method: str) -> int:
    return sum(1 for name in names if name.startswith(f"{method}()"))


def passed(root: ElementTree.Element) -> bool:
    return int(root.attrib.get("failures", "0")) == 0 and int(root.attrib.get("errors", "0")) == 0


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    environment = os.environ.copy()
    load_dotenv(repo / ".env", environment)
    environment["LINGSHU_RUN_REDIS_TESTS"] = "true"
    environment["LINGSHU_RUN_POSTGRES_TESTS"] = "true"
    compose = shutil.which("docker-compose.exe") or shutil.which("docker-compose")
    powershell = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
    if compose is None or powershell is None:
        raise RuntimeError("Docker Compose and PowerShell are required")

    compose_result = subprocess.run([
        compose,
        "--project-directory", str(repo),
        "--env-file", str(repo / ".env"),
        "-f", str(repo / "compose.yaml"),
        "up", "--detach", "--wait", "redis", "postgres",
    ], cwd=repo, env=environment, capture_output=True, text=True, check=False)
    if compose_result.returncode == 0:
        maven_result = subprocess.run([
            powershell,
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", str(repo / "scripts" / "mvn.ps1"),
            "verify",
        ], cwd=repo, env=environment, capture_output=True, text=True,
            encoding="utf-8", errors="replace", check=False)
    else:
        maven_result = None

    reports_available = maven_result is not None and all((
        repo / "lingshu-core" / "target" / "surefire-reports" / f"TEST-{name}.xml"
    ).exists() for name in (
        "com.lingshu.core.billing.RedisBudgetReservationStoreIntegrationTest",
        "com.lingshu.core.billing.JdbcBudgetLedgerIntegrationTest",
        "com.lingshu.core.billing.JdbcVirtualBillingStoreIntegrationTest",
        "com.lingshu.core.billing.BudgetReservationServiceTest",
        "com.lingshu.core.billing.BudgetSettlementIntegrationTest",
        "com.lingshu.core.billing.BudgetPipelineIntegrationTest",
    ))
    if reports_available:
        redis_root, redis_names = read_report(
            repo, "com.lingshu.core.billing.RedisBudgetReservationStoreIntegrationTest")
        ledger_root, ledger_names = read_report(
            repo, "com.lingshu.core.billing.JdbcBudgetLedgerIntegrationTest")
        virtual_root, virtual_names = read_report(
            repo, "com.lingshu.core.billing.JdbcVirtualBillingStoreIntegrationTest")
        service_root, service_names = read_report(
            repo, "com.lingshu.core.billing.BudgetReservationServiceTest")
        settlement_root, settlement_names = read_report(
            repo, "com.lingshu.core.billing.BudgetSettlementIntegrationTest")
        pipeline_root, pipeline_names = read_report(
            repo, "com.lingshu.core.billing.BudgetPipelineIntegrationTest")
        redis_concurrency = count_method(
            redis_names, "concurrentReservationsNeverOverspendAndSettlementsRemainIdempotent")
        redis_duplicates = count_method(
            redis_names, "concurrentDuplicateReservationDebitsAndReleasesOnlyOnce")
        redis_delta_settlements = count_method(
            redis_names, "concurrentDeltaSettlementAdjustsBalanceOnceAndPreservesUsageSource")
        ledger_confirms = count_method(
            ledger_names, "concurrentConfirmCreatesOneTransitionAndOneOutboxEvent")
        ledger_releases = count_method(
            ledger_names, "concurrentReleaseCreatesOneTransitionAndOneOutboxEvent")
        ledger_settlements = count_method(
            ledger_names, "concurrentSettlementIsIdempotentAndCreatesOneActualUsageEvent")
        virtual_atomic = sum(
            1 for name in virtual_names
            if name.startswith("chargesAtomicallyAcrossConcurrentRequestsAndDeduplicatesTrace")
        )
        recovery_tests = sum(1 for name in service_names if name in {
            "databaseFailureDoesNotChangeRedisBalance",
            "retriesRedisAfterLedgerAlreadyPersistedSettlement",
        })
        usage_source_tests = sum(1 for name in service_names if name in {
            "settlesActualUsageAndReleasesDifference",
            "settlesOverageAndPreservesEstimatedUsageSource",
            "retriesRedisAfterLedgerAlreadyPersistedSettlement",
        })
        vertical_settlement_tests = sum(1 for name in settlement_names if name in {
            "reservesSettlesActualUsageAndReleasesDifferenceAcrossRedisAndPostgres",
            "settlesOverageAndPreservesEstimatedUsageSource",
            "concurrentDuplicateSettlementChangesBalanceOnceAndCreatesOneOutboxEvent",
        })
        pipeline_tests = len(pipeline_names)
    else:
        redis_root = ledger_root = virtual_root = service_root = settlement_root = pipeline_root = ElementTree.Element(
            "testsuite", {"failures": "-1", "errors": "-1"})
        redis_concurrency = redis_duplicates = redis_delta_settlements = 0
        ledger_confirms = ledger_releases = ledger_settlements = virtual_atomic = 0
        recovery_tests = usage_source_tests = 0
        vertical_settlement_tests = 0
        pipeline_tests = 0

    checks = {
        "containers_ready": compose_result.returncode == 0,
        "maven_verify_succeeded": maven_result is not None and maven_result.returncode == 0,
        "redis_concurrency_repeated_10_times": redis_concurrency == 10,
        "redis_duplicate_replay_repeated_10_times": redis_duplicates == 10,
        "redis_delta_settlement_repeated_10_times": redis_delta_settlements == 10,
        "ledger_confirm_repeated_10_times": ledger_confirms == 10,
        "ledger_release_repeated_10_times": ledger_releases == 10,
        "ledger_actual_settlement_repeated_10_times": ledger_settlements == 10,
        "database_failure_recovery_verified": recovery_tests == 2,
        "usage_sources_preserved": usage_source_tests == 3,
        "redis_postgres_vertical_settlement_verified": vertical_settlement_tests == 3,
        "default_pipeline_budget_lifecycle_verified": pipeline_tests == 10,
        "virtual_balance_atomicity_verified": virtual_atomic == 1,
        "budget_reports_have_no_failures": all(passed(root) for root in (
            redis_root, ledger_root, virtual_root, service_root, settlement_root, pipeline_root)),
    }
    result = {
        "redisConcurrencyRepetitions": redis_concurrency,
        "redisDuplicateRepetitions": redis_duplicates,
        "redisDeltaSettlementRepetitions": redis_delta_settlements,
        "ledgerConfirmRepetitions": ledger_confirms,
        "ledgerReleaseRepetitions": ledger_releases,
        "ledgerActualSettlementRepetitions": ledger_settlements,
        "databaseRecoveryTests": recovery_tests,
        "usageSourceTests": usage_source_tests,
        "verticalSettlementTests": vertical_settlement_tests,
        "pipelineLifecycleTests": pipeline_tests,
        "virtualBalanceAtomicityTests": virtual_atomic,
        "checks": checks,
    }
    if compose_result.returncode != 0:
        result["composeOutputTail"] = (compose_result.stdout + compose_result.stderr)[-2000:]
    if maven_result is not None and maven_result.returncode != 0:
        result["mavenOutputTail"] = (maven_result.stdout + maven_result.stderr)[-4000:]
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
