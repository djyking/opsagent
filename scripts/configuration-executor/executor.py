"""Registered-target configuration publisher independent of business JVMs.

Private registry, drafts, backups and state must remain outside Git. No public command/path API.
"""

from __future__ import annotations

import argparse
import copy
import ctypes
import hashlib
import hmac
import io
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ruamel.yaml import YAML


MAX_BYTES = 1024 * 1024
IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$")
SECRET_KEY = re.compile(r"(?i)(password|passwd|secret|token|api[-_]?key|credential|private[-_]?key)")
MASK = "******"
TERMINAL = {"PUBLISHED_PENDING_APPLY", "VERIFIED", "FAILED", "PARTIAL_FAILURE",
            "RESULT_UNKNOWN", "ROLLED_BACK"}
COMPOSE_JAVA = {
    "ops-gateway": ("ops-gateway-app", 18080),
    "ops-auth-service": ("ops-auth-app", 8101),
    "ops-ticket-service": ("ops-ticket-app", 8102),
    "ops-knowledge-service": ("ops-knowledge-app", 8103),
    "ops-rag-service": ("ops-rag-app", 8104),
    "ops-platform-service": ("ops-platform-app", 8105),
    "ops-agent-service": ("ops-agent-app", 8106),
    "ops-demo-order-service": ("ops-demo-order-app", 8110),
}
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
IS_LINUX = sys.platform.startswith("linux")


def uses_compose(service):
    return service.get("kind") == "COMPOSE_JAVA" or service.get("kind") == "PROMETHEUS" and bool(service.get("composeService"))


def private_network(value):
    try:
        network = ipaddress.ip_network(value, strict=True)
        if network.version != 4 or network.prefixlen < 16 or not any(network.subnet_of(root) for root in PRIVATE_NETWORKS):
            raise ValueError()
        return network
    except (ValueError, TypeError):
        raise Rejected("仅允许明确登记的 RFC1918 IPv4 私网子网（前缀至少 /16）") from None


class Rejected(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(data):
    return hashlib.sha256(data if isinstance(data, bytes) else data.encode("utf-8")).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sensitive_name(key):
    # Token limits/counts are business parameters, while actual token credentials remain secret by default.
    lower = key.lower()
    if "token" in lower and re.search(r"(?:token[-_.]?(?:budget|count|ttl)|(?:max|context|output|target)[-_.].*tokens$)", lower):
        return bool(re.search(r"(?i)(password|passwd|secret|api[-_]?key|credential|private[-_]?key)", key))
    return bool(SECRET_KEY.search(key))


def yaml_engine():
    engine = YAML(typ="rt")
    engine.preserve_quotes = True
    engine.allow_duplicate_keys = False
    engine.width = 4096
    return engine


def plain(value, seen=None):
    seen = set() if seen is None else seen
    if isinstance(value, (dict, list)):
        if id(value) in seen:
            raise Rejected("暂不支持带别名引用或循环结构的配置；未进行有损写回")
        seen.add(id(value))
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            raise Rejected("配置键必须为字符串")
        return {key: plain(item, seen) for key, item in value.items()}
    if isinstance(value, list):
        return [plain(item, seen) for item in value]
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, float) and math.isfinite(value):
        return value
    raise Rejected("暂不支持该配置中的自定义类型；未进行有损写回")


def parse_document(content, format_name):
    if len(content.encode("utf-8")) > MAX_BYTES:
        raise Rejected("配置文件超过允许大小")
    try:
        document = json.loads(content) if format_name == "json" else yaml_engine().load(content)
        if not isinstance(document, dict):
            raise Rejected("配置根节点必须为对象")
        plain(document)
        return document
    except Rejected:
        raise
    except Exception:
        raise Rejected("配置格式无效、存在重复键或不支持的结构；未发布") from None


def dump_document(document, format_name):
    if format_name == "json":
        return json.dumps(plain(document), ensure_ascii=False, indent=2) + "\n"
    stream = io.StringIO()
    yaml_engine().dump(document, stream)
    return stream.getvalue()


def lookup(document, key):
    cursor = document
    parts = key.split(".")
    while parts:
        if not isinstance(cursor, dict):
            return None
        found = False
        for length in range(len(parts), 0, -1):
            part = ".".join(parts[:length])
            if part in cursor:
                cursor = cursor[part]
                parts = parts[length:]
                found = True
                break
        if not found:
            return None
    return cursor


def assign(document, key, value):
    cursor = document
    parts = key.split(".")
    while parts:
        for length in range(len(parts), 0, -1):
            part = ".".join(parts[:length])
            if part in cursor:
                if length == len(parts):
                    cursor[part] = value
                    return
                if not isinstance(cursor[part], dict):
                    raise Rejected("字段路径与已有值冲突")
                cursor = cursor[part]
                parts = parts[length:]
                break
        else:
            if len(parts) == 1:
                cursor[parts[0]] = value
                return
            cursor[parts[0]] = {}
            cursor = cursor[parts.pop(0)]


def flattened(document, prefix=""):
    result = {}
    for key, value in document.items():
        name = prefix + key
        if isinstance(value, dict) and value:
            result.update(flattened(value, name + "."))
        else:
            result[name] = plain(value)
    return result


def mask_document(value, prefix="", sensitive_keys=()):
    if isinstance(value, dict):
        return {key: MASK if sensitive_name(key) or prefix + key in sensitive_keys
                else mask_document(item, prefix + key + ".", sensitive_keys)
                for key, item in value.items()}
    if isinstance(value, list):
        return [mask_document(item, prefix, sensitive_keys) for item in value]
    if isinstance(value, str):
        if re.search(r"(?i)(Bearer |Basic |-----BEGIN|[?&](?:password|token|key)=)", value):
            return MASK
        return re.sub(r"(://)[^/@\s]+@", r"\1******@", value)
    return value


def mask_all_values(value):
    if isinstance(value, dict):
        return {key: mask_all_values(item) for key, item in value.items()}
    if isinstance(value, list):
        return [mask_all_values(item) for item in value]
    return MASK


def visitor_sensitive_name(key):
    return sensitive_name(key) or bool(re.search(r"(?i)(authorization|access[-_]?key|client[-_]?key|cookie|session[-_]?id)", key))


def visitor_value(value, key=""):
    """Public values come from registered fields, without comments or credential payloads."""
    if visitor_sensitive_name(key):
        return "未设置" if value is None or value == "" else "已设置"
    if isinstance(value, dict):
        named_secret = visitor_sensitive_name(str(value.get("name", "")))
        return {name: visitor_value(item, "secret" if named_secret and name == "value" else name)
                for name, item in value.items()}
    if isinstance(value, list):
        return [visitor_value(item) for item in value]
    if isinstance(value, str):
        decoded = urllib.parse.unquote(value)
        # A registered string may itself carry a JSON payload or a URL query credential.
        if decoded.lstrip().startswith(("{", "[")):
            try:
                nested = json.loads(decoded)
                return json.dumps(visitor_value(nested), ensure_ascii=False)
            except (ValueError, TypeError):
                return "已设置"
        if (mask_document(decoded) != decoded or MASK in decoded
                or re.search(r"(?i)(?:[?&;]|^)(?:[\w.-]*(?:password|passwd|secret|token|api[-_]?key|access[-_]?key|authorization|cookie))[\w.-]*\s*=", decoded)):
            return "已设置"
    return value


def contains_mask(value):
    if isinstance(value, dict):
        return any(contains_mask(item) for item in value.values())
    if isinstance(value, list):
        return any(contains_mask(item) for item in value)
    return isinstance(value, str) and any(marker in value for marker in (MASK, "••••••", "保留原值"))


def restore_masked_json(original, submitted, visible=None, identity_key=None):
    """Resolve only server-produced placeholders; never pair changed arrays by position."""
    if visible is None:
        visible = mask_document(plain(original))
    if not contains_mask(submitted):
        return copy.deepcopy(submitted)
    if submitted == visible and plain(original) != visible:
        # An unchanged masked subtree means preserve the whole original subtree, not individual indexes.
        return copy.deepcopy(original)
    if isinstance(submitted, dict):
        if not isinstance(original, dict) or not isinstance(visible, dict):
            raise Rejected("新增或结构变化的 JSON 含脱敏占位值，请明确提供新值，未创建草稿")
        result = {}
        for key, value in submitted.items():
            if key not in original and contains_mask(value):
                raise Rejected("新增 JSON 字段不能继承脱敏值，请明确提供新值：" + key)
            result[key] = restore_masked_json(original.get(key), value, visible.get(key))
        return result
    if isinstance(submitted, list):
        if not identity_key or not isinstance(original, list) or not isinstance(visible, list):
            raise Rejected("含脱敏值的数组缺少明确且稳定的对象标识，不能按位置恢复；请保留该数组或明确替换")

        def identified(items):
            mapped = {}
            for item in items:
                identity = item.get(identity_key) if isinstance(item, dict) else None
                if (not isinstance(identity, str) or not identity.strip() or contains_mask(identity)
                        or identity in mapped):
                    raise Rejected("含脱敏值的数组必须具有不重复且明确的 " + identity_key + "，未创建草稿")
                mapped[identity] = item
            return mapped

        before = identified(original)
        masked = identified(visible)
        identified(submitted)
        result = []
        for item in submitted:
            identity = item[identity_key]
            if identity not in before and contains_mask(item):
                raise Rejected("新增或改名对象不能继承其他对象的脱敏值，请明确提供新值：" + identity_key)
            result.append(restore_masked_json(before.get(identity), item, masked.get(identity)))
        return result
    raise Rejected("脱敏占位值与原字段不匹配；不能把星号写回或迁移到其他地址，请明确提供新值")


def atomic_write(path, data, preserve_permissions=False):
    path = Path(path)
    previous = path.stat() if preserve_permissions and os.name == "posix" and path.exists() else None
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        if previous is not None:
            os.chown(temporary, previous.st_uid, previous.st_gid)
            os.chmod(temporary, previous.st_mode & 0o777)
        for attempt in range(10):
            try:
                os.replace(temporary, path)
                break
            except PermissionError:
                if attempt == 9:
                    raise
                time.sleep(0.05)
    finally:
        if temporary.exists():
            temporary.unlink()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


class Executor:
    def __init__(self, registry, runner=None):
        self.registry = registry
        self.secret = Path(registry["secretFile"]).read_text(encoding="utf-8-sig").strip()
        if len(self.secret) < 32:
            raise Rejected("执行器信任密钥长度不足")
        self.state = Path(registry["stateDirectory"]).resolve()
        self.state.mkdir(parents=True, exist_ok=True)
        os.chmod(self.state, 0o700)
        for folder in ("drafts", "tasks", "backups"):
            (self.state / folder).mkdir(exist_ok=True)
            os.chmod(self.state / folder, 0o700)
        self.lock = threading.RLock()
        self.execution_lock = threading.Lock()
        self.runner = runner or ServiceRunner(self)
        self.files = registry["files"]
        self.services = registry["services"]
        self.resolved_paths = {}
        self.compose_controls = {}
        self.validate_registry()
        self.mark_interrupted()

    def validate_registry(self):
        self.listen_host = self.registry.get("listenHost", "127.0.0.1")
        cidrs = self.registry.get("allowedClientCidrs", [])
        if not isinstance(cidrs, list) or len(cidrs) > 1:
            raise Rejected("只能登记一个专用调用方私网子网")
        self.client_networks = tuple(private_network(cidr) for cidr in cidrs)
        if self.listen_host != "127.0.0.1":
            try:
                address = ipaddress.ip_address(self.listen_host)
            except ValueError:
                raise Rejected("执行器监听地址必须为登记的 IPv4 私网字面地址") from None
            if not self.client_networks or not any(address in network for network in self.client_networks):
                raise Rejected("执行器监听地址不在专用私网子网内")
        if not self.files or not self.services:
            raise Rejected("执行器尚未登记文件与目标")
        paths = set()
        for file_id, definition in self.files.items():
            self.check_id(file_id)
            target = self.path(definition)
            self.resolved_paths[str(Path(definition["path"]).absolute())] = target
            if str(target).lower() in paths:
                raise Rejected("同一文件不能以多个身份登记")
            paths.add(str(target).lower())
            if definition.get("format", "yaml") not in {"yaml", "json"}:
                raise Rejected("仅支持明确登记的 YAML/JSON 文件")
            if not definition.get("services") or any(
                    service not in self.services for service in definition["services"]):
                raise Rejected("配置文件缺少完整目标登记")
            keys = [field["key"] for field in definition.get("fields", [])]
            if len(keys) != len(set(keys)):
                raise Rejected("可管理字段必须唯一且明确登记")
            if any(sensitive_name(key) and not field.get("sensitive")
                   for key, field in zip(keys, definition["fields"])):
                raise Rejected("敏感参数必须显式声明，不能作为普通字段返回")
        for service_id, service in self.services.items():
            self.check_id(service_id)
            if service.get("kind") not in {"JAVA", "PROMETHEUS", "COMPOSE_JAVA"}:
                raise Rejected("不支持该目标启动方式")
            if uses_compose(service):
                self.validate_compose_service(service_id, service)
            for key in ("healthUrl", "snapshotUrl", "configurationUrl"):
                if service.get(key):
                    self.loopback_url(service[key])
            for probe in service.get("probeUrls", []):
                self.loopback_url(probe if isinstance(probe, str) else probe["url"])
                if isinstance(probe, dict) and probe.get("method", "GET") != "GET":
                    path = urllib.parse.urlsplit(probe["url"]).path
                    if (probe.get("method") != "POST" or not probe.get("oneShot")
                            or not re.fullmatch(r"/api/rag/admin/providers/(deepseek|openai|kimi)/probe", path)):
                        raise Rejected("仅允许登记的单次模型验证 POST，其他业务检查必须只读 GET")
            if service["kind"] == "JAVA":
                command = service.get("command", [])
                if (len(command) < 3 or Path(command[0]).name.lower() not in {"java", "java.exe"}
                        or not Path(command[0]).is_absolute() or "-jar" not in command):
                    raise Rejected("Java 必须登记固定可执行文件及 JAR 启动参数")
                if any("=" in arg and sensitive_name(arg.split("=", 1)[0]) for arg in command[1:]):
                    raise Rejected("固定启动命令不得携带凭据参数")

    @staticmethod
    def check_id(value):
        if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
            raise Rejected("对象标识不合法")

    def loopback_url(self, url):
        parsed = urllib.parse.urlsplit(url)
        registered = [service for service in self.services.values()
                      if uses_compose(service) and service.get("composeService") == parsed.hostname
                      and service.get("port") == parsed.port]
        if (parsed.scheme != "http" or parsed.username or parsed.password or parsed.fragment
                or (parsed.hostname not in {"127.0.0.1", "localhost", "::1"} and len(registered) != 1)):
            raise Rejected("目标证据接口必须是回环地址或已登记 Compose 服务及固定端口")

    def validate_compose_service(self, service_id, service):
        expected = ("prometheus", 9090) if service_id == "prometheus" and service["kind"] == "PROMETHEUS" else COMPOSE_JAVA.get(service_id)
        if expected != (service.get("composeService"), service.get("port")):
            raise Rejected("Compose 目标必须匹配固定服务名称与端口")
        if service.get("composeProject") != "opsagent" or not IDENTIFIER.fullmatch(service.get("network", "")):
            raise Rejected("Compose 项目或专用网络未明确登记")
        private_network(service.get("networkCidr"))
        for key, name in (("dockerExecutable", "docker"), ("bashExecutable", "bash")):
            value = Path(service.get(key, ""))
            if not value.is_absolute() or value.name != name:
                raise Rejected("Compose 管理程序必须登记固定绝对路径")
        script = Path(service.get("composeScript", ""))
        if (not script.is_absolute() or script.name != "compose.sh" or script.parent.name != "scripts"
                or script.is_symlink() or not script.is_file()):
            raise Rejected("必须登记现有 scripts/compose.sh 固定入口")
        self.compose_controls[service_id] = self.compose_control_digest(service)
        if not service.get("configurationFiles"):
            raise Rejected("Compose 目标缺少实际挂载的配置文件登记")
        for path in service["configurationFiles"]:
            self.path({"path": path})

    @staticmethod
    def compose_control_digest(service):
        script = Path(service["composeScript"])
        helper = script.parent / "compose-command.sh"
        if script.is_symlink() or helper.is_symlink() or not helper.is_file():
            raise Rejected("Compose 固定入口或 Trace 选择器缺失/重定向")
        return digest(script.read_bytes() + b"\0" + helper.read_bytes())

    def accepts_client(self, address):
        if address in {"127.0.0.1", "::1"}:
            return True
        try:
            return any(ipaddress.ip_address(address) in network for network in self.client_networks)
        except ValueError:
            return False

    def path(self, definition):
        configured = Path(definition["path"]).absolute()
        target = configured.resolve()
        roots = [Path(root).resolve() for root in self.registry["allowedRoots"]]
        pinned = getattr(self, "resolved_paths", {}).get(str(configured), target)
        if (configured.is_symlink() or target != pinned
                or not any(target.is_relative_to(root) for root in roots)):
            raise Rejected("配置文件路径不在固定白名单内或已被重定向", 409)
        return target

    def definition(self, file_id):
        self.check_id(file_id)
        if file_id not in self.files:
            raise Rejected("未登记的配置文件", 404)
        return self.files[file_id]

    def read_file(self, definition):
        path = self.path(definition)
        try:
            data = path.read_bytes()
            if len(data) > MAX_BYTES:
                raise Rejected("配置文件超过允许大小")
            return data, data.decode("utf-8-sig")
        except Rejected:
            raise
        except Exception:
            raise Rejected("登记的配置文件当前不可读取", 503) from None

    def summary(self, file_id):
        definition = self.definition(file_id)
        status, reason = "AVAILABLE", ""
        try:
            _, content = self.read_file(definition)
            parse_document(content, definition.get("format", "yaml"))
        except Rejected as failure:
            status, reason = "UNAVAILABLE", str(failure)
        return {"id": file_id, "serviceId": definition.get("serviceId", file_id),
                "label": definition.get("label", file_id), "format": definition.get("format", "yaml"),
                "sensitive": bool(definition.get("sensitive")),
                "editable": status == "AVAILABLE" and bool(definition.get("fields")),
                "status": status, "reason": reason, "affectedServices": definition["services"],
                "applyAction": "RELOAD" if all(self.services[s]["kind"] == "PROMETHEUS"
                                              for s in definition["services"]) else "RESTART"}

    def catalog(self):
        return {"items": [self.summary(file_id) for file_id in self.files], "executorStatus": "AVAILABLE"}

    def visitor_catalog(self):
        items = [{**self.summary(file_id), "editable": False, "reason": "访客可查看脱敏参数，不能修改配置。"}
                 for file_id in self.files]
        return {"items": items, "executorStatus": "AVAILABLE", "accessMode": "VISITOR_READ_ONLY"}

    def visitor_detail(self, file_id):
        with self.lock:
            definition = self.definition(file_id)
            raw, content = self.read_file(definition)
            document = parse_document(content, definition.get("format", "yaml"))
            fields, visible = [], {}
            for specification in definition["fields"]:
                key = specification["key"]
                value = lookup(document, key)
                sensitive = bool(specification.get("sensitive")) or visitor_sensitive_name(key)
                shown = visitor_value(value, "secret" if sensitive else key)
                field = {name: specification[name] for name in ("key", "label", "category", "type", "unit", "min", "max")
                         if name in specification}
                field.update(hasValue=value is not None and value != "", editable=False, sensitive=sensitive)
                if not sensitive:
                    field["value"] = shown
                fields.append(field)
                assign(visible, key, shown)
            result = self.summary(file_id)
            result.update(version=digest(raw), fields=fields, editable=False, rawEditable=False,
                          reason="访客只读 · 敏感值仅显示设置状态。", accessMode="VISITOR_READ_ONLY",
                          redactedContent=dump_document(visible, definition.get("format", "yaml")),
                          contentScope="REGISTERED_FIELDS", loadedVersion="", publishedVersion="", lastTaskId="")
            return result

    def visitor_history(self, file_id):
        definition = self.definition(file_id)
        with self.lock:
            history = self.history(file_id)
            published = {task["draftId"]: task for task in history["tasks"] if task.get("filePublished")}
            specifications = {field["key"]: field for field in definition["fields"]}
            versions = []
            for draft in history["drafts"]:
                if draft["id"] not in published:
                    continue
                task = published[draft["id"]]
                diff = []
                for change in draft.get("diff", []):
                    key = change.get("key", "")
                    specification = specifications.get(key)
                    if specification is None:
                        continue
                    sensitive = bool(specification.get("sensitive")) or visitor_sensitive_name(key)
                    diff.append({"key": key, "label": specification.get("label", key), "sensitive": sensitive,
                                 "before": "已设置" if sensitive else visitor_value(change.get("before"), key),
                                 "after": "已设置" if sensitive else visitor_value(change.get("after"), key)})
                versions.append({key: draft[key] for key in ("id", "baseVersion", "targetVersion", "action", "createdAt")})
                versions[-1].update(status=task["status"], diff=diff)
            return {"drafts": [], "tasks": [], "versions": versions, "accessMode": "VISITOR_READ_ONLY"}

    def detail(self, file_id):
        with self.lock:
            definition = self.definition(file_id)
            raw, content = self.read_file(definition)
            document = parse_document(content, definition.get("format", "yaml"))
            fields = []
            for specification in definition["fields"]:
                field = {key: value for key, value in specification.items()
                         if key in {"key", "label", "category", "type", "unit", "min", "max",
                                    "options", "sensitive", "description"}}
                value = lookup(document, specification["key"])
                field.update({"hasValue": value is not None, "editable": True,
                              "sensitive": bool(specification.get("sensitive"))})
                if not field["sensitive"]:
                    field["value"] = mask_document(plain(value))
                fields.append(field)
            sensitive = [field["key"] for field in definition["fields"] if field.get("sensitive")]
            redacted = copy.deepcopy(document)
            for key in sensitive:
                if lookup(redacted, key) is not None:
                    assign(redacted, key, MASK)
            # Redaction must inspect every unknown field as well, including nested JSON/list values.
            redacted = mask_document(plain(redacted), sensitive_keys=sensitive)
            if definition.get("sensitive"):
                redacted = mask_all_values(redacted)
            history = self.history(file_id)["tasks"]
            latest = history[0] if history else None
            unchanged = plain(document) == plain(redacted)
            raw_editable = not definition.get("sensitive") and not sensitive and unchanged and bool(fields)
            return {**self.summary(file_id), "version": digest(raw), "fields": fields,
                    "redactedContent": content if raw_editable else dump_document(redacted, definition.get("format", "yaml")),
                    "rawEditable": raw_editable,
                    "loadedVersion": latest["targetVersion"] if latest and latest["status"] == "VERIFIED" else "",
                    "publishedVersion": latest["targetVersion"] if latest and latest.get("filePublished") else "",
                    "lastTaskId": latest["id"] if latest else ""}

    def validate_value(self, specification, value):
        kind = specification.get("type", "string")
        valid = ((kind == "string" and isinstance(value, str))
                 or (kind == "boolean" and isinstance(value, bool))
                 or (kind == "integer" and isinstance(value, int) and not isinstance(value, bool))
                 or (kind == "number" and isinstance(value, (int, float)) and not isinstance(value, bool))
                 or (kind == "json" and isinstance(value, (dict, list))))
        if not valid:
            raise Rejected("参数类型不正确：" + specification["key"])
        plain(value)
        if specification.get("sensitive") and (not value or str(value) in {MASK, "••••••", "保留原值"}):
            raise Rejected("敏感参数必须明确提供新值，不能提交占位内容")
        if isinstance(value, str):
            if len(value) > specification.get("maxLength", 4096) or "\x00" in value:
                raise Rejected("参数长度或字符不合法：" + specification["key"])
            if specification.get("pattern") and not re.fullmatch(specification["pattern"], value):
                raise Rejected("参数格式不符合要求：" + specification["key"])
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if (value < specification.get("min", -float("inf"))
                    or value > specification.get("max", float("inf"))):
                raise Rejected("参数超出允许范围：" + specification["key"])
        if specification.get("options") and value not in specification["options"]:
            raise Rejected("参数不在允许选项内：" + specification["key"])

    def validate_document(self, definition, before, after):
        specifications = {field["key"]: field for field in definition["fields"]}
        left, right = flattened(before), flattened(after)
        for key in left.keys() | right.keys():
            if left.get(key) == right.get(key) and (key in left) == (key in right):
                continue
            if not any(key == known or spec.get("type") == "json" and key.startswith(known + ".")
                       for known, spec in specifications.items()):
                raise Rejected("不能修改未纳管字段：" + key)
        changes = []
        for key, specification in specifications.items():
            old, new = lookup(before, key), lookup(after, key)
            if plain(old) == plain(new):
                continue
            self.validate_value(specification, new)
            secret = bool(specification.get("sensitive"))
            changes.append({"key": key, "label": specification.get("label", key), "sensitive": secret,
                            "before": "已设置" if old is not None else "未设置" if secret else None,
                            "after": "替换新值" if secret else mask_document(plain(new))})
            if not secret:
                changes[-1]["before"] = mask_document(plain(old))
        for rule in definition.get("constraints", []):
            left_value, right_value = lookup(after, rule["left"]), lookup(after, rule["right"])
            if left_value is not None and right_value is not None and rule["operator"] == "LTE":
                if left_value > right_value:
                    raise Rejected(rule.get("message", "参数关系不符合要求"))
        return changes

    def create_draft(self, file_id, request, actor):
        with self.lock:
            definition = self.definition(file_id)
            if not definition.get("fields"):
                raise Rejected("该文件仅作为敏感引导引用展示，当前未开放字段变更或独立应用")
            raw, content = self.read_file(definition)
            if request.get("baseVersion") != digest(raw):
                raise Rejected("文件已被更新，请保留草稿并重新核对基础版本", 409)
            action = request.get("action")
            if action not in {"PUBLISH_ONLY", "PUBLISH_RESTART", "APPLY_ONLY"}:
                raise Rejected("请选择明确的发布或应用方式")
            if not isinstance(request.get("rollbackOnFailure", False), bool):
                raise Rejected("失败恢复选项必须明确")
            before = parse_document(content, definition.get("format", "yaml"))
            after = copy.deepcopy(before)
            changes = request.get("changes", [])
            if not isinstance(changes, list) or len(changes) > 200:
                raise Rejected("字段修改清单不合法")
            if request.get("content") is not None:
                if changes or not self.detail(file_id)["rawEditable"]:
                    raise Rejected("敏感配置只允许逐字段替换；原文与字段修改不能同时提交")
                if not isinstance(request["content"], str):
                    raise Rejected("原文必须为字符串")
                new_content = request["content"]
                after = parse_document(new_content, definition.get("format", "yaml"))
            else:
                known = {spec["key"]: spec for spec in definition["fields"]}
                seen = set()
                for change in changes:
                    if not isinstance(change, dict) or change.get("key") not in known:
                        raise Rejected("修改包含未纳管参数")
                    key = change["key"]
                    if key in seen:
                        raise Rejected("同一字段不能重复提交")
                    seen.add(key)
                    self.validate_value(known[key], change.get("value"))
                    value = change["value"]
                    if known[key].get("sensitive"):
                        if contains_mask(value):
                            raise Rejected("敏感参数必须明确提供新值，不能提交嵌套脱敏占位内容")
                    else:
                        # These are explicit platform schema identities, never guessed from arbitrary array indexes.
                        identity_key = {"scrape_configs": "job_name", "spring.cloud.gateway.routes": "id"}.get(key)
                        value = restore_masked_json(lookup(before, key), value, identity_key=identity_key)
                    assign(after, key, value)
                new_content = dump_document(after, definition.get("format", "yaml")) if changes else content
            diff = self.validate_document(definition, before, after)
            if action == "APPLY_ONLY" and (diff or changes or request.get("content") is not None):
                raise Rejected("单独应用只能审批当前已发布版本，不能附带文件修改")
            if action != "APPLY_ONLY" and not diff:
                raise Rejected("没有实际参数修改；应用现有文件请选择单独应用")
            if hasattr(self.runner, "validate_configuration"):
                for service_id in definition["services"]:
                    self.runner.validate_configuration(service_id, new_content)
            request_id = self.request_id(request)
            fingerprint = digest(canonical({"fileId": file_id, "baseVersion": digest(raw),
                                            "content": new_content, "action": action,
                                            "rollbackOnFailure": request.get("rollbackOnFailure", False)}))
            for previous in self.objects("drafts"):
                if previous.get("requestId") == request_id and previous["createdBy"] == actor:
                    if previous["requestFingerprint"] != fingerprint:
                        raise Rejected("同一请求标识不能用于不同草稿", 409)
                    return self.public_draft(previous)
            identity = str(uuid.uuid4())
            draft = {"id": identity, "fileId": file_id, "baseVersion": digest(raw),
                     "targetVersion": digest(new_content), "action": action,
                     "rollbackOnFailure": request.get("rollbackOnFailure", False),
                     "affectedServices": definition["services"], "diff": diff, "status": "DRAFT",
                     "createdBy": actor, "createdAt": now(), "approvedBy": None, "approvedAt": None,
                     "taskId": None, "content": new_content, "requestId": request_id,
                     "requestFingerprint": fingerprint,
                     "registryDigest": self.registry_digest(definition),
                     "impact": "仅发布文件，不主动重启或重载" if action == "PUBLISH_ONLY"
                     else "按登记顺序逐个重启或重载目标；单实例可能短暂不可用"}
            if action != "PUBLISH_ONLY" and any(
                    isinstance(probe, dict) and probe.get("method") == "POST"
                    for service_id in definition["services"] for probe in self.services[service_id].get("probeUrls", [])):
                draft["impact"] += "；模型验证会执行一次真实调用，参数与目标固定在本次审批"
            draft["digest"] = digest(canonical({key: draft[key] for key in
                                              ("id", "fileId", "baseVersion", "targetVersion", "action",
                                               "rollbackOnFailure", "affectedServices", "registryDigest")}))
            self.save("drafts", draft)
            return self.public_draft(draft)

    def registry_digest(self, definition):
        value = {"file": definition, "targets": {key: self.services[key] for key in definition["services"]}}
        controls = {key: self.compose_control_digest(self.services[key])
                    for key in definition["services"] if key in self.compose_controls}
        if controls:
            value["composeControls"] = controls
        return digest(canonical(value))

    @staticmethod
    def request_id(request):
        request_id = request.get("requestId")
        if not isinstance(request_id, str) or not IDENTIFIER.fullmatch(request_id):
            raise Rejected("必须提供唯一请求标识")
        return request_id

    @staticmethod
    def public_draft(draft):
        return {key: value for key, value in draft.items()
                if key not in {"content", "requestId", "requestFingerprint", "registryDigest", "operationRequests", "approvalProof"}}

    def approval_proof(self, draft):
        payload = canonical({key: draft[key] for key in ("digest", "approvedBy", "approvedAt")})
        return hmac.new(self.secret.encode(), payload.encode(), hashlib.sha256).hexdigest()

    def verify_approval(self, draft):
        expected = digest(canonical({key: draft[key] for key in
                                     ("id", "fileId", "baseVersion", "targetVersion", "action",
                                      "rollbackOnFailure", "affectedServices", "registryDigest")}))
        if (draft["targetVersion"] != digest(draft["content"]) or draft["digest"] != expected
                or not draft.get("approvedBy") or not draft.get("approvedAt")
                or not hmac.compare_digest(draft.get("approvalProof", ""), self.approval_proof(draft))):
            raise Rejected("持久审批或内容完整性核验失败，未执行变更", 409)

    def load(self, folder, identity):
        self.check_id(identity)
        try:
            with self.lock:
                return read_json(self.state / folder / (identity + ".json"))
        except FileNotFoundError:
            raise Rejected("记录不存在", 404) from None

    def save(self, folder, value):
        with self.lock:
            atomic_write(self.state / folder / (value["id"] + ".json"), canonical(value).encode("utf-8"))

    def objects(self, folder):
        with self.lock:
            return sorted((read_json(path) for path in (self.state / folder).glob("*.json")),
                          key=lambda item: item["createdAt"], reverse=True)

    def history(self, file_id):
        self.definition(file_id)
        with self.lock:
            return {"drafts": [self.public_draft(item) for item in self.objects("drafts")
                               if item["fileId"] == file_id][:50],
                    "tasks": [item for item in self.objects("tasks") if item["fileId"] == file_id][:50]}

    def transition(self, identity, operation, request, actor, user_authorization=None):
        with self.lock:
            draft = self.load("drafts", identity)
            if request.get("digest") != draft["digest"]:
                raise Rejected("审批内容摘要不一致，请重新核对", 409)
            request_id = self.request_id(request)
            records = draft.setdefault("operationRequests", {})
            marker = canonical({"operation": operation, "actor": actor, "decision": request.get("decision"),
                                "comment": request.get("comment", "")})
            if request_id in records:
                if records[request_id] != marker:
                    raise Rejected("请求标识已被不同操作使用", 409)
                return self.load("tasks", draft["taskId"]) if operation == "execute" else self.public_draft(draft)
            if operation == "submit":
                if draft["status"] != "DRAFT":
                    raise Rejected("当前草稿不能重复送审", 409)
                draft.update({"status": "PENDING_APPROVAL", "submittedBy": actor, "submittedAt": now()})
            elif operation == "approve":
                if draft["status"] != "PENDING_APPROVAL":
                    raise Rejected("当前草稿不在待审批状态", 409)
                if request.get("decision") not in {"APPROVE", "REJECT"}:
                    raise Rejected("审批决定不合法")
                comment = request.get("comment", "")
                if not isinstance(comment, str) or len(comment) > 500:
                    raise Rejected("审批说明长度不合法")
                draft.update({"status": "APPROVED" if request["decision"] == "APPROVE" else "REJECTED",
                              "approvedBy": actor, "approvedAt": now(), "approvalComment": comment})
                if request["decision"] == "APPROVE":
                    draft["approvalProof"] = self.approval_proof(draft)
            elif operation == "execute":
                if draft.get("taskId"):
                    return self.load("tasks", draft["taskId"])
                if draft["status"] != "APPROVED":
                    raise Rejected("该版本尚未通过逐次审批", 409)
                self.verify_approval(draft)
                definition = self.definition(draft["fileId"])
                if self.registry_digest(definition) != draft["registryDigest"]:
                    raise Rejected("固定目标或启动方式已变化，必须重新审批", 409)
                raw, _ = self.read_file(definition)
                if digest(raw) != draft["baseVersion"]:
                    raise Rejected("当前文件与批准版本不一致，请重新创建变更", 409)
                task = {"id": str(uuid.uuid4()), "draftId": identity, "fileId": draft["fileId"],
                        "action": draft["action"], "status": "QUEUED", "baseVersion": draft["baseVersion"],
                        "targetVersion": draft["targetVersion"], "createdAt": now(), "updatedAt": now(),
                        "createdBy": actor, "approvedBy": draft["approvedBy"],
                        "message": "已批准，等待受控执行", "filePublished": False,
                        "rollbackStatus": "NOT_REQUESTED", "targets": [
                            {"serviceId": service, "status": "PENDING", "beforePid": None, "afterPid": None,
                             "loaded": False, "healthy": False, "businessVerified": False, "evidence": []}
                            for service in draft["affectedServices"]], "events": []}
                self.save("tasks", task)
                draft.update({"taskId": task["id"], "status": "EXECUTING"})
            else:
                raise Rejected("不支持该操作", 404)
            records[request_id] = marker
            self.save("drafts", draft)
            if operation == "execute":
                threading.Thread(target=self.run_task, args=(task["id"], user_authorization), daemon=True).start()
                return task
            return self.public_draft(draft)

    def task_event(self, task, stage, message):
        with self.lock:
            task.update({"status": stage, "message": message, "updatedAt": now()})
            task["events"].append({"at": now(), "stage": stage, "message": message})
            self.save("tasks", task)

    def verify_task(self, identity, request, actor, user_authorization=None):
        self.request_id(request)
        with self.lock:
            task = self.load("tasks", identity)
            if task["status"] not in TERMINAL and not task.get("awaitingVerification"):
                raise Rejected("任务仍在执行，请先查询同一任务", 409)
            if task["status"] in {"PUBLISHED_PENDING_APPLY", "ROLLED_BACK"}:
                raise Rejected("该任务尚未应用或已恢复旧版；请另行审批目标当前版本", 409)
            draft = self.load("drafts", task["draftId"])
            self.verify_approval(draft)
            definition = self.definition(task["fileId"])
            restoring = task.get("rollbackFileRestored", False)
            expected_version = task["baseVersion"] if restoring else task["targetVersion"]
            if (digest(self.read_file(definition)[0]) != expected_version
                    or self.registry_digest(definition) != draft["registryDigest"]):
                raise Rejected("文件或目标已变化，不能沿用旧任务核验", 409)
            task["awaitingVerification"] = False
            task["verificationRequestedBy"] = actor
            self.task_event(task, "LOADED_PENDING_VERIFICATION",
                            "正在复查已恢复旧版的加载及业务证据；不发布或重启" if restoring
                            else "正在复查批准版本的加载及业务证据；不发布或重启")
            threading.Thread(target=self.recheck_task, args=(identity, user_authorization), daemon=True).start()
            return task

    def recheck_task(self, identity, user_authorization):
        with self.execution_lock:
            self.runner.user_authorization = user_authorization
            task = self.load("tasks", identity)
            draft = self.load("drafts", task["draftId"])
            definition = self.definition(task["fileId"])
            restoring = task.get("rollbackFileRestored", False)
            try:
                raw, content = self.read_file(definition)
                expected_version = task["baseVersion"] if restoring else task["targetVersion"]
                if digest(raw) != expected_version:
                    raise Rejected("等待复查期间文件已变化，未沿用旧版本")
                checked_targets = task["targets"]
                if restoring:
                    draft = {**draft, "targetVersion": task["baseVersion"], "content": content}
                    checked_targets = [target for target in task["targets"]
                                       if target["serviceId"] in task.get("rollbackTargets", [])]
                for target in checked_targets:
                    try:
                        self.runner.verify_existing(target, task, draft, definition)
                    except Exception:
                        target["businessVerified"] = False
                        target["status"] = "RESULT_UNKNOWN"
                        raise
                if restoring:
                    self.settle_rollback(task)
                    return
                if all(target["businessVerified"] for target in task["targets"]):
                    self.task_event(task, "VERIFIED", "批准版本的进程、加载与业务证据复查通过")
                else:
                    task["awaitingVerification"] = True
                    self.task_event(task, "LOADED_PENDING_VERIFICATION", "加载检查通过，仍需业务验证证据")
            except Exception as failure:
                message = str(failure) if isinstance(failure, Rejected) else "运行或业务证据复查失败，未重复发布和重启"
                if restoring:
                    task["rollbackStatus"] = "FILE_RESTORED_RUNTIME_UNCONFIRMED"
                    task["awaitingVerification"] = True
                    self.task_event(task, "PARTIAL_FAILURE", "原文件已恢复，恢复运行证据仍未全部确认：" + message)
                else:
                    self.task_event(task, "RESULT_UNKNOWN", message)
            finally:
                self.runner.user_authorization = None

    def mark_interrupted(self):
        for task in self.objects("tasks"):
            if task["status"] not in TERMINAL:
                self.task_event(task, "RESULT_UNKNOWN", "执行器曾中断，保留实际回执；先核对文件与进程后重新审批，未自动重复执行")

    def run_task(self, task_id, user_authorization=None):
        with self.execution_lock:
            self.runner.user_authorization = user_authorization
            task = self.load("tasks", task_id)
            draft = self.load("drafts", task["draftId"])
            definition = self.definition(task["fileId"])
            touched = []
            try:
                self.verify_approval(draft)
                with self.lock:
                    current, _ = self.read_file(definition)
                    if digest(current) != draft["baseVersion"]:
                        raise Rejected("等待执行期间文件版本已变化，未执行旧审批", 409)
                    if self.registry_digest(definition) != draft["registryDigest"]:
                        raise Rejected("目标登记变化，未执行旧审批", 409)
                # Validate fixed identities before any irreversible work; approval cannot stop unknown processes.
                if draft["action"] != "PUBLISH_ONLY":
                    for target in task["targets"]:
                        self.runner.preflight(target["serviceId"], task, draft, definition)
                if draft["action"] != "APPLY_ONLY":
                    self.task_event(task, "PUBLISHING", "正在核对版本并发布批准文件")
                    atomic_write(self.state / "backups" / (task_id + ".original"), current)
                    with self.lock:
                        latest, _ = self.read_file(definition)
                        if digest(latest) != draft["baseVersion"]:
                            raise Rejected("发布前文件版本冲突，未覆盖其他修改", 409)
                        atomic_write(self.path(definition), draft["content"].encode("utf-8"), preserve_permissions=True)
                        task["filePublished"] = True
                        self.task_event(task, "PUBLISHING", "批准文件已原子发布；尚未宣称业务生效")
                if draft["action"] == "PUBLISH_ONLY":
                    self.task_event(task, "PUBLISHED_PENDING_APPLY", "文件已发布，未主动重启或重载；需另行审批应用")
                    return
                for target in task["targets"]:
                    latest, _ = self.read_file(definition)
                    if digest(latest) != draft["targetVersion"]:
                        raise Rejected("文件已不再是批准版本，停止后续目标操作", 409)
                    touched.append(target)
                    self.task_event(task, "RESTARTING", "正在应用目标：" + target["serviceId"])
                    self.runner.apply(target, task, draft, definition)
                if all(target["businessVerified"] for target in task["targets"]):
                    self.task_event(task, "VERIFIED", "批准目标已加载配置，实例及登记的业务检查通过")
                else:
                    task["awaitingVerification"] = True
                    self.task_event(task, "LOADED_PENDING_VERIFICATION", "配置及实例检查通过，尚缺登记的业务验证结果")
            except Exception as failure:
                message = str(failure) if isinstance(failure, Rejected) else "执行未通过，请核对受控任务回执"
                uncertain = any(target.get("composeResultUnknown") for target in touched)
                if touched and touched[-1]["status"] != "VERIFIED":
                    touched[-1]["status"] = "RESULT_UNKNOWN" if uncertain else "FAILED"
                    touched[-1]["evidence"].append({"kind": "ERROR", "message": message, "at": now()})
                for target in task["targets"]:
                    if target["status"] == "PENDING":
                        target["status"] = "NOT_STARTED"
                restoring = (not uncertain and draft["rollbackOnFailure"] and task["filePublished"]
                             and draft["action"] != "APPLY_ONLY")
                stage = "RESULT_UNKNOWN" if uncertain else "ROLLING_BACK" if restoring else "PARTIAL_FAILURE" if any(
                    t["status"] == "VERIFIED" for t in task["targets"]) else "FAILED"
                self.task_event(task, stage, message)
                if restoring:
                    self.rollback(task, draft, definition, touched)
            finally:
                self.runner.user_authorization = None
                with self.lock:
                    latest = self.load("drafts", draft["id"])
                    latest["status"] = "EXECUTED"
                    self.save("drafts", latest)

    def rollback(self, task, draft, definition, touched):
        task["rollbackStatus"] = "RESTORING"
        task["rollbackTargets"] = [target["serviceId"] for target in touched]
        task["rollbackFileRestored"] = False
        try:
            with self.lock:
                current, _ = self.read_file(definition)
                if digest(current) != draft["targetVersion"]:
                    raise Rejected("恢复前检测到外部新版本，未覆盖新配置", 409)
                original = (self.state / "backups" / (task["id"] + ".original")).read_bytes()
                if digest(original) != draft["baseVersion"]:
                    raise Rejected("备份版本不匹配，停止恢复", 409)
                atomic_write(self.path(definition), original, preserve_permissions=True)
                task["filePublished"] = False
                task["currentFileVersion"] = draft["baseVersion"]
                task["rollbackFileRestored"] = True
                self.save("tasks", task)
            restored = {**draft, "targetVersion": draft["baseVersion"], "content": original.decode("utf-8-sig")}
            # Shared file affects every already-touched target: restore all of them, preserving original evidence.
            for target in touched:
                prior = copy.deepcopy(target)
                target.setdefault("previousAttempts", []).append(prior)
                target.update({"loaded": False, "healthy": False, "businessVerified": False,
                               "status": "STARTING", "evidence": []})
                try:
                    self.runner.apply(target, task, restored, definition)
                    if all(target.get(key) for key in ("loaded", "healthy", "businessVerified")):
                        target["status"] = "ROLLED_BACK"
                    else:
                        target["status"] = "LOADED_PENDING_VERIFICATION" if target.get("loaded") else "RESULT_UNKNOWN"
                except Exception:
                    target["businessVerified"] = False
                    target["status"] = "RESULT_UNKNOWN"
                    target["evidence"].append({"kind": "RESTORE_FAILURE", "at": now(),
                                               "message": "该目标恢复未通过，其他已触及目标继续核对"})
                self.save("tasks", task)
            self.settle_rollback(task)
        except Exception as failure:
            task["rollbackStatus"] = "FAILED"
            message = str(failure) if isinstance(failure, Rejected) else "原配置恢复未完成，需核对已留存回执"
            self.task_event(task, "PARTIAL_FAILURE", "配置恢复未全部通过：" + message)

    def settle_rollback(self, task):
        targets = [target for target in task["targets"] if target["serviceId"] in task.get("rollbackTargets", [])]
        if all(all(target.get(key) for key in ("loaded", "healthy", "businessVerified")) for target in targets):
            for target in targets:
                target["status"] = "ROLLED_BACK"
            task.update({"rollbackStatus": "RESTORED", "awaitingVerification": False})
            self.task_event(task, "ROLLED_BACK", "本次变更失败；原文件及已触及目标的加载、健康和业务恢复验证通过，原失败证据保留")
        else:
            runtime_known = all(target.get("loaded") and target.get("healthy") for target in targets)
            task.update({"rollbackStatus": "FILE_RESTORED_PENDING_VERIFICATION" if runtime_known
                         else "FILE_RESTORED_RUNTIME_UNCONFIRMED", "awaitingVerification": True})
            self.task_event(task, "PARTIAL_FAILURE", "原文件已恢复，旧版加载及健康检查通过，业务恢复尚未验证"
                            if runtime_known else "原文件已恢复，部分目标的运行及业务恢复仍待确认")


class ServiceRunner:
    def __init__(self, executor):
        self.executor = executor

    @staticmethod
    def command(command, timeout=30):
        try:
            environment = os.environ.copy()
            if IS_LINUX:
                environment["DOCKER_CONTEXT"] = "default"
                for key in ("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
                    environment.pop(key, None)
            completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                       errors="replace", timeout=timeout, shell=False, env=environment,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            if completed.returncode != 0:
                raise Rejected("固定管理命令未成功；未返回可能含敏感值的命令输出")
            return completed.stdout
        except Rejected:
            raise
        except Exception:
            raise Rejected("固定管理命令不可用或超时") from None

    def process_identity(self, pid):
        if not isinstance(pid, int) or pid <= 0:
            raise Rejected("目标进程身份未登记")
        if os.name != "nt":
            raise Rejected("本执行器仅支持已登记的 Windows Java 进程")
        script = ("[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);"
                  "$p=Get-CimInstance Win32_Process -Filter 'ProcessId=" + str(pid) + "';"
                  "if($null -eq $p){'null'}else{[pscustomobject]@{pid=$p.ProcessId;"
                  "executable=$p.ExecutablePath;commandLine=$p.CommandLine;"
                  "startedAt=$p.CreationDate.ToUniversalTime().ToString('o')}|ConvertTo-Json -Compress}")
        try:
            return json.loads(self.command(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script]))
        except Rejected:
            raise
        except Exception:
            raise Rejected("无法取得目标进程身份") from None

    @staticmethod
    def windows_arguments(command_line):
        count = ctypes.c_int()
        function = ctypes.windll.shell32.CommandLineToArgvW
        function.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_int)]
        function.restype = ctypes.POINTER(ctypes.c_wchar_p)
        values = function(command_line, ctypes.byref(count))
        if not values:
            raise Rejected("无法核对进程启动参数")
        try:
            return [values[index] for index in range(count.value)]
        finally:
            ctypes.windll.kernel32.LocalFree(values)

    def registered_process(self, service, allow_absent=False):
        try:
            receipt = read_json(service["processFile"])
        except Exception:
            raise Rejected("目标进程登记缺失，不能按端口结束进程") from None
        actual = self.process_identity(receipt.get("pid"))
        if actual is None:
            if allow_absent:
                return None
            raise Rejected("登记的目标进程已退出，请核对后重新登记")
        command = service["command"]
        if (not actual.get("executable") or not actual.get("commandLine")
                or os.path.normcase(str(Path(actual["executable"]).resolve()))
                != os.path.normcase(str(Path(command[0]).resolve()))
                or self.windows_arguments(actual["commandLine"])[1:] != command[1:]
                or receipt.get("startedAt") and receipt["startedAt"] != actual["startedAt"]):
            raise Rejected("进程身份或固定启动参数不匹配，未执行结束操作", 409)
        if not receipt.get("startedAt"):
            atomic_write(service["processFile"], canonical({"pid": actual["pid"],
                                                           "startedAt": actual["startedAt"]}).encode("utf-8"))
        return actual

    def preflight(self, service_id, task, draft, definition):
        service = self.executor.services[service_id]
        if service["kind"] == "COMPOSE_JAVA":
            actual = self.registered_container(service)
            self.configuration_mount(service, definition, actual)
            self.compose_command(service, ["config", "--quiet"])
        elif service["kind"] == "JAVA":
            self.registered_process(service)
            if not Path(service["command"][service["command"].index("-jar") + 1]).is_file():
                raise Rejected("登记的服务 JAR 不可用")
            if not service.get("configurationFiles") or str(self.executor.path(definition)).lower() not in {
                    str(Path(path).resolve()).lower() for path in service["configurationFiles"]}:
                raise Rejected("批准文件不在目标实际配置文件集合中")
        else:
            if uses_compose(service):
                self.configuration_mount(service, definition, self.registered_container(service))
            self.prometheus_check(service, draft["content"])

    def validate_configuration(self, service_id, content):
        service = self.executor.services[service_id]
        if service["kind"] == "PROMETHEUS":
            self.prometheus_check(service, content)

    def prometheus_check(self, service, content):
        container = self.registered_container(service)["id"] if uses_compose(service) else service["container"]
        name = "opsagent-validate-" + uuid.uuid4().hex + ".yml"
        host_file = self.executor.state / name
        container_file = "/tmp/" + name
        atomic_write(host_file, content.encode("utf-8"))
        docker = service["dockerExecutable"]
        try:
            self.command([docker, "cp", str(host_file), container + ":" + container_file])
            # docker cp creates a root-owned file; keep its 0600 mode and validate as root.
            # Reload and loaded/business evidence still use the running Prometheus identity.
            self.command([docker, "exec", "--user", "0", container, "promtool", "check", "config", container_file])
        finally:
            if host_file.exists():
                host_file.unlink()
            try:
                self.command([docker, "exec", "--user", "0", container, "rm", "-f", container_file])
            except Rejected:
                pass

    def fetch(self, url, signed=False, expected_json=True, authenticated=False, method="GET", body=None, timeout=4):
        self.executor.loopback_url(url)
        parsed = urllib.parse.urlsplit(url)
        for service in self.executor.services.values():
            if uses_compose(service) and parsed.hostname == service["composeService"]:
                actual = self.registered_container(service)
                url = urllib.parse.urlunsplit(("http", actual["ip"] + ":" + str(service["port"]),
                                               parsed.path, parsed.query, ""))
                break
        headers = {}
        if signed:
            stamp = str(int(time.time()))
            nonce = secrets.token_hex(16)
            path = urllib.parse.urlsplit(url).path
            signature = hmac.new(self.executor.secret.encode(),
                                 (stamp + "\n" + nonce + "\nGET\n" + path).encode(), hashlib.sha256).hexdigest()
            headers = {"X-Ops-Executor-Time": stamp, "X-Ops-Executor-Nonce": nonce,
                       "X-Ops-Executor-Signature": signature}
        if authenticated:
            authorization = getattr(self, "user_authorization", None)
            if not authorization:
                raise Rejected("业务核验需要当前有效会话；未保存登录凭据，请登录后重新核验")
            headers["Authorization"] = authorization
        data = None
        if method == "POST":
            data = canonical(body or {}).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, headers=headers, data=data, method=method)
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=timeout) as response:
            raw = response.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES or response.status != 200:
                raise Rejected("目标证据响应不符合合同")
            return json.loads(raw) if expected_json else raw.decode("utf-8")

    def apply(self, target, task, draft, definition):
        service = self.executor.services[target["serviceId"]]
        if service["kind"] == "PROMETHEUS":
            self.apply_prometheus(service, target, task, draft, definition)
            return
        if service["kind"] == "COMPOSE_JAVA":
            self.apply_compose(service, target, task, draft, definition)
            return
        actual = self.registered_process(service, allow_absent=True)
        target["beforePid"] = actual["pid"] if actual else None
        target.update({"status": "STOPPING", "loaded": False, "healthy": False, "businessVerified": False,
                       "afterPid": None, "afterStartedAt": None, "instanceId": None})
        self.executor.save("tasks", task)
        if actual:
            # Bind process stop to PID and creation time again in one PowerShell invocation.
            script = ("$p=Get-CimInstance Win32_Process -Filter 'ProcessId=" + str(actual["pid"]) + "';"
                      "if($null -eq $p -or $p.CreationDate.ToUniversalTime().ToString('o') -ne '"
                      + actual["startedAt"] + "'){throw 'PROCESS_IDENTITY_CHANGED'};Stop-Process -Id "
                      + str(actual["pid"]) + " -ErrorAction Stop")
            self.command(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script])
            deadline = time.monotonic() + service.get("stopTimeoutSeconds", 20)
            while self.process_identity(actual["pid"]) is not None:
                if time.monotonic() > deadline:
                    raise Rejected("目标进程未在期限内退出，未重复结束或启动")
                time.sleep(0.3)
        target["status"] = "STARTING"
        self.executor.save("tasks", task)
        environment = os.environ.copy()
        for key in service.get("clearEnvironmentKeys", []):
            environment.pop(key, None)
        logs = Path(service["logDirectory"])
        logs.mkdir(parents=True, exist_ok=True)
        log_id = target["serviceId"] + "-" + task["id"] + "-" + secrets.token_hex(3)
        with (logs / (log_id + ".stdout.log")).open("ab") as out, (logs / (log_id + ".stderr.log")).open("ab") as err:
            child = subprocess.Popen(service["command"], cwd=service["workingDirectory"], env=environment,
                                     stdin=subprocess.DEVNULL, stdout=out, stderr=err, shell=False,
                                     creationflags=(subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
                                     if os.name == "nt" else 0)
        identity = self.process_identity(child.pid)
        if identity is None:
            raise Rejected("目标启动后立即退出")
        atomic_write(service["processFile"], canonical({"pid": child.pid,
                                                       "startedAt": identity["startedAt"]}).encode("utf-8"))
        target["afterPid"] = child.pid
        target["afterStartedAt"] = identity["startedAt"]
        target["evidence"].append({"kind": "PROCESS", "message": "已按固定方式启动新进程",
                                   "pid": child.pid, "startedAt": identity["startedAt"], "at": now()})
        self.executor.save("tasks", task)
        deadline = time.monotonic() + service.get("startupTimeoutSeconds", 120)
        last_reason = "服务尚未就绪"
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise Rejected("新进程已退出，保留失败与进程回执")
            try:
                self.check_java_loaded(service, target, draft, definition)
                self.executor.task_event(task, "LOADED_PENDING_VERIFICATION", "配置已加载，正在验证实例与登记业务证据")
                break
            except Exception as failure:
                last_reason = str(failure) if isinstance(failure, Rejected) else "运行证据或依赖检查暂不可用"
                time.sleep(1)
        else:
            raise Rejected("启动验证超时：" + last_reason)
        # One-shot model verification is deliberately outside the readiness retry loop.
        verified = self.verify_probes(service, target, task)
        target.update({"businessVerified": verified,
                       "status": "VERIFIED" if verified else "LOADED_PENDING_VERIFICATION"})
        self.executor.save("tasks", task)

    def compose_command(self, service, arguments):
        if not IS_LINUX:
            raise Rejected("Compose 执行方式仅用于已登记的 Linux 宿主机")
        selected = service["composeService"]
        allowed = (["config", "--quiet"], ["ps", "--all", "--quiet", selected],
                   ["up", "--detach", "--no-deps", "--force-recreate", "--no-build", "--pull", "never", selected])
        if arguments not in allowed:
            raise Rejected("未登记的 Compose 管理动作")
        service_id = next(key for key, value in self.executor.services.items() if value is service)
        if self.executor.compose_control_digest(service) != self.executor.compose_controls[service_id]:
            raise Rejected("Compose 入口已变化，请核对后重启执行器并重新审批", 409)
        if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT") not in {None, "", "default"}:
            raise Rejected("宿主机 Docker 目标存在额外覆盖，未执行")
        return self.command([service["bashExecutable"], service["composeScript"], *arguments],
                            timeout=service.get("startupTimeoutSeconds", 120))

    def registered_container(self, service):
        container_id = self.compose_command(service, ["ps", "--all", "--quiet", service["composeService"]]).strip()
        if not re.fullmatch(r"[a-f0-9]{64}", container_id):
            raise Rejected("登记 Compose 服务必须恰好对应一个容器，未按名称猜测实例")
        format_text = ('{"id":{{json .Id}},"image":{{json .Image}},"state":{{json .State}},'
                       '"labels":{{json .Config.Labels}},"mounts":{{json .Mounts}},'
                       '"pidMode":{{json .HostConfig.PidMode}},"networks":{{json .NetworkSettings.Networks}}}')
        try:
            data = json.loads(self.command([service["dockerExecutable"], "inspect", "--format", format_text, container_id]))
            labels = data["labels"]
            if (data["id"] != container_id or labels.get("com.docker.compose.project") != service["composeProject"]
                    or labels.get("com.docker.compose.service") != service["composeService"]
                    or data.get("pidMode") not in {"", "private"} or not data["state"].get("Running")):
                raise Rejected("容器身份、独立进程空间或运行状态与登记不符")
            directory = Path(service["composeScript"]).parent.parent.resolve()
            compose_files = {str(Path(value).resolve()) for value in labels.get("com.docker.compose.project.config_files", "").split(",") if value}
            base_file = str(directory / "compose.yaml")
            trace_file = str(directory / "compose.observability-v3.yaml")
            if base_file not in compose_files or not compose_files <= {base_file, trace_file}:
                raise Rejected("实际容器未使用登记 Compose 文件集合")
            trace = trace_file in compose_files
            if trace:
                marker = directory / "runtime" / "observability-v3.enabled"
                if marker.is_symlink() or not marker.is_file() or marker.read_text().strip() != "opsagent-observability-v3-verified-v1":
                    raise Rejected("当前实例启用了 Trace 但持久标记不符，拒绝降级重建")
            address = ipaddress.ip_address(data["networks"][service["network"]]["IPAddress"])
            if address not in private_network(service["networkCidr"]):
                raise Rejected("容器 IP 不在登记专用私网内")
            return {"id": container_id, "image": data["image"], "startedAt": data["state"]["StartedAt"],
                    "ip": str(address), "mounts": data["mounts"], "trace": trace}
        except Rejected:
            raise
        except Exception:
            raise Rejected("无法核对登记 Compose 容器的实例证据") from None

    def configuration_mount(self, service, definition, actual):
        approved = self.executor.path(definition)
        if approved not in {Path(path).resolve() for path in service["configurationFiles"]}:
            raise Rejected("批准文件不在该 Compose 目标的纳管文件集合")
        candidates = []
        for mount in actual["mounts"]:
            if mount.get("Type") != "bind":
                continue
            source = Path(mount.get("Source", "")).resolve()
            destination = Path(mount.get("Destination", ""))
            if source == approved:
                if service["kind"] == "PROMETHEUS":
                    raise Rejected("Prometheus 必须通过目录挂载配置；单文件挂载无法读取原子发布后的新文件")
                candidates.append(destination)
            elif source.is_dir() and approved.is_relative_to(source):
                candidates.append(destination / approved.relative_to(source))
        if len(candidates) != 1 or candidates[0].name != approved.name:
            raise Rejected("容器未唯一挂载批准文件，或挂载文件名与运行证据不一致")

    @staticmethod
    def same_container(target, actual):
        if (actual["id"] != target.get("afterContainerId")
                or actual["startedAt"] != target.get("afterStartedAt")
                or actual["image"] != target.get("containerImage")
                or actual["trace"] != target.get("traceEnabled")):
            raise Rejected("容器已不是本次配置任务创建的实例，不能借用其他实例证据")

    def apply_compose(self, service, target, task, draft, definition):
        before = self.registered_container(service)
        self.configuration_mount(service, definition, before)
        target.update({"status": "STARTING", "loaded": False, "healthy": False, "businessVerified": False,
                       "beforeContainerId": before["id"], "afterContainerId": None, "afterPid": 1,
                       "afterStartedAt": None, "instanceId": None, "containerImage": before["image"],
                       "traceEnabled": before["trace"]})
        self.executor.save("tasks", task)
        # No stop/rm fallback: a timeout stays uncertain and is never followed by a second recreate.
        target["composeResultUnknown"] = True
        self.executor.save("tasks", task)
        try:
            self.compose_command(service, ["up", "--detach", "--no-deps", "--force-recreate", "--no-build",
                                           "--pull", "never", service["composeService"]])
            actual = self.registered_container(service)
            if actual["id"] == before["id"] or actual["image"] != before["image"] or actual["trace"] != before["trace"]:
                raise Rejected("重建未取得相同镜像与 Trace 设置的新容器")
            self.configuration_mount(service, definition, actual)
        except Exception:
            raise Rejected("Compose 重建结果尚未确认；已保留任务，未再次重建或自动回滚，请核对容器后重新审批") from None
        target["composeResultUnknown"] = False
        target.update({"afterContainerId": actual["id"], "afterStartedAt": actual["startedAt"]})
        target["evidence"].append({"kind": "CONTAINER", "containerId": actual["id"],
                                   "startedAt": actual["startedAt"], "traceEnabled": actual["trace"],
                                   "message": "固定 Compose 服务已重建，镜像与 Trace 设置保持一致", "at": now()})
        self.executor.save("tasks", task)
        deadline = time.monotonic() + service.get("startupTimeoutSeconds", 120)
        last_reason = "新容器尚未就绪"
        while time.monotonic() < deadline:
            try:
                self.same_container(target, self.registered_container(service))
                self.check_java_loaded(service, target, draft, definition)
                self.executor.task_event(task, "LOADED_PENDING_VERIFICATION", "新容器已证明加载批准配置，正在核对登记业务证据")
                break
            except Exception as failure:
                last_reason = str(failure) if isinstance(failure, Rejected) else "容器运行证据暂不可用"
                time.sleep(1)
        else:
            raise Rejected("容器启动或配置验证超时：" + last_reason)
        verified = self.verify_probes(service, target, task)
        self.same_container(target, self.registered_container(service))
        target.update({"businessVerified": verified, "status": "VERIFIED" if verified else "LOADED_PENDING_VERIFICATION"})
        self.executor.save("tasks", task)

    def check_java_loaded(self, service, target, draft, definition):
        container = None
        if service["kind"] == "COMPOSE_JAVA":
            container = self.registered_container(service)
            self.same_container(target, container)
        snapshot = self.fetch(service["snapshotUrl"], signed=True)
        data = snapshot.get("data", snapshot)
        if data.get("serviceId") != target["serviceId"] or data.get("pid") != target.get("afterPid"):
            raise Rejected("运行证据不是本次启动的服务实例")
        if not data.get("instanceId") or (target.get("instanceId") and data["instanceId"] != target["instanceId"]):
            raise Rejected("运行实例标识已变化，不能复用旧实例证据")
        if container:
            self.same_container(target, self.registered_container(service))
            started = datetime.fromisoformat(data.get("startedAt", "").replace("Z", "+00:00"))
            container_started = datetime.fromisoformat(container["startedAt"].replace("Z", "+00:00"))
            if started < container_started or started > datetime.now(timezone.utc):
                raise Rejected("Java 启动证据不是本次 Compose 容器内的新进程")
        expected = self.executor.path(definition).name
        versions = data.get("configurationFiles", [])
        matching = [item for item in versions if item.get("fileName") == expected
                    and item.get("loadedVersion") == draft["targetVersion"] and item.get("sourcePresent")]
        if len(matching) != 1:
            raise Rejected("新实例尚未证明已加载批准的文件版本")
        fields = {item["key"]: item for item in data.get("fields", [])}
        document = parse_document(draft["content"], definition.get("format", "yaml"))
        for specification in definition["fields"]:
            key = specification["key"]
            field = fields.get(key)
            if field is None:
                raise Rejected("缺少纳管参数的实际加载证据：" + key)
            if field.get("overridden"):
                raise Rejected("纳管参数仍被环境变量或命令行覆盖：" + key)
            expected_text = evidence_text(lookup(document, key))
            expected_digest = hmac.new(self.executor.secret.encode(), expected_text.encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(field.get("valueDigest", ""), expected_digest):
                raise Rejected("实际运行参数与批准内容不一致：" + key)
        target.update({"loaded": True, "status": "LOADED_PENDING_VERIFICATION", "instanceId": data["instanceId"]})
        target["evidence"].append({"kind": "CONFIGURATION", "message": "新进程加载批准文件且纳管参数无旧覆盖",
                                   "version": draft["targetVersion"], "instanceId": data.get("instanceId"), "at": now()})
        health = self.fetch(service["healthUrl"])
        if health.get("status") != "UP":
            raise Rejected("实例健康检查未通过")
        target["healthy"] = True

    def verify_existing(self, target, task, draft, definition):
        service = self.executor.services[target["serviceId"]]
        target.update({"loaded": False, "healthy": False, "businessVerified": False,
                       "status": "LOADED_PENDING_VERIFICATION"})
        if service["kind"] == "COMPOSE_JAVA":
            self.same_container(target, self.registered_container(service))
            self.check_java_loaded(service, target, draft, definition)
        elif service["kind"] == "JAVA":
            actual = self.registered_process(service)
            if actual["pid"] != target.get("afterPid") or actual.get("startedAt") != target.get("afterStartedAt"):
                raise Rejected("当前进程已不是该任务启动的实例，需按当前版本重新审批")
            self.check_java_loaded(service, target, draft, definition)
        else:
            if uses_compose(service):
                self.same_container(target, self.registered_container(service))
            status = self.fetch(service["configurationUrl"])
            loaded = plain(parse_document(status["data"]["yaml"], "yaml"))
            expected = plain(parse_document(draft["content"], "yaml"))
            if any(not contains_values(lookup(loaded, spec["key"]), lookup(expected, spec["key"]))
                   for spec in definition["fields"]):
                raise Rejected("Prometheus 当前加载值与该任务批准版本不一致")
            self.fetch(service["healthUrl"], expected_json=False)
            if uses_compose(service):
                self.same_container(target, self.registered_container(service))
        verified = self.verify_probes(service, target, task)
        target.update({"loaded": True, "healthy": True, "businessVerified": verified,
                       "status": "VERIFIED" if verified else "LOADED_PENDING_VERIFICATION"})
        self.executor.save("tasks", task)

    @staticmethod
    def matches_value(actual, expected):
        if isinstance(expected, bool):
            return str(actual).lower() == str(expected).lower()
        return str(actual) == str(expected)

    def verify_probes(self, service, target, task):
        probes = service.get("probeUrls", [])
        if not probes:
            raise Rejected("未登记必要业务或依赖检查，不能判定整体通过")
        business_verified = False
        unverified_one_shot = False
        for index, specification in enumerate(probes):
            spec = {"url": specification} if isinstance(specification, str) else specification
            if spec.get("oneShot"):
                prior_evidence = [item for prior in target.get("previousAttempts", [])
                                  for item in prior.get("evidence", [])]
                previous = next((item for item in target["evidence"] + prior_evidence if item.get("probeIndex") == index
                                 and item.get("kind") == "ONE_SHOT_PROBE"), None)
                if previous:
                    same_instance = (previous.get("instancePid") == target.get("afterPid")
                                     and previous.get("instanceStartedAt") == target.get("afterStartedAt")
                                     and previous.get("instanceId") == target.get("instanceId"))
                    if previous.get("passed") and same_instance:
                        business_verified = business_verified or spec.get("kind") == "BUSINESS"
                        continue
                    if task.get("rollbackFileRestored"):
                        unverified_one_shot = True
                        target["evidence"].append({"kind": "ONE_SHOT_PENDING_REAPPROVAL", "probeIndex": index,
                                                   "instancePid": target.get("afterPid"), "at": now(),
                                                   "message": "旧实例单次模型证据不适用于恢复实例，未重复付费调用；需另行审批核验"})
                        continue
                    raise Rejected("本次单次模型验证已提交但未确认成功，未自动重复调用；需核查后另行明确审批")
                attempt = {"kind": "ONE_SHOT_PROBE", "probeIndex": index, "at": now(),
                           "passed": False, "instancePid": target.get("afterPid"),
                           "instanceStartedAt": target.get("afterStartedAt"), "instanceId": target.get("instanceId"),
                           "message": "已记录单次模型验证尝试，禁止超时自动重发"}
                target["evidence"].append(attempt)
                self.executor.save("tasks", task)
            response = self.fetch(spec["url"], signed=bool(spec.get("signed")),
                                  expected_json=spec.get("json", True), authenticated=spec.get("authenticated", False),
                                  method=spec.get("method", "GET"), body=spec.get("body"),
                                  timeout=spec.get("timeoutSeconds", 30 if spec.get("oneShot") else 4))
            value = response
            for segment in spec.get("valuePath", "").split("."):
                if segment:
                    if not isinstance(value, dict):
                        raise Rejected("登记业务检查缺少预期证据")
                    value = value.get(segment)
            if "equals" in spec and value != spec["equals"]:
                raise Rejected("登记业务检查未取得预期结果")
            if spec.get("nonEmpty") and not value:
                raise Rejected("登记业务检查尚无实际数据")
            if isinstance(response, dict) and response.get("status") in {"DOWN", "OUT_OF_SERVICE", "error"}:
                raise Rejected("登记依赖检查未通过")
            if spec.get("oneShot"):
                attempt["passed"] = True
            kind = spec.get("kind", "HEALTH")
            business_verified = business_verified or kind == "BUSINESS"
            target["evidence"].append({"kind": kind + "_PROBE", "message": spec.get("label", "登记检查") + "通过",
                                       "probeIndex": index, "at": now()})
        return business_verified and not unverified_one_shot

    def apply_prometheus(self, service, target, task, draft, definition):
        target.update({"status": "STARTING", "loaded": False, "healthy": False, "businessVerified": False})
        self.executor.save("tasks", task)
        actual = self.registered_container(service) if uses_compose(service) else None
        if actual:
            self.configuration_mount(service, definition, actual)
            target.update({"afterContainerId": actual["id"], "afterStartedAt": actual["startedAt"],
                           "containerImage": actual["image"], "traceEnabled": actual["trace"]})
        self.prometheus_check(service, draft["content"])
        if actual:
            self.same_container(target, self.registered_container(service))
        if actual:
            target["composeResultUnknown"] = True
            self.executor.save("tasks", task)
        self.command([service["dockerExecutable"], "kill", "--signal", "HUP", actual["id"] if actual else service["container"]])
        if actual:
            target["composeResultUnknown"] = False
        deadline = time.monotonic() + service.get("startupTimeoutSeconds", 30)
        expected = plain(parse_document(draft["content"], "yaml"))
        while time.monotonic() < deadline:
            try:
                status = self.fetch(service["configurationUrl"])
                loaded = plain(parse_document(status["data"]["yaml"], "yaml"))
                # Prometheus adds defaults; compare managed values rather than emitted formatting.
                for specification in definition["fields"]:
                    key = specification["key"]
                    if not contains_values(lookup(loaded, key), lookup(expected, key)):
                        raise Rejected("Prometheus 运行配置尚未匹配批准参数")
                self.fetch(service["healthUrl"], expected_json=False)
                if actual:
                    self.same_container(target, self.registered_container(service))
                target.update({"loaded": True, "healthy": True})
                target["evidence"].append({"kind": "CONFIGURATION", "message": "Prometheus 已重载批准参数并完成目标查询",
                                           "version": draft["targetVersion"], "at": now()})
                break
            except Exception:
                time.sleep(1)
        else:
            raise Rejected("Prometheus 已请求重载，但实际加载或检查未通过")
        verified = self.verify_probes(service, target, task)
        target.update({"businessVerified": verified,
                       "status": "VERIFIED" if verified else "LOADED_PENDING_VERIFICATION"})
        self.executor.save("tasks", task)


def contains_values(actual, expected):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(key in actual and contains_values(actual[key], value)
                                                for key, value in expected.items())
    if isinstance(expected, list):
        return (isinstance(actual, list) and len(actual) == len(expected)
                and all(contains_values(left, right) for left, right in zip(actual, expected)))
    return actual == expected


def evidence_text(value):
    if isinstance(value, (dict, list)):
        values = {}

        def visit(item, prefix):
            if isinstance(item, dict):
                for key, child in item.items():
                    visit(child, prefix + "." + key if prefix else key)
            elif isinstance(item, list):
                for index, child in enumerate(item):
                    visit(child, prefix + "[" + str(index) + "]")
            else:
                values[prefix] = "true" if item is True else "false" if item is False else str(item)
        visit(value, "")
        return canonical(values)
    return "true" if value is True else "false" if value is False else str(value)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise Rejected("证据端点不允许跳转到其他地址")


def handler(executor):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format_string, *args):
            pass  # Request bodies, authorization and private paths never enter access logs.

        def do_GET(self):
            self.dispatch("GET")

        def do_POST(self):
            self.dispatch("POST")

        def dispatch(self, method):
            try:
                if not executor.accepts_client(self.client_address[0]):
                    raise Rejected("执行器仅接受登记私网及本机调用", 403)
                if not hmac.compare_digest(self.headers.get("X-Ops-Executor-Token", ""), executor.secret):
                    raise Rejected("执行器身份验证失败", 403)
                actor = int(self.headers.get("X-Ops-Actor-Id", "0"))
                role = self.headers.get("X-Ops-Actor-Role", "")
                visitor = role == "DEMO"
                visitor_read = method == "GET" and bool(re.fullmatch(r"/files(?:/(?!drafts(?:/|$)|tasks(?:/|$))[A-Za-z0-9][A-Za-z0-9_.-]{0,95}(?:/history)?)?", self.path))
                if (actor == 0 or actor < 0 and not visitor or role not in {"ADMIN", "OPS", "DEMO"}
                        or visitor and not visitor_read or method == "POST" and role != "ADMIN"):
                    raise Rejected("当前身份不允许执行该配置动作", 403)
                if self.headers.get("Origin") or self.headers.get("Transfer-Encoding"):
                    raise Rejected("执行器不接受浏览器直连或分块请求", 403)
                body = {}
                if method == "POST":
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= MAX_BYTES:
                        raise Rejected("请求大小不合法")
                    body = json.loads(self.rfile.read(length))
                    if not isinstance(body, dict):
                        raise Rejected("请求必须为对象")
                pieces = self.path.strip("/").split("/")
                if any(not IDENTIFIER.fullmatch(piece) for piece in pieces):
                    raise Rejected("不支持该请求路径", 404)
                if pieces == ["files"] and method == "GET":
                    result = executor.visitor_catalog() if visitor else executor.catalog()
                elif len(pieces) == 2 and pieces[0] == "files" and method == "GET":
                    result = executor.visitor_detail(pieces[1]) if visitor else executor.detail(pieces[1])
                elif len(pieces) == 3 and pieces[0] == "files" and pieces[2] == "history" and method == "GET":
                    result = executor.visitor_history(pieces[1]) if visitor else executor.history(pieces[1])
                elif len(pieces) == 3 and pieces[0] == "files" and pieces[2] == "drafts" and method == "POST":
                    result = executor.create_draft(pieces[1], body, actor)
                elif len(pieces) == 2 and pieces[0] == "drafts" and method == "GET":
                    result = executor.public_draft(executor.load("drafts", pieces[1]))
                elif len(pieces) == 3 and pieces[0] == "drafts" and method == "POST":
                    authorization = self.headers.get("X-Ops-User-Authorization", "")
                    if not authorization.startswith("Bearer ") or len(authorization) > 16384:
                        authorization = None
                    result = executor.transition(pieces[1], pieces[2], body, actor, authorization)
                elif len(pieces) == 2 and pieces[0] == "tasks" and method == "GET":
                    result = executor.load("tasks", pieces[1])
                elif len(pieces) == 3 and pieces[0] == "tasks" and pieces[2] == "verify" and method == "POST":
                    authorization = self.headers.get("X-Ops-User-Authorization", "")
                    if not authorization.startswith("Bearer ") or len(authorization) > 16384:
                        authorization = None
                    result = executor.verify_task(pieces[1], body, actor, authorization)
                else:
                    raise Rejected("接口不存在", 404)
                self.respond(200, {"code": 0, "message": "成功", "data": result})
            except Rejected as failure:
                message = "配置只读请求未完成，请核对权限或稍后重试" if self.headers.get("X-Ops-Actor-Role") == "DEMO" else str(failure)
                self.respond(failure.status, {"code": failure.status * 100, "message": message, "data": None})
            except (ValueError, TypeError):
                self.respond(400, {"code": 40000, "message": "请求格式不正确", "data": None})
            except Exception:
                self.respond(500, {"code": 50000, "message": "执行器请求失败，未返回敏感内部内容", "data": None})

        def respond(self, status, body):
            data = canonical(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", required=True)
    arguments = parser.parse_args()
    registry = read_json(arguments.registry)
    executor = Executor(registry)
    server = ThreadingHTTPServer((executor.listen_host, int(registry.get("port", 18110))), handler(executor))
    server.serve_forever(poll_interval=0.3)


if __name__ == "__main__":
    main()
