package handlers

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"notice-server/config"
	"notice-server/logger"
	"notice-server/ratelimit"
)

func imageFolder(cfg *config.Config) string {
	if cfg != nil && cfg.Image.Folder != "" {
		return cfg.Image.Folder
	}
	return "images"
}

func imageURLExpiry(cfg *config.Config) int64 {
	if cfg != nil && cfg.Image.URLExpirySeconds > 0 {
		return int64(cfg.Image.URLExpirySeconds)
	}
	return 86400
}

func maxUploadBytes(cfg *config.Config) int64 {
	if cfg != nil && cfg.Image.MaxUploadBytes > 0 {
		return cfg.Image.MaxUploadBytes
	}
	return 5 * 1024 * 1024
}

func maxUploadTotalBytes(cfg *config.Config) int64 {
	if cfg != nil && cfg.Image.MaxUploadTotalBytes > 0 {
		return cfg.Image.MaxUploadTotalBytes
	}
	return 20 * 1024 * 1024
}

// allowedImageExt 判断扩展名是否在配置的允许列表中（忽略大小写）；配置为空时使用默认列表
func allowedImageExt(cfg *config.Config, ext string) bool {
	ext = strings.ToLower(ext)
	list := defaultAllowedExts
	if cfg != nil && len(cfg.Image.AllowedExtensions) > 0 {
		list = cfg.Image.AllowedExtensions
	}
	for _, e := range list {
		if strings.ToLower(strings.TrimSpace(e)) == ext {
			return true
		}
	}
	return false
}

var defaultAllowedExts = []string{".jpg", ".jpeg", ".png", ".gif", ".webp"}

// randomImageName 生成随机文件名（16 字节 base64 URL-safe 编码 + 扩展名）
func randomImageName(ext string) (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b) + ext, nil
}

// imageSignPayload 用于 HMAC 的 payload：name + 空格 + expires
func imageSignPayload(name string, expires int64) string {
	return name + " " + strconv.FormatInt(expires, 10)
}

// imageSignKeyBytes 签名 key 固定长度（字节），只取哈希前 N 位，不暴露完整哈希
const imageSignKeyBytes = 16

// imageSignSigBytes 返回的 sig 只取 HMAC 前 N 字节（hex 后 32 字符），缩短 URL
const imageSignSigBytes = 16

// imageSignKey 用 SHA256 对 token 做派生，返回特定位数作为 HMAC key，避免签名直接暴露 token
func imageSignKey(secret string) []byte {
	h := sha256.Sum256([]byte(secret))
	return h[:imageSignKeyBytes]
}

// SignImageURL 生成带签名的图片 URL；n 为明文（仅做 query 转义），e 为明文时间戳，s 为 Base64 签名
func SignImageURL(baseURL, name string, expires int64, secret string) string {
	payload := imageSignPayload(name, expires)
	mac := hmac.New(sha256.New, imageSignKey(secret))
	mac.Write([]byte(payload))
	sum := mac.Sum(nil)
	if len(sum) > imageSignSigBytes {
		sum = sum[:imageSignSigBytes]
	}
	s := base64.RawURLEncoding.EncodeToString(sum)
	baseURL = strings.TrimSuffix(baseURL, "/")
	return fmt.Sprintf("%s/api/image?n=%s&e=%d&s=%s",
		baseURL, name, expires, s)
}

// VerifyImageSignature 校验 name、expires、sig（sig 为解码后的 16 字节）
func VerifyImageSignature(secret, name string, expires int64, sigBytes []byte) bool {
	if time.Now().Unix() > expires {
		return false
	}
	payload := imageSignPayload(name, expires)
	mac := hmac.New(sha256.New, imageSignKey(secret))
	mac.Write([]byte(payload))
	sum := mac.Sum(nil)
	if len(sum) > imageSignSigBytes {
		sum = sum[:imageSignSigBytes]
	}
	return hmac.Equal(sigBytes, sum)
}

// ImageHandler 处理 GET /api/image?n=...&e=...&s=...（短参数名节省传输）
func ImageHandler(cfg *config.Config, storagePath string) http.HandlerFunc {
	secret := cfg.Auth.Token
	basePath := filepath.Join(storagePath, imageFolder(cfg))

	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		name := r.URL.Query().Get("n")
		expiresStr := r.URL.Query().Get("e")
		sB64 := r.URL.Query().Get("s")

		if name == "" || expiresStr == "" || sB64 == "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		expires, err := strconv.ParseInt(expiresStr, 10, 64)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		sigBytes, err := base64.RawURLEncoding.DecodeString(sB64)
		if err != nil || len(sigBytes) != imageSignSigBytes {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		if ContainsPathTraversal(name) || filepath.IsAbs(name) {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		name = strings.TrimPrefix(filepath.Clean(name), string(filepath.Separator))
		if name == "" || name == "." {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		if secret == "" || !VerifyImageSignature(secret, name, expires, sigBytes) {
			w.WriteHeader(http.StatusForbidden)
			return
		}

		fullPath := filepath.Join(basePath, name)
		absBase, _ := filepath.Abs(basePath)
		absFull, _ := filepath.Abs(fullPath)
		if !strings.HasPrefix(absFull, absBase+string(filepath.Separator)) && absFull != absBase {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		w.Header().Set("Cache-Control", "private, max-age=3600")
		http.ServeFile(w, r, fullPath)
	}
}

// UploadHandler 处理 POST /api/upload，需 Bearer Token；支持单图或多图
func UploadHandler(cfg *config.Config, storagePath string, limiter *ratelimit.Limiter) http.HandlerFunc {
	secret := cfg.Auth.Token
	basePath := filepath.Join(storagePath, imageFolder(cfg))

	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")

		clientIP := ratelimit.GetClientIP(r)
		attemptedToken := ExtractToken(r)
		if limiter != nil && limiter.IsBlocked(clientIP, attemptedToken) {
			logger.Warn("上传请求被拒绝，已触发限流", "ip", clientIP)
			w.WriteHeader(http.StatusTooManyRequests)
			w.Write([]byte(`{"success":false,"message":"请求过于频繁，请稍后再试"}`))
			return
		}

		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		if !ValidateToken(r, cfg.Auth.Token) {
			if limiter != nil {
				limiter.RecordFailure(clientIP, attemptedToken)
			}
			w.WriteHeader(http.StatusUnauthorized)
			w.Write([]byte(`{"success":false,"message":"认证失败"}`))
			return
		}

		maxTotal := maxUploadTotalBytes(cfg)
		if maxTotal <= 0 {
			maxTotal = maxUploadBytes(cfg)
		}
		maxPerFile := maxUploadBytes(cfg)

		r.Body = http.MaxBytesReader(w, r.Body, maxTotal)
		if err := r.ParseMultipartForm(maxTotal); err != nil {
			if err.Error() == "http: request body too large" {
				w.WriteHeader(http.StatusRequestEntityTooLarge)
			} else {
				w.WriteHeader(http.StatusBadRequest)
			}
			w.Write([]byte(`{"success":false,"message":"请求体过大或格式错误"}`))
			return
		}

		files := r.MultipartForm.File["file"]
		if len(files) == 0 {
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"success":false,"message":"缺少 file 字段"}`))
			return
		}

		if err := ensureDir(basePath); err != nil {
			logger.Error("创建图片目录失败", "path", basePath, "error", err)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte(`{"success":false,"message":"服务器错误"}`))
			return
		}

		expires := time.Now().Unix() + imageURLExpiry(cfg)
		baseURL := "http://" + r.Host
		if r.TLS != nil {
			baseURL = "https://" + r.Host
		}

		var imageURLs []string
		for _, header := range files {
			ext := strings.ToLower(filepath.Ext(header.Filename))
			if !allowedImageExt(cfg, ext) {
				w.WriteHeader(http.StatusBadRequest)
				w.Write([]byte(`{"success":false,"message":"不允许的图片扩展名，文件名: ` + header.Filename + `"}`))
				return
			}
			file, err := header.Open()
			if err != nil {
				w.WriteHeader(http.StatusBadRequest)
				w.Write([]byte(`{"success":false,"message":"无法读取上传文件"}`))
				return
			}
			name, err := randomImageName(ext)
			if err != nil {
				file.Close()
				logger.Error("生成图片文件名失败", "error", err)
				w.WriteHeader(http.StatusInternalServerError)
				w.Write([]byte(`{"success":false,"message":"服务器错误"}`))
				return
			}
			fullPath := filepath.Join(basePath, name)
			dst, err := createFile(fullPath)
			if err != nil {
				file.Close()
				logger.Error("创建图片文件失败", "path", fullPath, "error", err)
				w.WriteHeader(http.StatusInternalServerError)
				w.Write([]byte(`{"success":false,"message":"服务器错误"}`))
				return
			}
			if _, err := copyMax(dst, file, maxPerFile); err != nil {
				file.Close()
				dst.Close()
				_ = removeFile(fullPath)
				logger.Error("写入图片失败", "error", err)
				w.WriteHeader(http.StatusInternalServerError)
				w.Write([]byte(`{"success":false,"message":"写入失败"}`))
				return
			}
			file.Close()
			dst.Close()
			signedURL := SignImageURL(baseURL, name, expires, secret)
			imageURLs = append(imageURLs, signedURL)
		}

		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success":    true,
			"image_urls": imageURLs,
		})
	}
}

func ensureDir(dir string) error {
	return os.MkdirAll(dir, 0755)
}

func createFile(path string) (*os.File, error) {
	return os.Create(path)
}

func removeFile(path string) error {
	return os.Remove(path)
}

func copyMax(dst io.Writer, src io.Reader, maxBytes int64) (int64, error) {
	return io.Copy(dst, io.LimitReader(src, maxBytes))
}

// RunImageCleanup 扫描图片目录，删除已过期的文件
func RunImageCleanup(cfg *config.Config, storagePath string) (deleted int, err error) {
	if cfg == nil {
		return 0, nil
	}
	basePath := filepath.Join(storagePath, imageFolder(cfg))
	expiry := time.Duration(imageURLExpiry(cfg)) * time.Second

	entries, err := os.ReadDir(basePath)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}

	now := time.Now()
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		if now.Sub(info.ModTime()) <= expiry {
			continue
		}
		fullPath := filepath.Join(basePath, e.Name())
		if err := os.Remove(fullPath); err != nil {
			logger.Warn("删除过期图片失败", "path", fullPath, "error", err)
			continue
		}
		deleted++
		logger.Debug("已删除过期图片", "path", e.Name())
	}
	return deleted, nil
}

// RunImageCleanupLoop 按配置间隔定期执行过期图片清理
func RunImageCleanupLoop(cfg *config.Config, storagePath string, stopCh <-chan struct{}) {
	if cfg == nil || !cfg.Image.CleanupEnabled || cfg.Image.CleanupIntervalSeconds <= 0 {
		return
	}
	interval := time.Duration(cfg.Image.CleanupIntervalSeconds) * time.Second
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	if n, err := RunImageCleanup(cfg, storagePath); err != nil {
		logger.Warn("图片清理任务执行失败", "error", err)
	} else if n > 0 {
		logger.Info("图片清理已删除过期文件", "count", n)
	}

	for {
		select {
		case <-stopCh:
			return
		case <-ticker.C:
			if n, err := RunImageCleanup(cfg, storagePath); err != nil {
				logger.Warn("图片清理任务执行失败", "error", err)
			} else if n > 0 {
				logger.Info("图片清理已删除过期文件", "count", n)
			}
		}
	}
}
