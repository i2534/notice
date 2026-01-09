package config

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestDefaultConfig(t *testing.T) {
	cfg := defaultConfig()

	// HTTP
	if cfg.HTTP.Port != "9090" {
		t.Errorf("HTTP.Port = %s, want 9090", cfg.HTTP.Port)
	}

	// MQTT
	if cfg.MQTT.TCPPort != "9091" {
		t.Errorf("MQTT.TCPPort = %s, want 9091", cfg.MQTT.TCPPort)
	}
	if cfg.MQTT.WSPort != "9092" {
		t.Errorf("MQTT.WSPort = %s, want 9092", cfg.MQTT.WSPort)
	}
	if cfg.MQTT.Topic != "notice" {
		t.Errorf("MQTT.Topic = %s, want notice", cfg.MQTT.Topic)
	}
	if cfg.MQTT.SessionExpiry != 86400 {
		t.Errorf("MQTT.SessionExpiry = %d, want 86400", cfg.MQTT.SessionExpiry)
	}
	if cfg.MQTT.MessageExpiry != 86400 {
		t.Errorf("MQTT.MessageExpiry = %d, want 86400", cfg.MQTT.MessageExpiry)
	}

	// Auth
	if cfg.Auth.Token != "" {
		t.Errorf("Auth.Token = %s, want empty", cfg.Auth.Token)
	}

	// RateLimit
	if cfg.RateLimit.MaxFailures != 5 {
		t.Errorf("RateLimit.MaxFailures = %d, want 5", cfg.RateLimit.MaxFailures)
	}
	if cfg.RateLimit.BlockTime != 900 {
		t.Errorf("RateLimit.BlockTime = %d, want 900", cfg.RateLimit.BlockTime)
	}
	if cfg.RateLimit.WindowTime != 300 {
		t.Errorf("RateLimit.WindowTime = %d, want 300", cfg.RateLimit.WindowTime)
	}

	// Log
	if cfg.Log.ConsoleLevel != "info" {
		t.Errorf("Log.ConsoleLevel = %s, want info", cfg.Log.ConsoleLevel)
	}
	if cfg.Log.FileLevel != "debug" {
		t.Errorf("Log.FileLevel = %s, want debug", cfg.Log.FileLevel)
	}
	if cfg.Log.Pretty != true {
		t.Errorf("Log.Pretty = %v, want true", cfg.Log.Pretty)
	}
	if cfg.Log.RotateDays != 1 {
		t.Errorf("Log.RotateDays = %d, want 1", cfg.Log.RotateDays)
	}
	if cfg.Log.MaxFiles != 7 {
		t.Errorf("Log.MaxFiles = %d, want 7", cfg.Log.MaxFiles)
	}
}

func TestLoadFromFile(t *testing.T) {
	// 创建临时配置文件
	tmpDir := t.TempDir()
	configPath := filepath.Join(tmpDir, "config.yaml")

	yamlContent := `
http:
  port: "8080"
mqtt:
  tcp_port: "1883"
  ws_port: "8083"
  topic: "test-topic"
  session_expiry: 3600
  message_expiry: 7200
auth:
  token: "test-token"
rate_limit:
  max_failures: 10
  block_time: 1800
  window_time: 600
log:
  console_level: "debug"
  file_level: "warn"
  file_path: "/var/log/test.log"
  pretty: false
  rotate_days: 7
  max_files: 30
`
	if err := os.WriteFile(configPath, []byte(yamlContent), 0644); err != nil {
		t.Fatalf("写入配置文件失败: %v", err)
	}

	cfg := defaultConfig()
	if err := loadFromFile(configPath, cfg); err != nil {
		t.Fatalf("加载配置文件失败: %v", err)
	}

	// 验证加载的值
	if cfg.HTTP.Port != "8080" {
		t.Errorf("HTTP.Port = %s, want 8080", cfg.HTTP.Port)
	}
	if cfg.MQTT.TCPPort != "1883" {
		t.Errorf("MQTT.TCPPort = %s, want 1883", cfg.MQTT.TCPPort)
	}
	if cfg.MQTT.Topic != "test-topic" {
		t.Errorf("MQTT.Topic = %s, want test-topic", cfg.MQTT.Topic)
	}
	if cfg.Auth.Token != "test-token" {
		t.Errorf("Auth.Token = %s, want test-token", cfg.Auth.Token)
	}
	if cfg.RateLimit.MaxFailures != 10 {
		t.Errorf("RateLimit.MaxFailures = %d, want 10", cfg.RateLimit.MaxFailures)
	}
	if cfg.Log.ConsoleLevel != "debug" {
		t.Errorf("Log.ConsoleLevel = %s, want debug", cfg.Log.ConsoleLevel)
	}
	if cfg.Log.Pretty != false {
		t.Errorf("Log.Pretty = %v, want false", cfg.Log.Pretty)
	}
}

func TestLoadFromFileNotExist(t *testing.T) {
	cfg := defaultConfig()
	err := loadFromFile("/nonexistent/path/config.yaml", cfg)
	if err == nil {
		t.Error("期望返回错误，但得到 nil")
	}
}

func TestApplyEnvOverrides(t *testing.T) {
	// 保存原始环境变量
	originalEnv := map[string]string{
		"HTTP_PORT":              os.Getenv("HTTP_PORT"),
		"MQTT_TCP_PORT":          os.Getenv("MQTT_TCP_PORT"),
		"MQTT_SESSION_EXPIRY":    os.Getenv("MQTT_SESSION_EXPIRY"),
		"AUTH_TOKEN":             os.Getenv("AUTH_TOKEN"),
		"RATE_LIMIT_MAX_FAILURES": os.Getenv("RATE_LIMIT_MAX_FAILURES"),
		"LOG_PRETTY":             os.Getenv("LOG_PRETTY"),
	}
	defer func() {
		for k, v := range originalEnv {
			if v == "" {
				os.Unsetenv(k)
			} else {
				os.Setenv(k, v)
			}
		}
	}()

	// 设置测试环境变量
	os.Setenv("HTTP_PORT", "7070")
	os.Setenv("MQTT_TCP_PORT", "1883")
	os.Setenv("MQTT_SESSION_EXPIRY", "7200")
	os.Setenv("AUTH_TOKEN", "env-token")
	os.Setenv("RATE_LIMIT_MAX_FAILURES", "20")
	os.Setenv("LOG_PRETTY", "false")

	cfg := defaultConfig()
	applyEnvOverrides(cfg)

	// 验证覆盖的值
	if cfg.HTTP.Port != "7070" {
		t.Errorf("HTTP.Port = %s, want 7070", cfg.HTTP.Port)
	}
	if cfg.MQTT.TCPPort != "1883" {
		t.Errorf("MQTT.TCPPort = %s, want 1883", cfg.MQTT.TCPPort)
	}
	if cfg.MQTT.SessionExpiry != 7200 {
		t.Errorf("MQTT.SessionExpiry = %d, want 7200", cfg.MQTT.SessionExpiry)
	}
	if cfg.Auth.Token != "env-token" {
		t.Errorf("Auth.Token = %s, want env-token", cfg.Auth.Token)
	}
	if cfg.RateLimit.MaxFailures != 20 {
		t.Errorf("RateLimit.MaxFailures = %d, want 20", cfg.RateLimit.MaxFailures)
	}
	if cfg.Log.Pretty != false {
		t.Errorf("Log.Pretty = %v, want false", cfg.Log.Pretty)
	}
}

func TestApplyEnvOverridesInvalidValue(t *testing.T) {
	originalEnv := os.Getenv("MQTT_SESSION_EXPIRY")
	defer func() {
		if originalEnv == "" {
			os.Unsetenv("MQTT_SESSION_EXPIRY")
		} else {
			os.Setenv("MQTT_SESSION_EXPIRY", originalEnv)
		}
	}()

	// 设置无效值
	os.Setenv("MQTT_SESSION_EXPIRY", "not-a-number")

	cfg := defaultConfig()
	originalValue := cfg.MQTT.SessionExpiry

	// 不应 panic，应保持原值
	applyEnvOverrides(cfg)

	if cfg.MQTT.SessionExpiry != originalValue {
		t.Errorf("MQTT.SessionExpiry = %d, want %d (无效值应被忽略)", cfg.MQTT.SessionExpiry, originalValue)
	}
}

func TestSetFieldValue(t *testing.T) {
	tests := []struct {
		name     string
		value    string
		expected any
		kind     reflect.Kind
	}{
		{"string", "hello", "hello", reflect.String},
		{"int", "42", int64(42), reflect.Int},
		{"int negative", "-10", int64(-10), reflect.Int},
		{"uint", "100", uint64(100), reflect.Uint},
		{"bool true", "true", true, reflect.Bool},
		{"bool false", "false", false, reflect.Bool},
		{"bool 1", "1", true, reflect.Bool},
		{"bool 0", "0", false, reflect.Bool},
		{"float", "3.14", 3.14, reflect.Float64},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var field reflect.Value

			switch tt.kind {
			case reflect.String:
				var v string
				field = reflect.ValueOf(&v).Elem()
			case reflect.Int:
				var v int64
				field = reflect.ValueOf(&v).Elem()
			case reflect.Uint:
				var v uint64
				field = reflect.ValueOf(&v).Elem()
			case reflect.Bool:
				var v bool
				field = reflect.ValueOf(&v).Elem()
			case reflect.Float64:
				var v float64
				field = reflect.ValueOf(&v).Elem()
			}

			err := setFieldValue(field, tt.value)
			if err != nil {
				t.Errorf("setFieldValue() error = %v", err)
				return
			}

			if field.Interface() != tt.expected {
				t.Errorf("setFieldValue() = %v, want %v", field.Interface(), tt.expected)
			}
		})
	}
}

func TestSetFieldValueErrors(t *testing.T) {
	tests := []struct {
		name  string
		value string
		kind  reflect.Kind
	}{
		{"invalid int", "not-a-number", reflect.Int},
		{"invalid uint", "-1", reflect.Uint},
		{"invalid bool", "maybe", reflect.Bool},
		{"invalid float", "not-a-float", reflect.Float64},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var field reflect.Value

			switch tt.kind {
			case reflect.Int:
				var v int64
				field = reflect.ValueOf(&v).Elem()
			case reflect.Uint:
				var v uint64
				field = reflect.ValueOf(&v).Elem()
			case reflect.Bool:
				var v bool
				field = reflect.ValueOf(&v).Elem()
			case reflect.Float64:
				var v float64
				field = reflect.ValueOf(&v).Elem()
			}

			err := setFieldValue(field, tt.value)
			if err == nil {
				t.Errorf("setFieldValue() 期望返回错误，但得到 nil")
			}
		})
	}
}

func TestGenerateToken(t *testing.T) {
	token1 := generateToken()
	token2 := generateToken()

	// Token 长度应为 32（16 字节 hex 编码）
	if len(token1) != 32 {
		t.Errorf("token 长度 = %d, want 32", len(token1))
	}

	// 两次生成的 token 应不同
	if token1 == token2 {
		t.Error("两次生成的 token 相同，应该不同")
	}
}

func TestHasAuth(t *testing.T) {
	cfg := &Config{}

	// 空 token
	cfg.Auth.Token = ""
	if cfg.HasAuth() {
		t.Error("HasAuth() = true, want false (空 token)")
	}

	// 有 token
	cfg.Auth.Token = "some-token"
	if !cfg.HasAuth() {
		t.Error("HasAuth() = false, want true")
	}
}

func TestGetConfigPath(t *testing.T) {
	// 保存原始环境变量和参数
	originalArgs := os.Args
	originalConfigPath := os.Getenv("CONFIG_PATH")
	defer func() {
		os.Args = originalArgs
		if originalConfigPath == "" {
			os.Unsetenv("CONFIG_PATH")
		} else {
			os.Setenv("CONFIG_PATH", originalConfigPath)
		}
	}()

	// 测试命令行参数 -c
	t.Run("命令行参数 -c", func(t *testing.T) {
		os.Args = []string{"app", "-c", "/path/to/config.yaml"}
		os.Unsetenv("CONFIG_PATH")

		path := getConfigPath()
		if path != "/path/to/config.yaml" {
			t.Errorf("getConfigPath() = %s, want /path/to/config.yaml", path)
		}
	})

	// 测试命令行参数 --config
	t.Run("命令行参数 --config", func(t *testing.T) {
		os.Args = []string{"app", "--config", "/path/to/config.yaml"}
		os.Unsetenv("CONFIG_PATH")

		path := getConfigPath()
		if path != "/path/to/config.yaml" {
			t.Errorf("getConfigPath() = %s, want /path/to/config.yaml", path)
		}
	})

	// 测试命令行参数 -c=
	t.Run("命令行参数 -c=", func(t *testing.T) {
		os.Args = []string{"app", "-c=/path/to/config.yaml"}
		os.Unsetenv("CONFIG_PATH")

		path := getConfigPath()
		if path != "/path/to/config.yaml" {
			t.Errorf("getConfigPath() = %s, want /path/to/config.yaml", path)
		}
	})

	// 测试环境变量
	t.Run("环境变量", func(t *testing.T) {
		os.Args = []string{"app"}
		os.Setenv("CONFIG_PATH", "/env/config.yaml")

		path := getConfigPath()
		if path != "/env/config.yaml" {
			t.Errorf("getConfigPath() = %s, want /env/config.yaml", path)
		}
	})

	// 测试默认路径
	t.Run("默认路径", func(t *testing.T) {
		os.Args = []string{"app"}
		os.Unsetenv("CONFIG_PATH")

		tmpDir := t.TempDir()
		originalWd, _ := os.Getwd()
		os.Chdir(tmpDir)
		defer os.Chdir(originalWd)

		// 创建默认配置文件
		os.WriteFile("config.yaml", []byte("http:\n  port: '9090'"), 0644)

		path := getConfigPath()
		if path != "config.yaml" {
			t.Errorf("getConfigPath() = %s, want config.yaml", path)
		}
	})
}

func TestLoad(t *testing.T) {
	// 保存原始状态
	originalArgs := os.Args
	originalConfigPath := os.Getenv("CONFIG_PATH")
	originalAuthToken := os.Getenv("AUTH_TOKEN")
	defer func() {
		os.Args = originalArgs
		if originalConfigPath == "" {
			os.Unsetenv("CONFIG_PATH")
		} else {
			os.Setenv("CONFIG_PATH", originalConfigPath)
		}
		if originalAuthToken == "" {
			os.Unsetenv("AUTH_TOKEN")
		} else {
			os.Setenv("AUTH_TOKEN", originalAuthToken)
		}
	}()

	t.Run("无配置文件自动生成 Token", func(t *testing.T) {
		os.Args = []string{"app"}
		os.Unsetenv("CONFIG_PATH")
		os.Unsetenv("AUTH_TOKEN")

		tmpDir := t.TempDir()
		originalWd, _ := os.Getwd()
		os.Chdir(tmpDir)
		defer os.Chdir(originalWd)

		cfg := Load()

		if cfg.Auth.Token == "" {
			t.Error("Auth.Token 应该自动生成")
		}
		if !cfg.Auth.Generated {
			t.Error("Auth.Generated 应该为 true")
		}
	})

	t.Run("环境变量设置 Token", func(t *testing.T) {
		os.Args = []string{"app"}
		os.Unsetenv("CONFIG_PATH")
		os.Setenv("AUTH_TOKEN", "my-custom-token")

		tmpDir := t.TempDir()
		originalWd, _ := os.Getwd()
		os.Chdir(tmpDir)
		defer os.Chdir(originalWd)

		cfg := Load()

		if cfg.Auth.Token != "my-custom-token" {
			t.Errorf("Auth.Token = %s, want my-custom-token", cfg.Auth.Token)
		}
		if cfg.Auth.Generated {
			t.Error("Auth.Generated 应该为 false")
		}
	})
}
