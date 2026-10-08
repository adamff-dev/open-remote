# AGENTS.md

## Project layout

- `server/server.py` — Windows server (Python). Runs with a system tray icon by default; `--no-tray` for console only.
- `android/` — Android client app.
- `dist/` — build outputs (`PcRemoteServer.exe`, `PcRemote.apk`). Not committed.

## Building the Windows server executable

- Build with `server/build_exe.bat`. It creates an isolated venv (`server/.venv-build`), installs `server/requirements.txt` + PyInstaller, generates the icon and produces a single-file, windowed `dist/PcRemoteServer.exe`.
- **Keep the build script up to date.** Whenever a change to the server affects the build (new dependencies, dynamically imported modules that need `--hidden-import`/`--collect-*`, data files, icons, Python version, output name, etc.), update `server/build_exe.bat` (and `server/requirements.txt`) in the same change, then rebuild and verify the exe still starts.
- When the exe runs without a console, stdout/stderr go to `%APPDATA%\PcRemote\server.log`, and `config.json` (PIN, port) lives in `%APPDATA%\PcRemote\`. When run as a script, `config.json` sits next to `server.py`.
