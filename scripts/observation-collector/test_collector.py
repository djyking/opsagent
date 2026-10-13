import io
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

import collector


class CollectorTests(unittest.TestCase):
    def test_elastic_yellow_is_preserved_and_search_is_read_only(self):
        target = {"kind": "elasticsearch", "url": "http://127.0.0.1:9200"}
        with patch.object(collector, "fetch", side_effect=[
            {"status": "yellow", "number_of_nodes": 1}, {"timed_out": False, "_shards": {"failed": 0}}
        ]) as fetch:
            result = collector.check_http(target, 3)
        self.assertEqual(result["elasticsearch_cluster_status"], 1)
        self.assertEqual(result["elasticsearch_search_success"], 1)
        self.assertEqual(fetch.call_args_list[1].args[1], "/_search?size=0&terminate_after=1")

    def test_partial_search_failure_is_not_success(self):
        with patch.object(collector, "fetch", side_effect=[
            {"status": "green", "number_of_nodes": 1}, {"timed_out": False, "_shards": {"failed": 1}}
        ]):
            result = collector.check_http({"kind": "elasticsearch"}, 3)
        self.assertEqual(result["elasticsearch_search_success"], 0)

    def test_auth_failure_has_safe_reason_and_never_exposes_credentials(self):
        with patch.object(collector, "check_http", side_effect=collector.CheckFailure("AUTH_REJECTED")), patch.object(collector.time, "time", return_value=1234):
            result = collector.collect({"kind": "grafana", "password": "private-password"}, 3)
        self.assertIn('reason="AUTH_REJECTED"', result)
        self.assertIn("opsagent_infra_auth_success 0", result)
        self.assertIn("opsagent_infra_read_success 0", result)
        self.assertIn("opsagent_infra_check_timestamp_seconds 1234", result)
        self.assertNotIn("private-password", result)

    def test_unexpected_response_cannot_become_success(self):
        with patch.object(collector, "check_http", return_value={"grafana_database_ready": float("nan")}):
            result = collector.collect({"kind": "grafana"}, 3)
        self.assertIn('reason="INVALID_RESPONSE"', result)
        self.assertIn("opsagent_infra_read_success 0", result)

    def test_redirects_never_forward_target_credentials(self):
        with self.assertRaises(collector.CheckFailure):
            collector.NoRedirect().redirect_request(None, None, 302, "", {}, "http://other/")

    def test_prometheus_downstream_count_is_separate(self):
        with patch.object(collector, "fetch", side_effect=["Prometheus Server is Ready.", "prometheus_tsdb_head_series 35\n",
                {"status": "success", "data": {"activeTargets": [{"health": "up"}, {"health": "down"}]}}]):
            result = collector.check_http({"kind": "prometheus"}, 3)
        self.assertEqual(result["prometheus_ready"], 1)
        self.assertEqual(result["prometheus_failed_targets"], 1)

    def test_alertmanager_ready_endpoint_uses_native_ok_response(self):
        with patch.object(collector, "fetch", side_effect=["OK", "alertmanager_config_last_reload_successful 1\n", []]):
            result = collector.check_http({"kind": "alertmanager"}, 3)
        self.assertEqual(result["alertmanager_ready"], 1)

    def test_invalid_url_credentials_and_short_token_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "private.json"
            config = {"bearer_token": "x" * 40, "targets": {"grafana": {"kind": "grafana", "url": "http://user:secret@localhost:3000"}}}
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                collector.load_config(path)
            config["targets"]["grafana"]["url"] = "http://localhost:3000"
            config["bearer_token"] = "short"
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                collector.load_config(path)

    def test_unauthorized_or_unknown_target_never_runs_a_check(self):
        server = collector.make_server({"listen_port": 0, "bearer_token": "x" * 40,
                "targets": {"grafana": {"kind": "grafana", "url": "http://localhost:3000"}}})
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with patch.object(collector, "collect", return_value="safe 1\n") as check:
                for path, token, expected in [("/metrics/grafana", "", 401), ("/metrics/../../mysql", "x" * 40, 404),
                        ("/metrics/grafana?url=http://other", "x" * 40, 404)]:
                    request = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token})
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(request)
                    self.assertEqual(error.exception.code, expected)
                check.assert_not_called()
                request = urllib.request.Request(base + "/metrics/grafana", headers={"Authorization": "Bearer " + "x" * 40})
                with urllib.request.urlopen(request) as result:
                    self.assertEqual(result.read(), b"safe 1\n")
                check.assert_called_once()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
