package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

// 版本信息（通过 -ldflags 注入）
var (
	Version   = "dev"
	BuildTime = "unknown"
)

// 全局配置
var globalExecCmd string

// Message 接收到的消息结构
type Message struct {
	Title     string    `json:"title"`
	Content   string    `json:"content"`
	Extra     any       `json:"extra,omitempty"`
	Timestamp time.Time `json:"timestamp"`
}

func main() {
	// 处理 --version 参数
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "-v") {
		fmt.Printf("notice-client %s\nBuild Time: %s\n", Version, BuildTime)
		os.Exit(0)
	}

	log.SetFlags(log.LstdFlags | log.Lshortfile)

	// 命令行参数
	broker := flag.String("broker", "tcp://localhost:9091", "MQTT Broker 地址")
	topic := flag.String("topic", "notice/#", "订阅的主题")
	clientID := flag.String("id", "cli-client", "客户端 ID")
	authToken := flag.String("token", "", "认证 Token (可选)")
	execCmd := flag.String("exec", "", "收到消息时执行的命令 (消息通过环境变量和stdin传递)")
	flag.Parse()

	// 保存到全局变量供 handleMessage 使用
	globalExecCmd = *execCmd

	log.Printf("启动 Notice Client...")
	log.Printf("连接到: %s", *broker)
	log.Printf("订阅主题: %s", *topic)
	if globalExecCmd != "" {
		log.Printf("消息处理命令: %s", globalExecCmd)
	}

	// Token 认证提示
	if *authToken == "" {
		log.Printf("警告: 未设置认证 Token，如果服务器启用了认证，连接将会失败")
		log.Printf("提示: 使用 -token=<your-token> 参数设置认证 Token")
	} else {
		log.Printf("认证已启用")
	}

	// 创建 MQTT 客户端
	opts := mqtt.NewClientOptions()
	opts.AddBroker(*broker)
	opts.SetClientID(*clientID)
	opts.SetCleanSession(false) // 持久会话，支持离线消息
	opts.SetAutoReconnect(true)
	opts.SetConnectRetry(false) // 首次连接失败时不自动重试，以便显示错误
	opts.SetConnectTimeout(10 * time.Second)
	opts.SetKeepAlive(30 * time.Second) // Cloudflare Tunnel 需要较短的心跳间隔
	opts.SetPingTimeout(10 * time.Second)
	opts.SetWriteTimeout(10 * time.Second)

	// Token 认证 (使用 username 传递 token)
	if *authToken != "" {
		opts.SetUsername(*authToken)
	}

	// 设置默认消息处理器（在连接前注册，确保能收到离线消息）
	opts.SetDefaultPublishHandler(func(c mqtt.Client, m mqtt.Message) {
		handleMessage(m.Topic(), m.Payload())
	})

	opts.SetOnConnectHandler(func(c mqtt.Client) {
		log.Println("已连接到 MQTT Broker")

		// 订阅主题（会话恢复时订阅已存在，但仍需注册处理函数）
		token := c.Subscribe(*topic, 1, nil) // 使用 nil，消息由 DefaultPublishHandler 处理
		if token.Wait() && token.Error() != nil {
			log.Printf("订阅失败: %v", token.Error())
		} else {
			log.Printf("已订阅: %s", *topic)
		}
	})

	opts.SetConnectionLostHandler(func(c mqtt.Client, err error) {
		log.Printf("连接断开: %v", err)
	})

	client := mqtt.NewClient(opts)

	// 连接
	log.Printf("正在连接...")
	token := client.Connect()
	if token.Wait() && token.Error() != nil {
		errMsg := token.Error().Error()
		log.Printf("连接失败: %v", token.Error())
		// 检查是否是认证问题
		if *authToken == "" {
			log.Printf("提示: 服务器可能需要认证，请使用 -token=<your-token> 参数")
		} else {
			log.Printf("提示: 请检查 Token 是否正确")
		}
		log.Fatalf("无法连接到服务器: %s", errMsg)
	}

	log.Println("等待消息推送...")

	// 等待退出信号
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("正在关闭...")
	client.Disconnect(1000)
	log.Println("已断开连接")
}

// handleMessage 处理接收到的消息
func handleMessage(topic string, payload []byte) {
	log.Printf("收到消息 [%s]: %s", topic, string(payload))

	var msg Message
	if err := json.Unmarshal(payload, &msg); err != nil {
		log.Printf("JSON 解析失败: %v", err)
		return
	}

	// 显示系统通知
	title := msg.Title
	if title == "" {
		title = "Notice"
	}
	showNotification(title, msg.Content)

	// 执行外部命令
	if globalExecCmd != "" {
		go executeCommand(globalExecCmd, topic, payload, &msg)
	}
}

// executeCommand 执行外部命令
// 消息通过以下方式传递:
// - 环境变量: NOTICE_TOPIC, NOTICE_TITLE, NOTICE_CONTENT, NOTICE_EXTRA, NOTICE_TIMESTAMP, NOTICE_RAW
// - stdin: 原始 JSON 消息
func executeCommand(cmdStr, topic string, payload []byte, msg *Message) {
	// 解析命令（支持带参数的命令）
	parts := parseCommand(cmdStr)
	if len(parts) == 0 {
		log.Printf("无效的命令: %s", cmdStr)
		return
	}

	cmd := exec.Command(parts[0], parts[1:]...)

	// 设置环境变量
	cmd.Env = append(os.Environ(),
		"NOTICE_TOPIC="+topic,
		"NOTICE_TITLE="+msg.Title,
		"NOTICE_CONTENT="+msg.Content,
		"NOTICE_TIMESTAMP="+msg.Timestamp.Format(time.RFC3339),
		"NOTICE_RAW="+string(payload),
	)

	// Extra 字段转为 JSON 字符串
	if msg.Extra != nil {
		extraJSON, err := json.Marshal(msg.Extra)
		if err == nil {
			cmd.Env = append(cmd.Env, "NOTICE_EXTRA="+string(extraJSON))
		}
	}

	// 通过 stdin 传递原始 JSON
	cmd.Stdin = bytes.NewReader(payload)

	// 捕获输出
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	log.Printf("执行命令: %s", cmdStr)
	if err := cmd.Run(); err != nil {
		log.Printf("命令执行失败: %v, stderr: %s", err, stderr.String())
		return
	}

	if stdout.Len() > 0 {
		log.Printf("命令输出: %s", strings.TrimSpace(stdout.String()))
	}
}

// parseCommand 解析命令字符串，支持引号
func parseCommand(cmdStr string) []string {
	var parts []string
	var current strings.Builder
	var inQuote rune
	var escaped bool

	for _, r := range cmdStr {
		if escaped {
			current.WriteRune(r)
			escaped = false
			continue
		}

		if r == '\\' {
			escaped = true
			continue
		}

		if inQuote != 0 {
			if r == inQuote {
				inQuote = 0
			} else {
				current.WriteRune(r)
			}
			continue
		}

		switch r {
		case '"', '\'':
			inQuote = r
		case ' ', '\t':
			if current.Len() > 0 {
				parts = append(parts, current.String())
				current.Reset()
			}
		default:
			current.WriteRune(r)
		}
	}

	if current.Len() > 0 {
		parts = append(parts, current.String())
	}

	return parts
}
