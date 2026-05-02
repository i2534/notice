import asyncio
import gzip
import json
import base64
import time
import logging
from abc import ABC
from typing import Any, Optional
from dataclasses import dataclass

try:
    import paho.mqtt.client as mqtt
    from paho.mqtt.enums import CallbackAPIVersion
except ImportError:
    raise ImportError("paho-mqtt is required. Install with: pip install paho-mqtt")

from gateway.config import Platform
from gateway.platforms.base import BasePlatformAdapter, SendResult, MessageEvent, MessageType
from gateway.session import SessionSource


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

        self.logger = logging.getLogger(__name__)
        self.logger.debug(f"NoticeAdapter config: brokerUrl={cfg.get('brokerUrl')}, token={cfg.get('token')}, topic={cfg.get('topic')}")

        self.broker_url = cfg.get("brokerUrl", "") or ""
        self.token = cfg.get("token", "") or ""
        self.topic = cfg.get("topic", "notice/#") or "notice/#"
        self.server_url = cfg.get("serverUrl") or None
        self.client_id = f"hermes-{int(time.time() * 1000)}"

        self._mqtt_client: Optional[Any] = None
        self._connected = False
        self._loop: Optional[asyncio.AbstractEventLoop] = None

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

            self._mqtt_client.username_pw_set(self.token, self.token)

            if is_ws or parsed.scheme == "tls":
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
            await asyncio.sleep(1)

            if self._connected:
                self.logger.info(f"Connected to Notice broker and subscribed to {self.topic}")
                return True
            else:
                self.logger.error("Connection timeout")
                return False

        except Exception as e:
            self.logger.error(f"Failed to connect to Notice broker: {e}")
            self._connected = False
            return False

    async def disconnect(self) -> None:
        self.logger.info("Disconnecting from Notice broker")

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
            extra = message_data.get("extra", {})

            chat_id = msg.topic
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
                asyncio.run_coroutine_threadsafe(self.handle_message(event), self._loop)
            else:
                self.logger.error("Event loop not available, dropping message")

            self.logger.info(f"Message scheduled from {chat_id}: {content[:50]}...")

        except Exception as e:
            self.logger.error(f"Error processing message: {e}")

    async def send(self, chat_id: str, content: str, metadata: dict, reply_to: Optional[str] = None) -> SendResult:
        if not self._mqtt_client or not self._connected:
            return SendResult(success=False, error="Not connected to broker")

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
            "extra": {}
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
                0  # QoS 0 - fire and forget (broker doesn't ACK over WSS)
            )
            self.logger.info(f"Message sent to {topic}: {content[:50]}...")
            return SendResult(success=True, message_id=str(result.mid))

        except Exception as e:
            self.logger.error(f"Failed to send message: {e}")
            return SendResult(success=False, error=str(e))

    async def send_typing(self, chat_id: str) -> None:
        pass

    async def send_image(self, chat_id: str, image_url: str, caption: str) -> SendResult:
        if not self._mqtt_client or not self._connected:
            return SendResult(success=False, error="Not connected to broker")

        final_url = image_url

        if self.server_url and image_url and not image_url.startswith(("http://", "https://", "//")):
            try:
                import os

                if os.path.exists(image_url):
                    self.logger.info(f"Uploading image to {self.server_url}")

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

        return await self.send(chat_id, markdown_image, None, {})

    async def get_chat_info(self, chat_id: str) -> dict:
        return {
            "chat_id": chat_id,
            "display_name": chat_id.split("/")[-1] if "/" in chat_id else chat_id,
            "type": "topic",
            "topic": chat_id,
            "is_active": True
        }


def validate_config(config) -> tuple[bool, str | None]:
    cfg = {}
    if isinstance(config, dict):
        cfg = config.get("extra", {}) or config
    elif hasattr(config, "extra") and isinstance(config.extra, dict):
        cfg = config.extra
        if hasattr(config, "token") and config.token:
            cfg.setdefault("token", config.token)

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
