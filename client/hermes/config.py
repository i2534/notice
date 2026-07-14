"""Notice 平台配置解析（config.yaml extra + 环境变量）。"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from typing import Any, Optional

logger = logging.getLogger("gateway.platforms.notice")

# Env 优先于 config.yaml（与 ntfy / IRC 等官方插件一致）
ENV_BROKER_URL = "NOTICE_BROKER_URL"
ENV_TOKEN = "NOTICE_TOKEN"
ENV_TOPIC = "NOTICE_TOPIC"
ENV_SERVER_URL = "NOTICE_SERVER_URL"
ENV_HOME_CHANNEL = "NOTICE_HOME_CHANNEL"
ENV_ALLOW_ALL = "NOTICE_ALLOW_ALL_USERS"
ENV_ALLOWED_USERS = "NOTICE_ALLOWED_USERS"


@dataclass(frozen=True)
class NoticeSettings:
    broker_url: str
    token: str
    topic: str = "notice/#"
    server_url: Optional[str] = None
    typing_interval: int = 20
    media_audio: Optional[dict] = None


def extract_config(config: Any) -> dict:
    """从 PlatformConfig / dict 取出 extra 字典，并合并顶层 token。"""
    cfg: dict = {}
    if isinstance(config, dict):
        if "extra" in config and isinstance(config["extra"], dict):
            cfg = dict(config["extra"])
        else:
            cfg = dict(config)
    elif hasattr(config, "extra"):
        if isinstance(config.extra, dict):
            cfg = dict(config.extra)
        if getattr(config, "token", None):
            cfg.setdefault("token", config.token)
    return cfg


def _env(name: str, default: str = "") -> str:
    return (os.getenv(name) or "").strip() or default


def load_settings(config: Any) -> NoticeSettings:
    cfg = extract_config(config)

    broker_url = _env(ENV_BROKER_URL) or str(cfg.get("brokerUrl") or cfg.get("broker_url") or "")
    token = _env(ENV_TOKEN) or str(cfg.get("token") or "")
    topic = _env(ENV_TOPIC) or str(cfg.get("topic") or "notice/#") or "notice/#"
    server_url = _env(ENV_SERVER_URL) or cfg.get("serverUrl") or cfg.get("server_url") or None
    if server_url:
        server_url = str(server_url).rstrip("/")

    raw_interval = cfg.get("typingInterval", cfg.get("typing_interval", 20))
    try:
        typing_interval = int(raw_interval)
    except (TypeError, ValueError):
        typing_interval = 20
    typing_interval = max(5, typing_interval)

    media_audio = cfg.get("mediaAudio") or cfg.get("media_audio") or {}
    if not isinstance(media_audio, dict):
        media_audio = {}

    return NoticeSettings(
        broker_url=broker_url.strip(),
        token=token.strip(),
        topic=topic.strip() or "notice/#",
        server_url=server_url,
        typing_interval=typing_interval,
        media_audio=media_audio,
    )


def check_requirements() -> bool:
    try:
        import paho.mqtt.client  # noqa: F401
    except ImportError:
        logger.warning("paho-mqtt is required. Install with: pip install paho-mqtt")
        return False
    return True


def validate_config(config: Any) -> bool:
    settings = load_settings(config)
    if not settings.broker_url:
        logger.warning("Notice: brokerUrl / NOTICE_BROKER_URL is required")
        return False
    if not settings.token:
        logger.warning("Notice: token / NOTICE_TOKEN is required")
        return False
    return True


def is_connected(config: Any) -> bool:
    """配置层「是否可连」探测（不探测实时 MQTT）。"""
    return validate_config(config)


def env_enablement() -> dict:
    """供 hermes config / gateway 从纯 env 种子 PlatformConfig.extra。"""
    extra: dict[str, Any] = {}
    if broker := _env(ENV_BROKER_URL):
        extra["brokerUrl"] = broker
    if token := _env(ENV_TOKEN):
        extra["token"] = token
    if topic := _env(ENV_TOPIC):
        extra["topic"] = topic
    if server := _env(ENV_SERVER_URL):
        extra["serverUrl"] = server
    return extra
