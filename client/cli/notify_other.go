//go:build !windows

package main

import (
	"log"

	"github.com/gen2brain/beeep"
)

// showNotification 在非 Windows 平台显示系统通知
func showNotification(title, content string) {
	if err := beeep.Notify(title, content, ""); err != nil {
		log.Printf("显示通知失败: %v", err)
	}
}
