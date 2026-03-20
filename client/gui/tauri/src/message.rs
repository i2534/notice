use base64::Engine;
use chrono::{DateTime, Utc};
use flate2::read::GzDecoder;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Read;
use std::path::PathBuf;

/// 接收到的消息结构
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NoticeMessage {
    #[serde(default)]
    pub title: String,
    pub content: String,
    #[serde(default, rename = "content_encoding")]
    pub content_encoding: Option<String>,
    #[serde(default)]
    pub extra: Option<serde_json::Value>,
    pub timestamp: DateTime<Utc>,
    #[serde(default)]
    pub client: Option<String>,
}

impl NoticeMessage {
    /// 若 `content_encoding` 为 `gzip+base64`，将 `content` 解为 UTF-8 明文。
    pub fn decode_content_if_needed(&mut self) {
        if self.content_encoding.as_deref() != Some("gzip+base64") {
            return;
        }
        let raw = match base64::engine::general_purpose::STANDARD.decode(self.content.as_bytes()) {
            Ok(b) => b,
            Err(e) => {
                log::warn!("notice base64 decode: {}", e);
                return;
            }
        };
        let mut dec = GzDecoder::new(&raw[..]);
        let mut s = String::new();
        if let Err(e) = dec.read_to_string(&mut s) {
            log::warn!("notice gzip decode: {}", e);
            return;
        }
        self.content = s;
        self.content_encoding = None;
    }
}

/// 消息事件 (发送到前端)
#[derive(Debug, Clone, Serialize)]
pub struct MessageEvent {
    pub topic: String,
    pub message: NoticeMessage,
}

/// 存储的消息结构 (包含 topic)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredMessage {
    pub topic: String,
    pub title: String,
    pub content: String,
    pub timestamp: String,
}

const MESSAGES_FILE: &str = "messages.json";
const MAX_MESSAGES: usize = 100;

/// 加载消息历史
pub fn load_messages(config_dir: &PathBuf) -> Vec<StoredMessage> {
    let path = config_dir.join(MESSAGES_FILE);
    if path.exists() {
        match fs::read_to_string(&path) {
            Ok(content) => match serde_json::from_str(&content) {
                Ok(messages) => return messages,
                Err(e) => log::warn!("解析消息文件失败: {}", e),
            },
            Err(e) => log::warn!("读取消息文件失败: {}", e),
        }
    }
    Vec::new()
}

/// 保存消息历史
pub fn save_messages(config_dir: &PathBuf, messages: &[StoredMessage]) -> Result<(), String> {
    fs::create_dir_all(config_dir).map_err(|e| e.to_string())?;
    
    // 限制保存数量
    let to_save: Vec<_> = messages.iter().take(MAX_MESSAGES).cloned().collect();
    
    let path = config_dir.join(MESSAGES_FILE);
    let content = serde_json::to_string_pretty(&to_save).map_err(|e| e.to_string())?;
    fs::write(path, content).map_err(|e| e.to_string())?;
    Ok(())
}
