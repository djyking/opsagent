"""Fixed Linux host sampling, executed on the host to avoid container scope ambiguity."""
from __future__ import annotations

import os
import re
import sys
from pathlib import PurePosixPath

from windows_host import render, sample


def validate(target):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", target.get("host_id", "")):
        raise ValueError("host_id is required")
    disks, interfaces = target.get("disks"), target.get("interfaces")
    if not isinstance(disks, list) or not 1 <= len(disks) <= 8 or len(set(disks)) != len(disks):
        raise ValueError("one to eight distinct mount points required")
    for disk in disks:
        if not isinstance(disk, str) or not disk.startswith("/") or disk.startswith("//") or ".." in PurePosixPath(disk).parts or any(ord(c) < 32 for c in disk):
            raise ValueError("only explicit absolute local mount points accepted")
    if not isinstance(interfaces, list) or not 1 <= len(interfaces) <= 4 or len(set(interfaces)) != len(interfaces):
        raise ValueError("one to four explicit interfaces required")
    if any(not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,32}", name) for name in interfaces):
        raise ValueError("invalid interface")


def collect(target):
    import psutil
    if not sys.platform.startswith("linux") or os.path.exists("/.dockerenv"):
        raise RuntimeError("Linux host sampling must run on the host")
    validate(target)
    values, complete, at = sample(target, psutil)
    return render(target, values, complete, at, "LINUX_HOST")
