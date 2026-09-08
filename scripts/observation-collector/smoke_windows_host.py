"""Two bounded correctness checks; no platform changes or fault injection."""
from types import SimpleNamespace as N
import unittest
import windows_host


class Driver:
    received = 100
    sent = 200
    def cpu_percent(self, interval): return 25
    def virtual_memory(self): return N(total=1000, available=300)
    def disk_usage(self, path):
        assert path == 'C:\\'
        return N(percent=50, free=500, total=1000)
    def net_if_stats(self): return {'WLAN': N(isup=True, speed=100)}
    def net_io_counters(self, pernic, nowrap): return {'WLAN': N(bytes_recv=self.received, bytes_sent=self.sent)}


class HostSmoke(unittest.TestCase):
    def test_real_deltas_and_reset_gap(self):
        target = {'host_id': 'host', 'disks': ['C:'], 'interfaces': ['WLAN']}
        driver = Driver()
        values, complete, _ = windows_host.sample(target, driver, lambda: 10, lambda: 100)
        self.assertFalse(complete)
        self.assertFalse(any('receive_bytes_per_second' == name.removeprefix('network_') for name, _, _ in values))
        driver.received, driver.sent = 400, 500
        values, complete, _ = windows_host.sample(target, driver, lambda: 25, lambda: 115)
        self.assertTrue(complete)
        self.assertEqual(next(v for k, v, _ in values if k == 'network_receive_bytes_per_second'), 20)
        driver.received = 10
        values, complete, _ = windows_host.sample(target, driver, lambda: 40, lambda: 130)
        self.assertFalse(complete)
        self.assertFalse(any(k == 'network_receive_bytes_per_second' for k, _, _ in values))

    def test_local_fixed_scope_validation(self):
        windows_host.validate({'host_id': 'host', 'disks': ['C:', 'D:'], 'interfaces': ['WLAN']})
        with self.assertRaises(ValueError):
            windows_host.validate({'host_id': 'host', 'disks': ['\\\\server\\share'], 'interfaces': ['WLAN']})


if __name__ == '__main__': unittest.main()
