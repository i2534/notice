#!/usr/bin/env python3
"""OpenCode Client 单元测试"""

import asyncio
import json
import logging
import os
import shutil
import tempfile
from pathlib import Path
from unittest import IsolatedAsyncioTestCase, TestCase
from unittest.mock import AsyncMock, MagicMock

from opencode import OpenCodeClient, decode_content, encode_content


class TestContentEncoding(TestCase):
    def test_decode_plain_text(self):
        self.assertEqual(decode_content("hello", None), "hello")
        self.assertEqual(decode_content("hello", ""), "hello")
        self.assertEqual(decode_content("hello", "utf-8"), "hello")

    def test_decode_gzip_base64(self):
        encoded, enc_type = encode_content("a" * 300)
        self.assertEqual(enc_type, "gzip+base64")
        self.assertEqual(decode_content(encoded, enc_type), "a" * 300)

    def test_decode_gzip_base64_invalid(self):
        result = decode_content("not-valid-base64!!!", "gzip+base64")
        self.assertEqual(result, "not-valid-base64!!!")

    def test_decode_empty(self):
        self.assertEqual(decode_content("", "gzip+base64"), "")
        self.assertEqual(decode_content("", None), "")

    def test_encode_short_no_compression(self):
        result, enc_type = encode_content("short")
        self.assertEqual(enc_type, None)
        self.assertEqual(result, "short")

    def test_encode_threshold(self):
        short_255 = "a" * 255
        result, enc_type = encode_content(short_255)
        self.assertEqual(enc_type, None)
        self.assertEqual(result, short_255)

        long_256 = "a" * 256
        result, enc_type = encode_content(long_256)
        self.assertEqual(enc_type, "gzip+base64")
        self.assertEqual(decode_content(result, enc_type), long_256)

    def test_encode_roundtrip(self):
        samples = [
            "a" * 300,
            "Hello 世界! 🌍" * 50,
            json.dumps({"key": "value", "nested": {"a": [1, 2, 3]}}) * 20,
        ]
        for original in samples:
            encoded, enc_type = encode_content(original)
            decoded = decode_content(encoded, enc_type) if enc_type else encoded
            self.assertEqual(decoded, original)

    def test_encode_unicode(self):
        text = "你好世界 🎉" * 50
        encoded, enc_type = encode_content(text)
        decoded = decode_content(encoded, enc_type) if enc_type else encoded
        self.assertEqual(decoded, text)

    def test_encode_newlines(self):
        text = "line1\nline2\nline3\n" * 100
        encoded, enc_type = encode_content(text)
        decoded = decode_content(encoded, enc_type) if enc_type else encoded
        self.assertEqual(decoded, text)


class TestConfigLoading(TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def _write_yaml(self, name: str, content: str) -> str:
        path = os.path.join(self.tmpdir, name)
        Path(path).write_text(content, encoding="utf-8")
        return path

    def test_load_base_config_only(self):
        path = self._write_yaml("config.yaml", "mqtt:\n  broker_url: \"tcp://localhost:1883\"\n  token: \"test\"\n")
        client = OpenCodeClient(path)
        self.assertEqual(client.mqtt.broker_url, "tcp://localhost:1883")
        self.assertEqual(client.mqtt.token, "test")

    def test_load_local_override(self):
        self._write_yaml("config.yaml", "mqtt:\n  broker_url: \"tcp://localhost:1883\"\n  token: \"base-token\"\n  topic: \"notice/default\"\nopencode:\n  server_url: \"http://localhost:4096\"\n")
        self._write_yaml("config.yaml.local", "mqtt:\n  broker_url: \"wss://override.com\"\n  token: \"local-token\"\n")
        base_path = os.path.join(self.tmpdir, "config.yaml")
        client = OpenCodeClient(base_path)
        self.assertEqual(client.mqtt.broker_url, "wss://override.com")
        self.assertEqual(client.mqtt.token, "local-token")
        self.assertEqual(client.mqtt.topic, "notice/default")
        self.assertEqual(client.oc.server_url, "http://localhost:4096")

    def test_load_local_only(self):
        self._write_yaml("config.yaml.local", "mqtt:\n  broker_url: \"ws://local-only.com\"\n  token: \"only\"\n")
        base_path = os.path.join(self.tmpdir, "config.yaml")
        client = OpenCodeClient(base_path)
        self.assertEqual(client.mqtt.broker_url, "ws://local-only.com")
        self.assertEqual(client.mqtt.token, "only")

    def test_load_nonexistent_raises(self):
        with self.assertRaises(FileNotFoundError):
            OpenCodeClient("/nonexistent/path/config.yaml")

    def test_deep_merge_nested(self):
        self._write_yaml("config.yaml", "mqtt:\n  broker_url: \"tcp://base\"\n  token: \"base-token\"\n  topic: \"base-topic\"\n")
        self._write_yaml("config.yaml.local", "mqtt:\n  broker_url: \"wss://local\"\n")
        base_path = os.path.join(self.tmpdir, "config.yaml")
        client = OpenCodeClient(base_path)
        self.assertEqual(client.mqtt.broker_url, "wss://local")
        self.assertEqual(client.mqtt.token, "base-token")
        self.assertEqual(client.mqtt.topic, "base-topic")

    def test_project_dir_resolved(self):
        self._write_yaml("config.yaml", "mqtt:\n  broker_url: \"tcp://localhost\"\n  token: \"t\"\nopencode:\n  project_dir: \"../other\"\n")
        base_path = os.path.join(self.tmpdir, "config.yaml")
        client = OpenCodeClient(base_path)
        self.assertTrue(os.path.isabs(client.oc.project_dir))


class TestMessageHandling(IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmpdir = tempfile.mkdtemp()
        config_path = os.path.join(self.tmpdir, "config.yaml")
        Path(config_path).write_text(
            "mqtt:\n  broker_url: \"tcp://localhost:1883\"\n  token: \"test\"\n"
            "opencode:\n  server_url: \"http://localhost:4096\"\n",
            encoding="utf-8",
        )
        self.client = OpenCodeClient(config_path)
        self.client._connected = True
        self.client._mqtt_client = MagicMock()
        self.client._mqtt_client.publish = MagicMock()

    async def asyncTearDown(self):
        shutil.rmtree(self.tmpdir)

    async def test_skip_own_messages(self):
        published = []

        async def mock_publish(content, extra=None):
            published.append(content)

        self.client._publish = mock_publish
        self.client._publish_error = mock_publish

        await self.client._handle_message("notice/opencode", {"client": "opencode", "content": "test"})
        self.assertEqual(len(published), 0)

    async def test_skip_empty_content(self):
        published = []

        async def mock_publish(content, extra=None):
            published.append(content)

        self.client._publish = mock_publish
        self.client._publish_error = mock_publish

        await self.client._handle_message("notice/opencode", {"client": "other", "content": "   "})
        self.assertEqual(len(published), 0)

    async def test_handle_calls_opencode(self):
        payload = {
            "client": "other",
            "content": "hello world",
        }

        self.client._call_opencode = AsyncMock(return_value="response")
        self.client._publish = AsyncMock()
        self.client._publish_typing = AsyncMock()

        await self.client._handle_message("notice/opencode", payload)
        self.client._call_opencode.assert_called_once_with("hello world")

    async def test_on_message_decodes_gzip(self):
        original = "a" * 300
        encoded, enc_type = encode_content(original)
        raw_payload = json.dumps({
            "client": "other",
            "content": encoded,
            "content_encoding": enc_type,
        })

        self.client._call_opencode = AsyncMock(return_value="response")
        self.client._publish = AsyncMock()
        self.client._publish_typing = AsyncMock()
        self.client._loop = asyncio.get_running_loop()

        mock_msg = MagicMock()
        mock_msg.payload = raw_payload.encode("utf-8")
        mock_msg.topic = "notice/opencode"

        self.client._on_message(self.client._mqtt_client, None, mock_msg)
        await asyncio.sleep(0.1)
        self.client._call_opencode.assert_called_once_with(original)

    async def test_command_help(self):
        published = []

        async def mock_publish(content, extra=None):
            published.append(content)

        self.client._publish = mock_publish
        self.client._publish_error = mock_publish

        await self.client._handle_message("notice/opencode", {"client": "user", "content": "/help"})
        self.assertTrue(any("可用指令" in p for p in published))

    async def test_command_unknown(self):
        published = []

        async def mock_publish(content, extra=None):
            published.append(content)

        self.client._publish = mock_publish
        self.client._publish_error = mock_publish

        await self.client._handle_message("notice/opencode", {"client": "user", "content": "/unknown"})
        self.assertTrue(any("未知指令" in p for p in published))


if __name__ == "__main__":
    import unittest
    unittest.main()
