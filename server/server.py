"""
PC Remote - servidor para Windows (MVP tipo Unified Remote).

Protocolo TCP (puerto 47000 por defecto):
  Cliente -> servidor : una línea JSON por mensaje, terminada en '\n'
  Servidor -> cliente : [tipo: 1 byte][longitud: 4 bytes big-endian][payload]
                        tipo 1 = JSON utf-8, tipo 2 = frame JPEG

Descubrimiento UDP (puerto 47001): el cliente envía "PCREMOTE_DISCOVER" por broadcast
y el servidor responde con un JSON {name, port}.

Mensajes del cliente:
  {"t":"hello","pin":"1234"}                      autenticación (primer mensaje)
  {"t":"move","dx":3.5,"dy":-1}                   movimiento relativo del ratón
  {"t":"click","b":"left|right|middle","n":1}     clic (n = nº de clics)
  {"t":"btn","b":"left","down":true}              pulsar/soltar botón (arrastrar)
  {"t":"scroll","dy":-120,"dx":0}                 rueda (120 = 1 muesca)
  {"t":"tap","x":0.5,"y":0.5,"b":"left"}          clic absoluto (coords normalizadas del monitor)
  {"t":"text","s":"hola"}                         escribir texto Unicode
  {"t":"key","k":"enter","mods":["ctrl"],"n":1}   tecla especial / combinación
  {"t":"screen","on":true,"mon":1,"w":1280,"q":60,"fps":15}
  {"t":"ack"}                                     frame recibido, se puede enviar el siguiente
"""

import argparse
import asyncio
import ctypes
import io
import json
import os
import random
import socket
import struct
import sys
import threading
import time
import zlib
from concurrent.futures import ThreadPoolExecutor
from ctypes import wintypes

if sys.platform != "win32":
    sys.exit("Este servidor solo funciona en Windows.")

APP_NAME = "PC Remote"
FROZEN = getattr(sys, "frozen", False)
# Compilado (.exe) la config y el log van a %APPDATA%\PcRemote; como script, junto a server.py
DATA_DIR = (os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "PcRemote")
            if FROZEN else os.path.dirname(os.path.abspath(__file__)))

if sys.stdout is None:
    # .exe sin consola: stdout/stderr no existen, se redirigen a un log
    os.makedirs(DATA_DIR, exist_ok=True)
    sys.stdout = sys.stderr = open(os.path.join(DATA_DIR, "server.log"), "w",
                                   encoding="utf-8", buffering=1)
else:
    sys.stdout.reconfigure(line_buffering=True)

# DPI awareness antes de cualquier llamada a la API de pantalla, para que las
# coordenadas del cursor y de la captura sean píxeles físicos.
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

from PIL import Image, ImageDraw  # noqa: E402

try:
    import mss  # noqa: E402
except ImportError:
    mss = None
    from PIL import ImageGrab  # noqa: E402

TCP_PORT = 47000
UDP_PORT = 47001
DISCOVER_MAGIC = b"PCREMOTE_DISCOVER"
MSG_JSON = 1
MSG_JPEG = 2

# --------------------------------------------------------------------------- #
# Entrada (SendInput)
# --------------------------------------------------------------------------- #

user32 = ctypes.WinDLL("user32", use_last_error=True)
ULONG_PTR = ctypes.c_size_t

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]


user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wintypes.UINT
user32.MapVirtualKeyW.argtypes = (wintypes.UINT, wintypes.UINT)
user32.MapVirtualKeyW.restype = wintypes.UINT
user32.VkKeyScanW.argtypes = (wintypes.WCHAR,)
user32.VkKeyScanW.restype = ctypes.c_short


def _send(inputs):
    if not inputs:
        return
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def _mouse(flags, dx=0, dy=0, data=0):
    inp = INPUT(type=INPUT_MOUSE)
    inp.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    return inp


def _key(vk=0, scan=0, flags=0):
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return inp


BUTTON_FLAGS = {
    "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}

VK = {
    "backspace": 0x08, "tab": 0x09, "enter": 0x0D, "shift": 0x10, "ctrl": 0x11,
    "alt": 0x12, "pause": 0x13, "capslock": 0x14, "esc": 0x1B, "space": 0x20,
    "pageup": 0x21, "pagedown": 0x22, "end": 0x23, "home": 0x24, "left": 0x25,
    "up": 0x26, "right": 0x27, "down": 0x28, "printscreen": 0x2C, "insert": 0x2D,
    "delete": 0x2E, "win": 0x5B, "menu": 0x5D,
    "volumemute": 0xAD, "volumedown": 0xAE, "volumeup": 0xAF,
    "nexttrack": 0xB0, "prevtrack": 0xB1, "playpause": 0xB3,
}
VK.update({f"f{i}": 0x6F + i for i in range(1, 13)})
EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2C, 0x2D, 0x2E, 0x5B, 0x5D,
            0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB3}


def _vk_for(name):
    name = name.lower()
    if name in VK:
        return VK[name]
    if len(name) == 1 and (name.isalnum()):
        return ord(name.upper())
    if len(name) == 1:
        # VkKeyScanW devuelve vk en el byte bajo (sin modificadores)
        res = user32.VkKeyScanW(name)
        if res != -1:
            return res & 0xFF
    return None


def _vk_event(vk, up):
    flags = KEYEVENTF_KEYUP if up else 0
    if vk in EXTENDED:
        flags |= KEYEVENTF_EXTENDEDKEY
    scan = user32.MapVirtualKeyW(vk, 0)
    return _key(vk=vk, scan=scan, flags=flags)


class Input:
    def __init__(self):
        self._rx = 0.0
        self._ry = 0.0

    def move(self, dx, dy):
        # acumulamos la parte fraccionaria para que los movimientos lentos no se pierdan
        self._rx += dx
        self._ry += dy
        ix, iy = int(self._rx), int(self._ry)
        self._rx -= ix
        self._ry -= iy
        if ix or iy:
            _send([_mouse(MOUSEEVENTF_MOVE, ix, iy)])

    def button(self, b, down):
        d, u = BUTTON_FLAGS.get(b, BUTTON_FLAGS["left"])
        _send([_mouse(d if down else u)])

    def click(self, b, n=1):
        d, u = BUTTON_FLAGS.get(b, BUTTON_FLAGS["left"])
        _send([_mouse(f) for _ in range(max(1, min(n, 3))) for f in (d, u)])

    def scroll(self, dy=0, dx=0):
        evs = []
        if dy:
            evs.append(_mouse(MOUSEEVENTF_WHEEL, data=int(dy)))
        if dx:
            evs.append(_mouse(MOUSEEVENTF_HWHEEL, data=int(dx)))
        _send(evs)

    def move_abs(self, x, y):
        user32.SetCursorPos(int(x), int(y))

    def text(self, s):
        evs = []
        for ch in s:
            if ch == "\n":
                evs += [_vk_event(VK["enter"], False), _vk_event(VK["enter"], True)]
                continue
            if ch == "\t":
                evs += [_vk_event(VK["tab"], False), _vk_event(VK["tab"], True)]
                continue
            data = ch.encode("utf-16-le")
            for i in range(0, len(data), 2):
                unit = int.from_bytes(data[i:i + 2], "little")
                evs.append(_key(scan=unit, flags=KEYEVENTF_UNICODE))
                evs.append(_key(scan=unit, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
        _send(evs)

    def key(self, name, mods=(), n=1):
        vk = _vk_for(name)
        if vk is None:
            return False
        mod_vks = [VK[m] for m in mods if m in VK]
        evs = [_vk_event(m, False) for m in mod_vks]
        for _ in range(max(1, min(int(n), 500))):
            evs += [_vk_event(vk, False), _vk_event(vk, True)]
        evs += [_vk_event(m, True) for m in reversed(mod_vks)]
        _send(evs)
        return True


# --------------------------------------------------------------------------- #
# Captura de pantalla
# --------------------------------------------------------------------------- #

class _POINT(ctypes.Structure):
    _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]


def cursor_pos():
    p = _POINT()
    user32.GetCursorPos(ctypes.byref(p))
    return p.x, p.y


class Capture:
    """Se usa siempre desde el mismo hilo (mss no es thread-safe)."""

    def __init__(self):
        self._local = threading.local()

    def _sct(self):
        if mss is None:
            return None
        if not hasattr(self._local, "sct"):
            self._local.sct = getattr(mss, "MSS", None)() if hasattr(mss, "MSS") else mss.mss()
        return self._local.sct

    def monitors(self):
        """Lista de dicts {left, top, width, height}; índice 0 = primer monitor físico."""
        sct = self._sct()
        if sct is None:
            w = user32.GetSystemMetrics(0)
            h = user32.GetSystemMetrics(1)
            return [{"left": 0, "top": 0, "width": w, "height": h}]
        return [dict(m) for m in sct.monitors[1:]]

    def grab(self, mon_index, max_w, quality, last_sig):
        mons = self.monitors()
        mon = mons[mon_index % len(mons)]
        cx, cy = cursor_pos()

        sct = self._sct()
        if sct is not None:
            shot = sct.grab(mon)
            raw = shot.bgra
            size = shot.size
        else:
            img = ImageGrab.grab()
            raw = img.tobytes()
            size = img.size

        sig = (zlib.adler32(raw), cx, cy, max_w, quality)
        if sig == last_sig:
            return None
        if sct is not None:
            img = Image.frombytes("RGB", size, raw, "raw", "BGRX")

        scale = 1.0
        if size[0] > max_w:
            scale = max_w / size[0]
            img = img.resize((max_w, max(1, int(size[1] * scale))), Image.BILINEAR)

        # Dibujar el cursor (la captura no lo incluye)
        lx = (cx - mon["left"]) * scale
        ly = (cy - mon["top"]) * scale
        if 0 <= lx < img.width and 0 <= ly < img.height:
            s = max(10, img.width / 90)
            pts = [(lx, ly), (lx, ly + s * 1.4), (lx + s * 0.38, ly + s * 1.05),
                   (lx + s * 0.62, ly + s * 1.55), (lx + s * 0.82, ly + s * 1.45),
                   (lx + s * 0.6, ly + s * 0.98), (lx + s * 1.05, ly + s * 0.98)]
            ImageDraw.Draw(img).polygon(pts, fill=(255, 255, 255), outline=(0, 0, 0))

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality)
        return buf.getvalue(), sig


# --------------------------------------------------------------------------- #
# Servidor
# --------------------------------------------------------------------------- #

def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


class Server:
    def __init__(self, port, pin):
        self.port = port
        self.pin = pin
        self.input = Input()
        self.capture = Capture()
        # un único hilo para captura -> mss siempre en el mismo hilo
        self.cap_exec = ThreadPoolExecutor(max_workers=1, thread_name_prefix="capture")
        self.name = socket.gethostname()
        self.clients = 0
        self.on_clients = None  # callback(n) al cambiar el nº de clientes

    def _set_clients(self, delta):
        self.clients += delta
        if self.on_clients:
            self.on_clients(self.clients)

    async def handle(self, reader, writer):
        peer = writer.get_extra_info("peername")
        sock = writer.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        session = Session(self, reader, writer)
        try:
            await session.run()
        except (ConnectionError, asyncio.IncompleteReadError):
            pass
        except Exception as e:  # noqa: BLE001
            print(f"[!] Error con {peer}: {e!r}")
        finally:
            session.stop_stream()
            writer.close()
            print(f"[-] Desconectado {peer[0]}")


class Session:
    def __init__(self, server, reader, writer):
        self.srv = server
        self.reader = reader
        self.writer = writer
        self.stream_task = None
        self.ack = asyncio.Event()
        self.mon = 0
        self.max_w = 1280
        self.quality = 60
        self.fps = 15

    async def send(self, kind, payload):
        self.writer.write(struct.pack(">BI", kind, len(payload)) + payload)
        await self.writer.drain()

    async def send_json(self, obj):
        await self.send(MSG_JSON, json.dumps(obj).encode())

    async def run(self):
        peer = self.writer.get_extra_info("peername")
        line = await asyncio.wait_for(self.reader.readline(), timeout=10)
        try:
            hello = json.loads(line)
        except ValueError:
            return
        if hello.get("t") != "hello" or (self.srv.pin and str(hello.get("pin", "")) != self.srv.pin):
            print(f"[!] PIN incorrecto desde {peer[0]}")
            await asyncio.sleep(1.0)  # frena intentos de fuerza bruta
            await self.send_json({"t": "hello", "ok": False, "error": "PIN incorrecto"})
            return

        mons = await self._run_cap(self.srv.capture.monitors)
        await self.send_json({"t": "hello", "ok": True, "name": self.srv.name,
                              "monitors": len(mons)})
        print(f"[+] Conectado {peer[0]}")

        self.srv._set_clients(1)
        try:
            while True:
                line = await self.reader.readline()
                if not line:
                    return
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                await self.dispatch(msg)
        finally:
            self.srv._set_clients(-1)

    async def _run_cap(self, fn, *args):
        return await asyncio.get_running_loop().run_in_executor(self.srv.cap_exec, fn, *args)

    async def dispatch(self, m):
        t = m.get("t")
        inp = self.srv.input
        if t == "move":
            inp.move(float(m.get("dx", 0)), float(m.get("dy", 0)))
        elif t == "click":
            inp.click(m.get("b", "left"), int(m.get("n", 1)))
        elif t == "btn":
            inp.button(m.get("b", "left"), bool(m.get("down")))
        elif t == "scroll":
            inp.scroll(float(m.get("dy", 0)), float(m.get("dx", 0)))
        elif t == "tap":
            mons = await self._run_cap(self.srv.capture.monitors)
            mon = mons[self.mon % len(mons)]
            x = mon["left"] + min(max(float(m["x"]), 0.0), 0.9999) * mon["width"]
            y = mon["top"] + min(max(float(m["y"]), 0.0), 0.9999) * mon["height"]
            inp.move_abs(x, y)
            b = m.get("b")
            if b:
                inp.click(b, int(m.get("n", 1)))
        elif t == "text":
            inp.text(str(m.get("s", "")))
        elif t == "key":
            inp.key(str(m.get("k", "")), [str(x).lower() for x in m.get("mods", [])],
                    int(m.get("n", 1)))
        elif t == "screen":
            if "mon" in m:
                self.mon = int(m["mon"])
            self.max_w = int(min(max(int(m.get("w", self.max_w)), 320), 3840))
            self.quality = int(min(max(int(m.get("q", self.quality)), 10), 95))
            self.fps = float(min(max(float(m.get("fps", self.fps)), 1), 60))
            if m.get("on", True):
                self.start_stream()
            else:
                self.stop_stream()
        elif t == "ack":
            self.ack.set()

    def start_stream(self):
        self.ack.set()
        if self.stream_task is None or self.stream_task.done():
            self.stream_task = asyncio.ensure_future(self._stream())

    def stop_stream(self):
        if self.stream_task is not None:
            self.stream_task.cancel()
            self.stream_task = None

    async def _stream(self):
        last_sig = None
        last_mon = None
        while True:
            # control de flujo: un solo frame en vuelo, así nunca se acumula retraso
            try:
                await asyncio.wait_for(self.ack.wait(), timeout=3)
            except asyncio.TimeoutError:
                pass  # ack perdido -> seguimos igualmente
            t0 = time.monotonic()
            if last_mon != self.mon:
                last_sig, last_mon = None, self.mon
            res = await self._run_cap(self.srv.capture.grab, self.mon, self.max_w,
                                      self.quality, last_sig)
            if res is not None:
                jpeg, last_sig = res
                self.ack.clear()
                await self.send(MSG_JPEG, jpeg)
            await asyncio.sleep(max(0.0, 1.0 / self.fps - (time.monotonic() - t0)))


class Discovery(asyncio.DatagramProtocol):
    def __init__(self, server):
        self.srv = server

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if data.strip() == DISCOVER_MAGIC:
            reply = json.dumps({"name": self.srv.name, "port": self.srv.port,
                                "pin": bool(self.srv.pin)}).encode()
            self.transport.sendto(reply, addr)


def load_config(path):
    cfg = {}
    if os.path.isfile(path):
        try:
            with open(path, encoding="utf-8") as f:
                cfg = json.load(f)
        except (OSError, ValueError):
            cfg = {}
    changed = False
    if "pin" not in cfg:
        cfg["pin"] = f"{random.SystemRandom().randint(0, 9999):04d}"
        changed = True
    if "port" not in cfg:
        cfg["port"] = TCP_PORT
        changed = True
    if changed:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    return cfg


def parse_args():
    ap = argparse.ArgumentParser(description="PC Remote - servidor")
    ap.add_argument("--port", type=int, help="puerto TCP (por defecto 47000)")
    ap.add_argument("--pin", help="PIN de acceso (se guarda en config.json)")
    ap.add_argument("--no-pin", action="store_true", help="desactiva el PIN (¡cualquiera en tu red podrá conectarse!)")
    ap.add_argument("--no-tray", action="store_true", help="sin icono en la bandeja (solo consola)")
    ap.add_argument("--config", default=os.path.join(DATA_DIR, "config.json"))
    return ap.parse_args()


async def serve(srv):
    tcp = await asyncio.start_server(srv.handle, "0.0.0.0", srv.port, limit=1 << 20)
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(lambda: Discovery(srv), local_addr=("0.0.0.0", UDP_PORT),
                                        allow_broadcast=True)

    print("=" * 46)
    print(f"  PC Remote - servidor   ({srv.name})")
    print("=" * 46)
    for ip in local_ips():
        print(f"  IP: {ip}   puerto: {srv.port}")
    print(f"  PIN: {srv.pin if srv.pin else '(desactivado)'}")
    print(f"  Captura: {'mss' if mss else 'PIL.ImageGrab'}")
    print("  Ctrl+C para salir" if srv.on_clients is None else "  Salir: icono de la bandeja")
    print("=" * 46)
    async with tcp:
        await tcp.serve_forever()


# --------------------------------------------------------------------------- #
# Icono en la bandeja del sistema
# --------------------------------------------------------------------------- #

def make_icon(size=64):
    """Icono de la app: un móvil blanco sobre un cuadrado azul redondeado."""
    s = size / 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=14 * s, fill=(37, 99, 235))
    d.rounded_rectangle((19 * s, 8 * s, 45 * s, 56 * s), radius=5 * s, fill=(255, 255, 255))
    d.rounded_rectangle((23 * s, 14 * s, 41 * s, 44 * s), radius=2 * s, fill=(37, 99, 235))
    d.ellipse((29 * s, 47 * s, 35 * s, 53 * s), fill=(37, 99, 235))
    return img


def message_box(text, error=False):
    flags = 0x10 if error else 0x40  # MB_ICONERROR / MB_ICONINFORMATION
    ctypes.windll.user32.MessageBoxW(None, text, APP_NAME, flags | 0x10000)  # MB_SETFOREGROUND


# Inicio automático: valor en HKCU\...\Run (por usuario, no requiere permisos de administrador)
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "PcRemote"


def autostart_command():
    if FROZEN:
        return f'"{sys.executable}"'
    # como script: pythonw.exe para que no aparezca ninguna consola
    exe = sys.executable
    pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")
    return f'"{pyw if os.path.isfile(pyw) else exe}" "{os.path.abspath(__file__)}"'


def autostart_enabled():
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            value, _ = winreg.QueryValueEx(k, RUN_VALUE)
    except OSError:
        return False
    # si el .exe se ha movido, la entrada antigua no cuenta como activada
    return value == autostart_command()


def set_autostart(enabled):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if enabled:
            winreg.SetValueEx(k, RUN_VALUE, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(k, RUN_VALUE)
            except FileNotFoundError:
                pass


def run_tray(srv, config_path):
    import pystray

    ips = local_ips()
    pin_txt = srv.pin if srv.pin else "(desactivado)"
    info = "\n".join([f"Equipo: {srv.name}"] + [f"IP: {ip}   puerto: {srv.port}" for ip in ips]
                     + [f"PIN: {pin_txt}"])

    def tooltip(n):
        return f"{APP_NAME} - PIN {pin_txt} - {n} conectado{'s' if n != 1 else ''}"

    def show_info(icon, item):
        # en otro hilo para no bloquear el bucle de mensajes del icono
        threading.Thread(target=message_box, args=(info,), daemon=True).start()

    def open_folder(icon, item):
        os.startfile(os.path.dirname(os.path.abspath(config_path)))

    def toggle_autostart(icon, item):
        try:
            set_autostart(not autostart_enabled())
        except OSError as e:
            threading.Thread(target=message_box, daemon=True,
                             args=(f"No se pudo cambiar el inicio automático:\n{e}", True)).start()
        icon.update_menu()

    def quit_app(icon, item):
        icon.stop()

    items = [pystray.MenuItem("Mostrar IP y PIN", show_info, default=True),
             pystray.Menu.SEPARATOR]
    items += [pystray.MenuItem(f"IP: {ip}:{srv.port}", None, enabled=False) for ip in ips]
    items += [pystray.MenuItem(f"PIN: {pin_txt}", None, enabled=False),
              pystray.Menu.SEPARATOR,
              pystray.MenuItem("Iniciar con Windows", toggle_autostart,
                               checked=lambda item: autostart_enabled()),
              pystray.MenuItem("Abrir carpeta de configuración", open_folder),
              pystray.MenuItem("Salir", quit_app)]

    icon = pystray.Icon("PcRemote", make_icon(64), tooltip(0), pystray.Menu(*items))

    def on_clients(n):
        icon.title = tooltip(n)

    srv.on_clients = on_clients

    def worker(icon):
        icon.visible = True
        try:
            asyncio.run(serve(srv))
        except OSError as e:
            message_box(f"No se pudo iniciar el servidor en el puerto {srv.port}.\n"
                        f"¿Ya hay otra copia de PC Remote abierta?\n\n{e}", error=True)
            icon.stop()
        except Exception as e:  # noqa: BLE001
            message_box(f"Error inesperado del servidor:\n{e!r}", error=True)
            icon.stop()

    icon.run(setup=worker)


def main():
    args = parse_args()
    cfg = load_config(args.config)
    if args.pin:
        cfg["pin"] = args.pin
        with open(args.config, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    port = args.port or int(cfg["port"])
    pin = "" if args.no_pin else str(cfg["pin"])
    srv = Server(port, pin)

    if args.no_tray:
        try:
            asyncio.run(serve(srv))
        except KeyboardInterrupt:
            pass
        return

    run_tray(srv, args.config)
    # el bucle asyncio y el hilo de captura siguen vivos: salida inmediata
    os._exit(0)


if __name__ == "__main__":
    main()
