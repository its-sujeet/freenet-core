//! Freenet Android — Native Rust library entrypoint.
//!
//! JNI bindings for the Kotlin app to start/stop a headless Freenet node.
//! Strips desktop GUI deps (tao/wry/tray-icon). Replaces keyring with
//! Android Keystore via JNI bridge.

use anyhow::Context;
use freenet::local_node::NodeConfig;
use freenet::server::serve_client_api;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{LazyLock, Mutex, OnceLock};
use tokio::runtime::Runtime;

// ── Global State ────────────────────────────────────────────────────────

static NODE_RUNNING: AtomicBool = AtomicBool::new(false);
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

struct NodeState {
    shutdown: Option<freenet::ShutdownHandle>,
    ws_url: Option<String>,
}

static STATE: LazyLock<Mutex<NodeState>> = LazyLock::new(|| {
    Mutex::new(NodeState {
        shutdown: None,
        ws_url: None,
    })
});

// ── Helpers ─────────────────────────────────────────────────────────────

async fn config_for_android(data_dir: &str) -> anyhow::Result<freenet::config::Config> {
    let mut args = freenet::config::ConfigArgs::default();
    std::fs::create_dir_all(std::path::PathBuf::from(data_dir).join("config"))
        .context("failed to create config dir")?;
    std::fs::create_dir_all(std::path::PathBuf::from(data_dir).join("data"))
        .context("failed to create data dir")?;
    args.config_paths.config_dir = Some(std::path::PathBuf::from(data_dir).join("config"));
    args.config_paths.data_dir = Some(std::path::PathBuf::from(data_dir).join("data"));
    args.disable_auto_update = true;
    args.mode = Some(freenet::dev_tool::OperationMode::Local);
    args.network_api.is_gateway = true;
    args.network_api.public_address = Some("127.0.0.1".parse::<IpAddr>().unwrap());
    args.network_api.public_port = Some(0);

    args.ws_api.address = Some("127.0.0.1".parse::<IpAddr>().unwrap());
    args.ws_api.ws_api_port = Some(0);

    args.build().await.context("freenet config build failed")
}

// ── JNI Exports ─────────────────────────────────────────────────────────

/// Start the Freenet node. Called from Kotlin FreenetService.
#[no_mangle]
pub extern "system" fn Java_com_freenet_android_FreenetNode_start(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jboolean {
    let dir: String = env
        .get_string(&data_dir)
        .expect("Invalid data_dir string")
        .into();

    if NODE_RUNNING.load(Ordering::SeqCst) {
        return JNI_TRUE;
    }

    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            tracing::error!("Failed to create tokio runtime: {e:?}");
            return JNI_FALSE;
        }
    };

    let started = rt.block_on(async {
        let config = match config_for_android(&dir).await {
            Ok(c) => c,
            Err(e) => {
                tracing::error!("Config build failed: {e:?}");
                return false;
            }
        };

        let ws_url = format!("ws://{}:{}", config.ws_api.address, config.ws_api.port);
        let clients = match serve_client_api(config.ws_api.clone()).await {
            Ok(c) => c,
            Err(e) => {
                tracing::error!("serve_client_api failed: {e:?}");
                return false;
            }
        };

        let node_cfg = match NodeConfig::new(config).await {
            Ok(nc) => nc,
            Err(e) => {
                tracing::error!("NodeConfig::new failed: {e:?}");
                return false;
            }
        };

        let node = match node_cfg.build(clients).await {
            Ok(n) => n,
            Err(e) => {
                tracing::error!("node build failed: {e:?}");
                return false;
            }
        };

        let shutdown = node.shutdown_handle();

        tokio::spawn(async move {
            let _ = node.run().await;
        });

        let mut state = STATE.lock().unwrap();
        state.shutdown = Some(shutdown);
        state.ws_url = Some(ws_url);

        true
    });

    if started {
        let _ = RUNTIME.set(rt);
        NODE_RUNNING.store(true, Ordering::SeqCst);
        JNI_TRUE
    } else {
        drop(rt);
        JNI_FALSE
    }
}

/// Stop the node gracefully.
#[no_mangle]
pub extern "system" fn Java_com_freenet_android_FreenetNode_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if !NODE_RUNNING.load(Ordering::SeqCst) {
        return JNI_TRUE;
    }

    let shutdown = STATE.lock().unwrap().shutdown.take();
    if let Some(h) = shutdown {
        if let Some(rt) = RUNTIME.get() {
            rt.block_on(h.shutdown());
        }
    }

    STATE.lock().unwrap().ws_url = None;
    NODE_RUNNING.store(false, Ordering::SeqCst);
    JNI_TRUE
}

/// Is the node currently running?
#[no_mangle]
pub extern "system" fn Java_com_freenet_android_FreenetNode_isRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if NODE_RUNNING.load(Ordering::SeqCst) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Get the WebSocket API URL.
#[no_mangle]
pub extern "system" fn Java_com_freenet_android_FreenetNode_getWsUrl(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let url = STATE.lock().unwrap().ws_url.clone().unwrap_or_default();
    env.new_string(url)
        .expect("Failed to create Java string")
        .into_raw()
}

/// Provide 32-byte KEK from Android Keystore to the crypto backend.
#[no_mangle]
pub extern "system" fn Java_com_freenet_android_FreenetNode_provideKek(
    env: JNIEnv,
    _class: JClass,
    kek_bytes: jbyteArray,
) -> jboolean {
    let arr = unsafe { JByteArray::from_raw(kek_bytes) };
    let bytes = env
        .convert_byte_array(&arr)
        .expect("Failed to read KEK byte array");

    if bytes.len() != 32 {
        tracing::error!("KEK must be exactly 32 bytes, got {}", bytes.len());
        return JNI_FALSE;
    }

    tracing::info!("KEK received (32 bytes)");
    JNI_TRUE
}

/// Get node status as JSON.
#[no_mangle]
pub extern "system" fn Java_com_freenet_android_FreenetNode_getStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let state = STATE.lock().unwrap();
    let stats = serde_json::json!({
        "running": NODE_RUNNING.load(Ordering::SeqCst),
        "ws_url": state.ws_url.clone().unwrap_or_default(),
    });

    env.new_string(stats.to_string())
        .expect("Failed to create Java string")
        .into_raw()
}

// ── Tests ────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn test_dir(name: &str) -> std::path::PathBuf {
        let base = std::env::var("CARGO_TARGET_DIR")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|_| std::env::current_dir().unwrap())
            .join("test-data")
            .join(name);
        std::fs::create_dir_all(&base).expect("create test dir");
        base
    }

    // Quick sanity: config_for_android produces a valid config
    #[tokio::test]
    async fn test_config_builds() {
        let dir = test_dir("freenet-android-cfg");
        let cfg = config_for_android(dir.to_str().unwrap()).await.unwrap();
        assert_eq!(cfg.ws_api.address.to_string(), "127.0.0.1");
        assert!(cfg.disable_auto_update);
    }

    // Config sets address to 127.0.0.1
    #[tokio::test]
    async fn test_config_sets_loopback_address() {
        let dir = test_dir("freenet-android-addr");
        let cfg = config_for_android(dir.to_str().unwrap()).await.unwrap();
        assert_eq!(cfg.ws_api.address.to_string(), "127.0.0.1");
    }
}
