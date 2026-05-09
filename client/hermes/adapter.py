import asyncio
import gzip
import json
import logging
import os
import random
import time
from typing import Any, Optional
from dataclasses import dataclass

try:
    import paho.mqtt.client as mqtt
    from paho.mqtt.enums import CallbackAPIVersion
except ImportError:
    raise ImportError("paho-mqtt is required. Install with: pip install paho-mqtt")

from gateway.config import Platform
from gateway.platforms.base import BasePlatformAdapter, SendResult, MessageEvent, MessageType, ProcessingOutcome
from gateway.session import SessionSource
from asr import Transcriber, CliTranscriber, _API_PROVIDERS

PLUGIN_DIR = os.path.dirname(os.path.abspath(__file__))


def _load_plugin_version() -> str:
    try:
        plugin_yaml = os.path.join(PLUGIN_DIR, "PLUGIN.yaml")
        with open(plugin_yaml, "r") as f:
            for line in f:
                if line.startswith("version:"):
                    return line.split(":", 1)[1].strip()
    except Exception:
        pass
    return "unknown"


def _extract_config(config) -> dict:
    """Extract config dict from various config formats."""
    cfg = {}
    if isinstance(config, dict):
        if "extra" in config and isinstance(config["extra"], dict):
            cfg = config["extra"]
        else:
            cfg = config
    elif hasattr(config, "extra"):
        if isinstance(config.extra, dict):
            cfg = dict(config.extra)
        else:
            cfg = {}
        if hasattr(config, "token") and config.token:
            cfg.setdefault("token", config.token)
    return cfg


@dataclass
class NoticeConfig:
    broker_url: str
    token: str
    topic: str
    server_url: Optional[str] = None


class NoticeAdapter(BasePlatformAdapter):
    """Hermes Agent 插件 - 接入 Notice MQTT Broker。
    
    配置方式（~/.hermes/config.yaml）:
    
    platforms:
      notice:
        enabled: true
        extra:
          brokerUrl: "wss://你的-notice-server.com"    # 或 tcp://localhost:9091
          token: "你的-auth-token"                      # Notice Server 的 auth.token
          topic: "notice/#"                             # 订阅主题 (默认 notice/#)
          serverUrl: "https://你的-notice-server.com"   # 可选，用于图片上传
    """
    
    def __init__(self, config):
        super().__init__(config, Platform("notice"))
        self._raw_config = config

        cfg = _extract_config(config)

        self.logger = logging.getLogger("gateway.platforms.notice")
        self.logger.info("notice plugin v%s loaded", _load_plugin_version())
        self.logger.debug(f"NoticeAdapter config: brokerUrl={cfg.get('brokerUrl')}, token={cfg.get('token')}, topic={cfg.get('topic')}")

        self.broker_url = cfg.get("brokerUrl", "") or ""
        self.token = cfg.get("token", "") or ""
        self.topic = cfg.get("topic", "notice/#") or "notice/#"
        self.server_url = cfg.get("serverUrl") or None
        self.client_id = f"hermes-{int(time.time() * 1000)}-{random.randint(1000, 9999)}"

        # Send-typing / alive signal — fires every N seconds during processing.
        # Configure via extra.typingInterval (default 20).
        raw = cfg.get("typingInterval", "20")
        try:
            self.typing_interval = int(raw)
        except (TypeError, ValueError):
            self.typing_interval = 20
        if self.typing_interval < 5:
            self.typing_interval = 5  # floor: too fast = spam
        self._last_typing_time: dict[str, float] = {}

        self._mqtt_client: Optional[Any] = None
        # Kept as simple boolean; fine under CPython GIL since MQTT callbacks and send() run
        # through the same event-loop thread context. The asyncio.Event in connect() handles
        # the actual connection-waiting synchronization.
        self._connected = False
        self._connect_event = asyncio.Event()
        self._loop: Optional[asyncio.AbstractEventLoop] = None

        # Content coalescing for streaming responses.
        # The stream consumer calls send() per delta + per tool commentary.
        # MQTT has no edit_message, so each send() would be a separate message.
        # Heuristic: if new content starts with buffered content (streaming
        # append), coalesce with debounce. Otherwise (new segment, fallback
        # chunk), flush pending and publish immediately.
        self._debounce_delay = 0.3  # seconds
        self._pending_content: dict[str, str] = {}
        self._pending_meta: dict[str, dict] = {}
        self._debounce_tasks: dict[str, asyncio.Task] = {}

        # Media audio (ASR) configuration
        media_cfg = cfg.get("mediaAudio", {}) or {}
        self._media_audio_enabled = bool(media_cfg.get("enabled", False))

        backends = []
        providers = media_cfg.get("providers", None)
        if providers and isinstance(providers, list):
            for prov in providers:
                ptype = (prov.get("type") or "").lower()
                if ptype == "cli":
                    try:
                        cli_timeout = int(prov.get("timeout", 60))
                    except (TypeError, ValueError):
                        cli_timeout = 60
                    backends.append(
                        CliTranscriber(
                            command=prov["command"],
                            args=prov.get("args") or [],
                            timeout=cli_timeout,
                        )
                    )
                else:
                    cls = _API_PROVIDERS.get(ptype)
                    if cls is None:
                        self.logger.warning("Unknown ASR provider type: %s", ptype)
                        continue
                    if not prov.get("url"):
                        continue
                    try:
                        api_timeout = int(prov.get("timeout", 300))
                    except (TypeError, ValueError):
                        api_timeout = 300
                    backends.append(
                        cls(
                            url=prov["url"],
                            token=prov.get("token", ""),
                            timeout=api_timeout,
                        )
                    )
        self._transcriber = Transcriber(backends=backends)

    async def connect(self) -> bool:
        self.logger.info(f"Connecting to Notice broker: {self.broker_url}")

        if not self.broker_url:
            self.logger.error("No broker URL configured")
            return False

        from urllib.parse import urlparse

        parsed = urlparse(self.broker_url)
        host = parsed.hostname or "localhost"
        port = parsed.port
        path = parsed.path or "/"
        is_ws = parsed.scheme.startswith("ws")

        if not port:
            if parsed.scheme in ("wss", "tls"):
                port = 443
            elif parsed.scheme in ("ws",):
                port = 80
            else:
                port = 1883

        transport = "websockets" if is_ws else "tcp"
        self.logger.debug(f"Parsed broker: host={host}, port={port}, path={path}, transport={transport}")

        try:
            self._mqtt_client = mqtt.Client(
                callback_api_version=CallbackAPIVersion.VERSION2,
                client_id=self.client_id,
                userdata=self,
                transport=transport
            )

            self._mqtt_client.reconnect_delay_set(min_delay=1, max_delay=120)

            self._mqtt_client.username_pw_set(self.token, self.token)

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
                self.logger.error("Connection timeout")
                return False

            if self._connected:
                self.logger.info(f"Connected to Notice broker and subscribed to {self.topic}")
                return True
            else:
                self.logger.error("Connection failed")
                return False

        except Exception as e:
            self.logger.error(f"Failed to connect to Notice broker: {e}")
            self._connected = False
            return False

    async def disconnect(self) -> None:
        self.logger.info("Disconnecting from Notice broker")

        # Cancel pending debounce tasks so they don't fire after disconnect.
        for task in self._debounce_tasks.values():
            if not task.done():
                task.cancel()
        self._debounce_tasks.clear()
        self._pending_content.clear()
        self._pending_meta.clear()

        if self._mqtt_client:
            try:
                self._mqtt_client.unsubscribe(self.topic)
            except Exception as e:
                self.logger.warning(f"Error unsubscribing: {e}")

            self._mqtt_client.loop_stop()

            try:
                self._mqtt_client.disconnect()
            except Exception as e:
                self.logger.warning(f"Error during disconnect: {e}")

            self._connected = False
            self._mqtt_client = None

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        if rc == 0:
            self._connected = True
            self._connect_event.set()
            self.logger.info(f"MQTT connected, subscribing to {self.topic}")
            client.subscribe(self.topic, qos=1)
        else:
            self._connected = False
            self.logger.error(f"MQTT connection failed with code {rc}")

    def _on_disconnect(self, client, userdata, disconnect_flags, rc, properties=None):
        self._connected = False
        self.logger.info("MQTT disconnected")

    def _on_message(self, client, userdata, msg):
        self.logger.info(f"Received MQTT message on topic: {msg.topic}, payload size: {len(msg.payload)}")
        try:
            payload_str = msg.payload.decode('utf-8')

            try:
                message_data = json.loads(payload_str)
            except json.JSONDecodeError:
                message_data = {
                    "content": payload_str,
                    "title": "",
                    "timestamp": int(time.time() * 1000),
                    "client": "unknown"
                }

            content = message_data.get("content", "")
            if message_data.get("content_encoding") == "gzip+base64" and content:
                try:
                    decoded = base64.b64decode(content)
                    content = gzip.decompress(decoded).decode('utf-8')
                    message_data["content"] = content
                except Exception as e:
                    self.logger.warning(f"Failed to decode content: {e}")

            title = message_data.get("title", "")
            content = message_data.get("content", "")
            client_name = message_data.get("client", "unknown")
            if client_name == "hermes":
                return  # Skip our own messages to prevent echo loops
            timestamp = message_data.get("extra", {}).get("timestamp", message_data.get("timestamp", int(time.time() * 1000)))

            chat_id = msg.topic

            if self._media_audio_enabled and self._transcriber.is_voice_message(content):
                if self._loop:
                    audio_urls = self._transcriber.extract_audio_urls(content)
                    future = asyncio.run_coroutine_threadsafe(
                        self._handle_voice_message(chat_id, audio_urls, client_name, timestamp),
                        self._loop,
                    )
                    future.add_done_callback(
                        lambda f: self.logger.error(f"Voice handler failed: {f.exception()}")
                        if f.exception()
                        else None
                    )
                else:
                    self.logger.error("Event loop not available, dropping voice message")
                return

            full_text = content

            source = SessionSource(
                platform=Platform("notice"),
                chat_id=chat_id,
                chat_name=client_name,
                chat_type="dm",
                user_id=client_name,
                user_name=client_name,
            )

            event = MessageEvent(
                text=full_text,
                message_type=MessageType.TEXT,
                source=source,
                raw_message=message_data,
                message_id=str(timestamp),
            )

            self.logger.info(f"Scheduling message handler for event from {client_name}")
            if self._loop:
                future = asyncio.run_coroutine_threadsafe(self.handle_message(event), self._loop)
                future.add_done_callback(lambda f: self.logger.error(f"Message handler failed: {f.exception()}") if f.exception() else None)
            else:
                self.logger.error("Event loop not available, dropping message")

            self.logger.info(f"Message scheduled from {chat_id}: {content[:50]}...")

        except Exception as e:
            self.logger.error(f"Error processing message: {e}")

    async def _handle_voice_message(
        self,
        chat_id: str,
        audio_urls: list[str],
        client_name: str,
        timestamp: int,
    ) -> None:
        if not self._transcriber.has_backend:
            await self._publish_error(
                chat_id,
                "⚠️ 语音转写未配置，请在 Hermes 配置中启用 mediaAudio",
                client_name,
                timestamp,
            )
            return

        filepath = None
        for url in audio_urls:
            filepath = await self._transcriber.download_audio(url, self.token)
            if filepath:
                break

        if not filepath:
            await self._publish_error(
                chat_id, "❌ 语音下载失败", client_name, timestamp
            )
            return

        try:
            text = await self._transcriber.transcribe_file(filepath)
        finally:
            try:
                os.unlink(filepath)
            except OSError:
                pass

        if not text:
            await self._publish_error(
                chat_id, "❌ 语音转写失败", client_name, timestamp
            )
            return

        source = SessionSource(
            platform=Platform("notice"),
            chat_id=chat_id,
            chat_name=client_name,
            chat_type="dm",
            user_id=client_name,
            user_name=client_name,
        )
        event = MessageEvent(
            text=text,
            message_type=MessageType.TEXT,
            source=source,
            raw_message={},
            message_id=str(timestamp),
        )
        await self.handle_message(event)

    async def _publish_error(
        self, chat_id: str, content: str, client_name: str, timestamp: int
    ) -> None:
        payload = {
            "content": content,
            "client": "hermes",
            "timestamp": timestamp,
            "extra": {"toUser": client_name},
        }
        await self._do_publish(chat_id, json.dumps(payload), None)

    async def _do_publish(self, chat_id: str, content: str, metadata: dict | None) -> SendResult:
        """Publish a message to MQTT immediately."""
        timestamp = int(time.time() * 1000)
        encoded_content = content
        content_encoding = None

        if len(content) >= 256:
            try:
                compressed = gzip.compress(content.encode('utf-8'))
                encoded = base64.b64encode(compressed).decode('ascii')
                if len(encoded) < len(content):
                    encoded_content = encoded
                    content_encoding = "gzip+base64"
            except Exception as e:
                self.logger.warning(f"Compression failed: {e}")

        payload = {
            "title": "",
            "content": encoded_content,
            "client": "hermes",
            "timestamp": timestamp,
            "extra": {},
        }

        if content_encoding:
            payload["content_encoding"] = content_encoding

        if metadata:
            payload["extra"] = metadata

        if ":" in chat_id:
            topic = chat_id
            if topic.startswith("topic:"):
                topic = topic[6:]
        else:
            topic = chat_id

        json_payload = json.dumps(payload)

        try:
            loop = asyncio.get_running_loop()
            result = await loop.run_in_executor(
                None,
                self._mqtt_client.publish,
                topic,
                json_payload,
                1,
            )
            self.logger.info(f"Sent {len(content)} chars to {topic}")
            return SendResult(success=True, message_id=str(result.mid))
        except Exception as e:
            self.logger.error(f"Failed to publish: {e}")
            return SendResult(success=False, error=str(e))

    async def send(self, chat_id: str, content: str, metadata: dict, reply_to: Optional[str] = None) -> SendResult:
        if not self._mqtt_client or not self._connected:
            return SendResult(success=False, error="Not connected to broker")

        # Cancel the pending debounce timer (it will be restarted below).
        old_task = self._debounce_tasks.pop(chat_id, None)
        if old_task and not old_task.done():
            old_task.cancel()

        old_content = self._pending_content.get(chat_id)

        if old_content is not None and content.startswith(old_content):
            # Streaming append: replace buffer, restart timer.
            pass  # Fall through to buffer below.
        else:
            # Non-append content (new segment, commentary, fallback chunk):
            # flush whatever was pending first, then buffer the new content.
            if old_content is not None:
                meta = self._pending_meta.pop(chat_id, None)
                await self._do_publish(chat_id, old_content, meta)
                self.logger.debug(f"Flushed pending for {chat_id}")

        # Buffer the latest content and start/restart the debounce timer.
        self._pending_content[chat_id] = content
        if metadata:
            self._pending_meta[chat_id] = metadata

        async def _coalesced_publish():
            try:
                await asyncio.sleep(self._debounce_delay)
                final_content = self._pending_content.pop(chat_id, None)
                meta = self._pending_meta.pop(chat_id, None) if final_content else None
                if final_content is not None:
                    await self._do_publish(chat_id, final_content, meta)
                    # Reset typing timer after real content is published so that
                    # _keep_typing won't fire a stale "⏳" right after the reply.
                    self._last_typing_time[chat_id] = time.monotonic()
            except asyncio.CancelledError:
                pass

        task = asyncio.create_task(_coalesced_publish())
        self._debounce_tasks[chat_id] = task
        return SendResult(success=True, message_id="coalesced")

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        now = time.monotonic()
        if now - self._last_typing_time.get(chat_id, 0.0) < self.typing_interval:
            return
        self._last_typing_time[chat_id] = now
        if self._mqtt_client and self._connected:
            await self._do_publish(chat_id, "⏳", {"status": "typing"})

    async def send_image(self, chat_id: str, image_url: str, caption: str) -> SendResult:
        if not self._mqtt_client or not self._connected:
            return SendResult(success=False, error="Not connected to broker")

        final_url = image_url

        if self.server_url and image_url and not image_url.startswith(("http://", "https://", "//")):
            try:
                import os

                if os.path.exists(image_url):
                    self.logger.info(f"Uploading image to {self.server_url}")

                    def _do_upload():
                        with open(image_url, "rb") as f:
                            image_data = f.read()

                        boundary = f"----FormBoundary{int(time.time() * 1000)}"
                        filename = os.path.basename(image_url)

                        body = []
                        body.append(f"--{boundary}".encode())
                        body.append(f'Content-Disposition: form-data; name="file"; filename="{filename}"'.encode())
                        body.append(b"Content-Type: image/jpeg")
                        body.append(b"")
                        body.append(image_data)
                        body.append(f"--{boundary}--".encode())
                        body.append(b"")
                        content_type = f"multipart/form-data; boundary={boundary}"

                        import urllib.request

                        req = urllib.request.Request(
                            f"{self.server_url.rstrip('/')}/api/upload",
                            data=b"\r\n".join(body),
                            headers={
                                "Authorization": f"Bearer {self.token}",
                                "Content-Type": content_type
                            },
                            method="POST"
                        )

                        with urllib.request.urlopen(req, timeout=30) as response:
                            response_data = json.loads(response.read().decode())

                        return response_data

                    loop = asyncio.get_running_loop()
                    response_data = await loop.run_in_executor(None, _do_upload)

                    if response_data.get("success") and response_data.get("image_urls"):
                        final_url = response_data["image_urls"][0]
                        if not final_url.startswith("http"):
                            final_url = self.server_url.rstrip("/") + "/" + final_url.lstrip("/")
                        self.logger.info(f"Image uploaded, URL: {final_url}")
                    else:
                        self.logger.warning(f"Upload failed: {response_data.get('message', 'unknown error')}")
                else:
                    self.logger.warning(f"Image file not found: {image_url}")

            except Exception as e:
                self.logger.error(f"Image upload failed: {e}")

        if caption:
            markdown_image = f"{caption}\n\n![]({final_url})"
        else:
            markdown_image = f"![]({final_url})"

        return await self.send(chat_id, markdown_image, {})

    async def get_chat_info(self, chat_id: str) -> dict:
        return {
            "chat_id": chat_id,
            "display_name": chat_id.split("/")[-1] if "/" in chat_id else chat_id,
            "type": "topic",
            "topic": chat_id,
            "is_active": True
        }

    async def on_processing_start(self, event: MessageEvent) -> None:
        if self._mqtt_client and self._connected:
            await self._do_publish(event.source.chat_id, "🧠 正在处理...", {"status": "started"})
        # Mark typing as just-sent so the first send_typing() won't
        # fire another pulse right after — 🧠 already serves as alive signal.
        self._last_typing_time[event.source.chat_id] = time.monotonic()

    async def on_processing_complete(self, event: MessageEvent, outcome: ProcessingOutcome) -> None:
        if outcome == ProcessingOutcome.SUCCESS:
            return  # Final reply is the success signal — skip.
        icon = "❌" if outcome == ProcessingOutcome.FAILURE else "⚠️"
        if self._mqtt_client and self._connected:
            await self._do_publish(event.source.chat_id, f"{icon} 处理未完成", {"status": outcome.value})


def validate_config(config) -> tuple[bool, str | None]:
    cfg = _extract_config(config)

    broker_url = cfg.get("brokerUrl", "")
    token = cfg.get("token", "")

    if not broker_url:
        return False, "brokerUrl is required"

    if not token:
        return False, "token is required"

    return True, None


def check_requirements() -> tuple[bool, str | None]:
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        return False, "paho-mqtt is required. Install with: pip install paho-mqtt"

    return True, None


def register(ctx):
    ctx.register_platform(
        name="notice",
        label="Notice",
        adapter_factory=lambda cfg: NoticeAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        platform_hint="You are on Notice. Supports markdown, images (gzip+base64 compression), and real-time messaging via MQTT.",
        max_message_length=65536,
        emoji="🔔",
        allow_all_env="NOTICE_ALLOW_ALL_USERS",
    )
