// Budget desktop shell.
//
// This file does three things and nothing else:
//   1. starts the bundled Java backend and waits for its `READY port=NNNN` line,
//   2. opens a window on that local address,
//   3. stops the backend when the app exits.
// All business logic lives in the backend; the page talks to it over HTTP only.

// No console window behind the app in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{mpsc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

/// The backend only listens on loopback unless phone access is turned on in Settings.
const LOOPBACK: &str = "127.0.0.1";
const DATA_FOLDER: &str = "Budget";
const BACKEND_EXE: &str = "backend/budget-backend.exe";
const READY_PREFIX: &str = "READY port=";
const READY_TIMEOUT: Duration = Duration::from_secs(45);
const SHUTDOWN_GRACE: Duration = Duration::from_secs(5);
const WINDOW_LABEL: &str = "main";
/// Windows process flag: run the console-mode backend launcher without showing a console.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

struct Backend {
    child: Child,
    port: u16,
    shutdown_token: String,
}

/// Empty until the backend has announced itself.
type BackendState = Mutex<Option<Backend>>;

fn main() {
    tauri::Builder::default()
        // A second launch just brings the existing window forward.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window(WINDOW_LABEL) {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .manage(BackendState::default())
        .setup(|app| {
            let handle = app.handle().clone();
            // Off the main thread: waiting for the backend must not freeze the event loop.
            thread::spawn(move || match start_backend(&handle) {
                Ok(backend) => {
                    let port = backend.port;
                    *handle.state::<BackendState>().lock().unwrap() = Some(backend);
                    open_app_window(&handle, port);
                }
                Err(_) => open_error_window(&handle),
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Budget could not start")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(backend) = app.state::<BackendState>().lock().unwrap().take() {
                    stop_backend(backend);
                }
            }
        });
}

/// Starts the backend and blocks until it prints its READY line (or the timeout passes).
fn start_backend(app: &AppHandle) -> Result<Backend, String> {
    let exe: PathBuf = app.path().resource_dir().map_err(|e| e.to_string())?.join(BACKEND_EXE);
    let data_dir = app.path().data_dir().map_err(|e| e.to_string())?.join(DATA_FOLDER);
    let shutdown_token = random_token()?;

    let mut command = Command::new(exe);
    command
        .env("BUDGET_DATA_DIR", &data_dir)
        // The backend watches this pid and exits by itself if the shell dies.
        .env("BUDGET_PARENT_PID", std::process::id().to_string())
        .env("BUDGET_SHUTDOWN_TOKEN", &shutdown_token)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = command.spawn().map_err(|e| e.to_string())?;

    // Read stdout on its own thread so the wait below can time out.
    let stdout = child.stdout.take().ok_or("backend has no stdout")?;
    let (sender, receiver) = mpsc::channel();
    thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Some(port) = line.trim().strip_prefix(READY_PREFIX).and_then(|p| p.parse::<u16>().ok()) {
                let _ = sender.send(port);
                return;
            }
        }
        // stdout closed without READY: dropping the sender wakes the receiver with an error.
    });

    match receiver.recv_timeout(READY_TIMEOUT) {
        Ok(port) => Ok(Backend { child, port, shutdown_token }),
        Err(_) => {
            let _ = child.kill();
            Err("backend did not become ready".into())
        }
    }
}

fn open_app_window(app: &AppHandle, port: u16) {
    let url: tauri::Url = format!("http://{LOOPBACK}:{port}/").parse().expect("valid local URL");
    let backend = url.origin();
    let _ = WebviewWindowBuilder::new(app, WINDOW_LABEL, WebviewUrl::External(url))
        .title("Budget")
        .inner_size(1440.0, 900.0)
        .min_inner_size(375.0, 600.0)
        // The shell would otherwise swallow dropped files itself; receipts are dropped onto the page.
        .disable_drag_drop_handler()
        // Privacy: the window may only ever show the local backend, never another site. Compared by
        // origin so a report the page has just built (a blob: address of the backend's) can be saved.
        .on_navigation(move |target| target.origin() == backend)
        .build();
}

/// Shown when the backend never became ready; a static page bundled with the shell.
fn open_error_window(app: &AppHandle) {
    let _ = WebviewWindowBuilder::new(app, WINDOW_LABEL, WebviewUrl::App("error.html".into()))
        .title("Budget")
        .inner_size(560.0, 420.0)
        .build();
}

/// Asks the backend to stop, then makes sure it is gone.
fn stop_backend(mut backend: Backend) {
    let _ = request_shutdown(&backend);
    let deadline = Instant::now() + SHUTDOWN_GRACE;
    while Instant::now() < deadline {
        if matches!(backend.child.try_wait(), Ok(Some(_))) {
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
    let _ = backend.child.kill();
}

/// `POST /api/shutdown` by hand: one tiny request does not justify an HTTP client dependency.
fn request_shutdown(backend: &Backend) -> std::io::Result<()> {
    let mut stream = TcpStream::connect((LOOPBACK, backend.port))?;
    stream.set_read_timeout(Some(SHUTDOWN_GRACE))?;
    write!(
        stream,
        "POST /api/shutdown HTTP/1.1\r\nHost: {LOOPBACK}:{}\r\nX-Budget-Shutdown-Token: {}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        backend.port, backend.shutdown_token
    )?;
    // Wait for the reply so the request is known to have been handled before we move on.
    let mut reply = Vec::new();
    let _ = stream.read_to_end(&mut reply);
    Ok(())
}

/// 256 random bits from the OS, hex encoded. Proves to the backend that a shutdown
/// request really comes from this shell.
fn random_token() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}
