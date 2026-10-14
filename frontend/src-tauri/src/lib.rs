use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs,
    path::PathBuf,
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{Emitter, Manager, State};
use tokio::sync::{mpsc, Mutex as AsyncMutex};
use tokio_tungstenite::tungstenite::{
    client::IntoClientRequest, protocol::WebSocketConfig, Message,
};

const MAX_FRAME: usize = 64 * 1024;
#[derive(Clone, Serialize, Deserialize)]
struct Credential {
    server: String,
    cookie: String,
    expires: u64,
}
struct NativeState {
    server: url::Url,
    client: reqwest::Client,
    credential: AsyncMutex<Option<Credential>>,
    credential_path: PathBuf,
    sockets: Mutex<HashMap<String, mpsc::Sender<Message>>>,
}
#[derive(Clone, Serialize)]
struct SocketEvent {
    id: String,
    kind: &'static str,
    data: Option<Vec<u8>>,
}
#[derive(Serialize)]
struct SessionResult {
    ok: bool,
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn endpoint(value: &str, allow_http: bool) -> Result<url::Url, String> {
    let mut url = url::Url::parse(value).map_err(|_| "Invalid XIM_APP_SERVER_URL")?;
    if !(url.scheme() == "https" || allow_http && url.scheme() == "http")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err("XIM_APP_SERVER_URL must be an HTTPS origin (no path, credentials, query or fragment); HTTP requires explicit development opt-in".into());
    }
    url.set_path("/");
    Ok(url)
}
fn cookie_name(server: &url::Url) -> &'static str {
    if server.scheme() == "https" {
        "__Host-xim_session"
    } else {
        "xim_session_dev"
    }
}
fn valid_cookie(cookie: &str, server: &url::Url) -> bool {
    cookie
        .strip_prefix(&format!("{}=", cookie_name(server)))
        .is_some_and(|token| {
            !token.is_empty()
                && token.len() <= 256
                && token
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        })
}
fn load_credential(path: &PathBuf, server: &url::Url) -> Option<Credential> {
    let saved: Credential = serde_json::from_slice(&fs::read(path).ok()?).ok()?;
    (saved.server == server.as_str()
        && saved.expires > now()
        && valid_cookie(&saved.cookie, server))
    .then_some(saved)
}
fn save_credential(path: &PathBuf, saved: &Credential) -> Result<(), String> {
    use std::io::Write;
    let mut options = fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path).map_err(|_| "Cannot save App session")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(0o600))
            .map_err(|_| "Cannot protect App session")?;
    }
    file.write_all(&serde_json::to_vec(saved).map_err(|_| "Cannot encode App session")?)
        .and_then(|_| file.sync_all())
        .map_err(|_| "Cannot save App session".into())
}
fn emit(app: &tauri::AppHandle, id: &str, kind: &'static str, data: Option<Vec<u8>>) {
    let _ = app.emit_to(
        "main",
        "xim-socket",
        SocketEvent {
            id: id.into(),
            kind,
            data,
        },
    );
}

#[tauri::command]
async fn session_exchange(
    state: State<'_, NativeState>,
    method: String,
    ticket: Option<String>,
) -> Result<SessionResult, String> {
    // Serialize exchanges: a stale POST cannot overwrite a later logout.
    let mut credential = state.credential.lock().await;
    let url = state
        .server
        .join("api/session")
        .map_err(|_| "Invalid session endpoint")?;
    let request = match method.as_str() {
        "POST" => {
            let ticket = ticket
                .filter(|value| !value.is_empty() && value.len() <= 256)
                .ok_or("Invalid session ticket")?;
            state
                .client
                .post(url)
                .json(&serde_json::json!({ "ticket": ticket }))
        }
        "DELETE" => {
            let mut request = state.client.delete(url);
            if let Some(saved) = credential.as_ref().filter(|value| value.expires > now()) {
                request = request.header(reqwest::header::COOKIE, &saved.cookie);
            }
            request
        }
        _ => return Err("Unsupported session method".into()),
    };
    let response = request.send().await.map_err(|_| {
        "Session service unavailable (check server URL and trusted TLS certificate)"
    })?;
    if !response.status().is_success() {
        return Ok(SessionResult { ok: false });
    }
    if method == "POST" {
        let header = response
            .headers()
            .get_all(reqwest::header::SET_COOKIE)
            .iter()
            .filter_map(|value| value.to_str().ok())
            .find(|value| value.starts_with(&format!("{}=", cookie_name(&state.server))))
            .ok_or("Session Cookie missing (check cookieSecure matches HTTP/HTTPS)")?;
        let mut parts = header.split(';').map(str::trim);
        let cookie = parts.next().ok_or("Invalid session Cookie")?.to_string();
        if !valid_cookie(&cookie, &state.server) {
            return Err("Invalid session Cookie".into());
        }
        let age = parts
            .find_map(|part| {
                part.strip_prefix("Max-Age=")
                    .and_then(|value| value.parse::<u64>().ok())
            })
            .filter(|age| *age > 0)
            .ok_or("Session Cookie expiry missing")?;
        let saved = Credential {
            server: state.server.to_string(),
            cookie,
            expires: now().saturating_add(age),
        };
        save_credential(&state.credential_path, &saved)?;
        *credential = Some(saved);
    } else {
        match fs::remove_file(&state.credential_path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err("Cannot remove App session".into()),
        }
        *credential = None;
    }
    Ok(SessionResult { ok: true })
}

#[tauri::command]
async fn socket_open(
    app: tauri::AppHandle,
    state: State<'_, NativeState>,
    id: String,
) -> Result<(), String> {
    if id.is_empty() || id.len() > 64 {
        return Err("Invalid socket ID".into());
    }
    let (sender, mut outbound) = mpsc::channel::<Message>(128);
    {
        let mut sockets = state
            .sockets
            .lock()
            .map_err(|_| "Socket state unavailable")?;
        if sockets.len() >= 4 || sockets.contains_key(&id) {
            return Err("Too many sockets".into());
        }
        sockets.insert(id.clone(), sender);
    }
    let mut url = state
        .server
        .join("ws")
        .map_err(|_| "Invalid socket endpoint")?;
    let scheme = if url.scheme() == "https" { "wss" } else { "ws" };
    url.set_scheme(scheme)
        .map_err(|_| "Invalid socket scheme")?;
    let mut request = url
        .as_str()
        .into_client_request()
        .map_err(|_| "Invalid socket request")?;
    if let Some(saved) = state
        .credential
        .lock()
        .await
        .as_ref()
        .filter(|value| value.expires > now())
    {
        request.headers_mut().insert(
            "Cookie",
            saved.cookie.parse().map_err(|_| "Invalid session Cookie")?,
        );
    }
    // Native client deliberately omits browser Origin/Sec-Fetch-Site. Existing backend permits native wire clients.
    // Close works while connecting, so StrictMode/reloads cannot leak an authenticated connection.
    tauri::async_runtime::spawn(async move {
        let config = WebSocketConfig::default()
            .max_message_size(Some(MAX_FRAME))
            .max_frame_size(Some(MAX_FRAME));
        let connection = tokio::select! {
            result = tokio::time::timeout(Duration::from_secs(10), tokio_tungstenite::connect_async_with_config(request, Some(config), false)) => result.ok().and_then(Result::ok),
            _ = outbound.recv() => None,
        };
        if let Some((mut socket, _)) = connection {
            emit(&app, &id, "open", None);
            loop {
                tokio::select! {
                    message = outbound.recv() => match message {
                        Some(Message::Close(_)) | None => { let _ = socket.close(None).await; break; },
                        Some(message) => if socket.send(message).await.is_err() { break; },
                    },
                    message = socket.next() => match message {
                        Some(Ok(Message::Binary(data))) => emit(&app, &id, "message", Some(data.to_vec())),
                        Some(Ok(Message::Ping(data))) => if socket.send(Message::Pong(data)).await.is_err() { break; },
                        Some(Ok(Message::Pong(_))) => {},
                        _ => break,
                    }
                }
            }
        }
        if let Ok(mut sockets) = app.state::<NativeState>().sockets.lock() {
            sockets.remove(&id);
        }
        emit(&app, &id, "close", None);
    });
    Ok(())
}
#[tauri::command]
async fn socket_send(
    state: State<'_, NativeState>,
    id: String,
    data: Vec<u8>,
) -> Result<(), String> {
    if data.is_empty() || data.len() > MAX_FRAME {
        return Err("Invalid binary frame size".into());
    }
    let sender = state
        .sockets
        .lock()
        .map_err(|_| "Socket state unavailable")?
        .get(&id)
        .cloned()
        .ok_or("Socket closed")?;
    sender
        .try_send(Message::Binary(data.into()))
        .map_err(|_| "Socket queue full or closed".into())
}
#[tauri::command]
async fn socket_close(state: State<'_, NativeState>, id: String) -> Result<(), String> {
    if let Some(sender) = state
        .sockets
        .lock()
        .map_err(|_| "Socket state unavailable")?
        .remove(&id)
    {
        // Dropping the last sender also closes a full queue.
        let _ = sender.try_send(Message::Close(None));
    }
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let value = option_env!("XIM_APP_SERVER_URL").unwrap_or("http://127.0.0.1:8080");
            let server = endpoint(
                value,
                cfg!(debug_assertions) || option_env!("XIM_APP_ALLOW_HTTP") == Some("1"),
            )?;
            if !cfg!(debug_assertions) && option_env!("XIM_APP_SERVER_URL").is_none() {
                return Err("Set XIM_APP_SERVER_URL before building the App".into());
            }
            let directory = app.path().app_data_dir()?;
            fs::create_dir_all(&directory)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))?;
            }
            let credential_path = directory.join("session.json");
            let credential = load_credential(&credential_path, &server);
            app.manage(NativeState {
                server,
                credential_path,
                credential: AsyncMutex::new(credential),
                sockets: Mutex::new(HashMap::new()),
                client: reqwest::Client::builder()
                    .redirect(reqwest::redirect::Policy::none())
                    .timeout(Duration::from_secs(8))
                    .build()?,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            socket_open,
            socket_send,
            socket_close,
            session_exchange
        ])
        .run(tauri::generate_context!())
        .expect("Failed to run XIM App");
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn service_is_an_explicit_trusted_origin() {
        assert!(endpoint("https://chat.example.com", false).is_ok());
        for value in [
            "http://chat.example.com",
            "file:///tmp/chat",
            "https://user:secret@chat.example.com",
            "https://chat.example.com/ws",
            "https://chat.example.com?secret=x",
        ] {
            assert!(endpoint(value, false).is_err(), "{value}");
        }
        assert!(endpoint("http://127.0.0.1:8080", true).is_ok());
    }
    #[test]
    fn cookie_rejects_headers_and_wrong_transport() {
        let https = endpoint("https://chat.example.com", false).unwrap();
        assert!(valid_cookie("__Host-xim_session=abc-_123", &https));
        assert!(!valid_cookie(
            "__Host-xim_session=abc\r\nOrigin: evil",
            &https
        ));
        assert!(!valid_cookie("xim_session_dev=abc", &https));
        assert!(!valid_cookie("__Host-xim_session=", &https));
    }
    #[test]
    fn persisted_credentials_are_scoped_to_service_and_expiry() {
        let path = std::env::temp_dir().join(format!(
            "xim-cookie-test-{}-{}.json",
            std::process::id(),
            now()
        ));
        let server = endpoint("https://chat.example.com", false).unwrap();
        let mut saved = Credential {
            server: server.to_string(),
            cookie: "__Host-xim_session=abc123".into(),
            expires: now() + 3600,
        };
        save_credential(&path, &saved).unwrap();
        assert!(load_credential(&path, &server).is_some());
        assert!(load_credential(
            &path,
            &endpoint("https://another.example.com", false).unwrap()
        )
        .is_none());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        saved.expires = now() - 1;
        save_credential(&path, &saved).unwrap();
        assert!(load_credential(&path, &server).is_none());
        fs::remove_file(path).unwrap();
    }
}
