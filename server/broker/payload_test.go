package broker

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"strings"
	"testing"
)

func TestDecodeMessageContent_plainUnchanged(t *testing.T) {
	msg := &Message{Title: "t", Content: "hello"}
	if err := DecodeMessageContent(msg); err != nil {
		t.Fatal(err)
	}
	if msg.Content != "hello" {
		t.Fatalf("content = %q", msg.Content)
	}
}

func TestDecodeMessageContent_gzipBase64(t *testing.T) {
	plain := "hello 世界\nline2"
	var gz bytes.Buffer
	w := gzip.NewWriter(&gz)
	if _, err := w.Write([]byte(plain)); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	enc := base64.StdEncoding.EncodeToString(gz.Bytes())
	msg := &Message{
		Title:           "x",
		Content:         enc,
		ContentEncoding: ContentEncodingGzipBase64,
	}
	if err := DecodeMessageContent(msg); err != nil {
		t.Fatal(err)
	}
	if msg.ContentEncoding != "" {
		t.Fatalf("encoding should be cleared, got %q", msg.ContentEncoding)
	}
	if msg.Content != plain {
		t.Fatalf("got %q want %q", msg.Content, plain)
	}
}

func TestGzipBase64Encode_roundtrip(t *testing.T) {
	plain := "hello 世界\n" + strings.Repeat("x", 300)
	b64, err := GzipBase64Encode(plain)
	if err != nil {
		t.Fatal(err)
	}
	msg := &Message{
		Content:         b64,
		ContentEncoding: ContentEncodingGzipBase64,
	}
	if err := DecodeMessageContent(msg); err != nil {
		t.Fatal(err)
	}
	if msg.Content != plain {
		t.Fatalf("got %q want %q", msg.Content, plain)
	}
}
