"""流式回复 debounce 合并（MQTT 无 edit_message）。"""

from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable, Optional

logger = logging.getLogger("gateway.platforms.notice")


class ContentCoalescer:
    """若新内容是旧缓冲的前缀扩展则合并；否则先 flush 再缓冲。"""

    def __init__(
        self,
        publish: Callable[[str, str, Optional[dict]], Awaitable],
        *,
        delay: float = 0.3,
        on_flushed: Optional[Callable[[str], None]] = None,
    ):
        self._publish = publish
        self._delay = delay
        self._on_flushed = on_flushed
        self._pending_content: dict[str, str] = {}
        self._pending_meta: dict[str, dict] = {}
        self._tasks: dict[str, asyncio.Task] = {}

    def cancel_all(self) -> None:
        for task in self._tasks.values():
            if not task.done():
                task.cancel()
        self._tasks.clear()
        self._pending_content.clear()
        self._pending_meta.clear()

    async def submit(self, chat_id: str, content: str, metadata: Optional[dict] = None) -> None:
        old_task = self._tasks.pop(chat_id, None)
        if old_task and not old_task.done():
            old_task.cancel()

        old_content = self._pending_content.get(chat_id)
        if old_content is not None and not content.startswith(old_content):
            meta = self._pending_meta.pop(chat_id, None)
            await self._publish(chat_id, old_content, meta)
            logger.debug("Flushed pending for %s", chat_id)

        self._pending_content[chat_id] = content
        if metadata:
            self._pending_meta[chat_id] = metadata

        async def _coalesced() -> None:
            try:
                await asyncio.sleep(self._delay)
                final = self._pending_content.pop(chat_id, None)
                meta = self._pending_meta.pop(chat_id, None) if final is not None else None
                if final is not None:
                    await self._publish(chat_id, final, meta)
                    if self._on_flushed:
                        self._on_flushed(chat_id)
            except asyncio.CancelledError:
                pass

        self._tasks[chat_id] = asyncio.create_task(_coalesced())
