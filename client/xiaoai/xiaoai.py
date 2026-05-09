#!/usr/bin/env python3
"""
小爱音箱 Pro 客户端 - 接入 Notice 消息系统

双向通信:
- 接收: Notice 推送消息 -> 小爱 TTS 播报
- 发送: 用户对音箱说话 -> 发布到 Notice

使用方式:
    python xiaoai.py --config config.yaml
"""

import argparse
import asyncio
import base64
import gzip
import json
import logging
import os
import random
import signal
import sys
import time
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlparse

try:
    import paho.mqtt.client as mqtt
    from paho.mqtt.enums import CallbackAPIVersion
except ImportError:
    raise ImportError("paho-mqtt is required. Install with: pip install paho-mqtt")

try:
    import yaml
except ImportError:
    raise ImportError("pyyaml is required. Install with: pip install pyyaml")

try:
    import aiohttp
except ImportError:
    raise ImportError("aiohttp is required. Install with: pip install aiohttp")


def setup_logging(level: str = "INFO") -> None:
    """配置日志"""
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )


def decode_content(content: str, encoding: Optional[str]) -> str:
    """解码消息内容 (gzip+base64)"""
    if encoding == "gzip+base64" and content:
        try:
            decoded = base64.b64decode(content)
            return gzip.decompress(decoded).decode("utf-8")
        except Exception:
            pass
    return content


def encode_content(content: str) -> tuple[str, Optional[str]]:
    """编码消息内容 (gzip+base64)，超过 256 字符时压缩"""
    if len(content) >= 256:
        try:
            compressed = gzip.compress(content.encode("utf-8"))
            encoded = base64.b64encode(compressed).decode("ascii")
            if len(encoded) < len(content):
                return encoded, "gzip+base64"
        except Exception:
            pass
    return content, None


class XiaoAiClient:
    """小爱音箱 Pro 客户端"""

    def __init__(self, config_path: str):
        self.logger = logging.getLogger("xiaoai")
        self.config = self._load_config(config_path)

        # MQTT 配置
        mqtt_cfg = self.config.get("mqtt", {})
        self.broker_url = mqtt_cfg.get("broker_url", "")
        self.token = mqtt_cfg.get("token", "")
        self.subscribe_topic = mqtt_cfg.get("subscribe_topic", "notice/xiaoai")
        self.publish_topic = mqtt_cfg.get("publish_topic", "notice/from-xiaoai")

        # 小爱配置
        xiaoai_cfg = self.config.get("xiaoai", {})
        self.mi_user = xiaoai_cfg.get("mi_user", "")
        self.mi_pass = xiaoai_cfg.get("mi_pass", "")
        self.device_id = xiaoai_cfg.get("device_id", "")
        token_cache = xiaoai_cfg.get("token_cache", "~/.config/xiaoai/mi_token.json")
        self.token_cache_path = os.path.expanduser(token_cache)

        # 轮询配置
        poll_cfg = self.config.get("poll", {})
        self.poll_enabled = poll_cfg.get("enabled", True)
        self.poll_interval = poll_cfg.get("interval", 5)
        self.filter_prefix = poll_cfg.get("filter_prefix", "")

        # TTS 配置
        tts_cfg = self.config.get("tts", {})
        self.tts_prefix = tts_cfg.get("prefix", "")
        self.tts_format = tts_cfg.get("format", "{title}，{content}")

        # MQTT 客户端状态
        self.client_id = f"xiaoai-{int(time.time() * 1000)}-{random.randint(1000, 9999)}"
        self._mqtt_client: Optional[mqtt.Client] = None
        self._connected = False
        self._connect_event = asyncio.Event()
        self._loop: Optional[asyncio.AbstractEventLoop] = None

        # MiService
        self._session: Optional[aiohttp.ClientSession] = None
        self._mi_account: Optional[Any] = None
        self._mi_na: Optional[Any] = None

        # 对话轮询状态
        self._last_ask_time: float = 0
        self._poll_task: Optional[asyncio.Task] = None

        # 运行状态
        self._running = False

    def _load_config(self, config_path: str) -> dict:
        """加载配置文件"""
        path = Path(config_path)
        if not path.exists():
            raise FileNotFoundError(f"Config file not found: {config_path}")

        with open(path, "r", encoding="utf-8") as f:
            return yaml.safe_load(f) or {}

    async def _init_miservice(self) -> bool:
        """初始化 MiService"""
        try:
            from miservice import MiAccount, MiNAService
        except ImportError:
            # 尝试 miservice_fork
            try:
                from miservice_fork import MiAccount, MiNAService
            except ImportError:
                self.logger.error(
                    "miservice is required. Install with: pip install miservice-fork"
                )
                return False

        # 确保缓存目录存在
        cache_dir = os.path.dirname(self.token_cache_path)
        if cache_dir:
            os.makedirs(cache_dir, exist_ok=True)

        self._session = aiohttp.ClientSession()
        self._mi_account = MiAccount(
            self._session,
            self.mi_user,
            self.mi_pass,
            self.token_cache_path,
        )
        self._mi_na = MiNAService(self._mi_account)

        # 登录
        if not await self._mi_account.login("micoapi"):
            self.logger.error("Failed to login to Xiaomi account")
            return False

        self.logger.info("Logged in to Xiaomi account")

        # 获取设备列表
        if not self.device_id:
            devices = await self._mi_na.device_list()
            if devices:
                # 选择第一个小爱设备
                self.device_id = devices[0].get("deviceID", "")
                self.logger.info(f"Auto-selected device: {self.device_id}")
            else:
                self.logger.error("No devices found")
                return False

        self.logger.info(f"Using device: {self.device_id}")
        return True

    async def connect(self) -> bool:
        """连接 MQTT broker"""
        self.logger.info(f"Connecting to Notice broker: {self.broker_url}")

        if not self.broker_url:
            self.logger.error("No broker URL configured")
            return False

        parsed = urlparse(self.broker_url)
        host = parsed.hostname or "localhost"
        port = parsed.port
        path = parsed.path or "/"
        is_ws = parsed.scheme.startswith("ws")

        if not port:
            if parsed.scheme in ("wss", "tls"):
                port = 443
            elif parsed.scheme == "ws":
                port = 80
            else:
                port = 1883

        transport = "websockets" if is_ws else "tcp"
        self.logger.debug(
            f"Parsed broker: host={host}, port={port}, path={path}, transport={transport}"
        )

        try:
            self._mqtt_client = mqtt.Client(
                callback_api_version=CallbackAPIVersion.VERSION2,
                client_id=self.client_id,
                userdata=self,
                transport=transport,
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
            await loop.run_in_executor(
                None, self._mqtt_client.connect, host, port, 60
            )

            self._mqtt_client.loop_start()

            self._connect_event.clear()
            try:
                await asyncio.wait_for(self._connect_event.wait(), timeout=10.0)
            except asyncio.TimeoutError:
                self.logger.error("Connection timeout")
                return False

            if self._connected:
                self.logger.info(
                    f"Connected to Notice broker and subscribed to {self.subscribe_topic}"
                )
                return True
            else:
                self.logger.error("Connection failed")
                return False

        except Exception as e:
            self.logger.error(f"Failed to connect to Notice broker: {e}")
            self._connected = False
            return False

    async def disconnect(self) -> None:
        """断开连接"""
        self.logger.info("Disconnecting...")

        self._running = False

        # 停止轮询任务
        if self._poll_task and not self._poll_task.done():
            self._poll_task.cancel()
            try:
                await self._poll_task
            except asyncio.CancelledError:
                pass

        # 断开 MQTT
        if self._mqtt_client:
            try:
                self._mqtt_client.unsubscribe(self.subscribe_topic)
            except Exception:
                pass

            self._mqtt_client.loop_stop()

            try:
                self._mqtt_client.disconnect()
            except Exception:
                pass

            self._connected = False
            self._mqtt_client = None

        # 关闭 aiohttp session
        if self._session:
            await self._session.close()
            self._session = None

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        """MQTT 连接回调"""
        if rc == 0:
            self._connected = True
            self._connect_event.set()
            self.logger.info(f"MQTT connected, subscribing to {self.subscribe_topic}")
            client.subscribe(self.subscribe_topic, qos=1)
        else:
            self._connected = False
            self.logger.error(f"MQTT connection failed with code {rc}")

    def _on_disconnect(self, client, userdata, disconnect_flags, rc, properties=None):
        """MQTT 断开回调"""
        self._connected = False
        self.logger.info("MQTT disconnected")

    def _on_message(self, client, userdata, msg):
        """MQTT 消息回调 -> TTS 播报"""
        try:
            payload_str = msg.payload.decode("utf-8")

            try:
                message_data = json.loads(payload_str)
            except json.JSONDecodeError:
                message_data = {
                    "content": payload_str,
                    "title": "",
                    "timestamp": int(time.time() * 1000),
                    "client": "unknown",
                }

            # 解码内容
            content = message_data.get("content", "")
            content_encoding = message_data.get("content_encoding")
            content = decode_content(content, content_encoding)
            message_data["content"] = content

            # 回声抑制
            client_name = message_data.get("client", "unknown")
            if client_name == "xiaoai":
                self.logger.debug("Skipping own message (echo)")
                return

            title = message_data.get("title", "")

            # 格式化播报文本
            text = self.tts_format.format(
                title=title or "消息",
                content=content,
            )
            if self.tts_prefix:
                text = self.tts_prefix + text

            # 调度 TTS 播报
            if self._loop:
                future = asyncio.run_coroutine_threadsafe(
                    self._do_tts(text), self._loop
                )
                future.add_done_callback(
                    lambda f: self.logger.error(f"TTS failed: {f.exception()}")
                    if f.exception()
                    else None
                )

        except Exception as e:
            self.logger.error(f"Error processing message: {e}")

    async def _do_tts(self, text: str) -> None:
        """执行 TTS 播报"""
        if not self._mi_na or not self.device_id:
            self.logger.warning("MiService not initialized, skipping TTS")
            return

        try:
            self.logger.info(f"TTS: {text[:50]}...")
            await self._mi_na.text_to_speech(self.device_id, text)
        except Exception as e:
            self.logger.error(f"TTS failed: {e}")

    async def _poll_conversation(self) -> None:
        """轮询小爱对话 -> 发布到 Notice"""
        while self._running:
            try:
                await asyncio.sleep(self.poll_interval)

                if not self._mi_na or not self.device_id or not self._connected:
                    continue

                # 获取最近对话
                asks = await self._mi_na.get_latest_ask(self.device_id)
                if not asks:
                    continue

                for ask in asks:
                    # 解析时间戳
                    ask_time = 0
                    if hasattr(ask, "time"):
                        ask_time = ask.time
                    elif isinstance(ask, dict):
                        ask_time = ask.get("time", 0)

                    # 只处理新对话
                    if ask_time <= self._last_ask_time:
                        continue

                    self._last_ask_time = ask_time

                    # 提取对话内容
                    text = ""
                    if hasattr(ask, "content"):
                        text = ask.content
                    elif isinstance(ask, dict):
                        text = ask.get("content", "")

                    if not text:
                        continue

                    # 过滤前缀
                    if self.filter_prefix and text.startswith(self.filter_prefix):
                        text = text[len(self.filter_prefix) :].strip()

                    if not text:
                        continue

                    self.logger.info(f"New conversation: {text[:50]}...")

                    # 发布到 Notice
                    await self._do_publish(text)

            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Poll error: {e}")

    async def _do_publish(self, content: str) -> None:
        """发布消息到 MQTT"""
        if not self._mqtt_client or not self._connected:
            self.logger.warning("Not connected, skipping publish")
            return

        timestamp = int(time.time() * 1000)
        encoded_content, content_encoding = encode_content(content)

        payload = {
            "title": "来自小爱",
            "content": encoded_content,
            "client": "xiaoai",
            "timestamp": timestamp,
            "extra": {"source": "xiaoai-voice"},
        }

        if content_encoding:
            payload["content_encoding"] = content_encoding

        json_payload = json.dumps(payload)

        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(
                None,
                self._mqtt_client.publish,
                self.publish_topic,
                json_payload,
                1,
            )
            self.logger.info(f"Published {len(content)} chars to {self.publish_topic}")
        except Exception as e:
            self.logger.error(f"Publish failed: {e}")

    async def run(self) -> None:
        """运行客户端"""
        self._running = True

        # 初始化 MiService
        if not await self._init_miservice():
            self.logger.error("Failed to initialize MiService")
            return

        # 连接 MQTT
        if not await self.connect():
            self.logger.error("Failed to connect to MQTT broker")
            return

        # 启动轮询任务
        if self.poll_enabled:
            self._poll_task = asyncio.create_task(self._poll_conversation())
            self.logger.info(f"Started conversation polling (interval: {self.poll_interval}s)")

        self.logger.info("XiaoAi client started")

        # 等待退出信号
        while self._running:
            await asyncio.sleep(1)

    def stop(self) -> None:
        """停止客户端"""
        self._running = False


async def main():
    parser = argparse.ArgumentParser(description="XiaoAi Pro Notice Client")
    parser.add_argument(
        "--config",
        "-c",
        default="config.yaml",
        help="Path to config file (default: config.yaml)",
    )
    parser.add_argument(
        "--log-level",
        "-l",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Log level (default: INFO)",
    )
    args = parser.parse_args()

    setup_logging(args.log_level)

    client = XiaoAiClient(args.config)

    # 信号处理
    loop = asyncio.get_running_loop()

    def signal_handler():
        logging.getLogger("xiaoai").info("Received shutdown signal")
        client.stop()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, signal_handler)
        except NotImplementedError:
            # Windows 不支持 add_signal_handler
            signal.signal(sig, lambda s, f: signal_handler())

    try:
        await client.run()
    finally:
        await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
