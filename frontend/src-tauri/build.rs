fn main() {
    println!("cargo:rerun-if-env-changed=XIM_APP_SERVER_URL");
    println!("cargo:rerun-if-env-changed=XIM_APP_ALLOW_HTTP");
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "socket_open",
            "socket_send",
            "socket_close",
            "session_exchange",
        ]),
    ))
    .expect("Tauri build configuration failed");
}
