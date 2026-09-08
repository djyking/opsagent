import unittest
from types import SimpleNamespace
from unittest.mock import Mock

from linux_host import validate
from windows_host import render, sample


class LinuxHostTests(unittest.TestCase):
    def target(self):
        return {"kind": "linux-host", "host_id": "cloud-host", "disks": ["/"], "interfaces": ["eth0"]}

    def test_rejects_traversal_and_windows_disks(self):
        validate(self.target())
        for disk in ["../etc", "/var/../", "C:", "//remote/share"]:
            with self.assertRaises(ValueError):
                validate({**self.target(), "disks": [disk]})

    def test_mount_identity_and_network_warmup_are_preserved(self):
        target = self.target()
        driver = Mock()
        driver.cpu_percent.return_value = 0
        driver.virtual_memory.return_value = SimpleNamespace(total=1000, available=700)
        driver.disk_usage.return_value = SimpleNamespace(percent=20, free=800, total=1000)
        driver.net_if_stats.return_value = {"eth0": SimpleNamespace(isup=True, speed=0)}
        driver.net_io_counters.return_value = {"eth0": SimpleNamespace(bytes_recv=100, bytes_sent=50)}
        values, complete, at = sample(target, driver, lambda: 10, lambda: 100)
        driver.disk_usage.assert_called_with("/")
        self.assertFalse(complete)
        self.assertFalse(any(name == "network_receive_bytes_per_second" for name, _, _ in values))
        driver.net_io_counters.return_value = {"eth0": SimpleNamespace(bytes_recv=200, bytes_sent=100)}
        values, complete, at = sample(target, driver, lambda: 20, lambda: 110)
        self.assertTrue(complete)
        text = render(target, values, complete, at, "LINUX_HOST")
        self.assertIn('scope="LINUX_HOST"', text)
        self.assertIn('disk="/"', text)
        self.assertNotIn('network_utilization_percent', text)


if __name__ == "__main__":
    unittest.main()
