package broker

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"encoding/json"
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

func TestInjectStoreIDIntoPayload_addsIdAndPreservesFields(t *testing.T) {
	in := []byte(`{"title":"t","content":"c","content_encoding":"gzip+base64","client":"cli"}`)
	out, ok := InjectStoreIDIntoPayload(in, 42)
	if !ok {
		t.Fatal("expected ok")
	}
	var obj map[string]any
	if err := json.Unmarshal(out, &obj); err != nil {
		t.Fatal(err)
	}
	if id, ok := obj["id"].(float64); !ok || id != 42 {
		t.Fatalf("id = %v", obj["id"])
	}
	if obj["title"] != "t" || obj["content"] != "c" {
		t.Fatalf("fields corrupted: %v", obj)
	}
	if obj["content_encoding"] != "gzip+base64" {
		t.Fatalf("content_encoding lost: %v", obj["content_encoding"])
	}
}

func TestInjectStoreIDIntoPayload_overridesClientId(t *testing.T) {
	in := []byte(`{"id":999,"content":"x"}`)
	out, ok := InjectStoreIDIntoPayload(in, 7)
	if !ok {
		t.Fatal("expected ok")
	}
	var obj map[string]any
	if err := json.Unmarshal(out, &obj); err != nil {
		t.Fatal(err)
	}
	if id, ok := obj["id"].(float64); !ok || id != 7 {
		t.Fatalf("id = %v", obj["id"])
	}
}

func TestInjectStoreIDIntoPayload_nonJSON(t *testing.T) {
	in := []byte("plain text")
	out, ok := InjectStoreIDIntoPayload(in, 1)
	if ok {
		t.Fatal("expected !ok for non-JSON")
	}
	if string(out) != "plain text" {
		t.Fatalf("payload mutated: %q", out)
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
