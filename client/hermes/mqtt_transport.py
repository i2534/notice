"""MQTT 连接与发布辅助。"""

from __future__ import annotations

import logging
import random
import time
from dataclasses import dataclass
from typing import Any, Callable, Optional
from urllib.parse import urlparse

logger = logging.getLogger("gateway.platforms.notice")


@dataclass(frozen=True)
class BrokerEndpoint:
    host: str
    port: int
    path: str
    transport: str  # "websockets" | "tcp"
    use_tls: bool


def parse_broker_url(broker_url: str) -> BrokerEndpoint:
    parsed = urlparse(broker_url)
    host = parsed.hostname or "localhost"
    path = parsed.path or "/"
    is_ws = parsed.scheme.startswith("ws")
    use_tls = parsed.scheme in ("wss", "tls", "mqtts")

    port = parsed.port
    if not port:
        if parsed.scheme in ("wss", "tls", "mqtts"):
            port = 443
        elif parsed.scheme in ("ws",):
            port = 80
        else:
            port = 1883

    return BrokerEndpoint(
        host=host,
        port=port,
        path=path,
        transport="websockets" if is_ws else "tcp",
        use_tls=use_tls,
    )


def make_client_id(prefix: str = "hermes") -> str:
    return f"{prefix}-{int(time.time() * 1000)}-{random.randint(1000, 9999)}"


def create_mqtt_client(
    broker_url: str,
    token: str,
    *,
    client_id: Optional[str] = None,
    on_connect: Optional[Callable] = None,
    on_disconnect: Optional[Callable] = None,
    on_message: Optional[Callable] = None,
    userdata: Any = None,
):
    """创建已配置认证/TLS/WS 的 paho Client（未 connect）。"""
    import paho.mqtt.client as mqtt
    from paho.mqtt.enums import CallbackAPIVersion

    ep = parse_broker_url(broker_url)
    client = mqtt.Client(
        callback_api_version=CallbackAPIVersion.VERSION2,
        client_id=client_id or make_client_id(),
        userdata=userdata,
        transport=ep.transport,
    )
    client.reconnect_delay_set(min_delay=1, max_delay=120)
    client.username_pw_set(token, token)

    if ep.use_tls:
        client.tls_set()
        client.tls_insecure_set(False)
    if ep.transport == "websockets":
        client.ws_set_options(path=ep.path)

    if on_connect:
        client.on_connect = on_connect
    if on_disconnect:
        client.on_disconnect = on_disconnect
    if on_message:
        client.on_message = on_message

    return client, ep


def publish_qos1(client, topic: str, payload: str):
    return client.publish(topic, payload, 1)
