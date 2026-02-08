package broker

import (
	"context"
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/eclipse/paho.golang/paho"
	"notice-server/logger"
	"notice-server/ratelimit"
)

const testMQTTToken = "correct-token"

func init() {
	// 测试前初始化 logger，避免 broker 内 logger.Get() 为 nil
	if logger.Get() == nil {
		_, _ = logger.Init(logger.Config{
			ConsoleLevel: "off",
			FileLevel:    "off",
		})
	}
}

// mqttConnect 使用指定 username/password 连接 broker，返回连接是否成功（未报错即成功）
func mqttConnect(t *testing.T, addr, clientID, username string, password []byte) (ok bool) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		t.Logf("dial error: %v", err)
		return false
	}
	defer conn.Close()

	cfg := paho.ClientConfig{
		Conn:         conn,
		ClientID:     clientID,
		PingHandler:  paho.NewDefaultPinger(),
		PacketTimeout: 2 * time.Second,
	}
	client := paho.NewClient(cfg)

	cp := &paho.Connect{
		ClientID:     clientID,
		KeepAlive:    60,
		CleanStart:   true,
		UsernameFlag: username != "",
		Username:     username,
		PasswordFlag: len(password) > 0,
		Password:     password,
	}

	_, err = client.Connect(ctx, cp)
	if err != nil {
		// 认证失败或封禁都会导致连接失败
		return false
	}
	_ = client.Disconnect(&paho.Disconnect{})
	return true
}

// TestMqttBruteForceToken 暴力破解 token：同一 IP 连续错误 token 超过 max_failures 后被封禁，封禁期内正确 token 也无法连接
func TestMqttBruteForceToken(t *testing.T) {
	blockTime := 200 * time.Millisecond
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures: 3,
		BlockTime:   blockTime,
		WindowTime:  60 * time.Second,
	})

	b := New("notice", Config{
		SessionExpiry:  120,
		MessageExpiry:  60,
		AuthToken:      testMQTTToken,
		StorageEnabled: false,
		AuthLimiter:    limiter,
	}, nil)

	err := b.Start("127.0.0.1:0", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("broker start: %v", err)
	}
	defer b.Close()

	time.Sleep(80 * time.Millisecond)
	addr := b.TCPAddr()
	if addr == "" {
		t.Fatal("TCP listener address is empty")
	}

	// 前 3 次：错误 token，应认证失败（连接不成功）
	for i := 0; i < 3; i++ {
		ok := mqttConnect(t, addr, fmt.Sprintf("wrong-client-%d", i), "wrong-token", nil)
		if ok {
			t.Errorf("attempt %d: wrong token should not connect", i+1)
		}
	}

	// 第 4 次：仍错误 token，应因封禁被拒（连接不成功）
	ok := mqttConnect(t, addr, "wrong-client-4", "wrong-token", nil)
	if ok {
		t.Error("4th wrong token (blocked): should not connect")
	}

	// 封禁期内用正确 token 也应被拒（按 IP 封禁，不区分 token）
	ok = mqttConnect(t, addr, "correct-client-blocked", testMQTTToken, nil)
	if ok {
		t.Error("correct token while blocked: should not connect")
	}

	// 等待封禁过期
	time.Sleep(blockTime + 80*time.Millisecond)

	// 解封后先用正确 token 应能连接（若先试错误 token 会再次触发封禁）
	ok = mqttConnect(t, addr, "correct-after-unblock", testMQTTToken, nil)
	if !ok {
		t.Error("correct token after unblock: should connect")
	}

	// 再用错误 token 应认证失败
	ok = mqttConnect(t, addr, "wrong-after-unblock", "wrong-token", nil)
	if ok {
		t.Error("wrong token after unblock: should not connect")
	}
}

// TestMqttBruteForceTokenUnblockAfterBlockTime 封禁过期后同一 IP 可再次尝试认证（错误仍 401，正确可连）
func TestMqttBruteForceTokenUnblockAfterBlockTime(t *testing.T) {
	blockTime := 100 * time.Millisecond
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures: 2,
		BlockTime:   blockTime,
		WindowTime:  60 * time.Second,
	})

	b := New("notice", Config{
		SessionExpiry:  120,
		MessageExpiry:  60,
		AuthToken:      "secret",
		StorageEnabled: false,
		AuthLimiter:    limiter,
	}, nil)

	err := b.Start("127.0.0.1:0", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("broker start: %v", err)
	}
	defer b.Close()

	time.Sleep(80 * time.Millisecond)
	addr := b.TCPAddr()
	if addr == "" {
		t.Fatal("TCP listener address is empty")
	}

	// 触发封禁：2 次错误
	for i := 0; i < 2; i++ {
		ok := mqttConnect(t, addr, fmt.Sprintf("unblock-test-wrong-%d", i), "wrong", nil)
		if ok {
			t.Fatalf("attempt %d: wrong token should not connect", i+1)
		}
	}
	// 第 3 次应被封禁
	ok := mqttConnect(t, addr, "unblock-test-blocked", "wrong", nil)
	if ok {
		t.Fatal("expected blocked after 2 failures")
	}

	time.Sleep(blockTime + 50*time.Millisecond)

	// 解封后先正确 token 应能连（先试错误会再次封禁）
	ok = mqttConnect(t, addr, "unblock-test-correct", "secret", nil)
	if !ok {
		t.Error("correct token after unblock should connect")
	}
	// 再错误 token 应认证失败
	ok = mqttConnect(t, addr, "unblock-test-after", "wrong", nil)
	if ok {
		t.Error("wrong token after unblock should not connect")
	}
}
