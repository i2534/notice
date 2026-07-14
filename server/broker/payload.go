package broker

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
)

// ContentEncodingGzipBase64 MQTT JSON 载荷中 content 为 gzip(UTF-8) 再 standard base64 时的 content_encoding 取值。
const ContentEncodingGzipBase64 = "gzip+base64"

// DecodeMessageContent 若 msg 带已知 content_encoding，将 content 解为明文 UTF-8 并清空编码字段；无编码则不变。
func DecodeMessageContent(msg *Message) error {
	if msg == nil {
		return nil
	}
	enc := strings.TrimSpace(msg.ContentEncoding)
	if enc == "" {
		return nil
	}
	if enc != ContentEncodingGzipBase64 {
		return fmt.Errorf("unknown content_encoding: %q", enc)
	}
	raw, err := base64.StdEncoding.DecodeString(msg.Content)
	if err != nil {
		return fmt.Errorf("base64 decode: %w", err)
	}
	r, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		return fmt.Errorf("gzip reader: %w", err)
	}
	defer r.Close()
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(r); err != nil {
		return fmt.Errorf("gzip read: %w", err)
	}
	msg.Content = buf.String()
	msg.ContentEncoding = ""
	return nil
}

// InjectStoreIDIntoPayload 在 JSON 载荷中写入服务端消息 id（覆盖客户端自带的 id）。
// 非 JSON 载荷原样返回，ok=false。尽量保留原字段（含 content_encoding），仅增补 id。
func InjectStoreIDIntoPayload(payload []byte, id uint64) (out []byte, ok bool) {
	var obj map[string]any
	if err := json.Unmarshal(payload, &obj); err != nil {
		return payload, false
	}
	obj["id"] = id
	b, err := json.Marshal(obj)
	if err != nil {
		return payload, false
	}
	return b, true
}

// GzipBase64Encode 将 UTF-8 明文 gzip 压缩后做 standard base64（与 DecodeMessageContent 对偶）。
func GzipBase64Encode(plain string) (string, error) {
	if plain == "" {
		return "", fmt.Errorf("empty plain text")
	}
	var buf bytes.Buffer
	w := gzip.NewWriter(&buf)
	if _, err := w.Write([]byte(plain)); err != nil {
		_ = w.Close()
		return "", err
	}
	if err := w.Close(); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(buf.Bytes()), nil
}
