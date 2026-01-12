mod config;
mod message;
mod mqtt;

use config::Config;
use mqtt::{create_shared_client, SharedMqttClient};
use std::sync::Mutex;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, State,
};

/// 应用状态
struct AppState {
    config: Mutex<Config>,
    mqtt_client: SharedMqttClient,
    config_dir: std::path::PathBuf,
}

/// 获取当前配置
#[tauri::command]
fn get_config(state: State<AppState>) -> Config {
    state.config.lock().unwrap().clone()
}

/// 保存配置
#[tauri::command]
fn save_config(state: State<AppState>, config: Config) -> Result<(), String> {
    config.save(&state.config_dir)?;
    *state.config.lock().unwrap() = config.clone();

    // 更新 MQTT 客户端配置
    let mqtt = state.mqtt_client.clone();
    tauri::async_runtime::spawn(async move {
        let mut client = mqtt.lock().await;
        client.update_config(config);
    });

    Ok(())
}

/// 连接到 MQTT Broker
#[tauri::command]
async fn connect(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let mqtt = state.mqtt_client.clone();
    let config = state.config.lock().unwrap().clone();

    let mut client = mqtt.lock().await;
    client.update_config(config);
    client.connect(app).await
}

/// 断开连接
#[tauri::command]
async fn disconnect(state: State<'_, AppState>) -> Result<(), String> {
    let mqtt = state.mqtt_client.clone();
    let mut client = mqtt.lock().await;
    client.disconnect().await
}

/// 获取连接状态
#[tauri::command]
async fn get_connection_state(state: State<'_, AppState>) -> Result<String, String> {
    let mqtt = state.mqtt_client.clone();
    let client = mqtt.lock().await;
    Ok(format!("{:?}", client.state()))
}

/// 打开 URL
#[tauri::command]
async fn open_url(app: AppHandle, url: String) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_url(&url, None::<&str>)
        .map_err(|e| e.to_string())
}

/// 获取消息历史
#[tauri::command]
fn get_messages(state: State<AppState>) -> Vec<message::StoredMessage> {
    message::load_messages(&state.config_dir)
}

/// 保存消息历史
#[tauri::command]
fn save_messages(state: State<AppState>, messages: Vec<message::StoredMessage>) -> Result<(), String> {
    message::save_messages(&state.config_dir, &messages)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    env_logger::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            // 获取配置目录
            let config_dir = app.path().app_config_dir().expect("无法获取配置目录");

            // 加载配置
            let config = Config::load(&config_dir);
            let mqtt_client = create_shared_client(config.clone());

            // 设置应用状态
            app.manage(AppState {
                config: Mutex::new(config),
                mqtt_client,
                config_dir,
            });

            // 创建系统托盘
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;

            let _tray = TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "quit" => {
                        app.exit(0);
                    }
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_config,
            save_config,
            connect,
            disconnect,
            get_connection_state,
            open_url,
            get_messages,
            save_messages,
        ])
        .run(tauri::generate_context!())
        .expect("运行应用失败");
}
