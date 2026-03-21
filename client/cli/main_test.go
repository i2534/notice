package main

import (
	"strings"
	"testing"
)

func TestMaybeWebhookCompress(t *testing.T) {
	short := "hello"
	c, enc := maybeWebhookCompress(short, 255)
	if enc != "" || c != short {
		t.Fatalf("short text: got enc=%q content=%q", enc, c)
	}
	long := strings.Repeat("a", 400)
	c, enc = maybeWebhookCompress(long, 255)
	if enc != contentEncodingGzipBase64 {
		t.Fatalf("long ascii: want gzip+base64, got %q", enc)
	}
	if len(c) >= len(long) {
		t.Fatalf("compressed should be shorter than plain bytes: %d vs %d", len(c), len(long))
	}
	c, enc = maybeWebhookCompress(long, 0)
	if enc != "" || c != long {
		t.Fatalf("min 0: got enc=%q", enc)
	}
}

func TestDecodeMessageContent_roundtrip(t *testing.T) {
	plain := "你好\n" + strings.Repeat("x", 300)
	content, enc := maybeWebhookCompress(plain, 255)
	if enc == "" {
		t.Fatal("expected compression")
	}
	m := Message{Content: content, ContentEncoding: enc}
	if err := decodeMessageContent(&m); err != nil {
		t.Fatal(err)
	}
	if m.Content != plain {
		t.Fatalf("got %q want %q", m.Content, plain)
	}
	if m.ContentEncoding != "" {
		t.Fatalf("encoding should clear")
	}
}

func TestDecodeMessageContent_unknownEncoding(t *testing.T) {
	m := Message{Content: "eA==", ContentEncoding: "br"}
	err := decodeMessageContent(&m)
	if err == nil {
		t.Fatal("want error")
	}
}
