use crate::config::{Config, Protocol};
use crate::message::{MessageEvent, NoticeMessage};
use rumqttc::{AsyncClient, Event, MqttOptions, Packet, QoS, TlsConfiguration, Transport};
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::sync::Mutex;

/// MQTT 客户端状态
#[derive(Debug, Clone, PartialEq)]
pub enum ConnectionState {
    Disconnected,
    Connecting,
    Connected,
}

/// MQTT 客户端管理器
pub struct MqttClient {
    client: Option<AsyncClient>,
    config: Config,
    state: ConnectionState,
}

impl MqttClient {
    pub fn new(config: Config) -> Self {
        Self {
            client: None,
            config,
            state: ConnectionState::Disconnected,
        }
    }

    pub fn state(&self) -> &ConnectionState {
        &self.state
    }

    pub fn update_config(&mut self, config: Config) {
        self.config = config;
    }

    /// 连接到 MQTT Broker
    pub async fn connect(&mut self, app: AppHandle) -> Result<(), String> {
        if self.state == ConnectionState::Connected {
            return Ok(());
        }

        self.state = ConnectionState::Connecting;
        let _ = app.emit("connection-state", "connecting");

        // 解析服务器地址
        let server_info = self.config.parse_server()?;
        log::info!(
            "连接到 {:?} {}:{} 路径: {}",
            server_info.protocol,
            server_info.host,
            server_info.port,
            server_info.path
        );

        // 根据协议类型配置连接
        let mut mqttopts = match server_info.protocol {
            Protocol::Tcp => {
                // 普通 TCP
                let mut opts = MqttOptions::new(
                    &self.config.client_id,
                    &server_info.host,
                    server_info.port,
                );
                opts.set_keep_alive(Duration::from_secs(30));
                opts.set_clean_session(false);
                opts
            }
            Protocol::Ssl => {
                // TCP + TLS
                let mut opts = MqttOptions::new(
                    &self.config.client_id,
                    &server_info.host,
                    server_info.port,
                );
                opts.set_keep_alive(Duration::from_secs(30));
                opts.set_clean_session(false);
                opts.set_transport(Transport::tls_with_config(TlsConfiguration::Native));
                opts
            }
            Protocol::Ws => {
                // WebSocket
                let ws_url = server_info.ws_url();
                let mut opts = MqttOptions::new(
                    &self.config.client_id,
                    &ws_url,
                    server_info.port,
                );
                opts.set_keep_alive(Duration::from_secs(30));
                opts.set_clean_session(false);
                opts.set_transport(Transport::Ws);
                opts
            }
            Protocol::Wss => {
                // WebSocket + TLS
                let ws_url = server_info.ws_url();
                let mut opts = MqttOptions::new(
                    &self.config.client_id,
                    &ws_url,
                    server_info.port,
                );
                opts.set_keep_alive(Duration::from_secs(30));
                opts.set_clean_session(false);
                opts.set_transport(Transport::tls_with_config(TlsConfiguration::Native));
                opts
            }
        };

        // Token 认证
        if !self.config.token.is_empty() {
            mqttopts.set_credentials(&self.config.token, "");
        }

        let (client, mut eventloop) = AsyncClient::new(mqttopts, 10);
        self.client = Some(client.clone());

        // 订阅主题
        let topic = self.config.topic.clone();
        let client_clone = client.clone();

        // 启动事件循环
        let app_handle = app.clone();
        tokio::spawn(async move {
            let mut connected = false;

            loop {
                match eventloop.poll().await {
                    Ok(Event::Incoming(Packet::ConnAck(_))) => {
                        log::info!("已连接到 MQTT Broker");
                        connected = true;
                        let _ = app_handle.emit("connection-state", "connected");

                        // 订阅
                        if let Err(e) = client_clone.subscribe(&topic, QoS::AtLeastOnce).await {
                            log::error!("订阅失败: {}", e);
                        } else {
                            log::info!("已订阅: {}", topic);
                        }
                    }
                    Ok(Event::Incoming(Packet::Publish(publish))) => {
                        let topic = publish.topic.clone();
                        let payload = String::from_utf8_lossy(&publish.payload).to_string();

                        log::debug!("收到消息 [{}]: {}", topic, payload);

                        // 解析消息
                        match serde_json::from_str::<NoticeMessage>(&payload) {
                            Ok(msg) => {
                                let event = MessageEvent {
                                    topic: topic.clone(),
                                    message: msg.clone(),
                                };
                                // 发送到前端
                                let _ = app_handle.emit("message", &event);

                                // 显示系统通知
                                show_notification(&app_handle, &msg);
                            }
                            Err(e) => {
                                log::warn!("消息解析失败: {}", e);
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(e) => {
                        log::error!("MQTT 错误: {}", e);
                        if connected {
                            let _ = app_handle.emit("connection-state", "disconnected");
                            connected = false;
                        }
                        // 等待后重试
                        tokio::time::sleep(Duration::from_secs(5)).await;
                    }
                }
            }
        });

        self.state = ConnectionState::Connected;
        Ok(())
    }

    /// 断开连接
    pub async fn disconnect(&mut self) -> Result<(), String> {
        if let Some(client) = &self.client {
            client.disconnect().await.map_err(|e| e.to_string())?;
        }
        self.client = None;
        self.state = ConnectionState::Disconnected;
        Ok(())
    }
}

/// 显示系统通知
fn show_notification(app: &AppHandle, msg: &NoticeMessage) {
    use tauri_plugin_notification::NotificationExt;

    let title = if msg.title.is_empty() {
        "Notice"
    } else {
        &msg.title
    };

    if let Err(e) = app
        .notification()
        .builder()
        .title(title)
        .body(&msg.content)
        .show()
    {
        log::error!("显示通知失败: {}", e);
    }
}

/// 全局 MQTT 客户端
pub type SharedMqttClient = Arc<Mutex<MqttClient>>;

pub fn create_shared_client(config: Config) -> SharedMqttClient {
    Arc::new(Mutex::new(MqttClient::new(config)))
}
