"""Hermes Notice 平台适配器 — MQTT 接入 Notice 推送系统。"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from typing import Any, Optional

from gateway.config import Platform
from gateway.platforms.base import (
    BasePlatformAdapter,
    MessageEvent,
    MessageType,
    ProcessingOutcome,
    SendResult,
)
from gateway.session import SessionSource

from asr import Transcriber, CliTranscriber, _API_PROVIDERS
from coalesce import ContentCoalescer
from config import (
    ENV_ALLOW_ALL,
    ENV_ALLOWED_USERS,
    ENV_HOME_CHANNEL,
    check_requirements,
    env_enablement,
    is_connected,
    load_settings,
    validate_config,
)
from media import markdown_with_images, resolve_media_url
from mqtt_transport import create_mqtt_client, make_client_id, publish_qos1
from payload import (
    OUTBOUND_CLIENT,
    build_outbound_payload,
    decode_inbound_payload,
    dumps_payload,
    resolve_publish_topic,
)
from standalone import standalone_send

PLUGIN_DIR = os.path.dirname(os.path.abspath(__file__))
logger = logging.getLogger("gateway.platforms.notice")

MAX_MESSAGE_LENGTH = 65536


def _load_plugin_version() -> str:
    try:
        with open(os.path.join(PLUGIN_DIR, "plugin.yaml"), "r", encoding="utf-8") as f:
            for line in f:
                if line.startswith("version:"):
                    return line.split(":", 1)[1].strip()
    except Exception:
        pass
    return "unknown"


def _build_transcriber(media_cfg: dict) -> Transcriber:
    backends = []
    providers = media_cfg.get("providers") if isinstance(media_cfg, dict) else None
    if providers and isinstance(providers, list):
        for prov in providers:
            if not isinstance(prov, dict):
                continue
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
                    logger.warning("Unknown ASR provider type: %s", ptype)
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
    return Transcriber(backends=backends)


class NoticeAdapter(BasePlatformAdapter):
    """Notice MQTT 适配器。"""

    MAX_MESSAGE_LENGTH = MAX_MESSAGE_LENGTH

    def __init__(self, config):
        super().__init__(config, Platform("notice"))
        settings = load_settings(config)
        self.logger = logger
        self.logger.info("notice plugin v%s loaded", _load_plugin_version())

        self.broker_url = settings.broker_url
        self.token = settings.token
        self.topic = settings.topic
        self.server_url = settings.server_url
        self.typing_interval = settings.typing_interval
        self.client_id = make_client_id("hermes")

        self._last_typing_time: dict[str, float] = {}
        self._mqtt_client: Any = None
        self._mqtt_connected = False
        self._connect_event = asyncio.Event()
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._lock_key: Optional[str] = None

        self._coalescer = ContentCoalescer(
            self._do_publish,
            delay=0.3,
            on_flushed=lambda chat_id: self._last_typing_time.__setitem__(
                chat_id, time.monotonic()
            ),
        )

        media_cfg = settings.media_audio or {}
        self._media_audio_enabled = bool(media_cfg.get("enabled", False))
        self._transcriber = _build_transcriber(media_cfg)

    # -- connection ---------------------------------------------------------

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        self.logger.info(
            "Connecting to Notice broker: %s (reconnect=%s)",
            self.broker_url,
            is_reconnect,
        )
        if not self.broker_url:
            self.logger.error("No broker URL configured")
            return False

        # 多 profile 防抢同一 token
        try:
            from gateway.status import acquire_scoped_lock

            lock_key = f"{self.broker_url}|{self.token[:8]}"
            ok, _conflict = acquire_scoped_lock(
                "notice",
                lock_key,
                metadata={"broker": self.broker_url},
            )
            if not ok:
                self.logger.error("Notice token/broker already in use by another profile")
                self._set_fatal_error(
                    "lock_conflict",
                    "Notice identity in use by another profile",
                    retryable=False,
                )
                return False
            self._lock_key = lock_key
        except ImportError:
            self._lock_key = None

        try:
            self._mqtt_client, ep = create_mqtt_client(
                self.broker_url,
                self.token,
                client_id=self.client_id,
                on_connect=self._on_connect,
                on_disconnect=self._on_disconnect,
                on_message=self._on_message,
                userdata=self,
            )

            loop = asyncio.get_running_loop()
            self._loop = loop
            await loop.run_in_executor(None, self._mqtt_client.connect, ep.host, ep.port, 60)
            self._mqtt_client.loop_start()

            self._connect_event.clear()
            try:
                await asyncio.wait_for(self._connect_event.wait(), timeout=10.0)
            except asyncio.TimeoutError:
                self.logger.error("Connection timeout")
                await self._cleanup_client()
                return False

            if self._mqtt_connected:
                self._mark_connected()
                self.logger.info("Connected to Notice broker, subscribed to %s", self.topic)
                return True

            self.logger.error("Connection failed")
            await self._cleanup_client()
            return False
        except Exception as e:
            self.logger.error("Failed to connect to Notice broker: %s", e)
            self._mqtt_connected = False
            await self._cleanup_client()
            return False

    async def disconnect(self) -> None:
        self.logger.info("Disconnecting from Notice broker")
        self._coalescer.cancel_all()
        await self._cleanup_client()
        self._mark_disconnected()
        if self._lock_key:
            try:
                from gateway.status import release_scoped_lock

                release_scoped_lock("notice", self._lock_key)
            except ImportError:
                pass
            self._lock_key = None

    async def _cleanup_client(self) -> None:
        if not self._mqtt_client:
            self._mqtt_connected = False
            return
        try:
            self._mqtt_client.unsubscribe(self.topic)
        except Exception as e:
            self.logger.warning("Error unsubscribing: %s", e)
        try:
            self._mqtt_client.loop_stop()
        except Exception:
            pass
        try:
            self._mqtt_client.disconnect()
        except Exception as e:
            self.logger.warning("Error during disconnect: %s", e)
        self._mqtt_connected = False
        self._mqtt_client = None

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        try:
            success = not bool(getattr(rc, "is_failure", int(rc) != 0))
        except Exception:
            success = rc == 0
        if success:
            self._mqtt_connected = True
            self._connect_event.set()
            self.logger.info("MQTT connected, subscribing to %s", self.topic)
            client.subscribe(self.topic, qos=1)
        else:
            self._mqtt_connected = False
            self._connect_event.set()
            self.logger.error("MQTT connection failed with code %s", rc)

    def _on_disconnect(self, client, userdata, disconnect_flags, rc, properties=None):
        self._mqtt_connected = False
        self.logger.info("MQTT disconnected")

    # -- inbound ------------------------------------------------------------

    def _on_message(self, client, userdata, msg):
        self.logger.info(
            "Received MQTT message on topic: %s, payload size: %d",
            msg.topic,
            len(msg.payload),
        )
        try:
            message_data = decode_inbound_payload(msg.payload)
            content = message_data.get("content", "")
            client_name = message_data.get("client", "unknown")
            if client_name == OUTBOUND_CLIENT:
                return

            extra = message_data.get("extra") or {}
            timestamp = extra.get("timestamp", message_data.get("timestamp", int(time.time() * 1000)))
            chat_id = msg.topic

            if self._media_audio_enabled and self._transcriber.is_voice_message(content):
                if self._loop:
                    audio_urls = self._transcriber.extract_audio_urls(content)
                    future = asyncio.run_coroutine_threadsafe(
                        self._handle_voice_message(chat_id, audio_urls, client_name, timestamp),
                        self._loop,
                    )
                    future.add_done_callback(
                        lambda f: self.logger.error("Voice handler failed: %s", f.exception())
                        if f.exception()
                        else None
                    )
                else:
                    self.logger.error("Event loop not available, dropping voice message")
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
                text=content,
                message_type=MessageType.TEXT,
                source=source,
                raw_message=message_data,
                message_id=str(timestamp),
            )
            if self._loop:
                future = asyncio.run_coroutine_threadsafe(self.handle_message(event), self._loop)
                future.add_done_callback(
                    lambda f: self.logger.error("Message handler failed: %s", f.exception())
                    if f.exception()
                    else None
                )
            else:
                self.logger.error("Event loop not available, dropping message")
        except Exception as e:
            self.logger.error("Error processing message: %s", e)

    async def _handle_voice_message(
        self,
        chat_id: str,
        audio_urls: list[str],
        client_name: str,
        timestamp: int,
    ) -> None:
        filepath = None
        for url in audio_urls:
            filepath = await self._transcriber.download_audio(url, self.token)
            if filepath:
                break

        if not filepath:
            await self._publish_error(chat_id, "❌ 语音下载失败", client_name)
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
                chat_id,
                "❌ 语音转写失败（请配置 platforms.notice.extra.mediaAudio 或全局 stt）",
                client_name,
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

    # -- outbound -----------------------------------------------------------

    async def _publish_error(self, chat_id: str, content: str, client_name: str) -> None:
        await self._do_publish(chat_id, content, {"toUser": client_name})

    async def _do_publish(
        self, chat_id: str, content: str, metadata: Optional[dict] = None
    ) -> SendResult:
        if not self._mqtt_client or not self._mqtt_connected:
            return SendResult(success=False, error="Not connected to broker")

        payload = build_outbound_payload(content, metadata=metadata)
        topic = resolve_publish_topic(chat_id)
        json_payload = dumps_payload(payload)
        try:
            loop = asyncio.get_running_loop()
            result = await loop.run_in_executor(
                None, publish_qos1, self._mqtt_client, topic, json_payload
            )
            self.logger.info("Sent %d chars to %s", len(content), topic)
            return SendResult(success=True, message_id=str(result.mid))
        except Exception as e:
            self.logger.error("Failed to publish: %s", e)
            return SendResult(success=False, error=str(e))

    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[dict] = None,
    ) -> SendResult:
        if not self._mqtt_client or not self._mqtt_connected:
            return SendResult(success=False, error="Not connected to broker")
        await self._coalescer.submit(chat_id, content, metadata)
        return SendResult(success=True, message_id="coalesced")

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        now = time.monotonic()
        if now - self._last_typing_time.get(chat_id, 0.0) < self.typing_interval:
            return
        self._last_typing_time[chat_id] = now
        if self._mqtt_client and self._mqtt_connected:
            await self._do_publish(chat_id, "⏳", {"status": "typing"})

    async def send_image(
        self,
        chat_id: str,
        image_url: str,
        caption: Optional[str] = None,
        reply_to: Optional[str] = None,
        metadata: Optional[dict] = None,
    ) -> SendResult:
        if not self._mqtt_client or not self._mqtt_connected:
            return SendResult(success=False, error="Not connected to broker")

        loop = asyncio.get_running_loop()
        final_url = await loop.run_in_executor(
            None,
            lambda: resolve_media_url(
                image_url, server_url=self.server_url, token=self.token
            ),
        )
        return await self.send(
            chat_id=chat_id,
            content=markdown_with_images(caption or "", [final_url]),
            reply_to=reply_to,
            metadata=metadata,
        )

    async def send_image_file(
        self,
        chat_id: str,
        image_path: str,
        caption: Optional[str] = None,
        reply_to: Optional[str] = None,
        metadata: Optional[dict] = None,
        **kwargs,
    ) -> SendResult:
        return await self.send_image(
            chat_id=chat_id,
            image_url=image_path,
            caption=caption,
            reply_to=reply_to,
            metadata=metadata,
        )

    async def get_chat_info(self, chat_id: str) -> dict:
        return {
            "chat_id": chat_id,
            "display_name": chat_id.split("/")[-1] if "/" in chat_id else chat_id,
            "type": "topic",
            "topic": chat_id,
            "is_active": True,
        }

    async def on_processing_start(self, event: MessageEvent) -> None:
        self._last_typing_time[event.source.chat_id] = time.monotonic()
        if self._mqtt_client and self._mqtt_connected:
            await self._do_publish(
                event.source.chat_id, "🧠 正在处理...", {"status": "started"}
            )

    async def on_processing_complete(
        self, event: MessageEvent, outcome: ProcessingOutcome
    ) -> None:
        if outcome == ProcessingOutcome.SUCCESS:
            return
        icon = "❌" if outcome == ProcessingOutcome.FAILURE else "⚠️"
        if self._mqtt_client and self._mqtt_connected:
            await self._do_publish(
                event.source.chat_id,
                f"{icon} 处理未完成",
                {"status": outcome.value},
            )


def register(ctx) -> None:
    """Plugin entry — Hermes plugin loader 启动时调用。"""
    ctx.register_platform(
        name="notice",
        label="Notice",
        adapter_factory=lambda cfg: NoticeAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        is_connected=is_connected,
        required_env=["NOTICE_BROKER_URL", "NOTICE_TOKEN"],
        install_hint="pip install paho-mqtt>=2.0",
        env_enablement_fn=env_enablement,
        cron_deliver_env_var=ENV_HOME_CHANNEL,
        standalone_sender_fn=standalone_send,
        allowed_users_env=ENV_ALLOWED_USERS,
        allow_all_env=ENV_ALLOW_ALL,
        max_message_length=MAX_MESSAGE_LENGTH,
        emoji="🔔",
        pii_safe=True,
        allow_update_command=True,
        platform_hint=(
            "You are on Notice (MQTT push). Prefer concise Markdown. "
            "Images are delivered as markdown image URLs after upload. "
            "Long text may be gzip+base64 compressed transparently."
        ),
    )
