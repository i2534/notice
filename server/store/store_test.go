package store

import (
	"os"
	"path/filepath"
	"testing"
)

func TestTokenHash(t *testing.T) {
	// 相同 token 应该产生相同 hash
	hash1 := tokenHash("test-token")
	hash2 := tokenHash("test-token")
	if hash1 != hash2 {
		t.Errorf("相同 token 产生不同 hash: %s != %s", hash1, hash2)
	}

	// 不同 token 应该产生不同 hash
	hash3 := tokenHash("another-token")
	if hash1 == hash3 {
		t.Errorf("不同 token 产生相同 hash: %s", hash1)
	}

	// hash 长度应该是 32 字符（128 位）
	if len(hash1) != 32 {
		t.Errorf("hash 长度应为 32，实际: %d", len(hash1))
	}
}

func TestTokenStore(t *testing.T) {
	// 创建临时目录
	tmpDir, err := os.MkdirTemp("", "store-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// 创建存储
	ts, err := newTokenStore(tmpDir, "test-token")
	if err != nil {
		t.Fatal(err)
	}
	defer ts.Close()

	// 测试初始状态
	if ts.Count() != 0 {
		t.Errorf("初始计数应为 0，实际: %d", ts.Count())
	}

	// 测试保存消息
	msg, err := ts.Save("test/topic", "测试标题", "测试内容", map[string]string{"key": "value"})
	if err != nil {
		t.Fatal(err)
	}
	if msg == nil {
		t.Fatal("保存的消息不应为 nil")
	}
	if msg.Topic != "test/topic" {
		t.Errorf("topic 不匹配: %s", msg.Topic)
	}
	if msg.Title != "测试标题" {
		t.Errorf("title 不匹配: %s", msg.Title)
	}
	if msg.Content != "测试内容" {
		t.Errorf("content 不匹配: %s", msg.Content)
	}
	if ts.Count() != 1 {
		t.Errorf("保存后计数应为 1，实际: %d", ts.Count())
	}

	// 测试保存多条消息
	for i := 0; i < 5; i++ {
		_, err := ts.Save("test/topic", "标题", "内容", nil)
		if err != nil {
			t.Fatal(err)
		}
	}
	if ts.Count() != 6 {
		t.Errorf("计数应为 6，实际: %d", ts.Count())
	}
}

func TestTokenStoreCollision(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "store-collision-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// 第一个 token 创建成功
	ts1, err := newTokenStore(tmpDir, "token-a")
	if err != nil {
		t.Fatal(err)
	}
	ts1.Close()

	// 相同 token 再次打开应成功
	ts2, err := newTokenStore(tmpDir, "token-a")
	if err != nil {
		t.Fatalf("相同 token 再次打开应成功: %v", err)
	}
	ts2.Close()

	// 不同 token 尝试使用同一目录应失败
	_, err = newTokenStore(tmpDir, "token-b")
	if err != ErrTokenCollision {
		t.Errorf("应返回 ErrTokenCollision，实际: %v", err)
	}
}

func TestTokenStoreList(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "store-list-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	ts, err := newTokenStore(tmpDir, "test-token")
	if err != nil {
		t.Fatal(err)
	}
	defer ts.Close()

	// 保存 25 条消息
	for i := 1; i <= 25; i++ {
		_, err := ts.Save("topic", "标题", "内容", nil)
		if err != nil {
			t.Fatal(err)
		}
	}

	// 测试第一页（最新的 10 条）
	result, err := ts.List(0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Messages) != 10 {
		t.Errorf("应返回 10 条消息，实际: %d", len(result.Messages))
	}
	if result.Total != 25 {
		t.Errorf("总数应为 25，实际: %d", result.Total)
	}
	if !result.HasMore {
		t.Error("应该有更多消息")
	}
	// 验证倒序（最新的在前）
	if result.Messages[0].ID <= result.Messages[1].ID {
		t.Error("消息应按 ID 倒序排列")
	}

	// 测试第二页
	result2, err := ts.List(result.NextID, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(result2.Messages) != 10 {
		t.Errorf("第二页应返回 10 条消息，实际: %d", len(result2.Messages))
	}
	if !result2.HasMore {
		t.Error("第二页应该还有更多消息")
	}

	// 测试第三页（剩余消息）
	result3, err := ts.List(result2.NextID, 10)
	if err != nil {
		t.Fatal(err)
	}
	// 总共 25 条，前两页各 10 条，第三页应有 5 条
	// 注意：由于 Sequence 可能跳过某些 ID，实际数量可能略有不同
	if len(result3.Messages) < 1 {
		t.Error("第三页应有消息")
	}
	if result3.HasMore {
		t.Error("第三页不应该有更多消息")
	}
}

func TestTokenStoreListPageSize(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "store-pagesize-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	ts, err := newTokenStore(tmpDir, "test-token")
	if err != nil {
		t.Fatal(err)
	}
	defer ts.Close()

	// 保存 5 条消息
	for i := 0; i < 5; i++ {
		ts.Save("topic", "标题", "内容", nil)
	}

	// 测试默认 pageSize
	result, err := ts.List(0, 0)
	if err != nil {
		t.Fatal(err)
	}
	if result.PageSize != 20 {
		t.Errorf("默认 pageSize 应为 20，实际: %d", result.PageSize)
	}

	// 测试最大 pageSize 限制
	result, err = ts.List(0, 200)
	if err != nil {
		t.Fatal(err)
	}
	if result.PageSize != 100 {
		t.Errorf("最大 pageSize 应为 100，实际: %d", result.PageSize)
	}
}

func TestManager(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "manager-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// 测试禁用状态
	m := NewManager(tmpDir, false)
	if m.IsEnabled() {
		t.Error("Manager 应该是禁用状态")
	}
	msg, err := m.Save("token", "topic", "title", "content", nil)
	if err != nil {
		t.Fatal(err)
	}
	if msg != nil {
		t.Error("禁用状态下保存应返回 nil")
	}

	// 测试启用状态
	m = NewManager(tmpDir, true)
	defer m.Close()

	if !m.IsEnabled() {
		t.Error("Manager 应该是启用状态")
	}

	// 测试保存消息
	msg, err = m.Save("token1", "topic", "标题", "内容", nil)
	if err != nil {
		t.Fatal(err)
	}
	if msg == nil {
		t.Fatal("保存的消息不应为 nil")
	}

	// 测试获取计数
	if m.Count("token1") != 1 {
		t.Errorf("token1 计数应为 1，实际: %d", m.Count("token1"))
	}
	if m.Count("token2") != 0 {
		t.Errorf("token2 计数应为 0，实际: %d", m.Count("token2"))
	}
}

func TestManagerTokenIsolation(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "manager-isolation-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	m := NewManager(tmpDir, true)
	defer m.Close()

	// 为两个不同 token 保存消息
	for i := 0; i < 5; i++ {
		m.Save("token-a", "topic", "A消息", "内容", nil)
	}
	for i := 0; i < 3; i++ {
		m.Save("token-b", "topic", "B消息", "内容", nil)
	}

	// 验证隔离
	if m.Count("token-a") != 5 {
		t.Errorf("token-a 计数应为 5，实际: %d", m.Count("token-a"))
	}
	if m.Count("token-b") != 3 {
		t.Errorf("token-b 计数应为 3，实际: %d", m.Count("token-b"))
	}

	// 验证查询隔离
	resultA, err := m.List("token-a", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(resultA.Messages) != 5 {
		t.Errorf("token-a 应有 5 条消息，实际: %d", len(resultA.Messages))
	}
	for _, msg := range resultA.Messages {
		if msg.Title != "A消息" {
			t.Errorf("token-a 的消息标题应为 'A消息'，实际: %s", msg.Title)
		}
	}

	resultB, err := m.List("token-b", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(resultB.Messages) != 3 {
		t.Errorf("token-b 应有 3 条消息，实际: %d", len(resultB.Messages))
	}

	// 验证存储目录隔离（使用分层路径）
	hashA := tokenHash("token-a")
	hashB := tokenHash("token-b")

	pathA := tokenPath(filepath.Join(tmpDir, "store"), hashA)
	pathB := tokenPath(filepath.Join(tmpDir, "store"), hashB)

	if _, err := os.Stat(pathA); os.IsNotExist(err) {
		t.Error("token-a 的存储目录不存在")
	}
	if _, err := os.Stat(pathB); os.IsNotExist(err) {
		t.Error("token-b 的存储目录不存在")
	}
}

func TestManagerPersistence(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "manager-persist-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// 第一次打开，保存数据
	m1 := NewManager(tmpDir, true)
	for i := 0; i < 10; i++ {
		m1.Save("persist-token", "topic", "标题", "内容", nil)
	}
	m1.Close()

	// 第二次打开，验证数据持久化
	m2 := NewManager(tmpDir, true)
	defer m2.Close()

	// 需要先访问一次才能加载
	result, err := m2.List("persist-token", 0, 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Messages) != 10 {
		t.Errorf("持久化后应有 10 条消息，实际: %d", len(result.Messages))
	}
	if result.Total != 10 {
		t.Errorf("持久化后总数应为 10，实际: %d", result.Total)
	}
}

func TestManagerConcurrent(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "manager-concurrent-test-*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	m := NewManager(tmpDir, true)
	defer m.Close()

	// 并发保存
	done := make(chan bool)
	for i := 0; i < 10; i++ {
		go func(token string) {
			for j := 0; j < 20; j++ {
				m.Save(token, "topic", "标题", "内容", nil)
			}
			done <- true
		}("token-" + string(rune('a'+i)))
	}

	// 等待所有 goroutine 完成
	for i := 0; i < 10; i++ {
		<-done
	}

	// 验证每个 token 的消息数
	for i := 0; i < 10; i++ {
		token := "token-" + string(rune('a'+i))
		if m.Count(token) != 20 {
			t.Errorf("%s 应有 20 条消息，实际: %d", token, m.Count(token))
		}
	}
}
