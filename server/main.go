package main

import (
	"embed"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"notice-server/broker"
	"notice-server/config"
	"notice-server/logger"
	"notice-server/webhook"
)

// 版本信息（通过 -ldflags 注入）
var (
	Version   = "dev"
	BuildTime = "unknown"
)

//go:embed web/*
var webFS embed.FS

func main() {
	// 处理 --version 参数
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "-v") {
		fmt.Printf("notice-server %s\nBuild Time: %s\n", Version, BuildTime)
		os.Exit(0)
	}

	// 加载配置
	cfg := config.Load()

	// 初始化日志
	logCfg := logger.Config{
		ConsoleLevel: cfg.LogConsoleLevel,
		FileLevel:    cfg.LogFileLevel,
		FilePath:     cfg.LogFilePath,
		Pretty:       cfg.LogPretty,
		RotateDays:   cfg.LogRotateDays,
		MaxFiles:     cfg.LogMaxFiles,
	}
	if _, err := logger.Init(logCfg); err != nil {
		fmt.Printf("日志初始化失败: %v\n", err)
		os.Exit(1)
	}

	logger.Info("启动 Notice Server...")

	// 创建并启动 MQTT Broker
	brokerCfg := broker.Config{
		SessionExpiry: cfg.SessionExpiry,
		MessageExpiry: cfg.MessageExpiry,
		AuthToken:     cfg.AuthToken,
	}
	mqttBroker := broker.New(cfg.MQTTTopic, brokerCfg)

	// 日志输出认证状态
	if cfg.TokenGenerated {
		logger.Warn("未设置 AUTH_TOKEN，已自动生成", "token", cfg.AuthToken)
	} else {
		logger.Info("认证已启用", "token_length", len(cfg.AuthToken))
	}
	if err := mqttBroker.Start(":"+cfg.MQTTTCPPort, ":"+cfg.MQTTWSPort); err != nil {
		logger.Error("MQTT Broker 启动失败", "error", err)
		os.Exit(1)
	}

	// 创建 Webhook 处理器
	webhookHandler := webhook.NewHandler(mqttBroker, cfg)

	// 注册 API 路由
	http.Handle("/webhook", webhookHandler)
	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/status", statusHandler(mqttBroker))

	// 注册 Web 页面路由
	webContent, _ := fs.Sub(webFS, "web")
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
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
		mqttBroker.Close()
		logger.Close() // 刷新并关闭日志文件
		os.Exit(0)
	}()

	// 启动 HTTP 服务器
	addr := ":" + cfg.HTTPPort
	logger.Info("HTTP 服务器启动", "addr", addr)
	logger.Info("Web 控制台", "url", fmt.Sprintf("http://localhost%s/", addr))
	logger.Info("Webhook 端点", "url", fmt.Sprintf("POST http://localhost%s/webhook", addr))

	if err := http.ListenAndServe(addr, nil); err != nil {
		logger.Error("HTTP 服务器启动失败", "error", err)
		os.Exit(1)
	}
}

// healthHandler 健康检查
func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status":"ok"}`))
}

// statusHandler 状态检查
func statusHandler(b *broker.Broker) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)

		clientCount := b.ClientCount()
		fmt.Fprintf(w, `{"status":"ok","clients":%d}`, clientCount)
	}
}
