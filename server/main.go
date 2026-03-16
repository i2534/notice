package main

import (
	"embed"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"notice-server/broker"
	"notice-server/config"
	"notice-server/handlers"
	"notice-server/logger"
	"notice-server/ratelimit"
	"notice-server/store"
)

// 版本信息（通过 -ldflags 注入）
var (
	Version    = "dev"
	BuildTime  = "unknown"
	ProjectURL = "https://github.com/i2534/notice"
)

//go:embed web/*
var webFS embed.FS

func main() {
	// 处理 --version 参数
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "-v") {
		fmt.Printf("notice-server %s\nBuild Time: %s\nProject: %s\n", Version, BuildTime, ProjectURL)
		os.Exit(0)
	}

	// 加载配置
	cfg := config.Load()

	// 初始化日志
	logCfg := logger.Config{
		ConsoleLevel: cfg.Log.ConsoleLevel,
		FileLevel:    cfg.Log.FileLevel,
		FilePath:     cfg.Log.FilePath,
		Pretty:       cfg.Log.Pretty,
		RotateDays:   cfg.Log.RotateDays,
		MaxFiles:     cfg.Log.MaxFiles,
	}
	if _, err := logger.Init(logCfg); err != nil {
		fmt.Printf("日志初始化失败: %v\n", err)
		os.Exit(1)
	}

	logger.Info("启动 Notice Server...", "version", Version, "build", BuildTime)
	logger.Info("项目地址", "url", ProjectURL)

	// 创建消息存储管理器
	storeManager := store.NewManager(cfg.Storage.Path, cfg.Storage.Enabled)
	if storeManager.IsEnabled() {
		logger.Info("消息存储已启用", "path", cfg.Storage.Path)
	}

	// 认证限流（Webhook 与 MQTT 共用，防暴力尝试）
	authLimiter := ratelimit.New(ratelimit.Config{
		MaxFailures:            cfg.RateLimit.MaxFailures,
		BlockTime:             time.Duration(cfg.RateLimit.BlockTime) * time.Second,
		WindowTime:            time.Duration(cfg.RateLimit.WindowTime) * time.Second,
		GlobalMaxPerMinute:     cfg.RateLimit.GlobalMaxPerMinute,
		GlobalBlockTime:       time.Duration(cfg.RateLimit.GlobalBlockTime) * time.Second,
		CredentialMaxFailures: cfg.RateLimit.CredentialMaxFailures,
	})

	// 创建并启动 MQTT Broker
	brokerCfg := broker.Config{
		SessionExpiry:  cfg.MQTT.SessionExpiry,
		MessageExpiry:  cfg.MQTT.MessageExpiry,
		AuthToken:      cfg.Auth.Token,
		StorageEnabled: cfg.Storage.Enabled,
		StoragePath:    cfg.Storage.Path,
		AuthLimiter:    authLimiter,
	}
	mqttBroker := broker.New(cfg.MQTT.Topic, brokerCfg, storeManager)

	// 日志输出认证状态
	if cfg.Auth.Generated {
		logger.Warn("未设置 AUTH_TOKEN，已自动生成", "token", cfg.Auth.Token)
	} else {
		logger.Info("认证已启用", "token_length", len(cfg.Auth.Token))
	}
	if err := mqttBroker.Start(":"+cfg.MQTT.TCPPort, ":"+cfg.MQTT.WSPort); err != nil {
		logger.Error("MQTT Broker 启动失败", "error", err)
		os.Exit(1)
	}

	// 注册 API 路由（Webhook 与 MQTT 共用同一限流器）
	http.Handle("/webhook", handlers.NewWebhookHandler(mqttBroker, cfg, authLimiter))
	http.HandleFunc("/health", handlers.HealthHandler)
	http.HandleFunc("/status", handlers.StatusHandler(mqttBroker, storeManager))
	http.HandleFunc("/messages", handlers.MessagesHandler(storeManager, cfg))
	http.HandleFunc("/api/image", handlers.ImageHandler(cfg, cfg.Storage.Path))
	http.HandleFunc("/api/upload", handlers.UploadHandler(cfg, cfg.Storage.Path, authLimiter))
	http.HandleFunc("/api/media/upload", handlers.MediaUploadHandler(cfg, cfg.Storage.Path, authLimiter))
	http.HandleFunc("/api/media", handlers.MediaHandler(cfg, cfg.Storage.Path))

	// 过期图片与多媒体自动清理（按配置间隔执行）
	imageCleanupStop := make(chan struct{})
	mediaCleanupStop := make(chan struct{})
	go handlers.RunImageCleanupLoop(cfg, cfg.Storage.Path, imageCleanupStop)
	go handlers.RunMediaCleanupLoop(cfg, cfg.Storage.Path, mediaCleanupStop)

	// 注册 Web 页面路由（拒绝路径穿越）
	webContent, _ := fs.Sub(webFS, "web")
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "..") || strings.Contains(r.URL.Path, "\\") {
			http.Error(w, "Bad Request", http.StatusBadRequest)
			return
		}
		if r.URL.Path == "/" {
			http.ServeFileFS(w, r, webContent, "index.html")
			return
		}
		http.FileServerFS(webContent).ServeHTTP(w, r)
	})

	// 优雅关闭
	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
		<-sigChan

		logger.Info("正在关闭服务...")
		close(imageCleanupStop)
		close(mediaCleanupStop)
		mqttBroker.Close()
		storeManager.Close()
		logger.Close() // 刷新并关闭日志文件
		os.Exit(0)
	}()

	// 启动 HTTP 服务器
	addr := ":" + cfg.HTTP.Port
	logger.Info("HTTP 服务器启动", "addr", addr)
	logger.Info("Web 控制台", "url", fmt.Sprintf("http://localhost%s/", addr))
	logger.Info("Webhook 端点", "url", fmt.Sprintf("POST http://localhost%s/webhook", addr))
	logger.Info("消息历史", "url", fmt.Sprintf("GET http://localhost%s/messages", addr))

	if err := http.ListenAndServe(addr, nil); err != nil {
		logger.Error("HTTP 服务器启动失败", "error", err)
		os.Exit(1)
	}
}
