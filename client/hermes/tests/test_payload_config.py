"""纯函数单测（不依赖 Hermes gateway / 真 MQTT）。"""

from __future__ import annotations

import gzip
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from config import extract_config, load_settings, validate_config  # noqa: E402
from payload import (  # noqa: E402
    build_outbound_payload,
    decode_inbound_payload,
    maybe_gzip_content,
    resolve_publish_topic,
)
from media import markdown_with_images  # noqa: E402


class PayloadTests(unittest.TestCase):
    def test_decode_plain_json(self):
        raw = json.dumps({"content": "hello", "client": "android"})
        msg = decode_inbound_payload(raw)
        self.assertEqual(msg["content"], "hello")
        self.assertEqual(msg["client"], "android")

    def test_decode_gzip(self):
        plain = "x" * 300
        encoded = __import__("base64").b64encode(gzip.compress(plain.encode())).decode()
        raw = json.dumps(
            {"content": encoded, "content_encoding": "gzip+base64", "client": "cli"}
        )
        msg = decode_inbound_payload(raw)
        self.assertEqual(msg["content"], plain)
        self.assertNotIn("content_encoding", msg)

    def test_build_outbound_no_double_wrap(self):
        payload = build_outbound_payload("⚠️ err", metadata={"toUser": "android"})
        self.assertEqual(payload["content"], "⚠️ err")
        self.assertEqual(payload["extra"]["toUser"], "android")
        self.assertEqual(payload["client"], "hermes")

    def test_gzip_threshold(self):
        short, enc = maybe_gzip_content("hi")
        self.assertIsNone(enc)
        self.assertEqual(short, "hi")
        long_text = "a" * 400
        encoded, enc = maybe_gzip_content(long_text)
        self.assertEqual(enc, "gzip+base64")
        self.assertLess(len(encoded), len(long_text))

    def test_resolve_topic(self):
        self.assertEqual(resolve_publish_topic("notice/nga"), "notice/nga")
        self.assertEqual(resolve_publish_topic("topic:notice/nga"), "notice/nga")


class ConfigTests(unittest.TestCase):
    def test_extract_and_validate(self):
        self.assertTrue(
            validate_config({"extra": {"brokerUrl": "wss://x/ws", "token": "t"}})
        )
        self.assertFalse(validate_config({"extra": {"brokerUrl": "wss://x/ws"}}))
        self.assertFalse(validate_config({"extra": {"token": "t"}}))

    def test_load_settings_aliases(self):
        s = load_settings({"extra": {"broker_url": "tcp://localhost:9091", "token": "abc"}})
        self.assertEqual(s.broker_url, "tcp://localhost:9091")
        self.assertEqual(s.token, "abc")

    def test_extract_platform_config_shape(self):
        class Fake:
            extra = {"brokerUrl": "wss://a/ws"}
            token = "tok"

        cfg = extract_config(Fake())
        self.assertEqual(cfg["brokerUrl"], "wss://a/ws")
        self.assertEqual(cfg["token"], "tok")


class MediaTests(unittest.TestCase):
    def test_markdown_with_images(self):
        self.assertEqual(markdown_with_images("", ["http://u/a.png"]), "![](http://u/a.png)")
        self.assertIn("cap", markdown_with_images("cap", ["http://u/a.png"]))


if __name__ == "__main__":
    unittest.main()
