"""Out-of-process Notice 发送（cron / send_message 无 live adapter 时）。"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Optional

from config import load_settings
from media import markdown_with_images, resolve_media_url
from mqtt_transport import create_mqtt_client, make_client_id, publish_qos1
from payload import build_outbound_payload, dumps_payload, resolve_publish_topic

logger = logging.getLogger("gateway.platforms.notice")


async def standalone_send(
    pconfig: Any,
    chat_id: str,
    message: str,
    *,
    thread_id: Optional[str] = None,
    media_files: Optional[list] = None,
    force_document: bool = False,
):
    settings = load_settings(pconfig)
    if not settings.broker_url or not settings.token:
        return {"error": "Notice: brokerUrl and token are required in platform config"}

    content = (message or "").strip()
    media_files = media_files or []
    image_urls: list[str] = []

    for media_path, _is_voice in media_files:
        import os as _os

        if not _os.path.exists(media_path):
            return {"error": f"Notice: media file not found: {media_path}"}
        try:
            loop = asyncio.get_running_loop()
            url = await loop.run_in_executor(
                None,
                lambda p=media_path: resolve_media_url(
                    p, server_url=settings.server_url, token=settings.token
                ),
            )
            image_urls.append(url)
        except Exception as e:
            return {"error": f"Notice: image upload failed: {e}"}

    content = markdown_with_images(content, image_urls)

    mqttc = None
    try:
        connect_ok = asyncio.Event()

        def _on_connect(client, userdata, flags, rc, properties=None):
            connect_ok.set()

        mqttc, ep = create_mqtt_client(
            settings.broker_url,
            settings.token,
            client_id=make_client_id("hermes-standalone"),
            on_connect=_on_connect,
        )

        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, mqttc.connect, ep.host, ep.port, 60)
        mqttc.loop_start()

        try:
            await asyncio.wait_for(connect_ok.wait(), timeout=10.0)
        except asyncio.TimeoutError:
            return {"error": "Notice: MQTT connection timeout"}

        topic_name = resolve_publish_topic(chat_id)
        extra = {"thread_id": thread_id} if thread_id else None
        payload = build_outbound_payload(content, metadata=extra)
        json_payload = dumps_payload(payload)

        result = await loop.run_in_executor(
            None, publish_qos1, mqttc, topic_name, json_payload
        )
        return {"success": True, "message_id": str(result.mid)}
    except asyncio.CancelledError:
        raise
    except Exception as e:
        return {"error": f"Notice standalone send failed: {e}"}
    finally:
        if mqttc is not None:
            try:
                mqttc.loop_stop()
                mqttc.disconnect()
            except Exception:
                pass
