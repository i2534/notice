package handlers

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
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

const mediaSignKeyBytes = 16
const mediaSignSigBytes = 16

func mediaFolder(cfg *config.Config) string {
	if cfg != nil && cfg.Media.Folder != "" {
		return cfg.Media.Folder
	}
	return "media"
}

func mediaURLExpiry(cfg *config.Config) int64 {
	if cfg != nil && cfg.Media.URLExpirySeconds > 0 {
		return int64(cfg.Media.URLExpirySeconds)
	}
	return 86400
}

func maxMediaUploadBytes(cfg *config.Config) int64 {
	if cfg != nil && cfg.Media.MaxUploadBytes > 0 {
		return cfg.Media.MaxUploadBytes
	}
	return 10 * 1024 * 1024 // 10MB
}

func allowedMediaExt(cfg *config.Config, ext string) bool {
	ext = strings.ToLower(ext)
	list := defaultMediaExts
	if cfg != nil && len(cfg.Media.AllowedExtensions) > 0 {
		list = cfg.Media.AllowedExtensions
	}
	for _, e := range list {
		if strings.ToLower(strings.TrimSpace(e)) == ext {
			return true
		}
	}
	return false
}

var defaultMediaExts = []string{".m4a", ".mp3", ".webm", ".ogg", ".wav"}

func randomMediaName(ext string) (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b) + ext, nil
}

func mediaSignPayload(name string, expires int64) string {
	return name + " " + strconv.FormatInt(expires, 10)
}

func mediaSignKey(secret string) []byte {
	h := sha256.Sum256([]byte(secret))
	return h[:mediaSignKeyBytes]
}

// SignMediaURL 生成带签名的多媒体 URL
func SignMediaURL(baseURL, name string, expires int64, secret string) string {
	payload := mediaSignPayload(name, expires)
	mac := hmac.New(sha256.New, mediaSignKey(secret))
	mac.Write([]byte(payload))
	sum := mac.Sum(nil)
	if len(sum) > mediaSignSigBytes {
		sum = sum[:mediaSignSigBytes]
	}
	s := base64.RawURLEncoding.EncodeToString(sum)
	baseURL = strings.TrimSuffix(baseURL, "/")
	return fmt.Sprintf("%s/api/media?n=%s&e=%d&s=%s", baseURL, name, expires, s)
}

func verifyMediaSignature(secret, name string, expires int64, sigBytes []byte) bool {
	if time.Now().Unix() > expires {
		return false
	}
	payload := mediaSignPayload(name, expires)
	mac := hmac.New(sha256.New, mediaSignKey(secret))
	mac.Write([]byte(payload))
	sum := mac.Sum(nil)
	if len(sum) > mediaSignSigBytes {
		sum = sum[:mediaSignSigBytes]
	}
	return hmac.Equal(sigBytes, sum)
}

// MediaHandler 处理 GET /api/media?n=...&e=...&s=...
func MediaHandler(cfg *config.Config, storagePath string) http.HandlerFunc {
	secret := cfg.Auth.Token
	basePath := filepath.Join(storagePath, mediaFolder(cfg))

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
		if err != nil || len(sigBytes) != mediaSignSigBytes {
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

		if secret == "" || !verifyMediaSignature(secret, name, expires, sigBytes) {
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

// MediaUploadHandler 处理 POST /api/media/upload，单文件，返回 media_url
func MediaUploadHandler(cfg *config.Config, storagePath string, limiter *ratelimit.Limiter) http.HandlerFunc {
	secret := cfg.Auth.Token
	basePath := filepath.Join(storagePath, mediaFolder(cfg))
	maxPerFile := maxMediaUploadBytes(cfg)

	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")

		clientIP := ratelimit.GetClientIP(r)
		attemptedToken := ExtractToken(r)
		if limiter != nil && limiter.IsBlocked(clientIP, attemptedToken) {
			logger.Warn("多媒体上传请求被拒绝，已触发限流", "ip", clientIP)
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

		r.Body = http.MaxBytesReader(w, r.Body, maxPerFile)
		if err := r.ParseMultipartForm(maxPerFile); err != nil {
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
		// 单文件上传：只取第一个
		header := files[0]

		ext := strings.ToLower(filepath.Ext(header.Filename))
		if !allowedMediaExt(cfg, ext) {
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"success":false,"message":"不允许的扩展名，文件名: ` + header.Filename + `"}`))
			return
		}

		if err := ensureDir(basePath); err != nil {
			logger.Error("创建多媒体目录失败", "path", basePath, "error", err)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte(`{"success":false,"message":"服务器错误"}`))
			return
		}

		file, err := header.Open()
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"success":false,"message":"无法读取上传文件"}`))
			return
		}
		name, err := randomMediaName(ext)
		if err != nil {
			file.Close()
			logger.Error("生成多媒体文件名失败", "error", err)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte(`{"success":false,"message":"服务器错误"}`))
			return
		}
		fullPath := filepath.Join(basePath, name)
		dst, err := createFile(fullPath)
		if err != nil {
			file.Close()
			logger.Error("创建多媒体文件失败", "path", fullPath, "error", err)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte(`{"success":false,"message":"服务器错误"}`))
			return
		}
		if _, err := copyMax(dst, file, maxPerFile); err != nil {
			file.Close()
			dst.Close()
			_ = removeFile(fullPath)
			logger.Error("写入多媒体文件失败", "error", err)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte(`{"success":false,"message":"写入失败"}`))
			return
		}
		file.Close()
		dst.Close()

		expires := time.Now().Unix() + mediaURLExpiry(cfg)
		baseURL := requestScheme(r) + "://" + r.Host
		signedURL := SignMediaURL(baseURL, name, expires, secret)

		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success":   true,
			"media_url": signedURL,
		})
	}
}

// RunMediaCleanup 扫描多媒体目录，删除已过期的文件
func RunMediaCleanup(cfg *config.Config, storagePath string) (deleted int, err error) {
	if cfg == nil {
		return 0, nil
	}
	basePath := filepath.Join(storagePath, mediaFolder(cfg))
	expiry := time.Duration(mediaURLExpiry(cfg)) * time.Second

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
			logger.Warn("删除过期多媒体文件失败", "path", fullPath, "error", err)
			continue
		}
		deleted++
		logger.Debug("已删除过期多媒体", "path", e.Name())
	}
	return deleted, nil
}

// RunMediaCleanupLoop 按配置间隔定期执行过期多媒体清理
func RunMediaCleanupLoop(cfg *config.Config, storagePath string, stopCh <-chan struct{}) {
	if cfg == nil || !cfg.Media.CleanupEnabled || cfg.Media.CleanupIntervalSeconds <= 0 {
		return
	}
	interval := time.Duration(cfg.Media.CleanupIntervalSeconds) * time.Second
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	if n, err := RunMediaCleanup(cfg, storagePath); err != nil {
		logger.Warn("多媒体清理任务执行失败", "error", err)
	} else if n > 0 {
		logger.Info("多媒体清理已删除过期文件", "count", n)
	}

	for {
		select {
		case <-stopCh:
			return
		case <-ticker.C:
			if n, err := RunMediaCleanup(cfg, storagePath); err != nil {
				logger.Warn("多媒体清理任务执行失败", "error", err)
			} else if n > 0 {
				logger.Info("多媒体清理已删除过期文件", "count", n)
			}
		}
	}
}
