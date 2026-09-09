"""Critical publication/approval/concurrency invariants, entirely within temporary directories."""

import copy
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock
import urllib.error
import urllib.request
import uuid
from http.server import ThreadingHTTPServer

import executor as module


class SimulatedRunner:
    def __init__(self):
        self.applied = []
        self.fail_once = None

    def preflight(self, service_id, task, draft, definition):
        pass

    def apply(self, target, task, draft, definition):
        self.applied.append((target["serviceId"], draft["targetVersion"]))
        if target["serviceId"] == self.fail_once:
            self.fail_once = None
            raise module.Rejected("测试目标启动失败")
        target.update(status="VERIFIED", loaded=True, healthy=True, businessVerified=True)
        target["evidence"].append({"kind": "TEST", "version": draft["targetVersion"]})


class ExecutorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.file = self.root / "service.yml"
        self.original = "# keep comment\nspring:\n  datasource:\n    password: original-secret\n    hikari:\n      maximum-pool-size: 10 # keep inline\ncustom:\n  untouched: 'stay'\n"
        self.file.write_text(self.original, encoding="utf-8")
        self.secret = "x" * 48
        (self.root / "secret.txt").write_text(self.secret, encoding="utf-8")
        self.registry = {"secretFile": str(self.root / "secret.txt"), "stateDirectory": str(self.root / "state"),
                         "allowedRoots": [str(self.root)],
                         "services": {"auth": {"kind": "JAVA", "command": [str(self.root / "java.exe"), "-jar", "test.jar"],
                                               "healthUrl": "http://127.0.0.1:18199/health"}},
                         "files": {"auth": {"path": str(self.file), "format": "yaml", "sensitive": True,
                                            "services": ["auth"], "fields": [
                                                {"key": "spring.datasource.hikari.maximum-pool-size", "type": "integer", "min": 1, "max": 50},
                                                {"key": "spring.datasource.password", "type": "string", "sensitive": True}]}}}
        self.runner = SimulatedRunner()
        self.executor = module.Executor(self.registry, self.runner)

    def tearDown(self):
        self.temporary.cleanup()

    def draft(self, value=12, action="PUBLISH_RESTART", rollback=False):
        return self.executor.create_draft("auth", {
            "baseVersion": self.executor.detail("auth")["version"], "action": action,
            "changes": [] if action == "APPLY_ONLY" else [{"key": "spring.datasource.hikari.maximum-pool-size", "value": value}],
            "rollbackOnFailure": rollback, "requestId": str(uuid.uuid4())}, 1)

    def approve(self, draft):
        self.executor.transition(draft["id"], "submit", {"digest": draft["digest"], "requestId": str(uuid.uuid4())}, 1)
        return self.executor.transition(draft["id"], "approve", {"digest": draft["digest"], "decision": "APPROVE",
                                                                  "requestId": str(uuid.uuid4())}, 1)

    def execute(self, draft):
        task = self.executor.transition(draft["id"], "execute", {"digest": draft["digest"], "requestId": str(uuid.uuid4())}, 1)
        for _ in range(200):
            result = self.executor.load("tasks", task["id"])
            if result["status"] in module.TERMINAL and self.executor.load("drafts", draft["id"])["status"] == "EXECUTED":
                return result
            time.sleep(0.01)
        self.fail("task did not terminate")

    def test_preserves_unknown_fields_and_yaml_comments(self):
        draft = self.approve(self.draft())
        result = self.execute(draft)
        self.assertEqual("VERIFIED", result["status"])
        text = self.file.read_text(encoding="utf-8")
        self.assertIn("# keep comment", text)
        self.assertIn("12 # keep inline", text)
        self.assertIn("untouched: 'stay'", text)
        self.assertIn("password: original-secret", text)

    def test_secret_values_absent_from_every_public_projection(self):
        self.file.write_text(self.original + "identity:\n  value: obscure-private-identity\n", encoding="utf-8")
        detail = self.executor.detail("auth")
        request = {"baseVersion": detail["version"], "action": "PUBLISH_ONLY",
                   "changes": [{"key": "spring.datasource.password", "value": "new-private-secret"}],
                   "requestId": str(uuid.uuid4())}
        draft = self.approve(self.executor.create_draft("auth", request, 1))
        task = self.execute(draft)
        projections = json.dumps([detail, draft, task, self.executor.history("auth")])
        self.assertNotIn("original-secret", projections)
        self.assertNotIn("new-private-secret", projections)
        self.assertNotIn("obscure-private-identity", projections)
        self.assertFalse(detail["rawEditable"])
        self.assertIn("new-private-secret", self.file.read_text())

    def test_token_budget_is_not_a_credential(self):
        for key in ["ops.agent.run-token-budget", "ops.ai.max-output-tokens", "ops.rag.max-context-tokens",
                    "ops.knowledge.chunk.target-tokens"]:
            self.assertFalse(module.sensitive_name(key), key)
        for key in ["ops.jwt.token", "spring.datasource.password", "ops.ai.api-key"]:
            self.assertTrue(module.sensitive_name(key), key)

    def test_visitor_reads_real_parameters_and_sanitized_published_history_only(self):
        self.file.write_text(self.original + "server:\n  port: 8101 # internal-private-comment\n", encoding="utf-8")
        self.registry["files"]["auth"]["fields"].append({"key": "server.port", "label": "服务端口", "type": "integer"})
        detail = self.executor.visitor_detail("auth")
        values = {field["key"]: field for field in detail["fields"]}
        self.assertEqual(8101, values["server.port"]["value"])
        self.assertEqual(10, values["spring.datasource.hikari.maximum-pool-size"]["value"])
        self.assertTrue(values["spring.datasource.password"]["hasValue"])
        self.assertNotIn("value", values["spring.datasource.password"])
        self.assertFalse(detail["editable"])
        self.assertFalse(detail["rawEditable"])
        first = self.approve(self.draft(12, action="PUBLISH_ONLY"))
        self.execute(first)
        self.draft(14, action="PUBLISH_ONLY")
        history = self.executor.visitor_history("auth")
        self.assertEqual(1, len(history["versions"]))
        self.assertEqual(12, history["versions"][0]["diff"][0]["after"])
        self.assertEqual([], history["drafts"])
        self.assertEqual([], history["tasks"])
        serialized = json.dumps([self.executor.visitor_catalog(), detail, history], ensure_ascii=False)
        for marker in ["original-secret", "internal-private-comment", "untouched", "createdBy", "approvalProof", str(self.root)]:
            self.assertNotIn(marker, serialized)

    def test_visitor_nested_headers_and_embedded_credentials_are_redacted(self):
        value = {"port": 8101, "run-token-budget": 100000,
                 "headers": [{"name": "Authorization", "value": "opaque-header-secret"}],
                 "url": "https://example.test/api?access_token=url-secret",
                 "uri": "jdbc:mysql://account:db-secret@localhost:3306/test",
                 "payload": '{"client_key":"json-secret","port":9000}',
                 "accessKey": "opaque-key-secret", "cookie": "session=opaque-cookie-secret"}
        visible = module.visitor_value(value)
        self.assertEqual(8101, visible["port"])
        self.assertEqual(100000, visible["run-token-budget"])
        self.assertEqual("已设置", visible["headers"][0]["value"])
        self.assertEqual(9000, json.loads(visible["payload"])["port"])
        serialized = json.dumps(visible)
        for marker in ["opaque-header-secret", "url-secret", "db-secret", "json-secret", "opaque-key-secret", "opaque-cookie-secret"]:
            self.assertNotIn(marker, serialized)

    def test_visitor_http_is_read_only_and_never_returns_raw_drafts_or_error_details(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), module.handler(self.executor))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:" + str(server.server_port)
        headers = {"X-Ops-Executor-Token": self.secret, "X-Ops-Actor-Id": "-99", "X-Ops-Actor-Role": "DEMO"}
        try:
            for path in ["/files", "/files/auth", "/files/auth/history"]:
                with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers)) as response:
                    body = json.load(response)
                    self.assertEqual("VISITOR_READ_ONLY", body["data"]["accessMode"])
                    self.assertNotIn("original-secret", json.dumps(body))
            for method, path in [("GET", "/drafts/a"), ("GET", "/tasks/a"), ("GET", "/files/auth/download"),
                                 ("POST", "/files/auth/drafts"), ("POST", "/drafts/a/approve"), ("POST", "/tasks/a/verify")]:
                with self.assertRaises(urllib.error.HTTPError) as failure:
                    urllib.request.urlopen(urllib.request.Request(base + path, headers=headers, method=method))
                self.assertEqual(403, failure.exception.code)
            self.file.write_text("invalid: [secret-in-broken-yaml", encoding="utf-8")
            with self.assertRaises(urllib.error.HTTPError) as failure:
                urllib.request.urlopen(urllib.request.Request(base + "/files/auth", headers=headers))
            self.assertNotIn("secret-in-broken-yaml", failure.exception.read().decode())
            self.assertEqual([], self.runner.applied)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_unapproved_or_modified_digest_never_executes(self):
        draft = self.draft()
        with self.assertRaises(module.Rejected):
            self.execute(draft)
        self.approve(draft)
        with self.assertRaises(module.Rejected):
            self.executor.transition(draft["id"], "execute", {"digest": "different", "requestId": str(uuid.uuid4())}, 1)
        self.assertEqual(self.original, self.file.read_text())

    def test_illegal_value_and_unknown_key_do_not_create_writes(self):
        with self.assertRaises(module.Rejected):
            self.draft(0)
        with self.assertRaises(module.Rejected):
            self.executor.create_draft("auth", {"baseVersion": self.executor.detail("auth")["version"],
                                                "changes": [{"key": "custom.untouched", "value": "attack"}],
                                                "action": "PUBLISH_ONLY", "requestId": str(uuid.uuid4())}, 1)
        self.assertEqual(self.original, self.file.read_text())

    def test_publish_only_does_not_restart_and_later_apply_reapproves_current_version(self):
        published = self.execute(self.approve(self.draft(action="PUBLISH_ONLY")))
        self.assertEqual("PUBLISHED_PENDING_APPLY", published["status"])
        self.assertEqual([], self.runner.applied)
        application = self.draft(action="APPLY_ONLY")
        with self.assertRaises(module.Rejected):
            self.execute(application)
        self.assertEqual("VERIFIED", self.execute(self.approve(application))["status"])

    def test_concurrent_base_version_drafts_cannot_both_overwrite(self):
        first, second = self.approve(self.draft(11)), self.approve(self.draft(12))
        self.execute(first)
        with self.assertRaises(module.Rejected) as failure:
            self.execute(second)
        self.assertEqual(409, failure.exception.status)
        self.assertIn("maximum-pool-size: 11", self.file.read_text())

    def test_repeated_execution_returns_same_persistent_task(self):
        draft = self.approve(self.draft())
        first = self.execute(draft)
        second = self.execute(draft)
        self.assertEqual(first["id"], second["id"])
        self.assertEqual(1, len(self.runner.applied))
        reopened = module.Executor(self.registry, self.runner)
        self.assertEqual("VERIFIED", reopened.load("tasks", first["id"])["status"])

    def test_changed_registry_invalidates_approval(self):
        draft = self.approve(self.draft())
        self.registry["services"]["auth"]["command"].append("--changed")
        with self.assertRaises(module.Rejected):
            self.execute(draft)

    def test_persisted_approval_rejects_content_or_author_tampering(self):
        draft = self.approve(self.draft())
        persisted = self.executor.load("drafts", draft["id"])
        persisted["content"] = persisted["content"].replace("12", "13")
        self.executor.save("drafts", persisted)
        with self.assertRaises(module.Rejected):
            self.execute(draft)
        self.assertEqual(self.original, self.file.read_text())
        persisted["content"] = persisted["content"].replace("13", "12")
        persisted["approvedBy"] = 99
        self.executor.save("drafts", persisted)
        with self.assertRaises(module.Rejected):
            self.execute(draft)

    def test_executor_restart_retains_interrupted_receipts_without_reexecution(self):
        task = {"id": "interrupted", "fileId": "auth", "createdAt": module.now(),
                "status": "RESTARTING", "events": [], "targets": [{"serviceId": "auth", "afterPid": 123}]}
        self.executor.save("tasks", task)
        reopened = module.Executor(self.registry, self.runner)
        result = reopened.load("tasks", "interrupted")
        self.assertEqual("RESULT_UNKNOWN", result["status"])
        self.assertEqual(123, result["targets"][0]["afterPid"])
        self.assertEqual([], self.runner.applied)

    def test_shared_failure_restores_every_touched_target_with_prior_evidence(self):
        self.registry["services"]["platform"] = copy.deepcopy(self.registry["services"]["auth"])
        self.registry["files"]["auth"]["services"].append("platform")
        self.runner.fail_once = "platform"
        result = self.execute(self.approve(self.draft(rollback=True)))
        self.assertEqual("ROLLED_BACK", result["status"])
        self.assertEqual(["auth", "platform", "auth", "platform"], [item[0] for item in self.runner.applied])
        self.assertEqual("VERIFIED", result["targets"][0]["previousAttempts"][0]["status"])
        self.assertEqual("FAILED", result["targets"][1]["previousAttempts"][0]["status"])
        self.assertEqual(self.original, self.file.read_text())

    def test_file_restoration_without_business_evidence_is_not_complete_rollback(self):
        self.registry["services"]["platform"] = copy.deepcopy(self.registry["services"]["auth"])
        self.registry["files"]["auth"]["services"].append("platform")
        original_version = module.digest(self.file.read_bytes())
        applied = self.runner.apply

        def missing_business(target, task, draft, definition):
            applied(target, task, draft, definition)
            if draft["targetVersion"] == original_version:
                target.update(status="LOADED_PENDING_VERIFICATION", businessVerified=False)

        self.runner.apply = missing_business
        self.runner.fail_once = "platform"
        result = self.execute(self.approve(self.draft(rollback=True)))
        self.assertEqual("PARTIAL_FAILURE", result["status"])
        self.assertEqual("FILE_RESTORED_PENDING_VERIFICATION", result["rollbackStatus"])
        self.assertTrue(result["rollbackFileRestored"])
        self.assertTrue(result["awaitingVerification"])
        self.assertEqual(self.original, self.file.read_text())
        self.assertTrue(all(target["loaded"] and target["healthy"] for target in result["targets"]))
        self.assertTrue(all(not target["businessVerified"] for target in result["targets"]))
        self.assertEqual("FAILED", result["targets"][1]["previousAttempts"][0]["status"])
        # Recheck must validate the restored base file; it cannot reuse the failed published version.
        checked_versions = []

        def verify_restored(target, task, draft, definition):
            checked_versions.append(draft["targetVersion"])
            target.update(status="VERIFIED", loaded=True, healthy=True, businessVerified=True)

        self.runner.verify_existing = verify_restored
        self.executor.verify_task(result["id"], {"requestId": str(uuid.uuid4())}, 1)
        for _ in range(200):
            checked = self.executor.load("tasks", result["id"])
            if checked["status"] == "ROLLED_BACK":
                break
            time.sleep(0.01)
        self.assertEqual("ROLLED_BACK", checked["status"])
        self.assertEqual("RESTORED", checked["rollbackStatus"])
        self.assertEqual([original_version, original_version], checked_versions)
        self.assertEqual(4, len(self.runner.applied))

    def test_runtime_restoration_failure_retains_file_and_original_failure_evidence(self):
        original_version = module.digest(self.file.read_bytes())

        def always_fail(target, task, draft, definition):
            if draft["targetVersion"] != original_version:
                target.update(loaded=True, healthy=True, businessVerified=True)
            raise module.Rejected("isolated failure")

        self.runner.apply = always_fail
        result = self.execute(self.approve(self.draft(rollback=True)))
        self.assertEqual("PARTIAL_FAILURE", result["status"])
        self.assertEqual("FILE_RESTORED_RUNTIME_UNCONFIRMED", result["rollbackStatus"])
        self.assertFalse(result["targets"][0]["loaded"])
        self.assertFalse(result["targets"][0]["healthy"])
        self.assertFalse(result["targets"][0]["businessVerified"])
        self.assertEqual("FAILED", result["targets"][0]["previousAttempts"][0]["status"])
        self.assertEqual(self.original, self.file.read_text())

    def test_partial_failure_without_approved_rollback_preserves_actual_state(self):
        self.registry["services"]["platform"] = copy.deepcopy(self.registry["services"]["auth"])
        self.registry["files"]["auth"]["services"].append("platform")
        self.runner.fail_once = "platform"
        result = self.execute(self.approve(self.draft()))
        self.assertEqual("PARTIAL_FAILURE", result["status"])
        self.assertTrue(result["filePublished"])
        self.assertEqual("VERIFIED", result["targets"][0]["status"])

    def test_duplicate_keys_custom_tags_aliases_rejected(self):
        for text in ["x: 1\nx: 2\n", "x: !unsafe value\n", "x: &shared {a: 1}\ny: *shared\n"]:
            with self.assertRaises(module.Rejected):
                module.parse_document(text, "yaml")

    def test_path_outside_root_rejected(self):
        changed = copy.deepcopy(self.registry)
        changed["files"]["auth"]["path"] = str(self.root.parent / "outside.yml")
        with self.assertRaises(module.Rejected):
            module.Executor(changed)

    def test_complex_field_digest_contract(self):
        value = [{"id": "auth", "uri": "http://localhost", "predicates": ["Path=/api/auth/**"]}]
        self.assertEqual('{"[0].id":"auth","[0].predicates[0]":"Path=/api/auth/**","[0].uri":"http://localhost"}',
                         module.evidence_text(value))

    def json_field(self, key, value):
        document = module.parse_document(self.file.read_text(encoding="utf-8"), "yaml")
        module.assign(document, key, value)
        self.file.write_text(module.dump_document(document, "yaml"), encoding="utf-8")
        self.registry["files"]["auth"]["fields"].append({"key": key, "type": "json"})
        self.executor = module.Executor(self.registry, self.runner)
        return next(field["value"] for field in self.executor.detail("auth")["fields"] if field["key"] == key)

    def json_draft(self, key, value):
        return self.executor.create_draft("auth", {"baseVersion": self.executor.detail("auth")["version"],
                "action": "PUBLISH_ONLY", "changes": [{"key": key, "value": value}],
                "requestId": str(uuid.uuid4())}, 1)

    def test_masked_scrape_configs_preserve_credentials_by_job_identity_after_reordering(self):
        original = [{"job_name": "a", "scrape_interval": "15s", "authorization": {"credentials_file": "/private/a"}},
                    {"job_name": "b", "scrape_interval": "15s", "authorization": {"credentials_file": "/private/b"}}]
        visible = self.json_field("scrape_configs", original)
        self.assertEqual(module.MASK, visible[0]["authorization"]["credentials_file"])
        edited = [visible[1], visible[0]]
        edited[0]["scrape_interval"] = "30s"
        draft = self.json_draft("scrape_configs", edited)
        self.assertNotIn("/private/a", json.dumps(draft))
        self.assertNotIn("/private/b", json.dumps(draft))
        task = self.execute(self.approve(draft))
        self.assertEqual("PUBLISHED_PENDING_APPLY", task["status"])
        actual = module.parse_document(self.file.read_text(encoding="utf-8"), "yaml")["scrape_configs"]
        self.assertEqual(["b", "a"], [job["job_name"] for job in actual])
        self.assertEqual(["/private/b", "/private/a"], [job["authorization"]["credentials_file"] for job in actual])
        self.assertEqual("30s", actual[0]["scrape_interval"])
        self.assertNotIn(module.MASK, self.file.read_text(encoding="utf-8"))

    def test_masked_route_credentials_follow_route_id_not_position(self):
        visible = self.json_field("spring.cloud.gateway.routes", [
            {"id": "auth", "uri": "http://name:auth-private@auth.test", "order": 1},
            {"id": "ticket", "uri": "http://name:ticket-private@ticket.test", "order": 2}])
        edited = [visible[1], visible[0]]
        edited[0]["order"] = 3
        draft = self.json_draft("spring.cloud.gateway.routes", edited)
        stored = self.executor.load("drafts", draft["id"])
        rows = module.lookup(module.parse_document(stored["content"], "yaml"), "spring.cloud.gateway.routes")
        self.assertEqual("http://name:ticket-private@ticket.test", rows[0]["uri"])
        self.assertEqual("http://name:auth-private@auth.test", rows[1]["uri"])

    def test_ambiguous_or_new_masked_array_objects_are_rejected_without_creating_drafts(self):
        visible = self.json_field("scrape_configs", [
            {"job_name": "a", "authorization": {"credentials_file": "/private/a"}},
            {"job_name": "b", "authorization": {"credentials_file": "/private/b"}}])
        for replacement in ["b", "new", None]:
            edited = copy.deepcopy(visible)
            if replacement is None:
                del edited[0]["job_name"]
            else:
                edited[0]["job_name"] = replacement
            with self.assertRaises(module.Rejected):
                self.json_draft("scrape_configs", edited)
        self.assertEqual([], self.executor.objects("drafts"))

    def test_changed_unidentified_arrays_and_partial_url_masks_are_not_restored_by_index(self):
        visible = self.json_field("custom.entries", [
            {"label": "first", "password": "first-private"}, {"label": "second", "password": "second-private"}])
        with self.assertRaises(module.Rejected):
            self.json_draft("custom.entries", list(reversed(visible)))
        visible_route = self.json_field("spring.cloud.gateway.routes", [
            {"id": "a", "uri": "http://name:private@original.test"}])
        visible_route[0]["uri"] = "http://******@another.test"
        with self.assertRaises(module.Rejected):
            self.json_draft("spring.cloud.gateway.routes", visible_route)
        self.assertEqual([], self.executor.objects("drafts"))

    def test_unchanged_masked_subtree_is_preserved_and_explicit_new_nested_values_are_allowed(self):
        visible = self.json_field("custom.settings", {"timeout": 3, "targets": [
            {"label": "first", "password": "first-private"}, {"label": "second", "password": "second-private"}]})
        visible["timeout"] = 4
        draft = self.json_draft("custom.settings", visible)
        value = module.lookup(module.parse_document(self.executor.load("drafts", draft["id"])["content"], "yaml"),
                              "custom.settings")
        self.assertEqual("first-private", value["targets"][0]["password"])
        self.assertEqual("second-private", value["targets"][1]["password"])
        visible["targets"][0]["password"] = "explicit-replacement"
        # Mixed edits without stable identity are rejected, even if only one hidden value remains.
        with self.assertRaises(module.Rejected):
            self.json_draft("custom.settings", visible)
        visible["targets"][1]["password"] = "second-explicit-replacement"
        replacement = self.json_draft("custom.settings", visible)
        self.assertNotIn("explicit-replacement", json.dumps(replacement))

    def test_health_only_evidence_does_not_claim_business_validation(self):
        runner = module.ServiceRunner(self.executor)
        runner.fetch = Mock(return_value={"status": "UP"})
        target = {"serviceId": "auth", "afterPid": 1, "evidence": []}
        self.assertFalse(runner.verify_probes({"probeUrls": ["http://127.0.0.1/health"]}, target, {}))

    def test_model_probe_is_attempted_once_even_when_result_is_uncertain(self):
        runner = module.ServiceRunner(self.executor)
        runner.fetch = Mock(side_effect=module.Rejected("probe timed out"))
        target = {"serviceId": "auth", "afterPid": 1, "evidence": []}
        task = {"id": "one-shot-task", "createdAt": module.now(), "targets": [target]}
        service = {"probeUrls": [{"url": "http://127.0.0.1/api/rag/admin/providers/deepseek/probe",
                                   "method": "POST", "oneShot": True, "kind": "BUSINESS", "valuePath": "code", "equals": 0}]}
        with self.assertRaises(module.Rejected):
            runner.verify_probes(service, target, task)
        with self.assertRaises(module.Rejected):
            runner.verify_probes(service, target, task)
        self.assertEqual(1, runner.fetch.call_count)
        self.assertFalse(self.executor.load("tasks", "one-shot-task")["targets"][0]["evidence"][0]["passed"])

    def test_successful_one_shot_evidence_is_bound_to_the_same_process(self):
        runner = module.ServiceRunner(self.executor)
        runner.fetch = Mock(return_value={"code": 0})
        target = {"serviceId": "auth", "afterPid": 1, "evidence": []}
        task = {"id": "one-shot-success", "createdAt": module.now(), "targets": [target]}
        service = {"probeUrls": [{"url": "http://127.0.0.1/api/rag/admin/providers/deepseek/probe",
                                   "method": "POST", "oneShot": True, "kind": "BUSINESS", "valuePath": "code", "equals": 0}]}
        self.assertTrue(runner.verify_probes(service, target, task))
        self.assertTrue(runner.verify_probes(service, target, task))
        self.assertEqual(1, runner.fetch.call_count)
        target["afterPid"] = 2
        with self.assertRaises(module.Rejected):
            runner.verify_probes(service, target, task)
        self.assertEqual(1, runner.fetch.call_count)

    def test_rollback_never_inherits_or_repeats_original_instance_one_shot_evidence(self):
        runner = module.ServiceRunner(self.executor)
        runner.fetch = Mock(return_value={"code": 0})
        target = {"serviceId": "auth", "afterPid": 7, "afterStartedAt": "first", "instanceId": "original",
                  "evidence": []}
        task = {"id": "rollback-one-shot", "createdAt": module.now(), "targets": [target]}
        service = {"probeUrls": [{"url": "http://127.0.0.1/api/rag/admin/providers/deepseek/probe",
                                   "method": "POST", "oneShot": True, "kind": "BUSINESS", "valuePath": "code", "equals": 0}]}
        self.assertTrue(runner.verify_probes(service, target, task))
        # Even a reused PID must not match another creation time / Java instance.
        target.update(afterStartedAt="second", instanceId="restored")
        with self.assertRaises(module.Rejected):
            runner.verify_probes(service, target, task)
        target["previousAttempts"] = [{"evidence": copy.deepcopy(target["evidence"])}]
        target["evidence"] = []
        task["rollbackFileRestored"] = True
        self.assertFalse(runner.verify_probes(service, target, task))
        self.assertFalse(runner.verify_probes(service, target, task))
        self.assertEqual(1, runner.fetch.call_count)
        self.assertTrue(target["previousAttempts"][0]["evidence"][0]["passed"])
        self.assertEqual("original", target["previousAttempts"][0]["evidence"][0]["instanceId"])
        self.assertFalse(any(item.get("kind") == "BUSINESS_PROBE" for item in target["evidence"]))
        self.assertEqual("ONE_SHOT_PENDING_REAPPROVAL", target["evidence"][-1]["kind"])

    def test_http_requires_trusted_actor_and_does_not_accept_browser_direct_access(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), module.handler(self.executor))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = "http://127.0.0.1:" + str(server.server_port) + "/files"
        try:
            with self.assertRaises(urllib.error.HTTPError) as denied:
                urllib.request.urlopen(url)
            self.assertEqual(403, denied.exception.code)
            headers = {"X-Ops-Executor-Token": self.secret, "X-Ops-Actor-Id": "1", "X-Ops-Actor-Role": "ADMIN"}
            data = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=headers)))
            self.assertEqual(0, data["code"])
            headers["Origin"] = "http://127.0.0.1:5173"
            with self.assertRaises(urllib.error.HTTPError):
                urllib.request.urlopen(urllib.request.Request(url, headers=headers))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
