//go:build windows

package main

import (
	"log"

	toast "git.sr.ht/~jackmordaunt/go-toast"
)

// showNotification 在 Windows 上显示 Toast 通知
func showNotification(title, content string) {
	n := toast.Notification{
		AppID: "Notice CLI",
		Title: title,
		Body:  content,
	}

	if err := n.Push(); err != nil {
		log.Printf("显示通知失败: %v", err)
	}
}
