package config

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"strconv"
	"time"
)

// Config 应用配置
type Config struct {
	// HTTP 服务配置
	HTTPPort string

	// MQTT Broker 配置
	MQTTTCPPort string
	MQTTWSPort  string
	MQTTTopic   string

	// MQTT 超时配置（秒）
	SessionExpiry uint32 // 会话过期时间，默认 3600 秒（1小时）
	MessageExpiry uint32 // 消息过期时间，默认 86400 秒（24小时）

	// 认证配置
	AuthToken      string // 访问令牌
	TokenGenerated bool   // Token 是否自动生成的

	// 限流配置
	RateLimitMaxFailures int // 最大失败次数，默认 5
	RateLimitBlockTime   int // 封禁时间（秒），默认 900（15分钟）
	RateLimitWindowTime  int // 统计窗口时间（秒），默认 300（5分钟）

	// 日志配置
	LogConsoleLevel string // Console 日志级别: debug, info, warn, error, off
	LogFileLevel    string // 文件日志级别: debug, info, warn, error, off
	LogFilePath     string // 日志文件路径，为空则不写入文件
	LogPretty       bool   // Console 是否美化输出
	LogRotateDays   int    // 日志轮转天数，0 表示不轮转，默认 1（每天一个文件）
	LogMaxFiles     int    // 保留的日志文件数量，0 表示不限制，默认 7
}

// HasAuth 是否启用认证
func (c *Config) HasAuth() bool {
	return c.AuthToken != ""
}

// Load 加载配置
func Load() *Config {
	authToken := getEnv("AUTH_TOKEN", "")
	tokenGenerated := false
	if authToken == "" {
		authToken = generateToken()
		tokenGenerated = true
	}

	return &Config{
		HTTPPort:             getEnv("HTTP_PORT", "9090"),
		MQTTTCPPort:          getEnv("MQTT_TCP_PORT", "9091"),
		MQTTWSPort:           getEnv("MQTT_WS_PORT", "9092"),
		MQTTTopic:            getEnv("MQTT_TOPIC", "notice"),
		SessionExpiry:        getEnvUint32("MQTT_SESSION_EXPIRY", 3600),
		MessageExpiry:        getEnvUint32("MQTT_MESSAGE_EXPIRY", 86400),
		AuthToken:            authToken,
		TokenGenerated:       tokenGenerated,
		RateLimitMaxFailures: getEnvInt("RATE_LIMIT_MAX_FAILURES", 5),
		RateLimitBlockTime:   getEnvInt("RATE_LIMIT_BLOCK_TIME", 900),
		RateLimitWindowTime:  getEnvInt("RATE_LIMIT_WINDOW_TIME", 300),
		LogConsoleLevel:      getEnv("LOG_CONSOLE_LEVEL", "info"),
		LogFileLevel:         getEnv("LOG_FILE_LEVEL", "debug"),
		LogFilePath:          getEnv("LOG_FILE_PATH", ""),
		LogPretty:            getEnvBool("LOG_PRETTY", true),
		LogRotateDays:        getEnvInt("LOG_ROTATE_DAYS", 1),
		LogMaxFiles:          getEnvInt("LOG_MAX_FILES", 7),
	}
}

// generateToken 生成随机 Token (32 字符)
func generateToken() string {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		// 降级使用时间戳
		return "auto-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(bytes)
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvUint32(key string, defaultValue uint32) uint32 {
	if value := os.Getenv(key); value != "" {
		if v, err := strconv.ParseUint(value, 10, 32); err == nil {
			return uint32(v)
		}
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if v, err := strconv.Atoi(value); err == nil {
			return v
		}
	}
	return defaultValue
}

func getEnvBool(key string, defaultValue bool) bool {
	if value := os.Getenv(key); value != "" {
		if v, err := strconv.ParseBool(value); err == nil {
			return v
		}
	}
	return defaultValue
}
