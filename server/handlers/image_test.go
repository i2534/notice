package handlers

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"notice-server/config"
	"notice-server/ratelimit"
)

const testImageToken = "test-secret-token"

func testImageConfig() *config.Config {
	return &config.Config{
		Auth:    config.AuthConfig{Token: testImageToken},
		Storage: config.StorageConfig{Path: "data"},
		Image: config.ImageConfig{
			Folder:                 "images",
			URLExpirySeconds:       86400,
			CleanupEnabled:         true,
			CleanupIntervalSeconds: 3600,
			MaxUploadBytes:         5 * 1024 * 1024,
			MaxUploadTotalBytes:    20 * 1024 * 1024,
			AllowedExtensions:      []string{".jpg", ".jpeg", ".png", ".gif", ".webp"},
		},
	}
}

// TestRandomImageName_HexVsBase64 对比 16 字节用 hex 与 base64 编码后的长度：base64 更短
func TestRandomImageName_HexVsBase64(t *testing.T) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		t.Fatalf("rand.Read: %v", err)
	}
	hexStr := hex.EncodeToString(b)
	b64Str := base64.RawURLEncoding.EncodeToString(b)

	if len(hexStr) != 32 {
		t.Errorf("hex(16 字节) 应为 32 字符: got %d", len(hexStr))
	}
	if len(b64Str) != 22 {
		t.Errorf("base64.RawURLEncoding(16 字节) 应为 22 字符: got %d", len(b64Str))
	}
	if len(b64Str) >= len(hexStr) {
		t.Errorf("base64 应比 hex 短: hex=%d, base64=%d", len(hexStr), len(b64Str))
	}
	t.Logf("same 16 bytes: hex=%q (%d) vs base64=%q (%d)", hexStr, len(hexStr), b64Str, len(b64Str))
}

// TestSignImageURL 签名 URL 格式与一致性
func TestSignImageURL(t *testing.T) {
	baseURL := "https://notice.example.com"
	path := "a1b2c3d4e5f6.png"
	expires := time.Now().Unix() + 3600
	secret := "my-secret"

	url1 := SignImageURL(baseURL, path, expires, secret)
	url2 := SignImageURL(baseURL, path, expires, secret)

	if url1 != url2 {
		t.Errorf("同一参数应生成相同 URL: %q != %q", url1, url2)
	}
	if !strings.HasPrefix(url1, "https://notice.example.com/api/image?") {
		t.Errorf("URL 应以 base 和 /api/image 开头: %s", url1)
	}
	if !strings.Contains(url1, "n=") || !strings.Contains(url1, "e=") || !strings.Contains(url1, "s=") {
		t.Errorf("URL 应包含短参数 n、e、s: %s", url1)
	}
	url3 := SignImageURL("https://notice.example.com/", path, expires, secret)
	if !strings.HasPrefix(url3, "https://notice.example.com/api/image?") {
		t.Errorf("baseURL 末尾 / 应被去掉: %s", url3)
	}
}

// parseSignedURL 从 SignImageURL 生成的 URL 中解析 n(明文)、e(明文)、s(base64)
func parseSignedURL(u string) (n, eStr, sB64 string, ok bool) {
	_, after, ok0 := strings.Cut(u, "?")
	if !ok0 {
		return "", "", "", false
	}
	for _, part := range strings.Split(after, "&") {
		if strings.HasPrefix(part, "n=") {
			n = part[2:]
		} else if strings.HasPrefix(part, "e=") {
			eStr = part[2:]
		} else if strings.HasPrefix(part, "s=") {
			sB64 = part[2:]
		}
	}
	return n, eStr, sB64, n != "" && eStr != "" && sB64 != ""
}

// TestVerifyImageSignature_Valid 合法签名应通过
func TestVerifyImageSignature_Valid(t *testing.T) {
	secret := "secret"
	name := "test.png"
	expires := time.Now().Unix() + 3600
	signed := SignImageURL("http://x", name, expires, secret)
	n, eStr, sB64, ok := parseSignedURL(signed)
	if !ok {
		t.Fatalf("解析 URL 失败: %s", signed)
	}
	expiresVal, _ := strconv.ParseInt(eStr, 10, 64)
	sBytes, _ := base64.RawURLEncoding.DecodeString(sB64)
	// n 在 URL 中可能被 QueryEscape，解析时需 Unescape
	nUnescaped, _ := url.QueryUnescape(n)
	if !VerifyImageSignature(secret, nUnescaped, expiresVal, sBytes) {
		t.Error("合法签名应通过校验")
	}
}

// TestVerifyImageSignature_InvalidSig 错误签名应不通过
func TestVerifyImageSignature_InvalidSig(t *testing.T) {
	secret := "secret"
	name := "test.png"
	expires := time.Now().Unix() + 3600
	wrongSig := make([]byte, 16)
	if VerifyImageSignature(secret, name, expires, wrongSig) {
		t.Error("错误签名应不通过")
	}
}

// TestVerifyImageSignature_Expired 过期应不通过
func TestVerifyImageSignature_Expired(t *testing.T) {
	secret := "secret"
	name := "test.png"
	expires := time.Now().Unix() - 3600 // 已过期
	signed := SignImageURL("http://x", name, expires, secret)
	n, eStr, sB64, ok := parseSignedURL(signed)
	if !ok {
		t.Fatalf("解析 URL 失败: %s", signed)
	}
	nUnescaped, _ := url.QueryUnescape(n)
	expiresVal, _ := strconv.ParseInt(eStr, 10, 64)
	sBytes, _ := base64.RawURLEncoding.DecodeString(sB64)
	if VerifyImageSignature(secret, nUnescaped, expiresVal, sBytes) {
		t.Error("过期签名应不通过")
	}
}

// TestVerifyImageSignature_InvalidExpires 非法 e（非 8 字节）在 Handler 中会 400，这里仅测过期
func TestVerifyImageSignature_InvalidExpires(t *testing.T) {
	// 过期时间 + 错误长度 sig 不会命中 Verify，Handler 会先解码失败返回 400
	if VerifyImageSignature("s", "p", time.Now().Unix()-1, make([]byte, 16)) {
		t.Error("过期应不通过")
	}
}

// TestImageHandler_MethodNotAllowed 非 GET 返回 405
func TestImageHandler_MethodNotAllowed(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	h := ImageHandler(cfg, dir)
	req := httptest.NewRequest(http.MethodPost, "/api/image?n=x.png&e=9999999999&s=AAAAAAAAAAAAAAAAAAAAAA", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("got status %d, want 405", rec.Code)
	}
}

// TestImageHandler_MissingParams 缺少参数返回 400
func TestImageHandler_MissingParams(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	h := ImageHandler(cfg, dir)
	req := httptest.NewRequest(http.MethodGet, "/api/image", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("got status %d, want 400", rec.Code)
	}
}

// TestImageHandler_PathTraversal 路径穿越返回 400
func TestImageHandler_PathTraversal(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	h := ImageHandler(cfg, dir)
	expires := time.Now().Unix() + 3600
	url := SignImageURL("http://x", "..\\etc\\passwd", expires, testImageToken)
	q := strings.TrimPrefix(url, "http://x/api/image?")
	req := httptest.NewRequest(http.MethodGet, "/api/image?"+q, nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("got status %d, want 400 (path traversal)", rec.Code)
	}
}

// TestImageHandler_Valid 合法请求返回文件内容
func TestImageHandler_Valid(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	imgDir := filepath.Join(dir, "images")
	if err := os.MkdirAll(imgDir, 0755); err != nil {
		t.Fatal(err)
	}
	content := []byte("fake-png-content")
	name := "a1b2c3d4e5f6789012345678abcdef01.png"
	if err := os.WriteFile(filepath.Join(imgDir, name), content, 0644); err != nil {
		t.Fatal(err)
	}
	expires := time.Now().Unix() + 3600
	url := SignImageURL("http://x", name, expires, testImageToken)
	q := strings.TrimPrefix(url, "http://x/api/image?")
	req := httptest.NewRequest(http.MethodGet, "/api/image?"+q, nil)
	rec := httptest.NewRecorder()
	ImageHandler(cfg, dir).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("got status %d, want 200", rec.Code)
	}
	if !bytes.Equal(rec.Body.Bytes(), content) {
		t.Errorf("body mismatch")
	}
}

// buildMultipartUpload 构建带 file 字段的 multipart 请求体
func buildMultipartUpload(files map[string][]byte) (body *bytes.Buffer, contentType string, err error) {
	body = new(bytes.Buffer)
	w := multipart.NewWriter(body)
	for name, data := range files {
		part, err := w.CreateFormFile("file", name)
		if err != nil {
			return nil, "", err
		}
		if _, err := part.Write(data); err != nil {
			return nil, "", err
		}
	}
	if err := w.Close(); err != nil {
		return nil, "", err
	}
	return body, w.FormDataContentType(), nil
}

// TestUploadHandler_Unauthorized 无 token 返回 401
func TestUploadHandler_Unauthorized(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	body, ct, _ := buildMultipartUpload(map[string][]byte{"x.png": []byte("x")})
	req := httptest.NewRequest(http.MethodPost, "/api/upload", body)
	req.Header.Set("Content-Type", ct)
	rec := httptest.NewRecorder()
	UploadHandler(cfg, dir, nil).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("got status %d, want 401", rec.Code)
	}
}

// TestUploadHandler_Success 合法上传返回 200 和 image_urls
func TestUploadHandler_Success(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	body, ct, _ := buildMultipartUpload(map[string][]byte{"photo.png": []byte("png-content")})
	req := httptest.NewRequest(http.MethodPost, "/api/upload", body)
	req.Header.Set("Content-Type", ct)
	req.Header.Set("Authorization", "Bearer "+testImageToken)
	rec := httptest.NewRecorder()
	UploadHandler(cfg, dir, nil).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("got status %d, want 200", rec.Code)
	}
	var out struct {
		Success   bool     `json:"success"`
		ImageURLs []string `json:"image_urls"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if !out.Success || len(out.ImageURLs) != 1 {
		t.Errorf("success=%v image_urls=%v", out.Success, out.ImageURLs)
	}
	// 返回的 URL 中 n 应为 16 字节 base64(22 字符) + .png（明文，可能 query 转义）
	parsedN, _, _, ok := parseSignedURL(out.ImageURLs[0])
	if !ok {
		t.Fatal("parse signed url")
	}
	parsedName, _ := url.QueryUnescape(parsedN)
	if len(parsedName) != 22+4 || !strings.HasSuffix(parsedName, ".png") {
		t.Errorf("name 应为 base64(22 字符) + .png: %s", parsedName)
	}
}

// TestUploadHandler_DisallowedExt 不允许的扩展名返回 400
func TestUploadHandler_DisallowedExt(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	body, ct, _ := buildMultipartUpload(map[string][]byte{"x.exe": []byte("exe")})
	req := httptest.NewRequest(http.MethodPost, "/api/upload", body)
	req.Header.Set("Content-Type", ct)
	req.Header.Set("Authorization", "Bearer "+testImageToken)
	rec := httptest.NewRecorder()
	UploadHandler(cfg, dir, nil).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("got status %d, want 400", rec.Code)
	}
}

// TestUploadHandler_MissingFile 缺少 file 返回 400
func TestUploadHandler_MissingFile(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	body := bytes.NewBufferString("")
	req := httptest.NewRequest(http.MethodPost, "/api/upload", body)
	req.Header.Set("Content-Type", "multipart/form-data; boundary=xxx")
	req.Header.Set("Authorization", "Bearer "+testImageToken)
	rec := httptest.NewRecorder()
	UploadHandler(cfg, dir, nil).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("got status %d, want 400", rec.Code)
	}
}

// TestUploadHandler_RateLimit 触发限流后返回 429
func TestUploadHandler_RateLimit(t *testing.T) {
	cfg := testImageConfig()
	dir := t.TempDir()
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures: 2,
		BlockTime:   2 * time.Second,
		WindowTime:  60 * time.Second,
	})
	body, ct, _ := buildMultipartUpload(map[string][]byte{"x.png": []byte("x")})
	h := UploadHandler(cfg, dir, limiter)

	// 两次错误 token -> 401
	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodPost, "/api/upload", bytes.NewReader(body.Bytes()))
		req.RemoteAddr = "192.168.1.1:1234"
		req.Header.Set("Content-Type", ct)
		req.Header.Set("Authorization", "Bearer wrong")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Errorf("request %d: got %d, want 401", i+1, rec.Code)
		}
	}
	// 第三次应 429
	req := httptest.NewRequest(http.MethodPost, "/api/upload", bytes.NewReader(body.Bytes()))
	req.RemoteAddr = "192.168.1.1:1234"
	req.Header.Set("Content-Type", ct)
	req.Header.Set("Authorization", "Bearer wrong")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("got status %d, want 429", rec.Code)
	}
}

// TestRunImageCleanup 删除过期文件
func TestRunImageCleanup(t *testing.T) {
	dir := t.TempDir()
	imgDir := filepath.Join(dir, "images")
	if err := os.MkdirAll(imgDir, 0755); err != nil {
		t.Fatal(err)
	}
	oldFile := filepath.Join(imgDir, "old.png")
	if err := os.WriteFile(oldFile, []byte("old"), 0644); err != nil {
		t.Fatal(err)
	}
	// 将修改时间设为过去
	past := time.Now().Add(-48 * time.Hour)
	if err := os.Chtimes(oldFile, past, past); err != nil {
		t.Fatal(err)
	}
	cfg := testImageConfig()
	cfg.Image.URLExpirySeconds = 3600 // 1 小时内未修改视为过期
	deleted, err := RunImageCleanup(cfg, dir)
	if err != nil {
		t.Fatal(err)
	}
	if deleted != 1 {
		t.Errorf("deleted = %d, want 1", deleted)
	}
	if _, err := os.Stat(oldFile); !os.IsNotExist(err) {
		t.Error("过期文件应已被删除")
	}
}

// TestRunImageCleanup_None 未过期文件不删
func TestRunImageCleanup_None(t *testing.T) {
	dir := t.TempDir()
	imgDir := filepath.Join(dir, "images")
	if err := os.MkdirAll(imgDir, 0755); err != nil {
		t.Fatal(err)
	}
	f := filepath.Join(imgDir, "new.png")
	if err := os.WriteFile(f, []byte("new"), 0644); err != nil {
		t.Fatal(err)
	}
	cfg := testImageConfig()
	cfg.Image.URLExpirySeconds = 86400
	deleted, err := RunImageCleanup(cfg, dir)
	if err != nil {
		t.Fatal(err)
	}
	if deleted != 0 {
		t.Errorf("deleted = %d, want 0", deleted)
	}
	if _, err := os.Stat(f); err != nil {
		t.Error("新文件不应被删除", err)
	}
}
