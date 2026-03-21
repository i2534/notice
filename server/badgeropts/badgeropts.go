// Package badgeropts 为消息历史与 MQTT 持久化提供统一的 Badger 打开参数，便于在低内存环境（如 ~2GB RAM VPS）下调内存占用。
package badgeropts

import (
	badgerdb "github.com/dgraph-io/badger/v4"
)

// 默认值面向约 1.8GB RAM 的 VPS：双库（消息 + MQTT）时比 Badger 默认（64MB×memtable、256MB 块缓存等）省不少常驻内存。
const (
	DefaultMemTableMB   = 12
	DefaultBlockCacheMB = 24
	DefaultNumMemtables = 2
)

// Params 与 storage 配置对应；字段为 0 时使用 Default*。
type Params struct {
	MemTableMB   int
	BlockCacheMB int
	NumMemtables int
}

func clampInt(v, min, max int) int {
	if v < min {
		return min
	}
	if v > max {
		return max
	}
	return v
}

// Normalized 返回生效参数（缺省补全，并限制在合理范围内）。
func (p Params) Normalized() Params {
	out := p
	if out.MemTableMB <= 0 {
		out.MemTableMB = DefaultMemTableMB
	}
	if out.BlockCacheMB <= 0 {
		out.BlockCacheMB = DefaultBlockCacheMB
	}
	if out.NumMemtables <= 0 {
		out.NumMemtables = DefaultNumMemtables
	}
	out.MemTableMB = clampInt(out.MemTableMB, 4, 128)
	out.BlockCacheMB = clampInt(out.BlockCacheMB, 8, 512)
	out.NumMemtables = clampInt(out.NumMemtables, 2, 8)
	return out
}

// Options 构造用于 badger.Open 的 Options（Logger 置 nil，由调用方按需 WithLoggingLevel）。
func Options(path string, p Params) badgerdb.Options {
	p = p.Normalized()
	o := badgerdb.DefaultOptions(path).
		WithMemTableSize(int64(p.MemTableMB) << 20).
		WithBlockCacheSize(int64(p.BlockCacheMB) << 20).
		WithNumMemtables(p.NumMemtables).
		WithNumCompactors(2)
	o.Logger = nil
	return o
}
