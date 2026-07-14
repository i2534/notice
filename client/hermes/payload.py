"""Notice MQTT JSON 信封：gzip 编解码与出站组包。"""

from __future__ import annotations

import base64
import gzip
import json
import logging
import time
from typing import Any, Optional

logger = logging.getLogger("gateway.platforms.notice")

CONTENT_ENCODING_GZIP_B64 = "gzip+base64"
OUTBOUND_CLIENT = "hermes"
GZIP_MIN_CHARS = 256


def decode_inbound_payload(raw: str | bytes) -> dict[str, Any]:
    """解析 MQTT 正文为 dict，并解压 content_encoding。"""
    if isinstance(raw, bytes):
        text = raw.decode("utf-8", errors="replace")
    else:
        text = raw

    try:
        message_data = json.loads(text)
        if not isinstance(message_data, dict):
            raise json.JSONDecodeError("not an object", text, 0)
    except json.JSONDecodeError:
        return {
            "content": text,
            "title": "",
            "timestamp": int(time.time() * 1000),
            "client": "unknown",
        }

    content = message_data.get("content", "")
    if message_data.get("content_encoding") == CONTENT_ENCODING_GZIP_B64 and content:
        try:
            decoded = base64.b64decode(content)
            message_data["content"] = gzip.decompress(decoded).decode("utf-8")
            message_data.pop("content_encoding", None)
        except Exception as e:
            logger.warning("Failed to decode content: %s", e)
    return message_data


def maybe_gzip_content(content: str) -> tuple[str, Optional[str]]:
    """长文本 gzip+base64；压缩后更短才启用。"""
    if len(content) < GZIP_MIN_CHARS:
        return content, None
    try:
        compressed = gzip.compress(content.encode("utf-8"))
        encoded = base64.b64encode(compressed).decode("ascii")
        if len(encoded) < len(content):
            return encoded, CONTENT_ENCODING_GZIP_B64
    except Exception as e:
        logger.warning("Compression failed: %s", e)
    return content, None


def resolve_publish_topic(chat_id: str) -> str:
    topic = chat_id
    if topic.startswith("topic:"):
        topic = topic[6:]
    return topic


def build_outbound_payload(
    content: str,
    *,
    metadata: Optional[dict] = None,
    timestamp: Optional[int] = None,
    title: str = "",
    client: str = OUTBOUND_CLIENT,
) -> dict[str, Any]:
    """组出站 Notice 信封（明文 content，禁止双重包装）。"""
    encoded, encoding = maybe_gzip_content(content)
    payload: dict[str, Any] = {
        "title": title,
        "content": encoded,
        "client": client,
        "timestamp": timestamp if timestamp is not None else int(time.time() * 1000),
        "extra": dict(metadata) if metadata else {},
    }
    if encoding:
        payload["content_encoding"] = encoding
    return payload


def dumps_payload(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False)
