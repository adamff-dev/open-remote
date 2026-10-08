@echo off
rem Compila server.py en dist\PcRemoteServer.exe (un solo archivo, sin consola, con icono en la bandeja).
rem Requisitos: Python 3.8-3.12 de 64 bits en el PATH (o el lanzador "py").
setlocal
cd /d "%~dp0"

set "VENV=.venv-build"
set "NAME=PcRemoteServer"

where py >nul 2>nul && (set "PY=py -3") || (set "PY=python")

if not exist "%VENV%\Scripts\python.exe" (
    echo [1/4] Creando entorno virtual de compilacion...
    %PY% -m venv "%VENV%" || goto :error
)
set "VPY=%VENV%\Scripts\python.exe"

echo [2/4] Instalando dependencias...
"%VPY%" -m pip install -q --upgrade pip || goto :error
"%VPY%" -m pip install -q -r requirements.txt pyinstaller || goto :error

echo [3/4] Generando icono...
if not exist build mkdir build
"%VPY%" -c "from server import make_icon; make_icon(256).save('build/icon.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])" || goto :error

echo [4/4] Compilando con PyInstaller...
"%VPY%" -m PyInstaller --noconfirm --clean --onefile --windowed ^
    --name "%NAME%" ^
    --icon "%~dp0build\icon.ico" ^
    --hidden-import pystray._win32 ^
    --collect-submodules mss ^
    --distpath "..\dist" ^
    --workpath "build\pyinstaller" ^
    --specpath "build" ^
    server.py || goto :error

echo.
echo Listo: ..\dist\%NAME%.exe
exit /b 0

:error
echo.
echo *** Error durante la compilacion ***
exit /b 1
