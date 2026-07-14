import asyncio
import base64
import json
import logging
import os
import re
import subprocess
import tempfile
import urllib.parse
import urllib.request
from abc import ABC, abstractmethod

logger = logging.getLogger(__name__)

_AUDIO_EXT_PATTERN = re.compile(
    r'\.(m4a|mp3|webm|ogg|wav|aac|opus|weba)$', re.IGNORECASE
)
_MEDIA_URL_PATTERN = re.compile(
    r'https?://[^\s"<>]+/api/media/[^\s"<>]+', re.IGNORECASE
)


# --- Backend hierarchy ---


class TranscriberBackend(ABC):
    """语音转写后端基类。"""

    @abstractmethod
    async def transcribe(self, filepath: str) -> str | None:
        ...


class ApiTranscriber(TranscriberBackend, ABC):
    """通用 HTTP API 后端基类。

    子类只需实现 _build_payload / _parse_response 两个方法，
    HTTP 请求、超时、认证等共用逻辑由基类处理。
    """

    def __init__(
        self,
        url: str,
        token: str = "",
        timeout: int = 300,
    ):
        self.url = url
        self.token = token
        self.timeout = max(timeout, 5)

    @abstractmethod
    def _build_payload(self, b64_audio: str, mime: str) -> dict:
        """构建请求体。子类根据各自 API 格式实现。"""
        ...

    @abstractmethod
    def _parse_response(self, raw: bytes) -> str | None:
        """解析 API 响应，提取转写文本。"""
        ...

    async def transcribe(self, filepath: str) -> str | None:
        mime = "audio/wav"
        ext = filepath.rsplit(".", 1)[-1].lower() if "." in filepath else ""
        if ext == "mp3":
            mime = "audio/mpeg"
        try:
            def _read_b64(path):
                with open(path, "rb") as f:
                    return base64.b64encode(f.read()).decode("ascii")

            loop = asyncio.get_running_loop()
            b64 = await loop.run_in_executor(
                None,
                _read_b64,
                filepath,
            )
            payload = self._build_payload(b64, mime)
            body = json.dumps(payload).encode("utf-8")
            text = await loop.run_in_executor(
                None, self._request_sync, body
            )
            return text
        except Exception as e:
            logger.warning("API transcription failed: %s", e)
            return None

    def _request_sync(self, body: bytes) -> str | None:
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(
            self.url,
            data=body,
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            raw = resp.read()
        return self._parse_response(raw)


class QwenApiTranscriber(ApiTranscriber):
    """Qwen3 ASR 后端。

    请求格式: {"messages": [{"role":"user","content":[{"type":"audio_url","audio_url":{"url":"data:..."}}]}]}
    响应格式: {"choices":[{"message":{"content":"<asr_text>...</asr_text>"}}]}
    """

    def _build_payload(self, b64_audio: str, mime: str) -> dict:
        data_url = f"data:{mime};base64,{b64_audio}"
        return {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "audio_url",
                            "audio_url": {"url": data_url},
                        }
                    ],
                }
            ]
        }

    def _parse_response(self, raw: bytes) -> str | None:
        try:
            result = json.loads(raw)
            content = result.get("choices", [{}])[0].get("message", {}).get(
                "content", ""
            )
        except json.JSONDecodeError:
            return None
        if not content or "<asr_text>" not in content:
            return content if content else None
        text = content.split("<asr_text>")[1].split("</asr_text>")[0].strip()
        return text if text else None


class CliTranscriber(TranscriberBackend):
    def __init__(
        self,
        command: str,
        args: list[str] | None = None,
        timeout: int = 60,
    ):
        self.command = command
        self.args = args or []
        self.timeout = max(timeout, 5)

    async def transcribe(self, filepath: str) -> str | None:
        if not os.path.isfile(self.command):
            logger.warning("CLI command not found: %s", self.command)
            return None
        args = [a.replace("{{MediaPath}}", filepath) for a in self.args]
        try:
            loop = asyncio.get_running_loop()
            def _run_cmd():
                return subprocess.Popen(
                    [self.command] + args,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )

            proc = await loop.run_in_executor(None, _run_cmd)
            try:
                stdout, stderr = await asyncio.wait_for(
                    loop.run_in_executor(None, proc.communicate),
                    timeout=self.timeout,
                )
            except asyncio.TimeoutError:
                proc.kill()
                await loop.run_in_executor(None, proc.wait)
                logger.warning(
                    "CLI transcription timed out after %ds", self.timeout
                )
                return None
            text = (
                stdout.decode("utf-8", errors="replace").strip() if stdout else ""
            )
            if stderr:
                err_text = stderr.decode("utf-8", errors="replace").strip()
                logger.debug("CLI transcription stderr: %s", err_text[:200])
            return text if text else None
        except Exception as e:
            logger.warning("CLI transcription failed: %s", e)
            return None


# --- Registry: provider type -> class ---

_API_PROVIDERS: dict[str, type[ApiTranscriber]] = {
    "qwen": QwenApiTranscriber,
}


def register_api_provider(name: str, cls: type[ApiTranscriber]) -> None:
    """注册自定义 API 后端实现。"""
    _API_PROVIDERS[name.lower()] = cls


# --- Facade ---


class Transcriber:
    """语音转写器。组合检测、下载、后端链。"""

    def __init__(self, backends: list[TranscriberBackend] | None = None):
        self._backends = backends or []

    # --- URL detection ---

    def is_audio_url(self, url: str) -> bool:
        if not url or "/api/media" not in url:
            return False
        path_part = url.split("#")[0].split("?")[0]
        if _AUDIO_EXT_PATTERN.search(path_part):
            return True
        qs = url.split("#")[0]
        if "?" in qs:
            query = qs.split("?", 1)[1]
            for part in query.split("&"):
                if "=" in part:
                    key, _, val = part.partition("=")
                    if key.lower() == "n":
                        try:
                            decoded = urllib.parse.unquote(val)
                            if _AUDIO_EXT_PATTERN.search(decoded):
                                return True
                        except Exception:
                            pass
                        break
        return False

    def extract_audio_urls(self, content: str) -> list[str]:
        if not content:
            return []
        matches = _MEDIA_URL_PATTERN.findall(content)
        return [url for url in matches if self.is_audio_url(url)]

    def is_voice_message(self, content: str) -> bool:
        audio_urls = self.extract_audio_urls(content)
        if not audio_urls:
            return False
        rest = content.strip()
        for url in audio_urls:
            rest = rest.replace(url, "").strip()
        return len(rest) == 0

    @property
    def has_backend(self) -> bool:
        return len(self._backends) > 0

    # --- Download ---

    async def download_audio(self, url: str, auth_token: str = "") -> str | None:
        try:
            loop = asyncio.get_running_loop()
            ext = ".m4a"
            path_part = url.split("#")[0].split("?")[0]
            match = _AUDIO_EXT_PATTERN.search(path_part)
            if match:
                ext = "." + match.group(1).lower()

            fd, tmp_path = tempfile.mkstemp(suffix=ext)
            os.close(fd)

            def _do_download():
                req = urllib.request.Request(url)
                if auth_token:
                    req.add_header("Authorization", f"Bearer {auth_token}")
                with urllib.request.urlopen(req, timeout=30) as resp:
                    data = resp.read()
                with open(tmp_path, "wb") as f:
                    f.write(data)
                return tmp_path if data else None

            result = await loop.run_in_executor(None, _do_download)
            return result
        except Exception as e:
            logger.warning("Failed to download audio: %s", e)
            return None

    # --- Transcribe (chain backends) ---

    async def transcribe_file(self, filepath: str) -> str | None:
        for backend in self._backends:
            text = await backend.transcribe(filepath)
            if text:
                return text
        # 回退到 Hermes 全局 STT（stt.provider / built-in）
        return await self._transcribe_via_global_stt(filepath)

    @staticmethod
    async def _transcribe_via_global_stt(filepath: str) -> str | None:
        try:
            from tools.transcription_tools import transcribe_audio
        except ImportError:
            return None
        try:
            loop = asyncio.get_running_loop()
            result = await loop.run_in_executor(None, transcribe_audio, filepath)
            if isinstance(result, dict) and result.get("success") and result.get("transcript"):
                return str(result["transcript"]).strip() or None
            if isinstance(result, dict) and result.get("error"):
                logger.debug("Global STT failed: %s", result.get("error"))
        except Exception as e:
            logger.debug("Global STT unavailable: %s", e)
        return None

    @property
    def can_transcribe(self) -> bool:
        """有本地 backend 时优先；否则仍会尝试全局 STT。"""
        return True
