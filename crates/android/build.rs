fn main() {
    // Only set build metadata when compiling for Android target
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        println!("cargo:rustc-link-lib=c++_shared");
        println!("cargo:rustc-link-lib=log");
    }

    // Expose build timestamp
    println!("cargo:rustc-env=BUILD_TIMESTAMP={}", 
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs().to_string())
            .unwrap_or_else(|_| "unknown".to_string()));
}
