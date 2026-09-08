"""Authenticated, fixed-target read-only infrastructure checks for local OpsAgent.

No arbitrary command/URL interface, no mutation checks, no persistent business payloads.
Configuration and credentials are read from one private JSON file at process startup.
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hmac
import json
import math
import re
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_RESPONSE = 8 * 1024 * 1024
KINDS = {"mysql", "redis", "rabbitmq-amqp", "windows-host", "linux-host", "elasticsearch", "prometheus", "alertmanager", "grafana", "sentinel", "qdrant"}


class CheckFailure(Exception):
    def __init__(self, reason: str):
        self.reason = reason


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CheckFailure("INVALID_RESPONSE")


def fetch(target: dict, path: str, timeout: float, *, json_body: bool = True):
    """GET only, fixed path chosen by code, authenticated response never logged."""
    url = target["url"].rstrip("/") + path
    headers = {"Accept": "application/json" if json_body else "text/plain"}
    if target.get("username"):
        raw = (target["username"] + ":" + target.get("password", "")).encode()
        headers["Authorization"] = "Basic " + base64.b64encode(raw).decode()
    if target.get("api_key"):
        headers["api-key"] = target["api_key"]
    try:
        opener = urllib.request.build_opener(NoRedirect, urllib.request.ProxyHandler({}))
        with opener.open(urllib.request.Request(url, headers=headers, method="GET"), timeout=timeout) as response:
            body = response.read(MAX_RESPONSE + 1)
            if len(body) > MAX_RESPONSE:
                raise CheckFailure("INVALID_RESPONSE")
            text = body.decode("utf-8")
            return json.loads(text) if json_body else text
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            raise CheckFailure("AUTH_REJECTED") from None
        raise CheckFailure("CHECK_FAILED") from None


def native_value(text: str, name: str) -> float:
    values = []
    pattern = re.compile(r"^" + re.escape(name) + r"(?:\{[^\n]*\})?\s+([-+0-9.eE]+)(?:\s|$)")
    for line in text.splitlines():
        match = pattern.match(line)
        if match:
            value = float(match[1])
            if math.isfinite(value):
                values.append(value)
    if not values:
        raise CheckFailure("INVALID_RESPONSE")
    return sum(values)


def check_mysql(target: dict, timeout: float) -> dict:
    import pymysql
    with pymysql.connect(host=target["host"], port=target["port"], user=target["username"],
                         password=target.get("password", ""), database=target.get("database") or None,
                         connect_timeout=max(1, int(timeout)), read_timeout=timeout, write_timeout=timeout,
                         autocommit=True, init_command="SET SESSION TRANSACTION READ ONLY") as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            success = cursor.fetchone()[0] == 1
            cursor.execute("SHOW GLOBAL STATUS LIKE 'Threads_connected'")
            connections = float(cursor.fetchone()[1])
            cursor.execute("SHOW GLOBAL VARIABLES LIKE 'max_connections'")
            maximum = float(cursor.fetchone()[1])
    return {"mysql_query_success": int(success), "mysql_connections": connections,
            "mysql_max_connections": maximum}


def check_redis(target: dict, timeout: float) -> dict:
    import redis
    with redis.Redis(host=target["host"], port=target["port"], username=target.get("username") or None,
                     password=target.get("password") or None, db=target.get("database", 0),
                     socket_connect_timeout=timeout, socket_timeout=timeout,
                     decode_responses=True, retry_on_timeout=False) as client:
        pong = client.ping()
        # These commands never create, expire, replace, or delete a business key.
        clients = client.info("clients")
        memory = client.info("memory")
    return {"redis_ping_success": int(pong), "redis_clients": int(clients["connected_clients"]),
            "redis_used_memory": int(memory["used_memory"])}


def check_rabbitmq(target: dict, timeout: float) -> dict:
    """Authenticate and inspect an existing queue only; never publish, consume or create a queue."""
    import pika
    parameters = pika.ConnectionParameters(host=target["host"], port=target["port"],
        virtual_host=target["virtual_host"], credentials=pika.PlainCredentials(target["username"], target["password"]),
        socket_timeout=timeout, stack_timeout=max(timeout + 1, 2), connection_attempts=1,
        blocked_connection_timeout=timeout, heartbeat=0)
    connection = pika.BlockingConnection(parameters)
    try:
        result = connection.channel().queue_declare(queue=target["queue"], passive=True).method
        return {"rabbitmq_queue_read_success": 1, "rabbitmq_queue_messages": result.message_count,
                "rabbitmq_queue_consumers": result.consumer_count}
    finally:
        if connection.is_open:
            connection.close()


def check_http(target: dict, timeout: float) -> dict:
    kind = target["kind"]
    if kind == "elasticsearch":
        health = fetch(target, "/_cluster/health", timeout)
        result = fetch(target, "/_search?size=0&terminate_after=1", timeout)
        if health.get("status") not in ("green", "yellow", "red"):
            raise CheckFailure("INVALID_RESPONSE")
        search_ok = not result.get("timed_out", True) and result.get("_shards", {}).get("failed", 1) == 0
        return {"elasticsearch_cluster_status": {"green": 0, "yellow": 1, "red": 2}[health["status"]],
                "elasticsearch_nodes": health["number_of_nodes"], "elasticsearch_search_success": int(search_ok)}
    if kind == "prometheus":
        ready = fetch(target, "/-/ready", timeout, json_body=False)
        metrics = fetch(target, "/metrics", timeout, json_body=False)
        targets = fetch(target, "/api/v1/targets?state=active", timeout)
        if targets.get("status") != "success" or not isinstance(targets.get("data", {}).get("activeTargets"), list):
            raise CheckFailure("INVALID_RESPONSE")
        failed = sum(t.get("health") != "up" for t in targets["data"]["activeTargets"])
        return {"prometheus_ready": int("ready" in ready.lower()),
                "prometheus_time_series": native_value(metrics, "prometheus_tsdb_head_series"),
                "prometheus_failed_targets": failed}
    if kind == "alertmanager":
        ready = fetch(target, "/-/ready", timeout, json_body=False)
        metrics = fetch(target, "/metrics", timeout, json_body=False)
        alerts = fetch(target, "/api/v2/alerts", timeout)
        if not isinstance(alerts, list):
            raise CheckFailure("INVALID_RESPONSE")
        return {"alertmanager_ready": int(ready.strip().upper() == "OK" or "ready" in ready.lower()),
                "alertmanager_config_loaded": native_value(metrics, "alertmanager_config_last_reload_successful"),
                "alertmanager_alerts": len(alerts)}
    if kind == "grafana":
        result = fetch(target, "/api/health", timeout)
        if "database" not in result:
            raise CheckFailure("INVALID_RESPONSE")
        return {"grafana_database_ready": int(result["database"] == "ok")}
    if kind == "sentinel":
        # Console HTTP/UI readiness only; this cannot attest connected client rules or traffic.
        result = fetch(target, "/", timeout, json_body=False)
        if "sentinel" not in result.lower() or "<html" not in result.lower():
            raise CheckFailure("INVALID_RESPONSE")
        return {"sentinel_console_ready": 1}
    if kind == "qdrant":
        ready = fetch(target, "/readyz", timeout, json_body=False)
        result = fetch(target, "/collections", timeout)
        collections = result.get("result", {}).get("collections")
        if not isinstance(collections, list):
            raise CheckFailure("INVALID_RESPONSE")
        return {"qdrant_ready": int("ready" in ready.lower()), "qdrant_collections": len(collections)}
    raise CheckFailure("INVALID_RESPONSE")


def classify(error: Exception) -> str:
    if isinstance(error, CheckFailure):
        return error.reason
    if isinstance(error, (ImportError, ModuleNotFoundError)):
        return "DEPENDENCY_MISSING"
    if isinstance(error, (TimeoutError, socket.timeout)) or "timeout" in type(error).__name__.lower():
        return "TIMEOUT"
    if isinstance(error, ConnectionRefusedError):
        return "CONNECTION_REFUSED"
    if isinstance(error, urllib.error.URLError) and isinstance(error.reason, Exception):
        return classify(error.reason)
    # Never expose exception messages: drivers include usernames and endpoint details.
    if type(error).__name__ in ("AuthenticationError", "AuthorizationError", "ProbableAuthenticationError", "ProbableAccessDeniedError") or (error.args and error.args[0] in (1044, 1045, 1698)):
        return "AUTH_REJECTED"
    if isinstance(error, (ValueError, KeyError, TypeError, UnicodeDecodeError)):
        return "INVALID_RESPONSE"
    return "CHECK_FAILED"


def collect(target: dict, timeout: float) -> str:
    checked_at = time.time()
    values = {"read_success": 0, "auth_success": 1, "check_timestamp_seconds": checked_at}
    reason = None
    try:
        if target["kind"] == "windows-host":
            from windows_host import collect as collect_host
            return collect_host(target)
        if target["kind"] == "linux-host":
            from linux_host import collect as collect_host
            return collect_host(target)
        result = check_mysql(target, timeout) if target["kind"] == "mysql" else check_redis(target, timeout) if target["kind"] == "redis" else check_rabbitmq(target, timeout) if target["kind"] == "rabbitmq-amqp" else check_http(target, timeout)
        if not all(isinstance(value, (int, float)) and math.isfinite(value) for value in result.values()):
            raise CheckFailure("INVALID_RESPONSE")
        values.update(result)
        values["read_success"] = 1
        target["_last_success_at"] = checked_at
    except Exception as error:
        reason = classify(error)
        values["auth_success"] = 0 if reason == "AUTH_REJECTED" else 1
    if target.get("_last_success_at"):
        values["last_success_timestamp_seconds"] = target["_last_success_at"]
    lines = []
    for name, value in values.items():
        lines.append(f"# TYPE opsagent_infra_{name} gauge")
        lines.append(f"opsagent_infra_{name} {value}")
    if reason:
        lines.append(f'opsagent_infra_check_error{{reason="{reason}"}} 1')
    return "\n".join(lines) + "\n"


def load_config(path: Path) -> dict:
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(config.get("bearer_token"), str) or len(config["bearer_token"]) < 32:
        raise ValueError("collector requires a private bearer token of at least 32 characters")
    if not 1 <= config.get("listen_port", 18111) <= 65535:
        raise ValueError("invalid listen_port")
    if not 0.2 <= config.get("timeout_seconds", 3) <= 5:
        raise ValueError("timeout_seconds must be 0.2..5")
    if not isinstance(config.get("targets"), dict) or not 1 <= len(config["targets"]) <= 32:
        raise ValueError("one to 32 fixed targets required")
    for name, target in config["targets"].items():
        if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,64}", name) or target.get("kind") not in KINDS:
            raise ValueError("invalid fixed target")
        if target["kind"] == "windows-host":
            from windows_host import validate
            validate(target)
        elif target["kind"] == "linux-host":
            from linux_host import validate
            validate(target)
        elif target["kind"] in {"mysql", "redis", "rabbitmq-amqp"}:
            if not isinstance(target.get("host"), str) or not 1 <= target.get("port", 0) <= 65535:
                raise ValueError("invalid target address")
            if target["kind"] == "rabbitmq-amqp" and any(not isinstance(target.get(key), str) or not target[key]
                    or len(target[key]) > 255 for key in ("virtual_host", "queue", "username", "password")):
                raise ValueError("AMQP checks require one existing queue and explicit credentials")
        else:
            url = urllib.parse.urlsplit(target.get("url", ""))
            if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in {"", "/"}:
                raise ValueError("HTTP targets require a base URL without credentials or path")
    return config


def make_server(config: dict):
    targets = config["targets"]
    slots = threading.BoundedSemaphore(8)
    locks = {name: threading.Lock() for name in targets}

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(12)

        def log_message(self, fmt, *args):
            pass

        def send(self, code: int, content: str):
            body = content.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode("utf-8"), ("Bearer " + config["bearer_token"]).encode("utf-8")):
                self.send(401, "authentication required\n")
                return
            match = re.fullmatch(r"/metrics/([a-zA-Z0-9_.-]{1,64})", self.path)
            name = match[1] if match else ""
            if name not in targets or targets[name].get("enabled") is False:
                self.send(404, "unknown or disabled target\n")
                return
            if not slots.acquire(blocking=False):
                self.send(503, "collector busy\n")
                return
            if not locks[name].acquire(blocking=False):
                slots.release()
                self.send(503, "target check in progress\n")
                return
            try:
                self.send(200, collect(targets[name], config.get("timeout_seconds", 3)))
            finally:
                locks[name].release()
                slots.release()

    server = ThreadingHTTPServer((config.get("listen_host", "127.0.0.1"), config.get("listen_port", 18111)), Handler)
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="check every enabled target once; no server")
    args = parser.parse_args()
    config = load_config(args.config)
    if args.check:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            active = [(name, target) for name, target in config["targets"].items() if target.get("enabled") is not False]
            results = pool.map(lambda pair: collect(pair[1], config.get("timeout_seconds", 3)), active)
            for (name, _), result in zip(active, results):
                print(f"# target {name}\n{result}")
    else:
        make_server(config).serve_forever(poll_interval=0.5)


if __name__ == "__main__":
    main()
