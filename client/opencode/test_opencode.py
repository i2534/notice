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
from unittest.mock import AsyncMock, MagicMock, patch

import httpx

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


# ── Subagent 过滤 ──


class TestSubagentFilter(TestCase):

    def test_empty_dict(self):
        self.assertFalse(OpenCodeClient._is_subagent_session({}))

    def test_agent_explore(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"agent": "explore"}))

    def test_agent_sisyphus_junior(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"agent": "Sisyphus-Junior"}))

    def test_agent_oracle(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"agent": "oracle"}))

    def test_agent_user_not_subagent(self):
        self.assertFalse(OpenCodeClient._is_subagent_session({"agent": "user"}))

    def test_title_subagent_pattern(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"title": "(@general-purpose subagent)"}))

    def test_title_look_at_prefix(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"title": "look_at: foo"}))

    def test_title_user_question(self):
        self.assertFalse(OpenCodeClient._is_subagent_session({"title": "user question"}))

    def test_agent_zwsp_stripped(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"agent": "\u200bexplore"}))

    def test_title_zwsp_replaced(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"title": "\u200blook_at: foo"}))

    def test_empty_strings(self):
        self.assertFalse(OpenCodeClient._is_subagent_session({"agent": "", "title": ""}))

    def test_title_with_leading_text(self):
        self.assertTrue(OpenCodeClient._is_subagent_session({"title": "(@ code subagent)"}))


# ── _extract_new_text ──


class TestExtractNewText(IsolatedAsyncioTestCase):

    async def asyncSetUp(self):
        self.tmpdir = tempfile.mkdtemp()
        config_path = os.path.join(self.tmpdir, "config.yaml")
        Path(config_path).write_text("mqtt:\n  broker_url: 'tcp://localhost:1883'\n  token: 't'\n", encoding="utf-8")
        self.client = OpenCodeClient(config_path)

    async def asyncTearDown(self):
        shutil.rmtree(self.tmpdir)

    def _msg(self, role, mid=None, text=None, part_type="text"):
        m = {"info": {"role": role}}
        if mid is not None:
            m["info"]["id"] = mid
        m["parts"] = [{"type": part_type}]
        if text is not None:
            m["parts"][0]["text"] = text
        return m

    def test_empty_messages(self):
        self.assertEqual(self.client._extract_new_text([], set()), "")

    def test_no_baseline_one_message(self):
        msgs = [self._msg("assistant", mid="m1", text="hello")]
        self.assertEqual(self.client._extract_new_text(msgs, set()), "hello")

    def test_all_in_baseline(self):
        msgs = [self._msg("assistant", mid="m1", text="hello")]
        self.assertEqual(self.client._extract_new_text(msgs, {"m1"}), "")

    def test_mix_new_and_old(self):
        msgs = [
            self._msg("assistant", mid="m1", text="old"),
            self._msg("assistant", mid="m2", text="new"),
        ]
        self.assertEqual(self.client._extract_new_text(msgs, {"m1"}), "new")

    def test_non_text_parts_ignored(self):
        m = self._msg("assistant", mid="m1", text="hello", part_type="image")
        m["parts"][0]["text"] = "should be ignored"
        result = self.client._extract_new_text([m], set())
        self.assertEqual(result, "")

    def test_assistant_and_system_both_included(self):
        msgs = [
            self._msg("assistant", mid="m1", text="from assistant"),
            self._msg("system", mid="m2", text="from system"),
        ]
        result = self.client._extract_new_text(msgs, set())
        self.assertIn("from assistant", result)
        self.assertIn("from system", result)

    def test_user_role_filtered(self):
        msgs = [self._msg("user", mid="m1", text="user msg")]
        self.assertEqual(self.client._extract_new_text(msgs, set()), "")

    def test_message_without_id_treated_as_new(self):
        msgs = [self._msg("assistant", text="no id")]
        result = self.client._extract_new_text(msgs, {"any_id"})
        self.assertEqual(result, "no id")


# ── _publish / _publish_typing / _publish_error ──


class TestPublish(IsolatedAsyncioTestCase):

    async def asyncSetUp(self):
        self.tmpdir = tempfile.mkdtemp()
        config_path = os.path.join(self.tmpdir, "config.yaml")
        Path(config_path).write_text("mqtt:\n  broker_url: 'tcp://localhost:1883'\n  token: 't'\n", encoding="utf-8")
        self.client = OpenCodeClient(config_path)
        self.client._connected = True
        self.client._mqtt_client = MagicMock()
        self.client._mqtt_client.publish = MagicMock()
        self.client._loop = asyncio.get_running_loop()
        self.published_payloads = []

        def capture(topic, payload, qos):
            self.published_payloads.append(json.loads(payload))
        self.client._mqtt_client.publish = capture

    async def asyncTearDown(self):
        shutil.rmtree(self.tmpdir)

    async def test_not_connected_no_publish(self):
        self.client._connected = False
        await self.client._publish("test")
        self.assertEqual(self.published_payloads, [])

    async def test_short_content_no_encoding(self):
        await self.client._publish("short text")
        p = self.published_payloads[0]
        self.assertEqual(p["content"], "short text")
        self.assertNotIn("content_encoding", p)

    async def test_long_content_gzip_encoded(self):
        await self.client._publish("a" * 300)
        p = self.published_payloads[0]
        self.assertEqual(p["content_encoding"], "gzip+base64")
        self.assertEqual(decode_content(p["content"], p["content_encoding"]), "a" * 300)

    async def test_extra_propagates(self):
        await self.client._publish("hello", {"status": "custom", "extra_key": "val"})
        p = self.published_payloads[0]
        self.assertEqual(p["extra"]["status"], "custom")
        self.assertEqual(p["extra"]["extra_key"], "val")

    async def test_publish_typing(self):
        await self.client._publish_typing()
        p = self.published_payloads[0]
        self.assertEqual(p["content"], "⏳ 处理中...")
        self.assertEqual(p["extra"]["status"], "typing")

    async def test_publish_error(self):
        await self.client._publish_error("出错啦")
        p = self.published_payloads[0]
        self.assertEqual(p["content"], "❌ 出错啦")
        self.assertEqual(p["extra"]["status"], "error")

    async def test_payload_metadata(self):
        await self.client._publish("hi")
        p = self.published_payloads[0]
        self.assertEqual(p["client"], "opencode")
        self.assertIsInstance(p["timestamp"], int)
        self.assertEqual(p["title"], "")


# ── 命令处理器 ──


class TestCommandHandlers(IsolatedAsyncioTestCase):

    async def asyncSetUp(self):
        self.tmpdir = tempfile.mkdtemp()
        config_path = os.path.join(self.tmpdir, "config.yaml")
        Path(config_path).write_text("mqtt:\n  broker_url: 'tcp://localhost:1883'\n  token: 't'\n", encoding="utf-8")
        self.client = OpenCodeClient(config_path)
        self.client._connected = True
        self.client._mqtt_client = MagicMock()
        self.client._mqtt_client.publish = MagicMock()
        self.client._loop = asyncio.get_running_loop()
        self.published = []

        async def capture(content, extra=None):
            self.published.append({"content": content, "extra": extra})
        self.client._publish = capture
        self.client._publish_typing = capture
        self.client._publish_error = capture

    async def asyncTearDown(self):
        shutil.rmtree(self.tmpdir)

    # ── _cmd_dir ──

    async def test_cmd_dir_no_arg_shows_current(self):
        self.client._active_dir = "/tmp/proj"
        self.client._session_id = "ses_abcdef123456"
        await self.client._cmd_dir(None)
        msg = self.published[-1]["content"]
        self.assertIn("当前目录", msg)
        self.assertIn("/tmp/proj", msg)
        self.assertIn("ses_abcdef12...", msg)

    async def test_cmd_dir_no_arg_no_session(self):
        self.client._active_dir = "/tmp/proj"
        self.client._session_id = None
        await self.client._cmd_dir(None)
        msg = self.published[-1]["content"]
        self.assertIn("N/A", msg)

    async def test_cmd_dir_switch_success(self):
        real_dir = tempfile.mkdtemp()
        try:
            self.client._query_sessions = AsyncMock(return_value=[{"id": "ses_newid12345"}])
            await self.client._cmd_dir(real_dir)
            self.assertEqual(self.client._active_dir, real_dir)
            self.assertEqual(self.client._session_id, "ses_newid12345")
            msg = self.published[-1]["content"]
            self.assertIn("已切换", msg)
            self.assertIn(real_dir, msg)
        finally:
            shutil.rmtree(real_dir)

    async def test_cmd_dir_not_a_dir(self):
        await self.client._cmd_dir("/nonexistent/path/xyz")
        msg = self.published[-1]["content"]
        self.assertIn("目录不存在", msg)

    async def test_cmd_dir_no_sessions(self):
        real_dir = tempfile.mkdtemp()
        try:
            self.client._query_sessions = AsyncMock(return_value=[])
            await self.client._cmd_dir(real_dir)
            msg = self.published[-1]["content"]
            self.assertIn("下无 session", msg)
        finally:
            shutil.rmtree(real_dir)

    # ── _cmd_new ──

    async def test_cmd_new_success(self):
        self.client._create_session_in_dir = AsyncMock(return_value={"id": "ses_new789"})
        await self.client._cmd_new(None)
        self.assertEqual(self.client._session_id, "ses_new789")
        msg = self.published[-1]["content"]
        self.assertIn("新 Session", msg)
        self.assertIn("ses_new789...", msg)

    async def test_cmd_new_failure(self):
        self.client._create_session_in_dir = AsyncMock(return_value=None)
        await self.client._cmd_new(None)
        msg = self.published[-1]["content"]
        self.assertIn("创建 session 失败", msg)

    # ── _cmd_list ──

    async def test_cmd_list_no_sessions(self):
        self.client._query_sessions = AsyncMock(return_value=[])
        await self.client._cmd_list(None)
        msg = self.published[-1]["content"]
        self.assertIn("无 Session", msg)

    async def test_cmd_list_with_sessions_and_marker(self):
        self.client._session_id = "ses_active123"
        self.client._query_sessions = AsyncMock(return_value=[
            {"id": "ses_active123", "title": "current"},
            {"id": "ses_other456", "title": "other"},
        ])
        await self.client._cmd_list(None)
        msg = self.published[-1]["content"]
        self.assertIn("Session 列表", msg)
        self.assertIn("ses_active12...", msg)
        self.assertIn("ses_other456...", msg)
        active_row = next(line for line in msg.split("\n") if "ses_active12" in line)
        self.assertIn("← 当前", active_row)

    async def test_cmd_list_filters_subagent(self):
        self.client._query_sessions = AsyncMock(return_value=[
            {"id": "ses_user1", "title": "user", "agent": "user"},
            {"id": "ses_sub2", "title": "sub", "agent": "explore"},
        ])
        await self.client._cmd_list(None)
        msg = self.published[-1]["content"]
        self.assertIn("ses_user1", msg)
        self.assertNotIn("ses_sub2", msg)

    # ── _cmd_switch ──

    async def test_cmd_switch_no_arg(self):
        await self.client._cmd_switch(None)
        msg = self.published[-1]["content"]
        self.assertIn("/switch 需要 session ID", msg)

    async def test_cmd_switch_no_match(self):
        self.client._query_sessions = AsyncMock(return_value=[{"id": "ses_abc123"}])
        await self.client._cmd_switch("xxx")
        msg = self.published[-1]["content"]
        self.assertIn("未找到匹配", msg)

    async def test_cmd_switch_multiple_matches(self):
        self.client._query_sessions = AsyncMock(return_value=[
            {"id": "ses_abc123"},
            {"id": "ses_abc456"},
        ])
        await self.client._cmd_switch("ses_abc")
        msg = self.published[-1]["content"]
        self.assertIn("多个 session 匹配", msg)

    async def test_cmd_switch_one_match(self):
        self.client._query_sessions = AsyncMock(return_value=[{"id": "ses_abc123"}])
        await self.client._cmd_switch("ses_abc")
        self.assertEqual(self.client._session_id, "ses_abc123")
        msg = self.published[-1]["content"]
        self.assertIn("已切换", msg)
        self.assertIn("ses_abc123...", msg)

    # ── _cmd_model ──

    async def test_cmd_model_list_success(self):
        self.client._list_models = AsyncMock(return_value=["deepseek/deepseek-chat", "openai/gpt-4"])
        await self.client._cmd_model("list")
        msg = self.published[-1]["content"]
        self.assertIn("可用模型 (2)", msg)
        self.assertIn("deepseek/deepseek-chat", msg)

    async def test_cmd_model_list_empty(self):
        self.client._list_models = AsyncMock(return_value=[])
        await self.client._cmd_model("list")
        msg = self.published[-1]["content"]
        self.assertIn("无法获取模型列表", msg)

    async def test_cmd_model_set_valid(self):
        await self.client._cmd_model("deepseek/deepseek-chat")
        self.assertEqual(self.client.oc.model, "deepseek/deepseek-chat")
        msg = self.published[-1]["content"]
        self.assertIn("模型已切换", msg)
        self.assertIn("deepseek/deepseek-chat", msg)

    async def test_cmd_model_set_invalid_format(self):
        await self.client._cmd_model("no-slash-here")
        msg = self.published[-1]["content"]
        self.assertIn("模型格式错误", msg)

    async def test_cmd_model_query_no_session(self):
        self.client._session_id = None
        await self.client._cmd_model(None)
        msg = self.published[-1]["content"]
        self.assertIn("没有活跃的 session", msg)

    async def test_cmd_model_query_with_session(self):
        self.client._session_id = "ses_xyz"
        self.client._get_session_model = AsyncMock(return_value="deepseek/deepseek-chat")
        await self.client._cmd_model(None)
        msg = self.published[-1]["content"]
        self.assertIn("当前模型", msg)
        self.assertIn("deepseek/deepseek-chat", msg)

    async def test_cmd_model_query_no_model(self):
        self.client._session_id = "ses_xyz"
        self.client._get_session_model = AsyncMock(return_value=None)
        await self.client._cmd_model(None)
        msg = self.published[-1]["content"]
        self.assertIn("无法获取模型信息", msg)


# ── HTTP 方法 (MockTransport) ──


class _HttpCase(IsolatedAsyncioTestCase):

    async def asyncSetUp(self):
        self.tmpdir = tempfile.mkdtemp()
        config_path = os.path.join(self.tmpdir, "config.yaml")
        Path(config_path).write_text(
            "mqtt:\n  broker_url: 'tcp://localhost:1883'\n  token: 't'\n"
            "opencode:\n  server_url: 'http://localhost:4096'\n",
            encoding="utf-8",
        )
        self.client = OpenCodeClient(config_path)
        self.client._connected = True
        self.requests = []

        def handler(req: httpx.Request) -> httpx.Response:
            self.requests.append((req.method, req.url, req.content))
            return httpx.Response(200, json={})

        self.client._http = httpx.AsyncClient(transport=httpx.MockTransport(handler))

    async def asyncTearDown(self):
        if self.client._http is not None:
            await self.client._http.aclose()
        shutil.rmtree(self.tmpdir)


class TestCheckHealth(IsolatedAsyncioTestCase):

    async def asyncSetUp(self):
        self.tmpdir = tempfile.mkdtemp()
        config_path = os.path.join(self.tmpdir, "config.yaml")
        Path(config_path).write_text(
            "mqtt:\n  broker_url: 'tcp://localhost:1883'\n  token: 't'\n"
            "opencode:\n  server_url: 'http://localhost:4096'\n",
            encoding="utf-8",
        )
        self.client = OpenCodeClient(config_path)

    async def asyncTearDown(self):
        shutil.rmtree(self.tmpdir)

    def _patch_client(self, handler):
        mock_client = MagicMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)
        mock_client.get = AsyncMock(side_effect=handler)
        return patch("httpx.AsyncClient", return_value=mock_client)

    async def test_health_ok(self):
        async def fake_get(url, timeout=None):
            resp = MagicMock()
            resp.raise_for_status = MagicMock()
            resp.json.return_value = {"status": "ok"}
            return resp
        with self._patch_client(fake_get):
            result = await self.client._check_opencode_health()
        self.assertTrue(result)

    async def test_health_500(self):
        async def fake_get(url, timeout=None):
            resp = MagicMock()
            resp.raise_for_status.side_effect = httpx.HTTPStatusError(
                "500", request=MagicMock(), response=MagicMock(status_code=500)
            )
            return resp
        with self._patch_client(fake_get):
            result = await self.client._check_opencode_health()
        self.assertFalse(result)

    async def test_health_exception(self):
        async def fake_get(url, timeout=None):
            raise httpx.ConnectError("conn refused")
        with self._patch_client(fake_get):
            result = await self.client._check_opencode_health()
        self.assertFalse(result)


class TestQuerySessions(_HttpCase):
    async def test_returns_sorted_desc(self):
        def handler(req):
            self.requests.append((req.method, req.url, req.content))
            return httpx.Response(200, json=[
                {"id": "ses_old", "time": {"created": 1000}},
                {"id": "ses_new", "time": {"created": 3000}},
                {"id": "ses_mid", "time": {"created": 2000}},
            ])
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._query_sessions(directory="/tmp/proj")
        self.assertEqual([s["id"] for s in result], ["ses_new", "ses_mid", "ses_old"])
        self.assertEqual(self.requests[0][0], "GET")
        self.assertIn("directory=", str(self.requests[0][1]))
        self.assertIn("limit=", str(self.requests[0][1]))

    async def test_404_returns_empty(self):
        def handler(req):
            return httpx.Response(404)
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._query_sessions(directory="/no/such/dir")
        self.assertEqual(result, [])

    async def test_500_returns_empty(self):
        def handler(req):
            return httpx.Response(500, text="oops")
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._query_sessions(directory="/tmp")
        self.assertEqual(result, [])


class TestCreateSession(_HttpCase):
    async def test_create_success(self):
        def handler(req):
            return httpx.Response(200, json={"id": "ses_new_xyz"})
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._create_session_in_dir("/tmp/proj")
        self.assertEqual(result["id"], "ses_new_xyz")

    async def test_create_500(self):
        def handler(req):
            return httpx.Response(500, text="err")
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._create_session_in_dir("/tmp/proj")
        self.assertIsNone(result)

    async def test_create_includes_model_when_set(self):
        self.client.oc.model = "deepseek/deepseek-chat"
        bodies = []

        def handler(req):
            bodies.append(json.loads(req.content))
            return httpx.Response(200, json={"id": "ses_x"})

        self.client._http._transport = httpx.MockTransport(handler)
        await self.client._create_session_in_dir("/tmp/proj")
        self.assertEqual(bodies[0]["model"], {"modelID": "deepseek-chat", "providerID": "deepseek"})
        self.assertEqual(bodies[0]["directory"], "/tmp/proj")

    async def test_create_omits_model_when_unset(self):
        bodies = []

        def handler(req):
            bodies.append(json.loads(req.content))
            return httpx.Response(200, json={"id": "ses_x"})

        self.client._http._transport = httpx.MockTransport(handler)
        await self.client._create_session_in_dir("/tmp/proj")
        self.assertNotIn("model", bodies[0])
        self.assertEqual(bodies[0]["directory"], "/tmp/proj")


class TestGetSessionModel(_HttpCase):
    async def test_returns_model(self):
        def handler(req):
            return httpx.Response(200, json={"id": "ses_x", "model": {"providerID": "deepseek", "id": "deepseek-chat"}})
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._get_session_model("ses_x")
        self.assertEqual(result, "deepseek/deepseek-chat")

    async def test_no_model_returns_none(self):
        def handler(req):
            return httpx.Response(200, json={"id": "ses_x"})
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._get_session_model("ses_x")
        self.assertIsNone(result)

    async def test_404_returns_none(self):
        def handler(req):
            return httpx.Response(404)
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._get_session_model("ses_x")
        self.assertIsNone(result)


class TestValidateAndRecreate(_HttpCase):
    async def test_200_returns_true_unchanged(self):
        def handler(req):
            return httpx.Response(200, json={"id": "ses_x"})
        self.client._http._transport = httpx.MockTransport(handler)
        self.client._session_id = "ses_x"
        self.client._create_session_in_dir = AsyncMock()
        result = await self.client._validate_and_recreate_session()
        self.assertTrue(result)
        self.client._create_session_in_dir.assert_not_called()

    async def test_404_recreates(self):
        def handler(req):
            return httpx.Response(404)
        self.client._http._transport = httpx.MockTransport(handler)
        self.client._session_id = "ses_old"
        self.client._create_session_in_dir = AsyncMock(return_value={"id": "ses_new"})
        result = await self.client._validate_and_recreate_session()
        self.assertTrue(result)
        self.assertEqual(self.client._session_id, "ses_new")

    async def test_404_recreate_fails(self):
        def handler(req):
            return httpx.Response(404)
        self.client._http._transport = httpx.MockTransport(handler)
        self.client._session_id = "ses_old"
        self.client._create_session_in_dir = AsyncMock(return_value=None)
        result = await self.client._validate_and_recreate_session()
        self.assertFalse(result)

    async def test_exception_returns_false(self):
        def handler(req):
            raise httpx.ConnectError("conn refused")
        self.client._http._transport = httpx.MockTransport(handler)
        self.client._session_id = "ses_x"
        result = await self.client._validate_and_recreate_session()
        self.assertFalse(result)


# ── 轮询 ──


class TestPolling(_HttpCase):
    async def test_poll_stabilizes(self):
        poll_count = [0]

        def handler(req):
            poll_count[0] += 1
            if poll_count[0] == 1:
                msgs = [
                    {"info": {"role": "assistant", "id": "old1"}, "parts": [{"type": "text", "text": "old"}]},
                ]
            else:
                msgs = [
                    {"info": {"role": "assistant", "id": "old1"}, "parts": [{"type": "text", "text": "old"}]},
                    {"info": {"role": "assistant", "id": "new1"}, "parts": [{"type": "text", "text": "the answer"}]},
                ]
            return httpx.Response(200, json=msgs)

        self.client._http._transport = httpx.MockTransport(handler)
        published = []
        self.client._publish = AsyncMock(side_effect=lambda c, e=None: published.append(c))

        with patch("asyncio.sleep", new=AsyncMock()):
            baseline = {"old1"}
            result = await self.client._poll_response("ses_x", timeout=30, baseline_ids=baseline)

        self.assertEqual(result, "the answer")
        self.assertIn("the answer", published)

    async def test_poll_timeout_returns_final(self):
        def handler(req):
            return httpx.Response(200, json=[
                {"info": {"role": "assistant", "id": "new1"}, "parts": [{"type": "text", "text": "partial"}]},
            ])
        self.client._http._transport = httpx.MockTransport(handler)
        published = []
        self.client._publish = AsyncMock(side_effect=lambda c, e=None: published.append(c))

        with patch("asyncio.sleep", new=AsyncMock()):
            with patch("time.monotonic", side_effect=[0, 0, 0, 0, 0, 0, 200]):
                result = await self.client._poll_response("ses_x", timeout=10, baseline_ids=set())

        self.assertEqual(result, "partial")

    async def test_poll_no_http_returns_none(self):
        self.client._http = None
        result = await self.client._poll_response("ses_x")
        self.assertIsNone(result)

    async def test_poll_passes_directory_param(self):
        urls = []

        def handler(req):
            urls.append(str(req.url))
            return httpx.Response(200, json=[])

        self.client._http._transport = httpx.MockTransport(handler)
        self.client._active_dir = "/tmp/myproject"

        with patch("asyncio.sleep", new=AsyncMock()):
            with patch("time.monotonic", side_effect=[0, 0, 0, 0, 0, 0, 200]):
                await self.client._poll_response("ses_x", timeout=5, baseline_ids=set())

        self.assertTrue(any("directory=" in u and "limit=30" in u for u in urls))

    async def test_baseline_ids(self):
        def handler(req):
            return httpx.Response(200, json=[
                {"info": {"role": "assistant", "id": "a1"}, "parts": []},
                {"info": {"role": "system", "id": "s1"}, "parts": []},
                {"info": {"role": "user", "id": "u1"}, "parts": []},
            ])
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._get_baseline_ids("ses_x")
        self.assertEqual(result, {"a1", "s1"})

    async def test_baseline_ids_no_http(self):
        self.client._http = None
        result = await self.client._get_baseline_ids("ses_x")
        self.assertEqual(result, set())

    async def test_baseline_ids_empty_messages(self):
        def handler(req):
            return httpx.Response(200, json=[])
        self.client._http._transport = httpx.MockTransport(handler)
        result = await self.client._get_baseline_ids("ses_x")
        self.assertEqual(result, set())


# ── main() 入口 ──


class TestMainEntry(TestCase):

    def _make_mock_client(self):
        instance = MagicMock(spec=OpenCodeClient)
        instance.connect = AsyncMock(return_value=True)
        instance.run = AsyncMock(return_value=None)
        instance.disconnect = AsyncMock(return_value=None)
        instance.stop = MagicMock()
        return instance

    def test_default_args(self):
        instance = self._make_mock_client()
        with patch("sys.argv", ["opencode.py"]), \
             patch("opencode.OpenCodeClient", return_value=instance) as mock_client:
            from opencode import main
            asyncio.run(main())
            mock_client.assert_called_once_with("config.yaml")

    def test_custom_config(self):
        instance = self._make_mock_client()
        with patch("sys.argv", ["opencode.py", "--config", "/tmp/my.yaml"]), \
             patch("opencode.OpenCodeClient", return_value=instance) as mock_client:
            from opencode import main
            asyncio.run(main())
            mock_client.assert_called_once_with("/tmp/my.yaml")

    def test_log_level_debug(self):
        instance = self._make_mock_client()
        root = logging.getLogger()
        original_level = root.level
        for h in list(root.handlers):
            root.removeHandler(h)
        root.setLevel(logging.NOTSET)
        try:
            with patch("sys.argv", ["opencode.py", "--log-level", "DEBUG"]), \
                 patch("opencode.OpenCodeClient", return_value=instance):
                from opencode import main
                asyncio.run(main())
            self.assertEqual(logging.getLogger().level, logging.DEBUG)
        finally:
            root.setLevel(original_level)

    def test_invalid_log_level_exits(self):
        with patch("sys.argv", ["opencode.py", "--log-level", "BOGUS"]):
            from opencode import main
            with self.assertRaises(SystemExit):
                asyncio.run(main())


if __name__ == "__main__":
    import unittest
    unittest.main()
