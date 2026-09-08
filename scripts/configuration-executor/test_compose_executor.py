"""Fixed Linux Compose adapter tests; all commands and HTTP responses are simulated."""
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import executor as module


class ComposeExecutorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.script = self.root / "scripts" / "compose.sh"
        self.script.parent.mkdir()
        self.script.write_text("# fixed test wrapper\n")
        (self.script.parent / "compose-command.sh").write_text("# trace selector\n")
        self.file = self.root / "auth.yml"
        self.file.write_text("pool: 10\n")
        self.secret = "compose-test-only-secret-" * 3
        (self.root / "secret").write_text(self.secret)
        self.service = {"kind": "COMPOSE_JAVA", "composeProject": "opsagent", "composeService": "ops-auth-app",
                        "composeScript": str(self.script), "bashExecutable": str(self.root / "bash"),
                        "dockerExecutable": str(self.root / "docker"), "network": "opsagent_backplane",
                        "networkCidr": "172.20.0.0/16", "port": 8101,
                        "configurationFiles": [str(self.file)], "startupTimeoutSeconds": 2,
                        "snapshotUrl": "http://ops-auth-app:8101/internal/runtime/configuration-evidence",
                        "healthUrl": "http://ops-auth-app:8101/actuator/health",
                        "probeUrls": [{"url": "http://ops-auth-app:8101/api/auth/me", "kind": "BUSINESS",
                                       "valuePath": "code", "equals": 0, "authenticated": True}]}
        self.registry = {"secretFile": str(self.root / "secret"), "stateDirectory": str(self.root / "state"),
                         "allowedRoots": [str(self.root)], "listenHost": "172.20.0.1",
                         "allowedClientCidrs": ["172.20.0.0/16"], "services": {"ops-auth-service": self.service},
                         "files": {"auth": {"path": str(self.file), "services": ["ops-auth-service"],
                                            "fields": [{"key": "pool", "type": "integer", "min": 1, "max": 30}]}}}
        self.executor = module.Executor(self.registry)
        self.runner = self.executor.runner
        self.container = "a" * 64
        self.started = (datetime.now(timezone.utc) - timedelta(seconds=5)).isoformat()
        self.labels = {"com.docker.compose.project": "opsagent", "com.docker.compose.service": "ops-auth-app",
                       "com.docker.compose.project.config_files": str(self.root / "compose.yaml")}
        self.mutations = 0
        self.runner.command = Mock(side_effect=self.command)
        self.linux = patch.object(module, "IS_LINUX", True)
        self.linux.start()

    def tearDown(self):
        self.linux.stop()
        self.temporary.cleanup()

    def command(self, command, timeout=30):
        if command[1] == str(self.script):
            if command[2] == "ps":
                return self.container + "\n"
            if command[2] == "up":
                self.mutations += 1
                self.container = "b" * 64
            return ""
        if command[1] == "inspect":
            return json.dumps({"id": self.container, "image": "sha256:" + "c" * 64,
                               "state": {"Running": True, "StartedAt": self.started}, "labels": self.labels,
                               "pidMode": "", "networks": {"opsagent_backplane": {"IPAddress": "172.20.0.12"}},
                               "mounts": [{"Type": "bind", "Source": str(self.root), "Destination": "/managed"}]})
        raise AssertionError("Unexpected command")

    def test_private_binding_and_target_registration_are_explicit(self):
        self.assertTrue(self.executor.accepts_client("172.20.0.25"))
        self.assertFalse(self.executor.accepts_client("172.21.0.25"))
        self.assertFalse(self.executor.accepts_client("8.8.8.8"))
        for value in ("0.0.0.0/0", "10.0.0.0/8", "127.0.0.0/16"):
            with self.assertRaises(module.Rejected):
                module.private_network(value)
        with self.assertRaises(module.Rejected):
            self.executor.loopback_url("http://ops-auth-app:9999/health")
        with self.assertRaises(module.Rejected):
            self.executor.loopback_url("http://arbitrary-host:8101/health")
        changed = copy.deepcopy(self.registry)
        changed["services"]["ops-auth-service"]["composeService"] = "mysql"
        with self.assertRaises(module.Rejected):
            module.Executor(changed)

    @unittest.skipUnless(os.name == "posix", "POSIX owner/group/mode are checked on Linux")
    def test_published_file_keeps_container_read_permission_and_private_state_stays_private(self):
        os.chmod(self.file, 0o640)
        before = self.file.stat()
        module.atomic_write(self.file, b"pool: 12\n", preserve_permissions=True)
        after = self.file.stat()
        self.assertEqual(0o640, after.st_mode & 0o777)
        self.assertEqual((before.st_uid, before.st_gid), (after.st_uid, after.st_gid))
        private = self.root / "private-backup"
        module.atomic_write(private, b"private test")
        self.assertEqual(0o600, private.stat().st_mode & 0o777)

    def test_container_identity_mount_and_trace_marker_are_verified(self):
        actual = self.runner.registered_container(self.service)
        self.runner.configuration_mount(self.service, self.executor.definition("auth"), actual)
        actual["mounts"] = []
        with self.assertRaises(module.Rejected):
            self.runner.configuration_mount(self.service, self.executor.definition("auth"), actual)
        self.labels["com.docker.compose.project"] = "another-project"
        with self.assertRaises(module.Rejected):
            self.runner.registered_container(self.service)
        self.labels["com.docker.compose.project"] = "opsagent"
        self.labels["com.docker.compose.project.config_files"] += "," + str(self.root / "compose.observability-v3.yaml")
        with self.assertRaises(module.Rejected):
            self.runner.registered_container(self.service)
        (self.root / "runtime").mkdir()
        (self.root / "runtime" / "observability-v3.enabled").write_text("opsagent-observability-v3-verified-v1\n")
        self.assertTrue(self.runner.registered_container(self.service)["trace"])

    def test_compose_control_change_invalidates_approval_and_never_runs_arbitrary_command(self):
        before = self.executor.registry_digest(self.executor.definition("auth"))
        self.script.write_text("# wrapper changed\n")
        self.assertNotEqual(before, self.executor.registry_digest(self.executor.definition("auth")))
        with self.assertRaises(module.Rejected):
            self.runner.compose_command(self.service, ["ps", "--all", "--quiet", "ops-auth-app"])
        with self.assertRaises(module.Rejected):
            self.runner.compose_command(self.service, ["down"])
        self.runner.command.assert_not_called()

    def test_fetch_uses_inspected_private_ip_without_resolving_service_dns(self):
        response = Mock()
        response.status = 200
        response.read.return_value = b'{"status":"UP"}'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response
        with patch.object(module.urllib.request, "build_opener", return_value=opener):
            self.runner.fetch(self.service["snapshotUrl"], signed=True)
        request = opener.open.call_args.args[0]
        self.assertEqual("http://172.20.0.12:8101/internal/runtime/configuration-evidence", request.full_url)
        self.assertTrue(request.get_header("X-ops-executor-signature"))

    def test_new_container_requires_loaded_file_values_and_preserves_fixed_recreate_arguments(self):
        content = "pool: 12\n"
        draft = {"content": content, "targetVersion": module.digest(content)}
        target = {"serviceId": "ops-auth-service", "evidence": []}
        task = {"id": "test-compose-task", "createdAt": module.now(), "targets": [target], "events": []}
        proof = {"serviceId": "ops-auth-service", "pid": 1, "instanceId": "new-java-instance",
                 "startedAt": datetime.now(timezone.utc).isoformat(),
                 "configurationFiles": [{"fileName": "auth.yml", "loadedVersion": draft["targetVersion"], "sourcePresent": True}],
                 "fields": [{"key": "pool", "overridden": False,
                             "valueDigest": hmac.new(self.secret.encode(), b"12", hashlib.sha256).hexdigest()}]}
        self.runner.fetch = Mock(side_effect=lambda url, **kwargs: {"data": proof} if url == self.service["snapshotUrl"]
                                 else {"status": "UP"} if url == self.service["healthUrl"] else {"code": 0})
        self.runner.apply(target, task, draft, self.executor.definition("auth"))
        self.assertEqual("VERIFIED", target["status"])
        self.assertEqual("b" * 64, target["afterContainerId"])
        self.assertEqual(1, self.mutations)
        call = next(call for call in self.runner.command.call_args_list if call.args[0][2:3] == ["up"])
        self.assertEqual([str(self.root / "bash"), str(self.script), "up", "--detach", "--no-deps",
                          "--force-recreate", "--no-build", "--pull", "never", "ops-auth-app"], call.args[0])
        proof["fields"][0]["overridden"] = True
        with self.assertRaises(module.Rejected):
            self.runner.check_java_loaded(self.service, target, draft, self.executor.definition("auth"))
        proof["fields"][0]["overridden"] = False
        self.container = "d" * 64
        with self.assertRaises(module.Rejected):
            self.runner.verify_existing(target, task, draft, self.executor.definition("auth"))

    def test_uncertain_recreate_is_not_repeated_by_automatic_rollback(self):
        draft = self.executor.create_draft("auth", {"baseVersion": self.executor.detail("auth")["version"],
            "action": "PUBLISH_RESTART", "changes": [{"key": "pool", "value": 12}],
            "rollbackOnFailure": True, "requestId": uuid.uuid4().hex}, 1)
        for action in ("submit", "approve"):
            self.executor.transition(draft["id"], action, {"digest": draft["digest"], "decision": "APPROVE",
                                      "requestId": uuid.uuid4().hex}, 1)
        normal = self.command
        def uncertain(command, timeout=30):
            if command[2:3] == ["up"]:
                self.mutations += 1
                raise module.Rejected("simulated unknown transport outcome")
            return normal(command, timeout)
        self.runner.command.side_effect = uncertain
        task = self.executor.transition(draft["id"], "execute", {"digest": draft["digest"], "requestId": uuid.uuid4().hex}, 1)
        for _ in range(200):
            current = self.executor.load("tasks", task["id"])
            if current["status"] == "RESULT_UNKNOWN":
                break
            time.sleep(0.01)
        self.assertEqual("RESULT_UNKNOWN", current["status"])
        self.assertEqual(1, self.mutations)
        self.assertTrue(current["filePublished"])
        self.assertEqual("NOT_REQUESTED", current["rollbackStatus"])
        self.assertEqual("pool: 12\n", self.file.read_text())

    def test_prometheus_checks_and_hup_use_the_verified_container_id(self):
        prom = {**self.service, "kind": "PROMETHEUS", "composeService": "prometheus", "port": 9090,
                "healthUrl": "http://prometheus:9090/-/ready", "configurationUrl": "http://prometheus:9090/api/v1/status/config",
                "probeUrls": [{"url": "http://prometheus:9090/api/v1/targets", "kind": "BUSINESS",
                               "valuePath": "status", "equals": "success"}]}
        prom.pop("snapshotUrl")
        self.registry["services"] = {"prometheus": prom}
        self.registry["files"]["auth"]["services"] = ["prometheus"]
        executor = module.Executor(self.registry)
        runner = executor.runner
        self.labels["com.docker.compose.service"] = "prometheus"
        def command(values, timeout=30):
            return self.command(values, timeout) if values[1] in {str(self.script), "inspect"} else ""
        runner.command = Mock(side_effect=command)
        runner.fetch = Mock(side_effect=lambda url, **kwargs: {"data": {"yaml": "pool: 12\n"}}
                            if url == prom["configurationUrl"] else "ready" if url == prom["healthUrl"]
                            else {"status": "success"})
        target = {"serviceId": "prometheus", "evidence": []}
        task = {"id": "prom-task", "createdAt": module.now(), "targets": [target], "events": []}
        runner.apply(target, task, {"content": "pool: 12\n", "targetVersion": module.digest("pool: 12\n")},
                     executor.definition("auth"))
        self.assertEqual("VERIFIED", target["status"])
        calls = [call.args[0] for call in runner.command.call_args_list]
        self.assertIn([prom["dockerExecutable"], "kill", "--signal", "HUP", "a" * 64], calls)
        self.assertTrue(any(call[1:6] == ["exec", "--user", "0", "a" * 64, "promtool"] for call in calls))
        self.assertTrue(any(call[1:6] == ["exec", "--user", "0", "a" * 64, "rm"] for call in calls))
        self.assertFalse(any(call[2:3] == ["up"] for call in calls))

    def test_prometheus_rejects_single_file_mount_before_publication(self):
        prom = {**self.service, "kind": "PROMETHEUS"}
        actual = {"mounts": [{"Type": "bind", "Source": str(self.file),
                              "Destination": "/etc/prometheus/auth.yml"}]}
        with self.assertRaisesRegex(module.Rejected, "目录挂载"):
            self.runner.configuration_mount(prom, self.executor.definition("auth"), actual)


if __name__ == "__main__":
    unittest.main()
