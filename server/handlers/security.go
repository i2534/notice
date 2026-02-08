package handlers

import (
	"strconv"
	"strings"
	"unicode"
)

// 安全相关常量
const (
	MaxTokenLength = 100        // 从 Header/Query 读取的 token 最大长度
	MaxTopicLength = 50         // MQTT 主题最大长度
	MaxPageSize    = 100        // 消息列表单页最大条数
	DefaultMaxBody = 512 * 1024 // 默认请求体上限 512KB
)

// ContainsPathTraversal 检查是否包含路径穿越（.. 或 反斜杠）
func ContainsPathTraversal(s string) bool {
	if strings.Contains(s, "..") {
		return true
	}
	if strings.Contains(s, "\\") {
		return true
	}
	return false
}

// ContainsControlOrNull 是否包含控制字符或 NUL（用于防注入/异常解析）
func ContainsControlOrNull(s string) bool {
	for _, r := range s {
		if r == 0 || r == '\uFFFD' || unicode.IsControl(r) {
			return true
		}
	}
	return false
}

// SanitizeString 去除控制字符并截断长度，用于 title/client 等单行文本（防 XSS/注入）
// 会去除 \n \r \t，故不应用于 Markdown 正文。
func SanitizeString(s string, maxLen int) string {
	var b strings.Builder
	b.Grow(len(s))
	for i, r := range s {
		if i >= maxLen {
			break
		}
		if r == 0 || r == '\uFFFD' || unicode.IsControl(r) {
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

// SanitizeContent 用于消息正文（如 Markdown）：仅去除 NUL、替换字符及不可见控制字符，保留 \t \n \r
func SanitizeContent(s string, maxLen int) string {
	var b strings.Builder
	b.Grow(len(s))
	n := 0
	for _, r := range s {
		if n >= maxLen {
			break
		}
		if r == 0 || r == '\uFFFD' {
			continue
		}
		if unicode.IsControl(r) && r != '\t' && r != '\n' && r != '\r' {
			continue
		}
		b.WriteRune(r)
		n++
	}
	return b.String()
}

// ValidateTopic 校验 MQTT 主题：仅允许字母数字、/、-、_，禁止 .. 与控制字符，长度限制
func ValidateTopic(topic string, maxLen int) (string, bool) {
	topic = strings.TrimSpace(topic)
	if topic == "" {
		return "", true // 空表示使用默认主题，合法
	}
	if len(topic) > maxLen {
		return "", false
	}
	if ContainsPathTraversal(topic) || ContainsControlOrNull(topic) {
		return "", false
	}
	for _, r := range topic {
		if !isSafeTopicRune(r) {
			return "", false
		}
	}
	return topic, true
}

func isSafeTopicRune(r rune) bool {
	return r == '/' || r == '-' || r == '_' ||
		(r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
}

// TruncateToken 限制 token 长度，避免超大 Header/Query 导致问题
func TruncateToken(s string, maxLen int) string {
	if maxLen <= 0 {
		maxLen = MaxTokenLength
	}
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen]
}

// ValidatePageSize 校验分页大小，返回 [1, MaxPageSize] 内的值
func ValidatePageSize(n int) int {
	if n < 1 {
		return 1
	}
	if n > MaxPageSize {
		return MaxPageSize
	}
	return n
}

// ParseUintParam 解析无符号整数参数，禁止前导零等异常格式（防注入）
func ParseUintParam(s string) (uint64, bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, true
	}
	if len(s) > 20 {
		return 0, false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return 0, false
		}
	}
	v, err := strconv.ParseUint(s, 10, 64)
	return v, err == nil
}
