"""Notice 媒体上传与 Markdown 组装。"""

from __future__ import annotations

import json
import logging
import mimetypes
import os
import time
from typing import Optional
from urllib import request as urllib_request

logger = logging.getLogger("gateway.platforms.notice")


def guess_content_type(path: str) -> str:
    ctype, _ = mimetypes.guess_type(path)
    return ctype or "application/octet-stream"


def upload_file(
    server_url: str,
    token: str,
    file_path: str,
    *,
    timeout: int = 30,
) -> Optional[str]:
    """上传本地文件到 Notice /api/upload，返回可访问 URL。"""
    if not server_url or not os.path.exists(file_path):
        return None

    with open(file_path, "rb") as f:
        file_data = f.read()

    boundary = f"----FormBoundary{int(time.time() * 1000)}"
    filename = os.path.basename(file_path)
    content_type = guess_content_type(file_path)

    body = b"\r\n".join(
        [
            f"--{boundary}".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"'.encode(),
            f"Content-Type: {content_type}".encode(),
            b"",
            file_data,
            f"--{boundary}--".encode(),
            b"",
        ]
    )

    req = urllib_request.Request(
        f"{server_url.rstrip('/')}/api/upload",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )

    with urllib_request.urlopen(req, timeout=timeout) as response:
        response_data = json.loads(response.read().decode())

    if not (response_data.get("success") and response_data.get("image_urls")):
        logger.warning("Upload failed: %s", response_data.get("message", "unknown error"))
        return None

    final_url = response_data["image_urls"][0]
    if not final_url.startswith("http"):
        final_url = server_url.rstrip("/") + "/" + final_url.lstrip("/")
    return final_url


def resolve_media_url(
    path_or_url: str,
    *,
    server_url: Optional[str],
    token: str,
) -> str:
    """远程 URL 原样返回；本地路径则上传。失败时回退原路径。"""
    if path_or_url.startswith(("http://", "https://", "//")):
        return path_or_url
    if not server_url:
        return path_or_url
    if not os.path.exists(path_or_url):
        logger.warning("Media file not found: %s", path_or_url)
        return path_or_url
    try:
        uploaded = upload_file(server_url, token, path_or_url)
        return uploaded or path_or_url
    except Exception as e:
        logger.error("Media upload failed: %s", e)
        return path_or_url


def markdown_with_images(caption: str, urls: list[str]) -> str:
    if not urls:
        return caption
    images_md = "\n\n".join(f"![]({u})" for u in urls)
    if caption.strip():
        return f"{caption.strip()}\n\n{images_md}"
    return images_md
