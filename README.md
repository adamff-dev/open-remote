# PC Remote (MVP tipo Unified Remote)

Controla un PC con Windows desde Android: touchpad, ver la pantalla y hacer clic en ella, y escribir en vivo con el teclado del móvil.

## Servidor (Windows)

```
cd server
start_server.bat          # instala pillow + mss y arranca
# o: python server.py [--port 47000] [--pin 1234] [--no-pin]
```

Muestra las IPs y el **PIN** (se guarda en `server/config.json`). Por defecto aparece un icono en la bandeja del sistema (clic = ver IP y PIN, clic derecho → Salir); `--no-tray` para usar solo la consola. Usa TCP 47000 y UDP 47001 (descubrimiento); si Windows pregunta por el firewall, permite redes privadas.

### Ejecutable (.exe)

```
cd server
build_exe.bat             # genera dist\PcRemoteServer.exe
```

Un único `.exe` sin consola que funciona en cualquier Windows 10/11 de 64 bits sin instalar Python. Se queda en la bandeja del sistema; con clic derecho → **Salir** se cierra. La config y el log (`server.log`) se guardan en `%APPDATA%\PcRemote\`.

## App Android

```
cd android
gradlew assembleDebug     # APK en app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Uso

- **Ratón**: deslizar = mover · tocar = clic · 2 dedos tocar = clic derecho · 2 dedos deslizar = scroll · 3 dedos = clic central · mantener y mover = arrastrar. Los botones de abajo se pueden mantener pulsados.
- **Pantalla**: tocar = clic en ese punto (dos toques = doble clic) · mantener = clic derecho · pellizcar = zoom · arrastrar = desplazarse · ⤢ = ajustar. Botón de calidad (Baja/Media/Alta) y de monitor si hay varios.
- **Teclado (⌨)**: lo que escribes con el teclado de Android se envía al instante (autocorrector, gestos y dictado incluidos). Barra con Ctrl/Alt/Shift (se aplican a la siguiente tecla, p. ej. Ctrl + c), Win, Esc, Tab, flechas, F1–F12, multimedia…

## Protocolo

Cliente → servidor: una línea JSON por mensaje. Servidor → cliente: `[tipo 1B][longitud 4B BE][payload]` (1 = JSON, 2 = JPEG). Detalle de mensajes al inicio de `server/server.py`.
# open-remote
