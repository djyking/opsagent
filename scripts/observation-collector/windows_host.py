"""Fixed, local Windows host sampling. No commands, remote hosts or process inspection."""
from __future__ import annotations

import json
import math
import os
import re
import time


def validate(target):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", target.get("host_id", "")):
        raise ValueError("host_id is required")
    disks, interfaces = target.get("disks"), target.get("interfaces")
    if not isinstance(disks, list) or not 1 <= len(disks) <= 8 or len(set(disks)) != len(disks):
        raise ValueError("one to eight distinct local disks required")
    if any(not isinstance(disk, str) or not re.fullmatch(r"[A-Z]:", disk) for disk in disks):
        raise ValueError("only fixed local drive letters are accepted")
    if not isinstance(interfaces, list) or not 1 <= len(interfaces) <= 4 or len(set(interfaces)) != len(interfaces):
        raise ValueError("one to four explicit interfaces required")
    if any(not isinstance(name, str) or not name or len(name) > 100 or any(ord(c) < 32 for c in name) for name in interfaces):
        raise ValueError("invalid interface name")


def sample(target, driver, clock=time.monotonic, wall=time.time):
    """Counters require two real observations; warming/reset gaps never become zero throughput."""
    result = []
    add = lambda name, value, **labels: result.append((name, float(value), labels))
    cpu = driver.cpu_percent(interval=0.1)
    memory = driver.virtual_memory()
    add("cpu_usage_percent", cpu)
    add("physical_memory_usage_percent", 100 * (memory.total - memory.available) / memory.total)
    add("physical_memory_used_bytes", memory.total - memory.available)
    add("physical_memory_total_bytes", memory.total)
    complete = True
    for disk in target["disks"]:
        try:
            usage = driver.disk_usage(disk if target.get("kind") == "linux-host" else disk + "\\")
            add("disk_usage_percent", usage.percent, disk=disk)
            add("disk_free_bytes", usage.free, disk=disk)
            add("disk_total_bytes", usage.total, disk=disk)
        except (OSError, ValueError):
            complete = False
    statistics, counters = driver.net_if_stats(), driver.net_io_counters(pernic=True, nowrap=False)
    now = clock()
    previous = target.setdefault("_network_samples", {})
    for interface in target["interfaces"]:
        info, current = statistics.get(interface), counters.get(interface)
        if info is None or current is None:
            complete = False
            previous.pop(interface, None)
            continue
        add("network_up", int(info.isup), interface=interface)
        if not info.isup:
            complete = False
            previous.pop(interface, None)
            continue
        if info.speed > 0:
            add("network_speed_bits_per_second", info.speed * 1_000_000, interface=interface)
        before = previous.get(interface)
        previous[interface] = (now, current.bytes_recv, current.bytes_sent)
        if before is None or not 0.2 <= now - before[0] <= 90:
            complete = False
            continue
        receive, transmit = current.bytes_recv - before[1], current.bytes_sent - before[2]
        if receive < 0 or transmit < 0:
            complete = False
            continue
        rx, tx = receive / (now - before[0]), transmit / (now - before[0])
        add("network_receive_bytes_per_second", rx, interface=interface)
        add("network_transmit_bytes_per_second", tx, interface=interface)
        # Negotiated link speed is the denominator, not an assumed Internet subscription speed.
        if info.speed > 0:
            utilization = 100 * max(rx, tx) * 8 / (info.speed * 1_000_000)
            if 0 <= utilization <= 100:
                add("network_utilization_percent", utilization, interface=interface)
    if not all(math.isfinite(value) and value >= 0 for _, value, _ in result):
        raise ValueError("invalid native host reading")
    return result, complete, wall()


def collect(target):
    import psutil
    if os.name != "nt":
        raise RuntimeError("Windows host sampling requires Windows")
    values, complete, at = sample(target, psutil)
    return render(target, values, complete, at, "WINDOWS_HOST")


def render(target, values, complete, at, scope):
    labels = {"host_id": target["host_id"], "scope": scope}
    lines = []
    declared = set()
    for name, value, dimensions in values:
        encoded = ",".join(key + "=" + json.dumps(value, ensure_ascii=False) for key, value in {**labels, **dimensions}.items())
        metric = "opsagent_infra_host_" + name
        if metric not in declared:
            lines.append(f"# TYPE {metric} gauge")
            declared.add(metric)
        lines.append(f"{metric}{{{encoded}}} {value}")
    for name, value in {"read_success": 1, "auth_success": 1, "check_timestamp_seconds": at,
                        "last_success_timestamp_seconds": at, "host_complete": int(complete)}.items():
        lines.extend([f"# TYPE opsagent_infra_{name} gauge", f"opsagent_infra_{name} {value}"])
    return "\n".join(lines) + "\n"
