"""Verify the isolated HTTP contract without Docker, production services, or long waits."""

import importlib.util
import json
import os
from pathlib import Path
import threading
import time
import unittest
from http.client import HTTPConnection
from http.server import HTTPServer


os.environ["OPS_OPERATIONS_LAB_TOKEN"] = "isolated-unit-test-token-never-a-production-secret"
spec = importlib.util.spec_from_file_location("operations_lab", Path(__file__).with_name("app.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


class LaboratoryContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), lab.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(2)

    def setUp(self):
        lab.fault_until = 0

    def request(self, path, method="GET", token=None):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request(method, path, headers={"X-Lab-Token": token} if token else {})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_actual_health_fault_recovery_cycle(self):
        self.assertEqual(self.request("/health")[0], 200)
        self.assertEqual(self.request("/fault", "POST", lab.TOKEN)[0], 200)
        status, body = self.request("/health")
        self.assertEqual(status, 503)
        self.assertEqual(body["scope"], "ISOLATED_LAB")
        self.assertTrue(body["faultActive"])
        self.assertLessEqual(body["autoRecoverSeconds"], 45)
        self.assertEqual(self.request("/recover", "POST", lab.TOKEN)[0], 200)
        self.assertEqual(self.request("/health")[0], 200)

    def test_unauthorized_mutation_cannot_change_health(self):
        self.assertEqual(self.request("/fault", "POST", "wrong-token")[0], 403)
        self.assertEqual(self.request("/fault", "POST")[0], 403)
        self.assertEqual(self.request("/health")[0], 200)

    def test_fault_expires_without_platform_recovery(self):
        self.request("/fault", "POST", lab.TOKEN)
        lab.fault_until = time.monotonic() - 1
        self.assertEqual(self.request("/health")[0], 200)

    def test_unlisted_actions_are_not_executable(self):
        self.assertEqual(self.request("/exec", "POST", lab.TOKEN)[0], 404)
        self.assertEqual(self.request("/health")[0], 200)


if __name__ == "__main__":
    unittest.main()
