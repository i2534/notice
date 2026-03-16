package config

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"reflect"
	"strconv"
	"time"

	"gopkg.in/yaml.v3"
)

// Config 应用配置
type Config struct {
	HTTP      HTTPConfig      `yaml:"http"`
	MQTT      MQTTConfig      `yaml:"mqtt"`
	Auth      AuthConfig      `yaml:"auth"`
	RateLimit RateLimitConfig `yaml:"rate_limit"`
	Log       LogConfig       `yaml:"log"`
	Storage   StorageConfig   `yaml:"storage"`
	Message   MessageConfig   `yaml:"message"`
	Image     ImageConfig     `yaml:"image"`
	Media     MediaConfig     `yaml:"media"`
}

// MediaConfig 多媒体上传与缓存配置（语音、视频等）
type MediaConfig struct {
	Folder                 string   `yaml:"folder" env:"MEDIA_FOLDER"`
	URLExpirySeconds       int      `yaml:"url_expiry_seconds" env:"MEDIA_URL_EXPIRY_SECONDS"`
	CleanupEnabled         bool     `yaml:"cleanup_enabled" env:"MEDIA_CLEANUP_ENABLED"`
	CleanupIntervalSeconds int      `yaml:"cleanup_interval_seconds" env:"MEDIA_CLEANUP_INTERVAL_SECONDS"`
	MaxUploadBytes         int64    `yaml:"max_upload_bytes" env:"MEDIA_MAX_UPLOAD_BYTES"`
	AllowedExtensions      []string `yaml:"allowed_extensions"`
}

// ImageConfig 图片上传与缓存配置
type ImageConfig struct {
	// Folder 图片存储子目录名（相对 Storage.Path），默认 "images"
	Folder string `yaml:"folder" env:"IMAGE_FOLDER"`
	// URLExpirySeconds 签名 URL 有效期（秒），默认 86400（24 小时）
	URLExpirySeconds int `yaml:"url_expiry_seconds" env:"IMAGE_URL_EXPIRY_SECONDS"`
	// CleanupEnabled 是否启用过期图片自动清理
	CleanupEnabled bool `yaml:"cleanup_enabled" env:"IMAGE_CLEANUP_ENABLED"`
	// CleanupIntervalSeconds 清理任务执行间隔（秒），默认 3600（1 小时）
	CleanupIntervalSeconds int `yaml:"cleanup_interval_seconds" env:"IMAGE_CLEANUP_INTERVAL_SECONDS"`
	// MaxUploadBytes 单张图片最大字节数，默认 5MB
	MaxUploadBytes int64 `yaml:"max_upload_bytes" env:"IMAGE_MAX_UPLOAD_BYTES"`
	// MaxUploadTotalBytes 单次请求（多图）总最大字节数，默认 20MB；0 表示与 MaxUploadBytes 相同
	MaxUploadTotalBytes int64 `yaml:"max_upload_total_bytes" env:"IMAGE_MAX_UPLOAD_TOTAL_BYTES"`
	// AllowedExtensions 允许的图片扩展名（小写带点，如 .jpg），空则使用默认 [.jpg .jpeg .png .gif .webp]
	AllowedExtensions []string `yaml:"allowed_extensions"`
}

// MessageConfig 消息配置
type MessageConfig struct {
	MaxTitleLength   int `yaml:"max_title_length" env:"MESSAGE_MAX_TITLE_LENGTH"`     // 标题最大长度
	MaxContentLength int `yaml:"max_content_length" env:"MESSAGE_MAX_CONTENT_LENGTH"` // 内容最大长度
}

// StorageConfig 持久化存储配置
type StorageConfig struct {
	Enabled bool   `yaml:"enabled" env:"STORAGE_ENABLED"` // 是否启用持久化
	Path    string `yaml:"path" env:"STORAGE_PATH"`       // 数据存储路径
}

// HTTPConfig HTTP 服务配置
type HTTPConfig struct {
	Port                 string `yaml:"port" env:"HTTP_PORT"`
	MaxRequestBodyBytes  int    `yaml:"max_request_body_bytes" env:"HTTP_MAX_REQUEST_BODY_BYTES"` // 请求体最大字节数，0 表示默认 512KB
}

// MQTTConfig MQTT Broker 配置
type MQTTConfig struct {
	TCPPort       string `yaml:"tcp_port" env:"MQTT_TCP_PORT"`
	WSPort        string `yaml:"ws_port" env:"MQTT_WS_PORT"`
	Topic         string `yaml:"topic" env:"MQTT_TOPIC"`
	SessionExpiry uint32 `yaml:"session_expiry" env:"MQTT_SESSION_EXPIRY"`
	MessageExpiry uint32 `yaml:"message_expiry" env:"MQTT_MESSAGE_EXPIRY"`
}

// AuthConfig 认证配置
type AuthConfig struct {
	Token     string `yaml:"token" env:"AUTH_TOKEN"`
	Generated bool   `yaml:"-"` // Token 是否自动生成（内部字段）
}

// RateLimitConfig 限流配置
type RateLimitConfig struct {
	MaxFailures int `yaml:"max_failures" env:"RATE_LIMIT_MAX_FAILURES"`
	BlockTime   int `yaml:"block_time" env:"RATE_LIMIT_BLOCK_TIME"`
	WindowTime  int `yaml:"window_time" env:"RATE_LIMIT_WINDOW_TIME"`

	// 防「换 IP 暴力破解」：全局与按错误凭证限流（0 表示关闭）
	GlobalMaxPerMinute     int `yaml:"global_max_per_minute" env:"RATE_LIMIT_GLOBAL_MAX_PER_MINUTE"`           // 每分钟全局限流失败次数，超过则短时拒绝所有认证
	GlobalBlockTime        int `yaml:"global_block_time" env:"RATE_LIMIT_GLOBAL_BLOCK_TIME"`                    // 全局触发封禁后的冷却时间（秒）
	CredentialMaxFailures  int `yaml:"credential_max_failures" env:"RATE_LIMIT_CREDENTIAL_MAX_FAILURES"`        // 同一错误凭证（如错误 token）被尝试的最大次数，超过则临时封禁该凭证
}

// LogConfig 日志配置
type LogConfig struct {
	ConsoleLevel string `yaml:"console_level" env:"LOG_CONSOLE_LEVEL"`
	FileLevel    string `yaml:"file_level" env:"LOG_FILE_LEVEL"`
	FilePath     string `yaml:"file_path" env:"LOG_FILE_PATH"`
	Pretty       bool   `yaml:"pretty" env:"LOG_PRETTY"`
	RotateDays   int    `yaml:"rotate_days" env:"LOG_ROTATE_DAYS"`
	MaxFiles     int    `yaml:"max_files" env:"LOG_MAX_FILES"`
}

// HasAuth 是否启用认证
func (c *Config) HasAuth() bool {
	return c.Auth.Token != ""
}

// defaultConfig 返回默认配置
func defaultConfig() *Config {
	return &Config{
		HTTP: HTTPConfig{
			Port:                "9090",
			MaxRequestBodyBytes: 512 * 1024, // 512KB
		},
		MQTT: MQTTConfig{
			TCPPort:       "9091",
			WSPort:        "9092",
			Topic:         "notice",
			SessionExpiry: 86400,
			MessageExpiry: 86400,
		},
		Auth: AuthConfig{
			Token: "",
		},
		RateLimit: RateLimitConfig{
			MaxFailures:            5,
			BlockTime:              900,
			WindowTime:             300,
			GlobalMaxPerMinute:     0,   // 0 关闭；建议 200–500 防分布式暴力破解
			GlobalBlockTime:        60,  // 全局封禁冷却秒数
			CredentialMaxFailures:  0,   // 0 关闭；建议 10–20，同一错误 token 被多 IP 试多次则封禁该凭证
		},
		Log: LogConfig{
			ConsoleLevel: "info",
			FileLevel:    "debug",
			FilePath:     "",
			Pretty:       true,
			RotateDays:   1,
			MaxFiles:     7,
		},
		Storage: StorageConfig{
			Enabled: true,
			Path:    "data",
		},
		Message: MessageConfig{
			MaxTitleLength:   50,  // 标题最大 50 字符
			MaxContentLength: 1024, // 内容最大 1024 字符
		},
		Image: ImageConfig{
			Folder:                 "images",
			URLExpirySeconds:       86400,  // 24 小时
			CleanupEnabled:         true,
			CleanupIntervalSeconds: 3600,   // 1 小时
			MaxUploadBytes:         5 * 1024 * 1024,  // 5MB
			MaxUploadTotalBytes:    20 * 1024 * 1024, // 20MB
			AllowedExtensions:      []string{".jpg", ".jpeg", ".png", ".gif", ".webp"},
		},
		Media: MediaConfig{
			Folder:                 "media",
			URLExpirySeconds:       86400,  // 24 小时
			CleanupEnabled:         true,
			CleanupIntervalSeconds: 3600,   // 1 小时
			MaxUploadBytes:         10 * 1024 * 1024, // 10MB（语音等）
			AllowedExtensions:      []string{".m4a", ".mp3", ".webm", ".ogg", ".wav"},
		},
	}
}

// Load 加载配置
// 优先级: 环境变量 > 配置文件 > 默认值
func Load() *Config {
	cfg := defaultConfig()

	// 获取配置文件路径
	configPath := getConfigPath()

	if configPath != "" {
		fmt.Println("加载配置文件:", configPath)
		if err := loadFromFile(configPath, cfg); err != nil {
			fmt.Println("警告: 配置文件加载失败:", err.Error())
		}
	}

	// 环境变量覆盖（最高优先级）
	applyEnvOverrides(cfg)

	// 处理 Token
	if cfg.Auth.Token == "" {
		cfg.Auth.Token = generateToken()
		cfg.Auth.Generated = true
	}

	return cfg
}

// loadFromFile 从 YAML 文件加载配置
func loadFromFile(path string, cfg *Config) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return yaml.Unmarshal(data, cfg)
}

// applyEnvOverrides 通过反射自动应用环境变量覆盖
// 读取结构体字段的 env 标签，从环境变量获取值并设置
func applyEnvOverrides(cfg *Config) {
	applyEnvToStruct(reflect.ValueOf(cfg).Elem())
}

// applyEnvToStruct 递归处理结构体字段
func applyEnvToStruct(v reflect.Value) {
	t := v.Type()

	for i := 0; i < v.NumField(); i++ {
		field := v.Field(i)
		fieldType := t.Field(i)

		// 跳过不可设置的字段
		if !field.CanSet() {
			continue
		}

		// 如果是嵌套结构体，递归处理
		if field.Kind() == reflect.Struct {
			applyEnvToStruct(field)
			continue
		}

		// 获取 env 标签
		envKey := fieldType.Tag.Get("env")
		if envKey == "" {
			continue
		}

		// 获取环境变量值
		envValue := os.Getenv(envKey)
		if envValue == "" {
			continue
		}

		// 根据字段类型设置值
		if err := setFieldValue(field, envValue); err != nil {
			fmt.Printf("警告: 环境变量 %s 解析失败: %v\n", envKey, err)
		}
	}
}

// setFieldValue 根据字段类型设置值
func setFieldValue(field reflect.Value, value string) error {
	switch field.Kind() {
	case reflect.String:
		field.SetString(value)

	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		v, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			return err
		}
		field.SetInt(v)

	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		v, err := strconv.ParseUint(value, 10, 64)
		if err != nil {
			return err
		}
		field.SetUint(v)

	case reflect.Bool:
		v, err := strconv.ParseBool(value)
		if err != nil {
			return err
		}
		field.SetBool(v)

	case reflect.Float32, reflect.Float64:
		v, err := strconv.ParseFloat(value, 64)
		if err != nil {
			return err
		}
		field.SetFloat(v)

	default:
		return fmt.Errorf("不支持的类型: %s", field.Kind())
	}

	return nil
}

// getConfigPath 获取配置文件路径
// 优先级: 命令行参数 (-c, --config) > 环境变量 (CONFIG_PATH) > 默认路径
func getConfigPath() string {
	// 1. 从命令行参数获取
	args := os.Args[1:]
	for i := range args {
		arg := args[i]
		// -c config.yaml 或 --config config.yaml
		if arg == "-c" || arg == "--config" {
			if i+1 < len(args) {
				return args[i+1]
			}
		}
		// -c=config.yaml 或 --config=config.yaml
		if len(arg) > 3 && arg[:3] == "-c=" {
			return arg[3:]
		}
		if len(arg) > 9 && arg[:9] == "--config=" {
			return arg[9:]
		}
	}

	// 2. 从环境变量获取
	if path := os.Getenv("CONFIG_PATH"); path != "" {
		return path
	}

	// 3. 尝试默认路径
	defaultPaths := []string{"config.yaml", "config.yml", "config/config.yaml", "config/config.yml"}
	for _, path := range defaultPaths {
		if _, err := os.Stat(path); err == nil {
			return path
		}
	}

	return ""
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
