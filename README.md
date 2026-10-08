# Open Remote

Control a Windows PC from your Android phone over the local network: use it as a touchpad, view the screen and tap on it, and type live with the phone's keyboard.

## Windows server

### Prebuilt executable

Run `PcRemoteServer.exe`. It is a single file that works on any 64-bit Windows 10/11 without installing Python.

It lives in the system tray (the UI is in Spanish):

- **Left click**: show the PC's IP addresses and PIN.
- **Right click**: menu with *Iniciar con Windows* (toggle autostart), *Abrir carpeta de configuración* (open config folder) and *Salir* (exit).

Config (`config.json`, with the PIN and port) and the log (`server.log`) are stored in `%APPDATA%\PcRemote\`.

### Build the executable

Requires 64-bit Python 3.8+ on Windows.

```
cd server
build_exe.bat             # outputs dist\PcRemoteServer.exe
```

### Run from source

```
cd server
start_server.bat          # installs dependencies and starts the server
# or: python server.py [--port 47000] [--pin 1234] [--no-pin] [--no-tray]
```

The server uses TCP port 47000 and UDP port 47001 (discovery). If Windows Firewall asks, allow access on private networks.

## Android app

```
cd android
gradlew assembleDebug     # APK in app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app, pick your PC from the discovered servers (or enter its IP manually) and type the PIN shown by the server.

## Usage

- **Mouse**: swipe = move · tap = click · two-finger tap = right click · two-finger swipe = scroll · three-finger tap = middle click · hold and move = drag.
- **Screen**: tap = click at that point (double tap = double click) · hold = right click · pinch = zoom · drag = pan.
- **Keyboard**: whatever you type on the Android keyboard is sent instantly, with a bar for Ctrl/Alt/Shift, Win, Esc, Tab, arrows, F1–F12 and media keys.

## Protocol

Client → server: one JSON message per line. Server → client: `[type 1B][length 4B BE][payload]` (1 = JSON, 2 = JPEG). Message details are at the top of `server/server.py`.
