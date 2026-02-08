package handlers

import (
	"testing"
)

func TestContainsPathTraversal(t *testing.T) {
	tests := []struct {
		s    string
		want bool
	}{
		{"a/b", false},
		{"..", true},
		{"a/../b", true},
		{"topic", false},
		{"\\windows", true},
		{"", false},
	}
	for _, tt := range tests {
		if got := ContainsPathTraversal(tt.s); got != tt.want {
			t.Errorf("ContainsPathTraversal(%q) = %v, want %v", tt.s, got, tt.want)
		}
	}
}

func TestValidateTopic(t *testing.T) {
	tests := []struct {
		topic  string
		maxLen int
		ok     bool
	}{
		{"notice", 256, true},
		{"notice/foo", 256, true},
		{"a-b_c/d", 256, true},
		{"", 256, true},
		{"..", 256, false},
		{"a/../b", 256, false},
		{"a\x00b", 256, false},
		{"<script>", 256, false},
		{"a+b", 256, false}, // + 不允许（MQTT 通配符）
	}
	for _, tt := range tests {
		_, ok := ValidateTopic(tt.topic, tt.maxLen)
		if ok != tt.ok {
			t.Errorf("ValidateTopic(%q, %d) ok = %v, want %v", tt.topic, tt.maxLen, ok, tt.ok)
		}
	}
}

func TestSanitizeString(t *testing.T) {
	if got := SanitizeString("a\x00b\nc", 10); got != "abc" {
		t.Errorf("SanitizeString = %q, want abc", got)
	}
	if got := SanitizeString("hello世界", 3); len([]rune(got)) != 3 {
		t.Errorf("SanitizeString length = %d, want 3", len([]rune(got)))
	}
}

func TestSanitizeContent_PreservesMarkdownNewlines(t *testing.T) {
	// Markdown 依赖 \n \t，应保留
	input := "# 标题\n\n- 列表\n\t代码"
	got := SanitizeContent(input, 100)
	if got != input {
		t.Errorf("SanitizeContent = %q, want %q", got, input)
	}
	// NUL 仍应去除，\n 保留
	got2 := SanitizeContent("a\x00b\nc", 10)
	if got2 != "ab\nc" {
		t.Errorf("SanitizeContent with NUL = %q, want ab\\nc", got2)
	}
}

func TestValidatePageSize(t *testing.T) {
	if got := ValidatePageSize(0); got != 1 {
		t.Errorf("ValidatePageSize(0) = %d, want 1", got)
	}
	if got := ValidatePageSize(200); got != MaxPageSize {
		t.Errorf("ValidatePageSize(200) = %d, want %d", got, MaxPageSize)
	}
	if got := ValidatePageSize(50); got != 50 {
		t.Errorf("ValidatePageSize(50) = %d, want 50", got)
	}
}

func TestParseUintParam(t *testing.T) {
	if v, ok := ParseUintParam("123"); !ok || v != 123 {
		t.Errorf("ParseUintParam(123) = %v, %v", v, ok)
	}
	if _, ok := ParseUintParam("-1"); ok {
		t.Error("ParseUintParam(-1) should be invalid")
	}
	if _, ok := ParseUintParam("abc"); ok {
		t.Error("ParseUintParam(abc) should be invalid")
	}
}
