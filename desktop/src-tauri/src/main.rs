// Budget desktop shell.
//
// This file starts things, connects them and stops them, and nothing else:
//   1. opens a window straight away, on a bundled "opening" page,
//   2. starts the bundled Java backend, waits for its `READY port=NNNN` line and points the
//      window at that local address; or, on a computer set to use the budget kept on another PC
//      ("Connect to my other PC"), points the window at that PC instead and starts no backend,
//   3. keeps a tray icon: while phone access is on, closing the window hides it there so the
//      backend keeps answering the phone, and Quit is the way out,
//   4. writes or removes its Windows startup entry when Start with Windows is changed in Settings,
//   5. stops the backend when the app exits.
// All business logic lives in the backend; the page talks to it over HTTP only. What the shell
// needs to know about settings arrives as `SHELL …` lines on the backend's stdout (ShellLink.java).

// No console window behind the app in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, WindowEvent};

/// The desktop window is served on loopback; phone access listens beside it (backend PhoneAccess.java).
const LOOPBACK: &str = "127.0.0.1";
const DATA_FOLDER: &str = "Budget";
/// The backend's database file: a computer that has one already keeps its budget here.
const DATABASE_FILE: &str = "budget.db";
/// The backend launcher inside the bundled jpackage app image, which jpackage lays out differently on
/// each system. Only the Windows build is made and tried; the others are written so a build there can
/// follow (spec §12, Mac).
#[cfg(windows)]
const BACKEND_EXE: &str = "backend/budget-backend.exe";
#[cfg(target_os = "macos")]
const BACKEND_EXE: &str = "backend/budget-backend.app/Contents/MacOS/budget-backend";
#[cfg(not(any(windows, target_os = "macos")))]
const BACKEND_EXE: &str = "backend/bin/budget-backend";
const READY_PREFIX: &str = "READY port=";
const SHELL_PREFIX: &str = "SHELL ";
const READY_TIMEOUT: Duration = Duration::from_secs(45);
const SHUTDOWN_GRACE: Duration = Duration::from_secs(5);
/// How long to wait for the other PC to answer before saying it can't be reached.
const REMOTE_TIMEOUT: Duration = Duration::from_secs(4);
/// The port phone access listens on unless the address says otherwise (backend BUDGET_PHONE_PORT).
const PHONE_PORT: u16 = 4873;
const WINDOW_LABEL: &str = "main";
const TRAY_ID: &str = "budget";
/// Given by the Windows startup entry: start in the tray, without showing the window.
const HIDDEN_FLAG: &str = "--hidden";
/// Pages bundled with the shell (desktop/ui), and the host the web view serves them from.
const STARTING_PAGE: &str = "starting.html";
const STARTING_PAGE_PATH: &str = "/starting.html";
const CHOOSE_PAGE: &str = "/choose.html";
const ERROR_PAGE: &str = "/error.html";
const UNREACHABLE_PAGE: &str = "/unreachable.html";
const SHELL_HOST: &str = "tauri.localhost";
/// Where the shell remembers where Budget opens: this PC's own budget, or another PC's address.
const CONNECTION_FILE: &str = "connection.txt";
/// Windows process flag: run the console-mode backend launcher without showing a console.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

struct Backend {
    child: Child,
    port: u16,
    shutdown_token: String,
}

/// Where Budget opens on this computer.
#[derive(Clone, PartialEq)]
enum Connection {
    /// The budget kept on this PC, with its own backend.
    ThisPc,
    /// The budget kept on another PC, at this address (`http://100.101.102.103:4873/`).
    OtherPc(tauri::Url),
    /// Not chosen yet: the window asks.
    Ask,
}

/// Empty until the backend has announced itself.
type BackendState = Mutex<Option<Backend>>;

/// Where the window may show, once it is known: this PC's backend, or the other PC.
type AppOrigin = Mutex<Option<tauri::Url>>;

/// True while phone access is on: closing the window then hides it to the tray instead of quitting.
struct KeepInTray(AtomicBool);

/// Whether the Windows startup entry is wanted, as last told; `None` until the backend has said.
struct StartWithWindows(Mutex<Option<bool>>);

fn main() {
    let start_hidden = std::env::args().any(|arg| arg == HIDDEN_FLAG);
    tauri::Builder::default()
        // A second launch just brings the existing window forward (out of the tray, too).
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_window(app)))
        .manage(BackendState::default())
        .manage(AppOrigin::default())
        .manage(KeepInTray(AtomicBool::new(false)))
        .manage(StartWithWindows(Mutex::new(None)))
        .setup(move |app| {
            let handle = app.handle().clone();
            add_tray(&handle)?;
            // The window opens at once on a page of the shell's own, so there is something to
            // see while Java starts, and the web view gets ready at the same time, not after.
            let connection = read_connection(&handle);
            // Only this PC's own budget starts hidden: there is nothing else to keep running.
            open_window(&handle, start_hidden && connection == Connection::ThisPc);
            open(&handle, connection);
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                // With phone access on, the backend keeps answering the phone: the window goes to the tray.
                if window.app_handle().state::<KeepInTray>().0.load(Ordering::SeqCst) {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
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

/// Goes where `connection` says: starts this PC's backend, connects to the other PC, or asks.
fn open(app: &AppHandle, connection: Connection) {
    let handle = app.clone();
    match connection {
        Connection::Ask => show_shell_page(app, CHOOSE_PAGE, None),
        // Off the main thread: waiting for the backend, or the other PC, must not freeze the event loop.
        Connection::ThisPc => {
            thread::spawn(move || match start_backend(&handle) {
                Ok(backend) => {
                    let port = backend.port;
                    *handle.state::<BackendState>().lock().unwrap() = Some(backend);
                    let url: tauri::Url = format!("http://{LOOPBACK}:{port}/").parse().expect("valid local URL");
                    show_app(&handle, url);
                }
                Err(_) => show_shell_page(&handle, ERROR_PAGE, None),
            });
        }
        Connection::OtherPc(url) => {
            thread::spawn(move || {
                if answers(&url) {
                    show_app(&handle, url);
                } else {
                    show_shell_page(&handle, UNREACHABLE_PAGE, None);
                }
            });
        }
    }
}

/// Where Budget opens, as remembered. Never asked before: a computer that already has a budget of
/// its own keeps opening it, as it always has; a new one asks.
fn read_connection(app: &AppHandle) -> Connection {
    let saved = connection_file(app).and_then(|file| fs::read_to_string(file).ok());
    match saved.as_deref().map(str::trim) {
        Some("this-pc") => Connection::ThisPc,
        Some("ask") => Connection::Ask,
        Some(other) => match other.strip_prefix("other-pc ").and_then(other_pc_url) {
            Some(url) => Connection::OtherPc(url),
            None => Connection::Ask,
        },
        None => match data_dir(app) {
            Ok(folder) if folder.join(DATABASE_FILE).exists() => Connection::ThisPc,
            _ => Connection::Ask,
        },
    }
}

fn save_connection(app: &AppHandle, connection: &Connection) {
    let text = match connection {
        Connection::ThisPc => "this-pc".to_string(),
        Connection::OtherPc(url) => format!("other-pc {url}"),
        Connection::Ask => "ask".to_string(),
    };
    if let Some(file) = connection_file(app) {
        if let Some(folder) = file.parent() {
            let _ = fs::create_dir_all(folder);
        }
        let _ = fs::write(file, text);
    }
}

/// In the shell's own settings folder, not Budget's data folder: a computer that connects to another PC has none.
fn connection_file(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|folder| folder.join(CONNECTION_FILE))
}

/// Why an address typed for "Connect to my other PC" was refused.
enum AddressProblem {
    /// Not four numbers with dots (and maybe a port).
    NotAnAddress,
    /// An address out on the internet, not a home network or VPN.
    NotPrivate,
}

/// The other PC's address as typed (`100.101.102.103`, `100.101.102.103:4873`, or the whole
/// `http://…/` from its Settings), as the address to open. Only a number address on a home network
/// or a VPN is accepted: no names (nothing to look up anywhere), and nothing out on the internet.
fn parse_other_pc(typed: &str) -> Result<tauri::Url, AddressProblem> {
    let bare = typed.trim().trim_start_matches("http://").trim_end_matches('/');
    let (host, port) = match bare.split_once(':') {
        Some((host, port)) => (host, port.parse::<u16>().map_err(|_| AddressProblem::NotAnAddress)?),
        None => (bare, PHONE_PORT),
    };
    let ip: Ipv4Addr = host.parse().map_err(|_| AddressProblem::NotAnAddress)?;
    if port == 0 {
        return Err(AddressProblem::NotAnAddress);
    }
    if !is_home_or_vpn(ip) {
        return Err(AddressProblem::NotPrivate);
    }
    format!("http://{ip}:{port}/").parse().map_err(|_| AddressProblem::NotAnAddress)
}

/// A saved address read back: the same checks as when it was typed.
fn other_pc_url(saved: &str) -> Option<tauri::Url> {
    parse_other_pc(saved).ok()
}

/// Home networks (10.x, 172.16–31.x, 192.168.x) and the range VPNs such as Tailscale use (100.64–127.x).
fn is_home_or_vpn(ip: Ipv4Addr) -> bool {
    let [a, b, ..] = ip.octets();
    ip.is_private() || (a == 100 && (64..=127).contains(&b))
}

/// True when the other PC answers on its address; nothing is sent, the connection is only opened.
fn answers(url: &tauri::Url) -> bool {
    let address = url
        .host_str()
        .and_then(|host| host.parse::<Ipv4Addr>().ok())
        .zip(url.port())
        .map(|(ip, port)| SocketAddr::from((ip, port)));
    address.is_some_and(|address| TcpStream::connect_timeout(&address, REMOTE_TIMEOUT).is_ok())
}

fn data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    // A data folder already named in the environment wins, so a build can be tried against a
    // throwaway folder without touching the real data.
    match std::env::var_os("BUDGET_DATA_DIR") {
        Some(folder) => Ok(PathBuf::from(folder)),
        None => Ok(app.path().data_dir().map_err(|e| e.to_string())?.join(DATA_FOLDER)),
    }
}

/// Starts the backend and blocks until it prints its READY line (or the timeout passes).
fn start_backend(app: &AppHandle) -> Result<Backend, String> {
    let exe: PathBuf = app.path().resource_dir().map_err(|e| e.to_string())?.join(BACKEND_EXE);
    let data_dir = data_dir(app)?;
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

    // Read stdout on its own thread so the wait below can time out. It keeps reading after READY:
    // the backend's `SHELL …` lines follow, for the life of the backend.
    let stdout = child.stdout.take().ok_or("backend has no stdout")?;
    let (sender, receiver) = mpsc::channel();
    let handle = app.clone();
    thread::spawn(move || {
        let mut ready = Some(sender);
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let line = line.trim();
            if let Some(port) = line.strip_prefix(READY_PREFIX).and_then(|p| p.parse::<u16>().ok()) {
                if let Some(sender) = ready.take() {
                    let _ = sender.send(port);
                }
            } else if let Some(state) = line.strip_prefix(SHELL_PREFIX) {
                apply_shell_state(&handle, state);
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

/// `keep_in_tray=true start_with_windows=false`, from the backend.
fn apply_shell_state(app: &AppHandle, state: &str) {
    for pair in state.split_whitespace() {
        match pair.split_once('=') {
            Some(("keep_in_tray", value)) => {
                let keep = value == "true";
                app.state::<KeepInTray>().0.store(keep, Ordering::SeqCst);
                if let Some(tray) = app.tray_by_id(TRAY_ID) {
                    let _ = tray.set_tooltip(Some(if keep {
                        "Budget is still running for your phone. Right-click to open or quit."
                    } else {
                        "Budget"
                    }));
                }
            }
            Some(("start_with_windows", value)) => {
                let wanted = value == "true";
                let state = app.state::<StartWithWindows>();
                let mut last = state.0.lock().unwrap();
                if *last != Some(wanted) {
                    // Written only when it changes, and only ever under this user's own startup list.
                    if set_start_with_windows(wanted).is_ok() {
                        *last = Some(wanted);
                    }
                }
            }
            _ => {}
        }
    }
}

/// The Windows startup entry: this user's `Run` list in the registry, named "Budget", which starts
/// the app hidden in the tray when they sign in. Removed again when Start with Windows is turned off.
#[cfg(windows)]
fn set_start_with_windows(wanted: bool) -> std::io::Result<()> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;
    const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
    const ENTRY: &str = "Budget";
    let (run, _) = RegKey::predef(HKEY_CURRENT_USER).create_subkey(RUN_KEY)?;
    if wanted {
        let exe = std::env::current_exe()?;
        run.set_value(ENTRY, &format!("\"{}\" {HIDDEN_FLAG}", exe.display()))
    } else {
        match run.delete_value(ENTRY) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            other => other,
        }
    }
}

/// Start with Windows exists on Windows only; the Mac build would use a login item (spec §12, Mac).
#[cfg(not(windows))]
fn set_start_with_windows(_wanted: bool) -> std::io::Result<()> {
    Ok(())
}

/// The tray icon: Open Budget, Change where Budget opens…, Quit Budget. A click opens the window.
fn add_tray(app: &AppHandle) -> tauri::Result<()> {
    let open_item = MenuItem::with_id(app, "open", "Open Budget", true, None::<&str>)?;
    let change = MenuItem::with_id(app, "change", "Change where Budget opens…", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit Budget", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open_item, &change, &quit])?;
    let mut tray = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("Budget")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_window(app),
            "change" => change_where_budget_opens(app),
            // Ends the app, which stops the backend (RunEvent::Exit above).
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_window(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

/// Brings the window back, from the tray or from behind other windows.
fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(WINDOW_LABEL) {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// From the tray: stops this PC's backend if it is running, and shows the choice again. The data is
/// untouched; choosing "Keep my budget on this PC" again opens it as before.
fn change_where_budget_opens(app: &AppHandle) {
    let handle = app.clone();
    show_window(app);
    thread::spawn(move || {
        *handle.state::<AppOrigin>().lock().unwrap() = None;
        handle.state::<KeepInTray>().0.store(false, Ordering::SeqCst);
        if let Some(backend) = handle.state::<BackendState>().lock().unwrap().take() {
            stop_backend(backend);
        }
        save_connection(&handle, &Connection::Ask);
        show_shell_page(&handle, CHOOSE_PAGE, None);
    });
}

/// Opens the app's one window on the shell's own "opening" page.
fn open_window(app: &AppHandle, hidden: bool) {
    let handle = app.clone();
    let _ = WebviewWindowBuilder::new(app, WINDOW_LABEL, WebviewUrl::App(STARTING_PAGE.into()))
        .title("Budget")
        .inner_size(1440.0, 900.0)
        .min_inner_size(375.0, 600.0)
        .visible(!hidden)
        // The shell would otherwise swallow dropped files itself; receipts are dropped onto the page.
        .disable_drag_drop_handler()
        // Privacy: the window may only ever show Budget itself (this PC's backend, or the other PC
        // chosen under "Connect to my other PC"), never another site. Compared by origin so a report the
        // page has just built (a blob: address of the backend's) can be saved. Until then, it may only
        // show the pages bundled with the shell, whose links and form are caught here and acted on.
        .on_navigation(move |target| {
            if let Some(origin) = handle.state::<AppOrigin>().lock().unwrap().as_ref() {
                return target.origin() == origin.origin();
            }
            if !is_shell_page(target) {
                return false;
            }
            match target.path() {
                "/use-this-pc" => choose(&handle, Connection::ThisPc),
                "/retry" => {
                    show_shell_page(&handle, STARTING_PAGE_PATH, None);
                    open(&handle, read_connection(&handle));
                }
                "/connect" => {
                    let typed = target.query_pairs().find(|(name, _)| name == "address").map(|(_, value)| value.into_owned());
                    match parse_other_pc(typed.as_deref().unwrap_or("")) {
                        Ok(url) => choose(&handle, Connection::OtherPc(url)),
                        Err(AddressProblem::NotAnAddress) => show_shell_page(&handle, CHOOSE_PAGE, Some("bad-address")),
                        Err(AddressProblem::NotPrivate) => show_shell_page(&handle, CHOOSE_PAGE, Some("not-private")),
                    }
                }
                // A page of the shell's own: shown as it is.
                _ => return true,
            }
            false
        })
        .build();
}

/// Remembers the choice made on the "Where is your budget?" page and goes there.
fn choose(app: &AppHandle, connection: Connection) {
    save_connection(app, &connection);
    show_shell_page(app, STARTING_PAGE_PATH, None);
    open(app, connection);
}

/// The pages bundled with the shell (opening, choose, could not start, can't reach) are served from this host.
fn is_shell_page(target: &tauri::Url) -> bool {
    target.scheme() == "tauri" || target.host_str() == Some(SHELL_HOST)
}

/// Points the window at Budget, now that it answers: this PC's backend, or the other PC.
fn show_app(app: &AppHandle, url: tauri::Url) {
    *app.state::<AppOrigin>().lock().unwrap() = Some(url.clone());
    if let Some(window) = app.get_webview_window(WINDOW_LABEL) {
        let _ = window.navigate(url);
    }
}

/// Shows one of the static pages bundled with the shell; `fragment` picks out a message on it.
fn show_shell_page(app: &AppHandle, path: &str, fragment: Option<&str>) {
    let handle = app.clone();
    let path = path.to_string();
    let fragment = fragment.map(str::to_string);
    // From its own thread: this can be asked for while the window is deciding on a navigation.
    thread::spawn(move || {
        if let Some(window) = handle.get_webview_window(WINDOW_LABEL) {
            if let Ok(mut page) = window.url() {
                if !is_shell_page(&page) {
                    // Back from Budget itself (changing where it opens): the shell's own host again.
                    page = format!("http://{SHELL_HOST}/").parse().expect("valid shell URL");
                }
                page.set_path(&path);
                page.set_query(None);
                page.set_fragment(fragment.as_deref());
                let _ = window.navigate(page);
            }
        }
    });
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn takes_the_address_however_settings_shows_it() {
        for typed in ["100.101.102.103", "100.101.102.103:4873", "http://100.101.102.103:4873/", " 100.101.102.103:4873 "] {
            assert_eq!(parse_other_pc(typed).ok().map(|url| url.to_string()), Some("http://100.101.102.103:4873/".into()));
        }
        assert_eq!(parse_other_pc("192.168.1.20:5000").ok().map(|url| url.to_string()), Some("http://192.168.1.20:5000/".into()));
    }

    #[test]
    fn refuses_names_and_anything_that_isnt_an_address() {
        for typed in ["", "my-pc", "my-pc.tailnet.ts.net", "100.101.102", "100.101.102.103:port", "100.101.102.103:0", "https://10.0.0.2/"] {
            assert!(matches!(parse_other_pc(typed), Err(AddressProblem::NotAnAddress)), "{typed}");
        }
    }

    #[test]
    fn refuses_addresses_out_on_the_internet() {
        for typed in ["8.8.8.8", "100.128.0.1", "172.32.0.1", "127.0.0.1", "0.0.0.0"] {
            assert!(matches!(parse_other_pc(typed), Err(AddressProblem::NotPrivate)), "{typed}");
        }
        for typed in ["10.0.0.2", "172.16.5.4", "192.168.1.20", "100.64.0.1", "100.127.255.254"] {
            assert!(parse_other_pc(typed).is_ok(), "{typed}");
        }
    }
}
