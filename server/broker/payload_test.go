package broker

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
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
