use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// 连接协议类型
#[derive(Debug, Clone, PartialEq)]
pub enum Protocol {
    /// 普通 TCP (tcp://)
    Tcp,
    /// TCP + TLS (ssl://)
    Ssl,
    /// WebSocket (ws://)
    Ws,
    /// WebSocket + TLS (wss://)
    Wss,
}

/// 解析后的服务器信息
#[derive(Debug, Clone)]
pub struct ServerInfo {
    pub protocol: Protocol,
    pub host: String,
    pub port: u16,
    pub path: String,
}

impl ServerInfo {
    /// 从 URL 解析服务器信息
    /// 支持格式:
    /// - tcp://host:port
    /// - ssl://host:port
    /// - ws://host:port/path
    /// - wss://host:port/path
    /// - host:port (默认 tcp)
    /// - host (默认 tcp, 端口 1883)
    pub fn parse(url: &str) -> Result<Self, String> {
        let url = url.trim();
        
        // 解析协议
        let (protocol, rest) = if url.starts_with("ssl://") {
            (Protocol::Ssl, &url[6..])
        } else if url.starts_with("tcp://") {
            (Protocol::Tcp, &url[6..])
        } else if url.starts_with("wss://") {
            (Protocol::Wss, &url[6..])
        } else if url.starts_with("ws://") {
            (Protocol::Ws, &url[5..])
        } else {
            // 无协议前缀，默认 TCP
            (Protocol::Tcp, url)
        };

        // 默认端口
        let default_port = match protocol {
            Protocol::Tcp => 1883,
            Protocol::Ssl => 8883,
            Protocol::Ws => 8083,
            Protocol::Wss => 8084,
        };

        // 分离路径
        let (host_port, path) = match rest.find('/') {
            Some(idx) => (&rest[..idx], rest[idx..].to_string()),
            None => (rest, "/mqtt".to_string()),
        };

        // 解析主机和端口
        let (host, port) = if let Some(idx) = host_port.rfind(':') {
            let host = &host_port[..idx];
            let port_str = &host_port[idx + 1..];
            let port = port_str.parse::<u16>().map_err(|_| format!("无效的端口号: {}", port_str))?;
            (host.to_string(), port)
        } else {
            (host_port.to_string(), default_port)
        };

        if host.is_empty() {
            return Err("服务器地址不能为空".to_string());
        }

        Ok(Self {
            protocol,
            host,
            port,
            path,
        })
    }

    /// 获取完整的 WebSocket URL
    pub fn ws_url(&self) -> String {
        let scheme = match self.protocol {
            Protocol::Ws => "ws",
            Protocol::Wss => "wss",
            _ => "ws",
        };
        format!("{}://{}:{}{}", scheme, self.host, self.port, self.path)
    }
}

/// 客户端配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// 服务器地址 (完整 URL，如 mqtt://localhost:1883)
    pub server: String,
    /// 客户端 ID
    pub client_id: String,
    /// 订阅主题
    pub topic: String,
    /// 认证 Token
    pub token: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server: "tcp://localhost:1883".to_string(),
            client_id: String::new(), // 默认为空，前端会自动生成
            topic: "notice/#".to_string(),
            token: String::new(),
        }
    }
}

impl Config {
    /// 从配置文件加载
    pub fn load(config_dir: &PathBuf) -> Self {
        let config_path = config_dir.join("config.json");
        if config_path.exists() {
            match fs::read_to_string(&config_path) {
                Ok(content) => match serde_json::from_str(&content) {
                    Ok(config) => return config,
                    Err(e) => log::warn!("解析配置文件失败: {}", e),
                },
                Err(e) => log::warn!("读取配置文件失败: {}", e),
            }
        }
        Self::default()
    }

    /// 保存到配置文件
    pub fn save(&self, config_dir: &PathBuf) -> Result<(), String> {
        fs::create_dir_all(config_dir).map_err(|e| e.to_string())?;
        let config_path = config_dir.join("config.json");
        let content = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        fs::write(config_path, content).map_err(|e| e.to_string())?;
        Ok(())
    }

    /// 解析服务器信息
    pub fn parse_server(&self) -> Result<ServerInfo, String> {
        ServerInfo::parse(&self.server)
    }
}

/// 生成简单的 UUID
fn uuid() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    format!("{:x}", duration.as_nanos())
}
