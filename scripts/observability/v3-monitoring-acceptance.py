#!/usr/bin/env python3
"""Read-only V3 monitoring acceptance; an explicit flag permits one inspection POST.

Author: heyu. No Docker, deployment, login, token refresh, or scheduler mutation.
"""

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]
UTC = dt.timezone.utc
OBS = "/api/platform/observability"
STATES = {"READY", "PARTIAL", "NOT_CONFIGURED", "UNSUPPORTED", "NO_DATA", "STALE", "FAILED"}
HEALTH = {"HEALTHY", "DEGRADED", "CRITICAL", "UNKNOWN"}
EXECUTIONS = {"RUNNING", "COMPLETED", "FAILED", "SKIPPED", "TIMED_OUT"}
RESULTS = {"PASS", "ABNORMAL", "UNKNOWN", "NOT_APPLICABLE"}
NATIVE = {
    "rabbitmq": ("connections", "consumers", "messagesReady", "messagesUnacked", "memoryAlarm", "diskAlarm"),
    "ops-demo-rabbitmq": ("connections", "consumers", "messagesReady", "messagesUnacked", "memoryAlarm", "diskAlarm"),
    "nacos": ("registeredServices", "registeredInstances", "configurationCount"),
    "qdrant": ("collections", "vectors", "recoveryMode"),
}


class Blocked(Exception):
    pass


def now():
    return dt.datetime.now(UTC)


def stamp(value=None):
    return (value or now()).isoformat().replace("+00:00", "Z")


def instant(value):
    if not isinstance(value, str):
        return None
    try:
        result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        return result.astimezone(UTC) if result.tzinfo else None
    except ValueError:
        return None


def safe_time(value):
    parsed = instant(value)
    return stamp(parsed) if parsed else None


def fresh(value, at, age=90):
    parsed = instant(value)
    return parsed is not None and -15 <= (at - parsed).total_seconds() <= age


def code(value):
    return value if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", value) else None


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def fingerprint(value):
    return hashlib.sha256(str(value).encode()).hexdigest()[:16] if value else None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, status, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base, token):
        parsed = urllib.parse.urlsplit(base)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path not in {"", "/"}):
            raise Blocked("INVALID_BASE_ORIGIN")
        if parsed.scheme != "https" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise Blocked("REMOTE_ORIGIN_REQUIRES_HTTPS")
        if not token or any(char.isspace() for char in token):
            raise Blocked("ACCESS_TOKEN_MISSING_OR_INVALID")
        self.base, self.token = base.rstrip("/"), token
        self.opener = urllib.request.build_opener(NoRedirect())

    def call(self, path, manual=False):
        request = urllib.request.Request(
            self.base + path, data=b"" if manual else None,
            headers={"Authorization": "Bearer " + self.token, "Accept": "application/json"},
            method="POST" if manual else "GET")
        try:
            with self.opener.open(request, timeout=150 if manual else 30) as response:
                body = response.read(4 * 1024 * 1024 + 1)
                if len(body) > 4 * 1024 * 1024:
                    raise Blocked("RESPONSE_TOO_LARGE")
            payload = json.loads(body)
            if not isinstance(payload, dict) or payload.get("code") != 0 or "data" not in payload:
                raise Blocked("API_CONTRACT_OR_AUTH_FAILURE")
            return payload["data"]
        except urllib.error.HTTPError as error:
            raise Blocked("HTTP_" + str(error.code) + ("_MANUAL_OUTCOME_UNCERTAIN_NO_RETRY" if manual else "")) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise Blocked("TRANSPORT_FAILURE" + ("_MANUAL_OUTCOME_UNCERTAIN_NO_RETRY" if manual else "")) from None
        except (ValueError, TypeError):
            raise Blocked("INVALID_JSON_RESPONSE") from None


def token_for(args):
    token = os.environ.get("OPSAGENT_ACCESS_TOKEN")
    if token:
        return token
    try:
        session = json.loads(args.session_file.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        raise Blocked("SESSION_FILE_UNAVAILABLE_OR_INVALID") from None
    expires = session.get("expiresAt")
    if expires:
        expiry = instant(expires)
        if number(expires):
            expiry = dt.datetime.fromtimestamp(expires / (1000 if expires > 1e11 else 1), UTC)
        if expiry and expiry <= now():
            raise Blocked("SESSION_EXPIRED_REFRESH_SEPARATELY")
    return session.get("accessToken")


def add(report, key, condition, reason, missing=False):
    report["checks"].append({"id": key, "status": "PASS" if condition else "BLOCKED" if missing else "FAIL",
                             "reasonCode": "VERIFIED" if condition else reason})


def identity_ok(identity, ci, environment):
    return (isinstance(identity, dict) and identity.get("ciCode") == ci
            and identity.get("environment") == environment
            and all(key in identity for key in ("namespace", "cluster", "sourceInstanceId")))


def node_snapshot(node, at):
    ci, env = node.get("ciCode"), node.get("environment")
    obs = node.get("observation") or {}
    result = {"ciCode": code(ci), "environment": code(env), "health": code(node.get("health")),
              "healthScope": code(node.get("healthScope")), "healthReasonCode": code(node.get("healthReasonCode")),
              "lifecycle": code(node.get("lifecycle")), "requiresObservation": node.get("requiresObservation"),
              "observationStatus": code(obs.get("status")), "reasonCode": code(obs.get("reasonCode")),
              "sampledAt": safe_time(obs.get("sampledAt")), "fetchedAt": safe_time(obs.get("fetchedAt")),
              "observedAt": safe_time(node.get("observedAt")),
              "lastSuccessfulScrapeAt": safe_time(obs.get("lastSuccessfulScrapeAt")),
              "businessProbe": node.get("businessProbe") if number(node.get("businessProbe")) else None,
              "businessObservedAt": safe_time(node.get("businessObservedAt")),
              "instances": [], "metrics": {}, "issues": []}
    issues = result["issues"]
    if not ci or not env or not identity_ok(node.get("identity"), ci, env):
        issues.append("NODE_IDENTITY_INVALID")
    if node.get("health") not in HEALTH or obs.get("status") not in STATES:
        issues.append("STATE_CONTRACT_INVALID")
    if not node.get("healthScope") or not node.get("healthReasonCode"):
        issues.append("HEALTH_SCOPE_OR_REASON_MISSING")
    if not isinstance(node.get("requiresObservation"), bool) or not node.get("lifecycle"):
        issues.append("LIFECYCLE_CONTRACT_INVALID")
    age = obs.get("maximumSampleAgeSeconds")
    if not number(age) or age <= 0:
        issues.append("SAMPLE_AGE_CONTRACT_INVALID")
        age = 90
    result["maximumSampleAgeSeconds"] = age
    if not fresh(obs.get("fetchedAt"), at, age):
        issues.append("FETCH_TIMESTAMP_MISSING_OR_STALE")
    if node.get("health") == "HEALTHY" and (not fresh(node.get("observedAt"), at, age)
                                           or not node.get("evidenceRefs")):
        issues.append("HEALTHY_WITHOUT_TIMESTAMPED_EVIDENCE_REFERENCE")
    if not obs.get("reasonCode") or not isinstance(obs.get("checks"), list):
        issues.append("OBSERVATION_DIAGNOSTICS_MISSING")
    instances = obs.get("instances")
    if not isinstance(instances, list):
        issues.append("INSTANCES_CONTRACT_INVALID")
        instances = []
    keys = set()
    for instance in instances:
        identity = instance.get("identity") or {}
        key = (instance.get("job"), instance.get("instance"), identity.get("sourceInstanceId"))
        if key in keys:
            issues.append("DUPLICATE_INSTANCE")
        keys.add(key)
        if (not identity_ok(identity, ci, env) or not identity.get("sourceInstanceId")
                or not instance.get("job") or not instance.get("instance")
                or instance.get("source") != "PROMETHEUS_TARGET"):
            issues.append("INSTANCE_IDENTITY_INVALID")
        up = instance.get("up")
        if up is not None and (not number(up) or up not in {0, 1}):
            issues.append("INSTANCE_UP_INVALID")
        result["instances"].append({"identityHash": fingerprint(key), "ciCode": code(identity.get("ciCode")),
                                    "environment": code(identity.get("environment")),
                                    "job": code(instance.get("job")), "up": up if number(up) else None,
                                    "sampledAt": safe_time(instance.get("sampledAt")),
                                    "lastScrape": safe_time(instance.get("lastScrape")),
                                    "lastSuccessfulScrapeAt": safe_time(instance.get("lastSuccessfulScrapeAt")),
                                    "hasScrapeError": bool(instance.get("lastError"))})
    if obs.get("status") == "READY":
        if not instances or not fresh(obs.get("sampledAt"), at, age):
            issues.append("READY_WITHOUT_FRESH_SAMPLE")
        if any(item.get("up") != 1 or not fresh(item.get("sampledAt"), at, age) for item in instances):
            issues.append("READY_WITH_UNHEALTHY_OR_STALE_INSTANCE")
    if obs.get("status") in {"NOT_CONFIGURED", "UNSUPPORTED", "NO_DATA", "STALE"} and node.get("health") == "HEALTHY":
        if not (node.get("businessProbe") == 1 and fresh(node.get("businessObservedAt"), at, 30)):
            issues.append("HEALTHY_WITHOUT_CURRENT_EVIDENCE")
    metrics = node.get("metricEvidence")
    if not isinstance(metrics, dict):
        issues.append("METRIC_EVIDENCE_CONTRACT_MISSING")
        metrics = {}
    for name, metric in metrics.items():
        if not isinstance(metric, dict):
            issues.append("METRIC_CONTRACT_INVALID")
            continue
        value = metric.get("value")
        if not metric.get("reasonCode") or not metric.get("scope") or not metric.get("unit"):
            issues.append("METRIC_METADATA_MISSING:" + (code(name) or "UNKNOWN"))
        if value is not None and (not number(value) or not fresh(metric.get("sampledAt"), at, age)):
            issues.append("METRIC_VALUE_WITHOUT_FRESH_SAMPLE:" + (code(name) or "UNKNOWN"))
        result["metrics"][code(name) or "INVALID"] = {
            "value": value if number(value) else None, "sampledAt": safe_time(metric.get("sampledAt")),
            "reasonCode": code(metric.get("reasonCode")), "scope": code(metric.get("scope")),
            "windowSeconds": metric.get("windowSeconds") if number(metric.get("windowSeconds")) else None}
    result["missingNativeMetrics"] = [name for name in NATIVE.get(ci, ()) if metrics.get(name, {}).get("value") is None]
    if obs.get("status") == "READY" and result["missingNativeMetrics"]:
        issues.append("READY_WITH_NATIVE_METRICS_MISSING")
    result["issues"] = sorted(set(issues))
    return result


def validate_topology(report, topology, expected):
    nodes = topology.get("nodes")
    if not isinstance(nodes, list):
        raise Blocked("TOPOLOGY_NODES_MISSING")
    at = now()
    snapshots = [node_snapshot(node, at) for node in nodes]
    report["nodes"] = snapshots
    report["topologyCheckedAt"] = safe_time(topology.get("checkedAt"))
    identities = [(node.get("environment"), node.get("ciCode")) for node in nodes]
    add(report, "CI_INVENTORY_COUNT", len(nodes) == expected, "UNEXPECTED_CI_COUNT")
    add(report, "CI_UNIQUE_IDENTITIES", len(set(identities)) == len(nodes), "DUPLICATE_CI_IDENTITY")
    for snapshot in snapshots:
        add(report, "CI:" + str(snapshot["environment"]) + ":" + str(snapshot["ciCode"]),
            not snapshot["issues"], "NODE_CONTRACT_FAILED")
    report["actualCiCount"] = len(nodes)
    report["actualMappedInstanceCount"] = sum(len(node["instances"]) for node in snapshots)
    report["coverageStates"] = {state: sum(node["observationStatus"] == state for node in snapshots) for state in sorted(STATES)}
    return {node.get("ciCode"): node for node in nodes}


def valid_uuid(value):
    try:
        uuid.UUID(str(value))
        return True
    except (ValueError, TypeError):
        return False


def inspection_snapshot(row, ci, env):
    evidence = row.get("evidence") or {}
    state, conclusion = row.get("executionStatus"), row.get("result")
    start, finish, slot = (instant(row.get(key)) for key in ("startedAt", "finishedAt", "scheduledFor"))
    issues = []
    if (row.get("ciCode") != ci or row.get("targetId") != ci or row.get("environment") != env
            or row.get("checkId") != "HEALTH_CHECK:" + env + ":" + ci):
        issues.append("INSPECTION_IDENTITY_MISMATCH")
    if not valid_uuid(row.get("runId")) or not str(row.get("executor", "")).startswith("platform:"):
        issues.append("EXECUTION_IDENTITY_MISSING")
    if state not in EXECUTIONS or conclusion not in RESULTS or not row.get("reasonCode"):
        issues.append("EXECUTION_CONTRACT_INVALID")
    if not slot or not start or (state != "RUNNING" and (not finish or finish < start)):
        issues.append("EXECUTION_TIMESTAMPS_INVALID")
    if finish and instant(row.get("lastCheckedAt")) != finish:
        issues.append("LAST_CHECKED_TIMESTAMP_INCONSISTENT")
    if finish:
        duration = row.get("durationMs")
        if (not start or not number(duration) or duration < 0
                or abs(duration - (finish - start).total_seconds() * 1000) > 5):
            issues.append("EXECUTION_DURATION_INVALID")
    expected_ref = "inspection:" + str(row.get("runId")) + ":" + ci
    if expected_ref not in row.get("evidenceRefs", []):
        issues.append("EXECUTION_EVIDENCE_REFERENCE_MISSING")
    if state == "COMPLETED":
        observation = evidence.get("observation") or {}
        if (evidence.get("health") not in HEALTH or observation.get("status") not in STATES
                or not evidence.get("coverage") or "metrics" not in evidence):
            issues.append("COMPLETED_WITHOUT_EVIDENCE")
        expected_status = "UNKNOWN"
        if conclusion == "PASS":
            expected_status = "SUCCESS"
        elif conclusion == "ABNORMAL":
            expected_status = "WARNING" if evidence.get("health") == "DEGRADED" else "FAILED"
        if row.get("status") != expected_status:
            issues.append("EXECUTION_LEGACY_STATUS_INCONSISTENT")
        if conclusion == "PASS" and (evidence.get("health") != "HEALTHY"
                                     or not fresh(evidence.get("observedAt"), finish or now(),
                                                  observation.get("maximumSampleAgeSeconds") or 90)):
            issues.append("PASS_WITHOUT_CURRENT_HEALTH_EVIDENCE")
        if conclusion == "UNKNOWN" and row.get("status") == "SUCCESS":
            issues.append("UNKNOWN_MISREPRESENTED_AS_PASS")
    elif conclusion != "UNKNOWN":
        issues.append("INCOMPLETE_EXECUTION_HAS_CONCLUSION")
    return {"ciCode": code(ci), "environment": code(env), "checkId": code(row.get("checkId")),
            "runId": code(row.get("runId")), "source": code(row.get("source")),
            "executionStatus": code(state), "result": code(conclusion), "status": code(row.get("status")),
            "reasonCode": code(row.get("reasonCode")), "scheduledFor": safe_time(row.get("scheduledFor")),
            "startedAt": safe_time(row.get("startedAt")), "finishedAt": safe_time(row.get("finishedAt")),
            "lastCheckedAt": safe_time(row.get("lastCheckedAt")),
            "nextRunAt": safe_time(row.get("nextRunAt")), "durationMs": row.get("durationMs"),
            "workflowRunId": row.get("workflowRunId"), "executorHash": fingerprint(row.get("executor")),
            "evidenceRefs": [expected_ref] if expected_ref in row.get("evidenceRefs", []) else [],
            "evidenceHealth": code(evidence.get("health")), "evidenceObservedAt": safe_time(evidence.get("observedAt")),
            "evidenceHealthScope": code(evidence.get("healthScope")),
            "evidenceObservationStatus": code((evidence.get("observation") or {}).get("status")), "issues": issues}


def history(client, ci):
    result = client.call(OBS + "/inspections/" + urllib.parse.quote(ci, safe="") + "/history")
    if not isinstance(result, dict) or not isinstance(result.get("items"), list):
        raise Blocked("INSPECTION_HISTORY_CONTRACT_INVALID")
    return result["items"]


def scheduled_pair(rows, interval_ms):
    scheduled = sorted((row for row in rows if row.get("source") == "SCHEDULED" and instant(row.get("scheduledFor"))),
                       key=lambda row: instant(row["scheduledFor"]))
    for left, right in zip(scheduled, scheduled[1:]):
        delta = (instant(right["scheduledFor"]) - instant(left["scheduledFor"])).total_seconds() * 1000
        if (left.get("executionStatus") == right.get("executionStatus") == "COMPLETED"
                and left.get("runId") != right.get("runId") and abs(delta - interval_ms) < 5
                and instant(left.get("nextRunAt")) == instant(right["scheduledFor"])):
            return [left, right]
    return []


def inspect_runs(report, client, args, nodes):
    ci = args.ci
    if ci not in nodes or nodes[ci].get("environment") != args.environment:
        raise Blocked("SELECTED_INSPECTION_CI_OR_ENVIRONMENT_MISSING")
    overview = client.call(OBS + "/inspections?environment=" + urllib.parse.quote(args.environment))
    schedule = overview.get("schedule") or {}
    interval = schedule.get("intervalMs")
    report["schedule"] = {"enabled": schedule.get("enabled") is True,
                          "intervalMs": interval if number(interval) else None,
                          "nextRunAt": safe_time(schedule.get("nextRunAt")), "reasonCode": code(schedule.get("reasonCode"))}
    rows = history(client, ci)
    original_ids = {row.get("runId") for row in rows}
    manual = next((row for row in rows if row.get("source") == "MANUAL" and row.get("executionStatus") == "COMPLETED"), None)
    report["manualRequested"] = args.run_manual
    if args.run_manual:
        report["manualRequestSentAt"] = stamp()
        manual = client.call(OBS + "/inspections/" + urllib.parse.quote(ci, safe="") + "/run", manual=True)
        report["manualResponse"] = inspection_snapshot(manual, ci, args.environment)
        rows = history(client, ci)
        persisted = next((row for row in rows if row.get("runId") == manual.get("runId")), None)
        add(report, "MANUAL_PERSISTED", persisted is not None,
            "MANUAL_RESPONSE_NOT_PERSISTED")
        if persisted:
            manual = persisted
        schedule = client.call(OBS + "/inspections?environment=" + urllib.parse.quote(args.environment)).get("schedule") or {}
        if schedule.get("intervalMs") != interval:
            raise Blocked("SCHEDULE_INTERVAL_CHANGED_RESTART_ACCEPTANCE")
    report["manual"] = inspection_snapshot(manual, ci, args.environment) if manual else None
    if manual:
        add(report, "MANUAL_SOURCE", manual.get("source") == "MANUAL", "MANUAL_SOURCE_MISMATCH")
        add(report, "MANUAL_CONTRACT", not report["manual"]["issues"], "MANUAL_CONTRACT_FAILED")
        add(report, "MANUAL_COMPLETED", manual.get("executionStatus") == "COMPLETED", "MANUAL_NOT_COMPLETED", missing=True)
    else:
        report["checks"].append({"id": "MANUAL_COMPLETED", "status": "NOT_RUN", "reasonCode": "NO_REAL_MANUAL_EXECUTION"})
    if not number(interval) or interval < 60000:
        raise Blocked("REAL_SCHEDULE_INTERVAL_UNAVAILABLE")
    pair = scheduled_pair(rows, interval)
    if args.await_scheduled:
        if schedule.get("enabled") is not True or not instant(schedule.get("nextRunAt")):
            raise Blocked("SCHEDULER_DISABLED_OR_NEXT_RUN_UNAVAILABLE")
        deadline = time.monotonic() + args.max_wait_seconds
        pair = []
        while time.monotonic() < deadline:
            new_rows = [row for row in rows if row.get("runId") not in original_ids]
            pair = scheduled_pair(new_rows, interval)
            if pair:
                break
            report["scheduledProgress"] = [inspection_snapshot(row, ci, args.environment) for row in new_rows if row.get("source") == "SCHEDULED"]
            write_report(args, report, partial=True)
            next_run = instant(schedule.get("nextRunAt"))
            delay = min(30, max(5, (next_run - now()).total_seconds() if next_run else 15), deadline - time.monotonic())
            print(json.dumps({"status": "WAITING_FOR_REAL_SCHEDULER", "nextRunAt": safe_time(schedule.get("nextRunAt")),
                              "newScheduledCount": len(report["scheduledProgress"])}), flush=True)
            if delay > 0:
                time.sleep(delay)
            rows = history(client, ci)
            schedule = client.call(OBS + "/inspections?environment=" + urllib.parse.quote(args.environment)).get("schedule") or {}
            if schedule.get("enabled") is not True:
                raise Blocked("SCHEDULER_DISABLED_DURING_WAIT")
            if schedule.get("intervalMs") != interval:
                raise Blocked("SCHEDULE_INTERVAL_CHANGED_RESTART_ACCEPTANCE")
        if not pair:
            pair = scheduled_pair([row for row in rows if row.get("runId") not in original_ids], interval)
    report["scheduledProofScope"] = "NEW_REAL_SCHEDULED_EXECUTIONS" if args.await_scheduled else "EXISTING_HISTORY_ONLY"
    report["scheduled"] = [inspection_snapshot(row, ci, args.environment) for row in pair]
    if not pair:
        report["checks"].append({"id": "TWO_CONSECUTIVE_SCHEDULED", "status": "BLOCKED" if args.await_scheduled else "NOT_RUN",
                                 "reasonCode": "TWO_REAL_COMPLETED_CONSECUTIVE_SLOTS_UNAVAILABLE"})
    for index, snapshot in enumerate(report["scheduled"], 1):
        add(report, "SCHEDULED_CONTRACT_" + str(index), not snapshot["issues"], "SCHEDULED_CONTRACT_FAILED")
        workflow_id = snapshot.get("workflowRunId")
        if not isinstance(workflow_id, int) or workflow_id <= 0:
            add(report, "SCHEDULED_WORKFLOW_" + str(index), False, "SCHEDULED_WORKFLOW_REFERENCE_MISSING")
            continue
        workflow = client.call("/api/platform/operations/runs/" + str(workflow_id))
        valid = (workflow.get("id") == workflow_id and workflow.get("workflowCode") == "HEALTH_CHECK"
                 and workflow.get("actor") == "system-health" and instant(workflow.get("startedAt")) is not None
                 and instant(workflow.get("finishedAt")) is not None and workflow.get("status") != "RUNNING")
        add(report, "SCHEDULED_WORKFLOW_" + str(index), valid, "REAL_SCHEDULER_WORKFLOW_MISMATCH")
        snapshot["workflow"] = {"id": workflow_id, "workflowCode": code(workflow.get("workflowCode")),
                                "actor": code(workflow.get("actor")), "status": code(workflow.get("status")),
                                "startedAt": safe_time(workflow.get("startedAt")), "finishedAt": safe_time(workflow.get("finishedAt"))}


def check_business_phase(report, args, nodes, business):
    at = now()
    rabbit = nodes.get("ops-demo-rabbitmq") or {}
    formal = nodes.get("rabbitmq") or {}
    notification = nodes.get("ops-demo-notification-service") or {}
    obs = rabbit.get("observation") or {}
    instances = obs.get("instances") or []
    business_data = business.get("business") or {}
    report["business"] = {"targetCode": code(business.get("targetCode")), "scope": code(business.get("scope")),
                           "httpStatus": business_data.get("httpStatus"), "observedAt": safe_time(business.get("observedAt"))}
    add(report, "BUSINESS_HTTP_200", business.get("targetCode") == "ops-demo-notification-service"
        and business.get("scope") == "ISOLATED_DEMO" and business_data.get("httpStatus") == 200
        and fresh(business.get("observedAt"), at, 45), "BUSINESS_PROBE_HTTP_200_UNPROVEN", missing=True)
    add(report, "NOTIFICATION_BUSINESS_HEALTH", notification.get("environment") == "DEMO"
        and notification.get("businessProbe") == 1 and fresh(notification.get("businessObservedAt"), at, 30)
        and notification.get("health") in {"HEALTHY", "DEGRADED"}, "NOTIFICATION_FRESH_PROBE_OR_NON_RED_HEALTH_UNPROVEN", missing=True)
    formal_obs = formal.get("observation") or {}
    formal_instances = formal_obs.get("instances") or []
    add(report, "FORMAL_RABBIT_UNAFFECTED", formal.get("environment") == "PROD"
        and formal_obs.get("status") == "READY" and bool(formal_instances)
        and all(item.get("up") == 1 and fresh(item.get("sampledAt"), at, 90) for item in formal_instances)
        and formal.get("prometheusJob") != rabbit.get("prometheusJob"), "FORMAL_RABBIT_SCOPE_OR_FRESHNESS_UNPROVEN", missing=True)
    if args.phase == "check-fault":
        add(report, "DEMO_RABBIT_COLLECTION_FAILED", rabbit.get("environment") == "DEMO"
            and obs.get("status") == "FAILED" and bool(obs.get("reasonCode")) and bool(instances)
            and all(item.get("up") == 0 and fresh(item.get("sampledAt"), at, 90) for item in instances)
            and any(item.get("lastError") for item in instances), "FRESH_DEMO_RABBIT_SCRAPE_FAILURE_UNPROVEN", missing=True)
        add(report, "COLLECTION_FAILURE_NOT_BUSINESS_RED", rabbit.get("health") != "CRITICAL", "COLLECTION_FAILURE_COLORED_AS_BUSINESS_CRITICAL")
    else:
        add(report, "DEMO_RABBIT_COLLECTION_RECOVERED", rabbit.get("environment") == "DEMO"
            and obs.get("status") == "READY" and bool(instances)
            and all(item.get("up") == 1 and fresh(item.get("sampledAt"), at, 90) and not item.get("lastError") for item in instances),
            "FRESH_DEMO_RABBIT_RECOVERY_UNPROVEN", missing=True)
        if not args.prior_report:
            raise Blocked("RECOVERY_REQUIRES_PREVIOUS_FAULT_REPORT")
        try:
            prior = json.loads(args.prior_report.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            raise Blocked("PRIOR_FAULT_REPORT_UNAVAILABLE") from None
        fault_at = instant(prior.get("finishedAt"))
        add(report, "REAL_FAULT_TO_RECOVERY_TRANSITION", prior.get("phase") == "check-fault"
            and prior.get("status") == "PASS" and prior.get("originHash") == report.get("originHash")
            and fault_at is not None and bool(instances)
            and all(instant(item.get("sampledAt")) is not None and instant(item["sampledAt"]) > fault_at for item in instances),
            "PRIOR_VERIFIED_FAULT_OR_LATER_RECOVERY_SAMPLE_MISSING", missing=True)


def write_report(args, report, partial=False):
    report["updatedAt"] = stamp()
    statuses = [item["status"] for item in report["checks"]]
    report["status"] = "WAITING" if partial else next((status for status in ("FAIL", "BLOCKED", "NOT_RUN") if status in statuses), "PASS")
    if not partial:
        report["finishedAt"] = stamp()
    output = args.output.resolve()
    allowed = (ROOT / "data").resolve()
    if not output.is_relative_to(allowed):
        raise Blocked("OUTPUT_MUST_BE_UNDER_PROJECT_IGNORED_DATA")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://opsagent.cloud", help="HTTPS origin; local HTTP allowed")
    parser.add_argument("--session-file", type=Path, default=ROOT / "data/public-deploy/admin-session.json")
    parser.add_argument("--phase", choices=("baseline", "check-fault", "check-recovery"), default="baseline")
    parser.add_argument("--ci", default="ops-demo-notification-service")
    parser.add_argument("--environment", default="DEMO")
    parser.add_argument("--expected-ci-count", type=int, default=23)
    parser.add_argument("--run-manual", action="store_true", help="POST one read-only inspection; creates one execution record, never retried")
    parser.add_argument("--await-scheduled", action="store_true", help="wait for two NEW actual consecutive scheduler slots; no triggering")
    parser.add_argument("--max-wait-seconds", type=int, default=3600)
    parser.add_argument("--prior-report", type=Path, help="verified check-fault JSON for actual recovery transition")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()
    if args.phase != "baseline" and (args.run_manual or args.await_scheduled):
        parser.error("inspection flags are accepted only for --phase baseline")
    if args.max_wait_seconds < 1 or args.expected_ci_count < 1 or not code(args.ci) or not code(args.environment):
        parser.error("positive limits and valid CI/environment identifiers required")
    args.output = args.output or ROOT / ("data/public-deploy/v3/monitoring-" + args.phase + "-" + now().strftime("%Y%m%dT%H%M%SZ") + ".json")
    report = {"schemaVersion": 1, "phase": args.phase, "startedAt": stamp(), "originHash": fingerprint(args.base.rstrip("/")),
              "mode": "READ_ONLY_WITH_ONE_EXPLICIT_INSPECTION" if args.run_manual else "READ_ONLY",
              "limitations": ["Evidence reflects API observations; no Docker topology mutation or direct Prometheus target-count assertion.",
                              "COMPLETED/UNKNOWN proves execution only, never healthy PASS.",
                              "NOT_CONFIGURED/UNSUPPORTED are legitimate coverage gaps, not READY."], "checks": []}
    try:
        if not args.output.resolve().is_relative_to((ROOT / "data").resolve()):
            raise Blocked("OUTPUT_MUST_BE_UNDER_PROJECT_IGNORED_DATA")
        client = Client(args.base, token_for(args))
        business = client.call("/api/platform/operations/demo/target?targetCode=ops-demo-notification-service") if args.phase != "baseline" else None
        topology = client.call(OBS + "/topology?environment=ALL&timeRange=15m&mode=CONFIGURED")
        nodes = validate_topology(report, topology, args.expected_ci_count)
        if args.phase == "baseline":
            inspect_runs(report, client, args, nodes)
        else:
            check_business_phase(report, args, nodes, business)
    except Blocked as error:
        report["checks"].append({"id": "ACCEPTANCE_AVAILABLE", "status": "BLOCKED", "reasonCode": str(error)})
    except KeyboardInterrupt:
        report["checks"].append({"id": "ACCEPTANCE_COMPLETED", "status": "BLOCKED", "reasonCode": "OPERATOR_INTERRUPTED"})
    except Exception:
        report["checks"].append({"id": "ACCEPTANCE_CONTRACT", "status": "BLOCKED", "reasonCode": "UNEXPECTED_RESPONSE_OR_LOCAL_ERROR_NO_SECRET_OUTPUT"})
    try:
        write_report(args, report)
    except (OSError, Blocked):
        print("BLOCKED: REPORT_WRITE_UNAVAILABLE_OR_OUTSIDE_IGNORED_DATA", file=sys.stderr)
        return 2
    print(json.dumps({"status": report["status"], "report": str(args.output.resolve()),
                      "checks": len(report["checks"]), "phase": args.phase}, ensure_ascii=False))
    return {"PASS": 0, "FAIL": 1, "BLOCKED": 2, "NOT_RUN": 3}[report["status"]]


if __name__ == "__main__":
    sys.exit(main())
