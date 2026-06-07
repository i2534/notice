#!/usr/bin/env python3
"""
OpenCode Client — 接入 Notice 消息系统

通过 MQTT 接收消息，调用本地 OpenCode serve 处理，回复到 MQTT。

使用方式:
    python opencode.py --config config.yaml
"""

import argparse
import asyncio
import base64
import gzip
import json
import logging
import random
import re
import signal
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlparse

try:
    import paho.mqtt.client as mqtt
    from paho.mqtt.enums import CallbackAPIVersion
except ImportError:
    raise ImportError("paho-mqtt is required. Install with: pip install paho-mqtt")

try:
    import httpx
except ImportError:
    raise ImportError("httpx is required. Install with: pip install httpx")

try:
    import yaml
except ImportError:
    raise ImportError("pyyaml is required. Install with: pip install pyyaml")


# ── 配置 ──

@dataclass
class MqttConfig:
    broker_url: str
    token: str
    topic: str


@dataclass
class OpenCodeConfig:
    server_url: str
    username: str = "opencode"
    password: str = ""
    project_dir: str = "."
    system_prompt: str = ""
    model: str = ""


# ── 工具函数 ──

def decode_content(content: str, encoding: Optional[str]) -> str:
    if encoding == "gzip+base64" and content:
        try:
            decoded = base64.b64decode(content)
            return gzip.decompress(decoded).decode("utf-8")
        except Exception:
            pass
    return content


def encode_content(content: str) -> tuple[str, Optional[str]]:
    if len(content) >= 256:
        try:
            compressed = gzip.compress(content.encode("utf-8"))
            encoded = base64.b64encode(compressed).decode("ascii")
            if len(encoded) < len(content):
                return encoded, "gzip+base64"
        except Exception:
            pass
    return content, None


# ── 客户端 ──

class OpenCodeClient:
    CLIENT_NAME = "opencode"
    ROLE_ASSISTANT = "assistant"
    ROLE_SYSTEM = "system"
    STABLE_THRESHOLD = 5  # 连续 5 次轮询无新内容判定完成（10 秒）

    def __init__(self, config_path: str):
        self.logger = logging.getLogger("opencode")
        self.config = self._load_config(config_path)

        # MQTT 配置
        mqtt_cfg = self.config.get("mqtt", {})
        self.mqtt = MqttConfig(
            broker_url=mqtt_cfg.get("broker_url", ""),
            token=mqtt_cfg.get("token", ""),
            topic=mqtt_cfg.get("topic", "notice/opencode"),
        )

        # OpenCode 配置
        oc_cfg = self.config.get("opencode", {})
        self.oc = OpenCodeConfig(
            server_url=oc_cfg.get("server_url", "http://localhost:4096"),
            username=oc_cfg.get("username", "opencode"),
            password=oc_cfg.get("password", ""),
            project_dir=oc_cfg.get("project_dir", "."),
            system_prompt=oc_cfg.get("system_prompt", ""),
            model=oc_cfg.get("model", ""),
        )

        # 解析 project_dir 为绝对路径（相对于配置文件所在目录）
        config_dir = Path(config_path).parent
        project_resolved = (config_dir / self.oc.project_dir).absolute()
        self.oc.project_dir = str(project_resolved)

        # MQTT 客户端状态
        self.client_id = f"opencode-{int(time.time() * 1000)}-{random.randint(1000, 9999)}"
        self._mqtt_client: Optional[mqtt.Client] = None
        self._connected = False
        self._connect_event = asyncio.Event()
        self._loop: Optional[asyncio.AbstractEventLoop] = None

        # OpenCode session
        self._http: Optional[httpx.AsyncClient] = None
        self._session_id: Optional[str] = None
        self._active_dir: str = str(project_resolved)

        # 串行处理锁
        self._processing_lock = asyncio.Lock()

        # 运行状态
        self._running = False

        # 命令注册表 (name, handler, help_text, subcommands)
        self._commands: dict[str, tuple] = {
            "/dir":      (self._cmd_dir,      "查询当前目录 / 切换工作目录", []),
            "/help":     (self._cmd_help,     "显示此帮助信息", []),
            "/list":     (self._cmd_list,     "列出当前目录的 session", []),
            "/model":    (self._cmd_model,    "查看/切换模型", [
                ("list", "列出可用模型"),
                ("set <model>", "切换模型（provider/model 格式）"),
            ]),
            "/new":      (self._cmd_new,      "创建新 session", []),
            "/switch":   (self._cmd_switch,   "切换 session（前缀匹配）", []),
        }

    def _build_help_text(self) -> str:
        lines = ["## 可用指令\n"]
        lines.append("| 指令 | 说明 |")
        lines.append("|------|------|")
        for name, (_, help_text, subcmds) in sorted(self._commands.items()):
            if subcmds:
                for sub, sub_help in subcmds:
                    lines.append(f"| `{name} {sub}` | {sub_help} |")
            else:
                lines.append(f"| `{name}` | {help_text} |")
        return "\n".join(lines)

    def _load_config(self, config_path: str) -> dict:
        # .local 文件覆盖 base 配置（与 XiaoAi 一致）
        path = Path(config_path)
        local_path = Path(str(path) + ".local")

        if local_path.exists():
            with open(local_path, "r", encoding="utf-8") as f:
                cfg = yaml.safe_load(f) or {}
            if path.exists():
                with open(path, "r", encoding="utf-8") as f:
                    base_cfg = yaml.safe_load(f) or {}
                return self._deep_merge(base_cfg, cfg)
            return cfg

        if not path.exists():
            raise FileNotFoundError(f"Config file not found: {config_path}")

        with open(path, "r", encoding="utf-8") as f:
            return yaml.safe_load(f) or {}

    @staticmethod
    def _deep_merge(base: dict, override: dict) -> dict:
        result = base.copy()
        for key, value in override.items():
            if key in result and isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = OpenCodeClient._deep_merge(result[key], value)
            else:
                result[key] = value
        return result

    # ── 连接 ──

    async def connect(self) -> bool:
        if not await self._check_opencode_health():
            self.logger.error("OpenCode serve is not healthy")
            return False

        if not await self._connect_mqtt():
            return False

        if not await self._ensure_session():
            self.logger.error("Failed to ensure OpenCode session")
            return False

        return True

    async def disconnect(self) -> None:
        self._running = False

        if self._http:
            await self._http.aclose()
            self._http = None

        if self._mqtt_client and hasattr(self._mqtt_client, 'loop_stop'):
            try:
                self._mqtt_client.unsubscribe(self.mqtt.topic)
            except Exception:
                pass
            self._mqtt_client.loop_stop()
            try:
                self._mqtt_client.disconnect()
            except Exception:
                pass
            self._connected = False
            self._mqtt_client = None

    # ── MQTT ──

    async def _connect_mqtt(self) -> bool:
        self.logger.info("Connecting to MQTT broker: %s", self.mqtt.broker_url)
        if not self.mqtt.broker_url:
            self.logger.error("No broker URL configured")
            return False

        parsed = urlparse(self.mqtt.broker_url)
        host = parsed.hostname or "localhost"
        port = parsed.port
        path = parsed.path or "/"
        is_ws = parsed.scheme.startswith("ws")

        if not port:
            port = 443 if parsed.scheme in ("wss", "tls") else (80 if parsed.scheme == "ws" else 1883)

        transport = "websockets" if is_ws else "tcp"

        try:
            self._mqtt_client = mqtt.Client(
                callback_api_version=CallbackAPIVersion.VERSION2,
                client_id=self.client_id,
                userdata=self,
                transport=transport,
            )
            self._mqtt_client.reconnect_delay_set(min_delay=1, max_delay=120)
            self._mqtt_client.username_pw_set(self.mqtt.token, self.mqtt.token)

            if parsed.scheme in ("wss", "tls"):
                self._mqtt_client.tls_set()
                self._mqtt_client.tls_insecure_set(False)
            if is_ws:
                self._mqtt_client.ws_set_options(path=path)

            self._mqtt_client.on_connect = self._on_connect
            self._mqtt_client.on_disconnect = self._on_disconnect
            self._mqtt_client.on_message = self._on_message

            loop = asyncio.get_running_loop()
            self._loop = loop
            await loop.run_in_executor(None, self._mqtt_client.connect, host, port, 60)
            self._mqtt_client.loop_start()

            self._connect_event.clear()
            try:
                await asyncio.wait_for(self._connect_event.wait(), timeout=10.0)
            except asyncio.TimeoutError:
                self.logger.error("MQTT connection timeout")
                return False

            return self._connected

        except Exception as e:
            self.logger.error("Failed to connect to MQTT: %s", e)
            return False

    def _on_connect(self, client, userdata, flags, rc, properties):
        if rc == 0:
            self._connected = True
            self._connect_event.set()
            self.logger.info("MQTT connected, subscribing to %s", self.mqtt.topic)
            client.subscribe(self.mqtt.topic, qos=1)
           # MQTT 重连后验证 session 有效性
            if self._session_id and self._loop:
                asyncio.run_coroutine_threadsafe(
                    self._validate_and_recreate_session(),
                    self._loop,
                )
        else:
            self._connected = False
            self.logger.error("MQTT connection failed with code %d", rc)

    def _on_disconnect(self, client, userdata, disconnect_flags, rc, properties):
        self._connected = False
        self.logger.info("MQTT disconnected")

    def _on_message(self, client, userdata, msg):
        try:
            payload_str = msg.payload.decode("utf-8")
            self.logger.debug("<<< RX topic=%s payload=%s", msg.topic, payload_str[:500])
            try:
                message_data = json.loads(payload_str)
            except json.JSONDecodeError:
                message_data = {
                    "content": payload_str,
                    "title": "",
                    "timestamp": int(time.time() * 1000),
                    "client": "unknown",
                }

            content = message_data.get("content", "")
            content_encoding = message_data.get("content_encoding")
            content = decode_content(content, content_encoding)
            message_data["content"] = content
            self.logger.info("<<< RX client=%s content=%s", message_data.get("client"), content[:200])

            if self._loop:
                future = asyncio.run_coroutine_threadsafe(
                    self._handle_message(msg.topic, message_data), self._loop
                )
                future.add_done_callback(
                    lambda f: self.logger.error("Message handler failed: %s", f.exception())
                    if f.exception() else None
                )

        except Exception as e:
            self.logger.error("Error processing message: %s", e)

    # ── 消息处理 ──

    async def _handle_message(self, topic: str, payload: dict) -> None:
        sender = payload.get("client")
        if sender == self.CLIENT_NAME:
            self.logger.debug("Ignoring own message from client=%s", sender)
            return

        content = payload.get("content", "").strip()
        if not content:
            self.logger.debug("Empty message, skipping")
            return

        self.logger.info("Processing message: content=%s", content[:200])

        async with self._processing_lock:
            if content.startswith("/"):
                self.logger.info("Executing command: %s", content[:100])
                await self._handle_command(content)
            else:
                self.logger.info("Calling OpenCode with: %s", content[:200])
                await self._publish_typing()
                try:
                    result = await self._call_opencode(content)
                    self.logger.info("OpenCode response: %s", (result or "<none>")[:200])
                except Exception as e:
                    self.logger.error("OpenCode call failed: %s", e)
                    await self._publish_error(str(e))

    async def _handle_command(self, command: str) -> None:
        parts = command.strip().split(maxsplit=1)
        cmd = parts[0].lower()
        arg = parts[1] if len(parts) > 1 else None

        try:
            handler, _, _ = self._commands.get(cmd, (None, None, None))
            if handler is None:
                await self._publish_error(f"未知指令: {cmd}，发送 /help 查看可用指令")
                return
            await handler(arg)
        except Exception as e:
            self.logger.error("Command failed: %s", e)
            await self._publish_error(f"指令执行失败: {e}")

    async def _cmd_help(self, _arg: Optional[str]) -> None:
        await self._publish(self._build_help_text())

    async def _cmd_dir(self, arg: Optional[str]) -> None:
        if arg:
            path = str(Path(arg.strip()).expanduser())
            if not Path(path).is_dir():
                await self._publish_error(f"目录不存在: {path}")
                return
            sessions = await self._query_sessions(directory=path, limit=1)
            if not sessions:
                await self._publish_error(f"目录 `{path}` 下无 session")
                return
            self._active_dir = path
            sid = str(sessions[0]["id"])
            self._session_id = sid
            await self._publish(f"## ✓ 已切换\n\n- **目录**: `{path}`\n- **Session**: `{sid[:12]}...`")
        else:
            await self._publish(f"## 当前目录\n\n- **目录**: `{self._active_dir}`\n- **Session**: `{self._session_id[:12] if self._session_id else 'N/A'}...`")

    async def _cmd_new(self, _arg: Optional[str]) -> None:
        session = await self._create_session_in_dir(self._active_dir)
        if session:
            sid = str(session["id"])
            self._session_id = sid
            await self._publish(f"## ✓ 新 Session\n\n- **ID**: `{sid[:12]}...`")
        else:
            await self._publish_error("创建 session 失败")

    @staticmethod
    def _is_subagent_session(s: dict) -> bool:
        """判断是否为子代理会话（非用户直接创建的会话）
        
        子代理名称来源于 OpenCode 内置 agent 列表，需随 OpenCode 更新维护。
        """
        SUBAGENT_NAMES = frozenset({
            "Sisyphus-Junior", "explore", "librarian", "oracle",
            "metis", "momus", "plan", "multimodal-looker",
        })
        agent = (s.get("agent") or "").strip("\u200b")
        if any(name in agent for name in SUBAGENT_NAMES):
            return True
        title = (s.get("title") or "").replace("\u200b", "")
        if re.search(r'\(@\s*.*? subagent\)', title):
            return True
        if title.startswith("look_at:"):
            return True
        return False

    async def _cmd_list(self, _arg: Optional[str]) -> None:
        sessions = await self._query_sessions(directory=self._active_dir)
        sessions = [s for s in sessions if not OpenCodeClient._is_subagent_session(s)]
        if not sessions:
            await self._publish(f"## 无 Session\n\n目录 `{self._active_dir}` 下无 session")
            return
        lines = [f"## Session 列表\n\n目录: `{self._active_dir}`\n"]
        lines.append("| ID | 标题 | 状态 |")
        lines.append("|------|------|------|")
        for s in sessions:
            marker = "← 当前" if s["id"] == self._session_id else ""
            lines.append(f"| `{s['id'][:12]}...` | {s.get('title', 'N/A')} | {marker} |")
        await self._publish("\n".join(lines))

    async def _cmd_switch(self, arg: Optional[str]) -> None:
        if not arg:
            await self._publish_error("/switch 需要 session ID")
            return
        prefix = arg.strip()
        sessions = [s for s in await self._query_sessions(directory=self._active_dir) if not OpenCodeClient._is_subagent_session(s)]
        matches = [s for s in sessions if s["id"].startswith(prefix)]
        if len(matches) == 0:
            await self._publish_error(f"未找到匹配 '{prefix}' 的 session")
        elif len(matches) > 1:
            await self._publish_error(f"多个 session 匹配 '{prefix}'，请使用更精确的 ID")
        else:
            sid = str(matches[0]["id"])
            self._session_id = sid
            await self._publish(f"## ✓ 已切换\n\n- **Session**: `{sid[:12]}...`")

    async def _cmd_model(self, arg: Optional[str]) -> None:
        if arg == "list":
            models = await self._list_models()
            if not models:
                await self._publish_error("无法获取模型列表")
                return
            lines = [f"## 可用模型 ({len(models)})\n"]
            lines.append("| 模型 |")
            lines.append("|------|")
            for m in models:
                lines.append(f"| `{m}` |")
            await self._publish("\n".join(lines))
        elif arg:
            model_str = arg.strip()
            if "/" not in model_str:
                await self._publish_error(f"模型格式错误: {model_str}，请使用 provider/model 格式（如 deepseek/deepseek-chat）")
                return
            self.oc.model = model_str
            await self._publish(f"## ✓ 模型已切换\n\n- **模型**: `{model_str}`\n- **生效**: 下次发送消息时")
        else:
            if not self._session_id:
                await self._publish_error("没有活跃的 session")
                return
            model = await self._get_session_model(self._session_id)
            if model:
                await self._publish(f"## 当前模型\n\n- **模型**: `{model}`")
            else:
                await self._publish_error("无法获取模型信息")

    async def _get_session_model(self, session_id: str) -> Optional[str]:
        if not self._http:
            return None
        try:
            url = f"{self.oc.server_url}/session/{session_id}"
            resp = await self._get_http().get(url, params={"directory": self._active_dir}, timeout=10)
            resp.raise_for_status()
            session = resp.json()
            model = session.get("model", {})
            if model:
                return f"{model.get('providerID', '?')}/{model.get('id', '?')}"
            return None
        except Exception as e:
            self.logger.error("Get session model failed: %s", e)
            return None

    async def _list_models(self) -> list[str]:
        try:
            result = await asyncio.create_subprocess_exec(
                "opencode", "models",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await result.communicate()
            if result.returncode == 0:
                lines = stdout.decode().strip().split("\n")
                return [line.strip() for line in lines if line.strip()]
            self.logger.error("List models failed (exit %d): %s", result.returncode, stderr.decode())
            return []
        except FileNotFoundError:
            self.logger.error("'opencode' CLI not found in PATH")
            return []
        except Exception as e:
            self.logger.error("List models failed: %s", e)
            return []

    def _parse_model(self, model_str: str) -> Optional[dict]:
        if not model_str or "/" not in model_str:
            return None
        provider, model_id = model_str.split("/", 1)
        return {"modelID": model_id, "providerID": provider}

    async def _call_opencode(self, content: str) -> str:
        if not self._http or not self._session_id:
            raise RuntimeError("OpenCode not connected")

        parts = [{"type": "text", "text": content}]
        body: dict[str, Any] = {"parts": parts}
        if self.oc.system_prompt:
            body["system"] = self.oc.system_prompt
        if self.oc.model:
            model_obj = self._parse_model(self.oc.model)
            if model_obj:
                body["model"] = model_obj

        url = f"{self.oc.server_url}/session/{self._session_id}/prompt_async"
        self.logger.debug("Sending to OpenCode: %s", url)

        baseline_ids = await self._get_baseline_ids(self._session_id)

        try:
            resp = await self._http.post(
                url,
                params={"directory": self._active_dir},
                json=body,
                timeout=30,
            )
            resp.raise_for_status()
        except httpx.TimeoutException:
            raise RuntimeError("OpenCode API timeout")
        except httpx.HTTPStatusError as e:
            raise RuntimeError(f"OpenCode API error: {e.response.status_code} {e.response.text}")

        response = await self._poll_response(self._session_id, baseline_ids=baseline_ids)
        if response is None:
            raise RuntimeError("OpenCode response timeout (120s)")

        return response

    async def _get_baseline_ids(self, session_id: str) -> set:
        if not self._http:
            return set()
        try:
            url = f"{self.oc.server_url}/session/{session_id}/message"
            resp = await self._get_http().get(
                url,
                params={"limit": 30, "directory": self._active_dir},
                timeout=10,
            )
            resp.raise_for_status()
            return {
                m.get("info", {}).get("id")
                for m in resp.json()
                if m.get("info", {}).get("role", "") in (self.ROLE_ASSISTANT, self.ROLE_SYSTEM)
                and m.get("info", {}).get("id")
            }
        except Exception:
            return set()

    async def _poll_response(self, session_id: str, timeout: int = 120, baseline_ids: set | None = None) -> Optional[str]:
        if not self._http:
            return None
        if baseline_ids is None:
            baseline_ids = set()

        start = time.monotonic()
        poll_interval = 2
        last_length = 0
        stable_count = 0
        messages = []
        poll_count = 0
        self.logger.info("Polling response, baseline_ids=%d", len(baseline_ids))

        while time.monotonic() - start < timeout:
            poll_count += 1
            try:
                url = f"{self.oc.server_url}/session/{session_id}/message"
                resp = await self._get_http().get(
                    url,
                    params={"limit": 30, "directory": self._active_dir},
                    timeout=10,
                )
                resp.raise_for_status()
                messages = resp.json()

                new_text = self._extract_new_text(messages, baseline_ids)
                self.logger.debug("Poll #%d: %d messages, new_text=%d chars", poll_count, len(messages), len(new_text))
                if len(new_text) > last_length:
                    last_length = len(new_text)
                    stable_count = 0
                    self.logger.info("Poll #%d: content growing (%d chars)", poll_count, last_length)
                else:
                    stable_count += 1
                    if stable_count >= self.STABLE_THRESHOLD and new_text.strip():
                        elapsed = time.monotonic() - start
                        self.logger.info("Response stabilized after %d polls (%.0fs), publishing result", stable_count, elapsed)
                        await self._publish(new_text)
                        return new_text

            except Exception as e:
                self.logger.debug("Poll error: %s", e)

            await asyncio.sleep(poll_interval)

        final = self._extract_new_text(messages, baseline_ids) if messages else None
        self.logger.info("Poll loop ended (timeout), final=%d chars", len(final) if final else 0)
        if final:
            await self._publish(final)
        return final

    def _extract_new_text(self, messages: list, baseline_ids: set) -> str:
        texts = []
        for msg in messages:
            info = msg.get("info", {})
            if info.get("role", "") not in (self.ROLE_ASSISTANT, self.ROLE_SYSTEM):
                continue
            msg_id = info.get("id")
            if msg_id and msg_id in baseline_ids:
                continue
            for part in msg.get("parts", []):
                if part.get("type") == "text" and part.get("text"):
                    texts.append(part["text"])
        return "\n".join(texts)

    # ── 发布 ──

    async def _publish(self, content: str, extra: Optional[dict] = None) -> None:
        if not self._mqtt_client or not self._connected:
            self.logger.warning("Not connected, skipping publish")
            return

        encoded_content, content_encoding = encode_content(content)
        timestamp = int(time.time() * 1000)

        payload = {
            "title": "",
            "content": encoded_content,
            "client": self.CLIENT_NAME,
            "timestamp": timestamp,
            "extra": extra or {},
        }
        if content_encoding:
            payload["content_encoding"] = content_encoding

        json_payload = json.dumps(payload)
        status = (extra or {}).get("status", "message")
        self.logger.info(">>> TX status=%s content=%s", status, content[:300])
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(
                None,
                self._mqtt_client.publish,
                self.mqtt.topic,
                json_payload,
                1,
            )
            self.logger.info(">>> TX delivered (%d bytes)", len(json_payload))
        except Exception as e:
            self.logger.error("Publish failed: %s", e)

    async def _publish_typing(self) -> None:
        await self._publish("⏳ 处理中...", {"status": "typing"})

    async def _publish_error(self, message: str) -> None:
        await self._publish(f"❌ {message}", {"status": "error"})

    # ── OpenCode ──

    def _get_http(self) -> httpx.AsyncClient:
        """获取或创建 HTTP 客户端（单例）"""
        if not self._http:
            self._http = httpx.AsyncClient()
            if self.oc.password:
                self._http.auth = httpx.BasicAuth(self.oc.username, self.oc.password)
        return self._http

    async def _check_opencode_health(self) -> bool:
        try:
            async with httpx.AsyncClient() as client:
                if self.oc.password:
                    client.auth = httpx.BasicAuth(self.oc.username, self.oc.password)
                resp = await client.get(f"{self.oc.server_url}/global/health", timeout=10)
                resp.raise_for_status()
                self.logger.info("OpenCode serve is healthy: %s", resp.json())
                return True
        except Exception as e:
            self.logger.error("OpenCode serve health check failed: %s", e)
            return False

    async def _query_sessions(self, directory: str, limit: int = 50) -> list:
        try:
            url = f"{self.oc.server_url}/session"
            resp = await self._get_http().get(
                url,
                params={"directory": directory, "limit": limit},
                timeout=10,
            )
            resp.raise_for_status()
            sessions = resp.json()
            return sorted(sessions, key=lambda s: s.get("time", {}).get("created", 0), reverse=True)
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 404:
                return []
            self.logger.error("Query sessions failed: %s", e)
            return []
        except Exception as e:
            self.logger.error("Query sessions failed: %s", e)
            return []

    async def _create_session_in_dir(self, directory: str) -> Optional[dict]:
        try:
            url = f"{self.oc.server_url}/session"
            body: dict[str, Any] = {"directory": directory}
            if self.oc.model:
                model_obj = self._parse_model(self.oc.model)
                if model_obj:
                    body["model"] = model_obj
            resp = await self._get_http().post(
                url,
                json=body,
                timeout=10,
            )
            resp.raise_for_status()
            session = resp.json()
            self.logger.info("Created session %s in %s", session.get("id"), directory)
            return session
        except Exception as e:
            self.logger.error("Create session failed: %s", e)
            return None

    async def _validate_and_recreate_session(self) -> bool:
        if not self._http or not self._session_id:
            return False
        try:
            url = f"{self.oc.server_url}/session/{self._session_id}"
            resp = await self._http.get(
                url,
                params={"directory": self._active_dir},
                timeout=10,
            )
            if resp.status_code == 404:
                self.logger.warning("Session %s expired, recreating...", self._session_id)
                session = await self._create_session_in_dir(self._active_dir)
                if session:
                    self._session_id = session["id"]
                    return True
                return False
            self.logger.info("Session %s still valid", self._session_id)
            return True
        except Exception as e:
            self.logger.error("Session validation failed: %s", e)
            return False

    async def _ensure_session(self) -> bool:
        if self._session_id:
            return await self._validate_and_recreate_session()
        sessions = await self._query_sessions(directory=self._active_dir, limit=10)
        sessions = [s for s in sessions if not OpenCodeClient._is_subagent_session(s)]
        if sessions:
            sid = str(sessions[0]["id"])
            self._session_id = sid
            self.logger.info("Reusing existing session %s", sid[:12])
            return True
        return await self._create_session()

    async def _create_session(self) -> bool:
        session = await self._create_session_in_dir(self._active_dir)
        if session:
            self._session_id = session.get("id") or session.get("sessionID")
            return bool(self._session_id)
        return False

    # ── 主循环 ──

    async def run(self) -> None:
        self._running = True

        if not await self.connect():
            self.logger.error("Failed to connect")
            return

        self.logger.info("OpenCode client started")
        while self._running:
            await asyncio.sleep(1)

    def stop(self) -> None:
        self._running = False


async def main():
    parser = argparse.ArgumentParser(description="OpenCode Notice Client")
    parser.add_argument("--config", "-c", default="config.yaml", help="Path to config file")
    parser.add_argument("--log-level", "-l", default="INFO",
                        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                        help="Log level (default: INFO)")
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    client = OpenCodeClient(args.config)

    loop = asyncio.get_running_loop()

    def signal_handler():
        logging.getLogger("opencode").info("Received shutdown signal")
        client.stop()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, signal_handler)
        except NotImplementedError:
            signal.signal(sig, lambda s, f: signal_handler())

    try:
        await client.run()
    finally:
        await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
