//! Freenet Android — integration test suite.
//!
//! Tests config creation for Android (the public API surface).
//! Node lifecycle tests live in the crate's inline #[cfg(test)] module
//! where crate-private types (BoxedClient, etc.) are accessible.

use std::fs;
use freenet::config::ConfigArgs;

/// Path under CARGO_TARGET_DIR or cwd for test data.
fn test_dir(name: &str) -> std::path::PathBuf {
    let base = std::env::var("CARGO_TARGET_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| std::env::current_dir().unwrap());
    let d = base.join("freenet-android-int").join(name);
    fs::create_dir_all(&d).ok();
    d
}

/// Build ConfigArgs matching the Android crate's config_for_android().
fn local_args(dir: &std::path::Path) -> ConfigArgs {
    let data_dir = dir.join("data");
    fs::create_dir_all(&data_dir).ok();

    let mut args = ConfigArgs::default();
    args.config_paths.config_dir = Some(dir.to_path_buf());
    args.config_paths.data_dir = Some(data_dir);
    args.disable_auto_update = true;
    args.mode = Some(freenet::dev_tool::OperationMode::Local);
    args.network_api.is_gateway = true;
    args.network_api.public_address = Some("127.0.0.1".parse().unwrap());
    args.network_api.public_port = Some(0);
    args.ws_api.address = Some("127.0.0.1".parse().unwrap());
    args.ws_api.ws_api_port = Some(0);
    args
}

// ── Config tests ─────────────────────────────────────────────────────────

#[tokio::test]
async fn test_config_local_mode_args() {
    let args = local_args(&test_dir("int-cfg-args"));
    assert!(args.disable_auto_update);
    assert_eq!(args.mode, Some(freenet::dev_tool::OperationMode::Local));
    assert_eq!(args.ws_api.address, Some("127.0.0.1".parse().unwrap()));
}

#[tokio::test]
async fn test_config_local_mode_build() {
    let dir = test_dir("int-cfg-build");
    let args = local_args(&dir);
    let cfg = args.build().await.expect("ConfigArgs::build should succeed in Local mode");
    assert_eq!(cfg.mode, freenet::dev_tool::OperationMode::Local);
    assert_eq!(cfg.ws_api.port, 0);
}

#[tokio::test]
async fn test_ws_url_format() {
    let dir = test_dir("int-ws-url");
    let args = local_args(&dir);
    let cfg = args.build().await.expect("build config");

    let ws_url = format!("ws://{}:{}", cfg.ws_api.address, cfg.ws_api.port);
    assert!(ws_url.starts_with("ws://127.0.0.1:"));
    assert_eq!(ws_url, "ws://127.0.0.1:0");
}