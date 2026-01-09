package store

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"path/filepath"
	"sync"
	"time"

	"github.com/dgraph-io/badger/v4"
)

const (
	// storeDirName 存储目录名
	storeDirName = "store"
)

// ErrTokenCollision token 碰撞错误
var ErrTokenCollision = errors.New("token 碰撞：该存储目录已被其他 token 占用")

// Message 存储的消息结构
type Message struct {
	ID        uint64    `json:"id"`
	Topic     string    `json:"topic"`
	Title     string    `json:"title"`
	Content   string    `json:"content"`
	Extra     any       `json:"extra,omitempty"`
	Timestamp time.Time `json:"timestamp"`
}

// CursorResult 游标分页结果
type CursorResult struct {
	Messages []Message `json:"messages"`
	Total    int       `json:"total"`
	PageSize int       `json:"page_size"`
	HasMore  bool      `json:"has_more"`
	NextID   uint64    `json:"next_id,omitempty"`
}

// tokenHash 计算 token 的 hash（32 字符，用作文件夹名）
// 使用 128 位 hash，碰撞概率极低（需要约 2^64 个 token 才有 50% 碰撞概率）
func tokenHash(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:16]) // 16 字节 = 128 位 = 32 字符
}

// tokenPath 生成分层的存储路径
// 使用 hash 的前 2 字符作为第一层目录，避免单个目录下文件过多
// 例如: hash="a1b2c3..." -> "a1/a1b2c3..."
func tokenPath(basePath, hash string) string {
	if len(hash) < 2 {
		return filepath.Join(basePath, hash)
	}
	return filepath.Join(basePath, hash[:2], hash)
}

// ============== TokenStore: 单个 token 的存储 ==============

// TokenStore 单个 token 的消息存储
type TokenStore struct {
	db    *badger.DB
	seq   *badger.Sequence
	token string // 存储原始 token，用于验证
	count uint64
	mu    sync.RWMutex
}

// newTokenStore 创建单个 token 的存储
func newTokenStore(path string, token string) (*TokenStore, error) {
	opts := badger.DefaultOptions(path)
	opts.Logger = nil

	db, err := badger.Open(opts)
	if err != nil {
		return nil, err
	}

	// 验证或设置 token
	var storedToken string
	err = db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte("meta:token"))
		if err != nil {
			return err
		}
		return item.Value(func(val []byte) error {
			storedToken = string(val)
			return nil
		})
	})

	if err == badger.ErrKeyNotFound {
		// 首次创建，保存 token
		err = db.Update(func(txn *badger.Txn) error {
			return txn.Set([]byte("meta:token"), []byte(token))
		})
		if err != nil {
			db.Close()
			return nil, err
		}
	} else if err != nil {
		db.Close()
		return nil, err
	} else if storedToken != token {
		// token 不匹配，发生碰撞
		db.Close()
		return nil, ErrTokenCollision
	}

	seq, err := db.GetSequence([]byte("seq:msg"), 100)
	if err != nil {
		db.Close()
		return nil, err
	}

	ts := &TokenStore{
		db:    db,
		seq:   seq,
		token: token,
	}
	ts.loadCount()

	return ts, nil
}

func (ts *TokenStore) loadCount() {
	ts.mu.Lock()
	defer ts.mu.Unlock()

	err := ts.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte("meta:count"))
		if err != nil {
			return err
		}
		return item.Value(func(val []byte) error {
			if len(val) == 8 {
				ts.count = binary.BigEndian.Uint64(val)
			}
			return nil
		})
	})

	if err == badger.ErrKeyNotFound {
		ts.count = ts.countMessages()
		ts.saveCount()
	}
}

func (ts *TokenStore) countMessages() uint64 {
	var count uint64
	ts.db.View(func(txn *badger.Txn) error {
		opts := badger.DefaultIteratorOptions
		opts.PrefetchValues = false
		it := txn.NewIterator(opts)
		defer it.Close()

		prefix := []byte("msg:")
		for it.Seek(prefix); it.ValidForPrefix(prefix); it.Next() {
			count++
		}
		return nil
	})
	return count
}

func (ts *TokenStore) saveCount() {
	buf := make([]byte, 8)
	binary.BigEndian.PutUint64(buf, ts.count)
	ts.db.Update(func(txn *badger.Txn) error {
		return txn.Set([]byte("meta:count"), buf)
	})
}

func (ts *TokenStore) makeKey(id uint64) []byte {
	key := make([]byte, 12)
	copy(key, "msg:")
	binary.BigEndian.PutUint64(key[4:], id)
	return key
}

// Save 保存消息
func (ts *TokenStore) Save(topic, title, content string, extra any) (*Message, error) {
	id, err := ts.seq.Next()
	if err != nil {
		return nil, err
	}

	msg := &Message{
		ID:        id,
		Topic:     topic,
		Title:     title,
		Content:   content,
		Extra:     extra,
		Timestamp: time.Now(),
	}

	data, err := json.Marshal(msg)
	if err != nil {
		return nil, err
	}

	err = ts.db.Update(func(txn *badger.Txn) error {
		return txn.Set(ts.makeKey(id), data)
	})
	if err != nil {
		return nil, err
	}

	ts.mu.Lock()
	ts.count++
	count := ts.count
	ts.mu.Unlock()

	if count%100 == 0 {
		go ts.saveCount()
	}

	return msg, nil
}

// List 游标分页查询
func (ts *TokenStore) List(beforeID uint64, pageSize int) (*CursorResult, error) {
	if pageSize < 1 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}

	ts.mu.RLock()
	total := int(ts.count)
	ts.mu.RUnlock()

	var messages []Message
	var nextID uint64

	err := ts.db.View(func(txn *badger.Txn) error {
		opts := badger.DefaultIteratorOptions
		opts.Reverse = true

		it := txn.NewIterator(opts)
		defer it.Close()

		prefix := []byte("msg:")

		if beforeID > 0 {
			it.Seek(ts.makeKey(beforeID))
			if it.ValidForPrefix(prefix) {
				it.Next()
			}
		} else {
			it.Seek(append(prefix, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
		}

		count := 0
		for ; it.ValidForPrefix(prefix) && count < pageSize+1; it.Next() {
			if count == pageSize {
				key := it.Item().Key()
				if len(key) >= 12 {
					nextID = binary.BigEndian.Uint64(key[4:])
				}
				break
			}

			item := it.Item()
			err := item.Value(func(val []byte) error {
				var msg Message
				if err := json.Unmarshal(val, &msg); err != nil {
					return err
				}
				messages = append(messages, msg)
				return nil
			})
			if err != nil {
				return err
			}
			count++
		}

		return nil
	})

	if err != nil {
		return nil, err
	}

	return &CursorResult{
		Messages: messages,
		Total:    total,
		PageSize: pageSize,
		HasMore:  nextID > 0,
		NextID:   nextID,
	}, nil
}

// Count 获取消息总数
func (ts *TokenStore) Count() int {
	ts.mu.RLock()
	defer ts.mu.RUnlock()
	return int(ts.count)
}

// Close 关闭存储
func (ts *TokenStore) Close() error {
	if ts.seq != nil {
		ts.seq.Release()
	}
	ts.saveCount()
	if ts.db != nil {
		return ts.db.Close()
	}
	return nil
}

// ============== Manager: 管理所有 token 的存储 ==============

// Manager 管理多个 token 的存储
type Manager struct {
	basePath string
	enabled  bool
	stores   map[string]*TokenStore // hash -> store
	mu       sync.RWMutex
}

// NewManager 创建存储管理器
func NewManager(path string, enabled bool) *Manager {
	return &Manager{
		basePath: filepath.Join(path, storeDirName),
		enabled:  enabled,
		stores:   make(map[string]*TokenStore),
	}
}

// GetStore 获取或创建 token 的存储
func (m *Manager) GetStore(token string) (*TokenStore, error) {
	if !m.enabled {
		return nil, nil
	}

	hash := tokenHash(token)

	m.mu.RLock()
	ts, ok := m.stores[hash]
	m.mu.RUnlock()

	if ok {
		return ts, nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	// 双重检查
	if ts, ok = m.stores[hash]; ok {
		return ts, nil
	}

	// 创建新的存储（分层路径）
	path := tokenPath(m.basePath, hash)
	ts, err := newTokenStore(path, token)
	if err != nil {
		return nil, err
	}

	m.stores[hash] = ts
	return ts, nil
}

// Save 保存消息（便捷方法）
func (m *Manager) Save(token, topic, title, content string, extra any) (*Message, error) {
	if !m.enabled {
		return nil, nil
	}

	ts, err := m.GetStore(token)
	if err != nil {
		return nil, err
	}
	if ts == nil {
		return nil, nil
	}

	return ts.Save(topic, title, content, extra)
}

// List 查询消息（便捷方法）
func (m *Manager) List(token string, beforeID uint64, pageSize int) (*CursorResult, error) {
	if !m.enabled {
		return &CursorResult{
			Messages: []Message{},
			PageSize: pageSize,
		}, nil
	}

	ts, err := m.GetStore(token)
	if err != nil {
		return nil, err
	}
	if ts == nil {
		return &CursorResult{
			Messages: []Message{},
			PageSize: pageSize,
		}, nil
	}

	return ts.List(beforeID, pageSize)
}

// Count 获取消息总数（便捷方法）
func (m *Manager) Count(token string) int {
	if !m.enabled {
		return 0
	}

	m.mu.RLock()
	hash := tokenHash(token)
	ts, ok := m.stores[hash]
	m.mu.RUnlock()

	if !ok {
		return 0
	}
	return ts.Count()
}

// Close 关闭所有存储
func (m *Manager) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, ts := range m.stores {
		ts.Close()
	}
	m.stores = make(map[string]*TokenStore)
	return nil
}

// IsEnabled 是否启用
func (m *Manager) IsEnabled() bool {
	return m.enabled
}
