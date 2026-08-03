#!/usr/bin/env python3
"""Fast drop-in replacement for atv_driver: same conceptual API, ~15x faster
UI reads and ~100x faster input on the emulator.

How it is fast:
- UI reads: uiautomator2's persistent on-device instrumentation server
  (accessibility snapshot over a forwarded HTTP socket, ~150ms) instead of a
  one-shot `uiautomator dump` + `adb pull` (~2.3s, flaky "null root node").
- Input: the scrcpy 4.1 server's control socket (InputManager.injectInputEvent
  via app_process, shell uid) instead of one `adb shell input` process per
  event. A key press is a 28-byte socket write. Works on production devices
  (no root, no SELinux issue - direct /dev/input writes are denied to shell).
- Shell commands: one persistent `adb shell` with sentinel framing instead of
  a new adb client + shell process per call.

Both helpers are started lazily and cleaned up at exit. The XML from
uiautomator2 uses the exact same <hierarchy><node .../></hierarchy> schema as
`uiautomator dump`, so all tree-walking code is unchanged from atv_driver.
"""
import atexit
import os
import re
import socket
import struct
import subprocess
import sys
import time

try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET

_SCRATCH = os.path.dirname(os.path.abspath(__file__))
_VENV_SP = os.path.join(_SCRATCH, 'speedvenv', 'lib',
                        'python%d.%d' % sys.version_info[:2], 'site-packages')

try:
    import uiautomator2 as u2
except ImportError:
    if os.path.isdir(_VENV_SP):
        sys.path.append(_VENV_SP)
    import uiautomator2 as u2

UI_XML = os.path.join(_SCRATCH, 'atv-ui.xml')

KEY_BACK = 4
KEY_UP = 19
KEY_DOWN = 20
KEY_LEFT = 21
KEY_RIGHT = 22
KEY_CENTER = 23
KEY_ENTER = 66
KEY_WAKEUP = 224

_SCRCPY_JAR_CANDIDATES = (
    '/usr/share/scrcpy/scrcpy-server',
    '/usr/local/share/scrcpy/scrcpy-server',
)
_SCRCPY_VERSION = '4.1'
_DEVICE_JAR = '/data/local/tmp/scrcpy-server-atvfast.jar'


class _PersistentShell:
    """One long-lived `adb shell`, commands framed with unique sentinels."""

    def __init__(self, serial):
        self.serial = serial
        self._n = 0
        self._p = None

    def _ensure(self):
        if self._p is None or self._p.poll() is not None:
            self._p = subprocess.Popen(
                ['adb', '-s', self.serial, 'shell'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=0)

    def run(self, cmd):
        for attempt in range(2):
            self._ensure()
            self._n += 1
            sentinel = '__ATV_DONE_%d__' % self._n
            try:
                self._p.stdin.write(cmd + '\necho %s\n' % sentinel)
                self._p.stdin.flush()
                out = []
                while True:
                    line = self._p.stdout.readline()
                    if not line:
                        raise OSError('shell EOF')
                    if line.strip() == sentinel:
                        return ''.join(out)
                    out.append(line)
            except OSError:
                self._close()
                if attempt:
                    raise
        return ''

    def _close(self):
        if self._p is not None:
            try:
                self._p.kill()
            except OSError:
                pass
            self._p = None

    def close(self):
        self._close()


class _ScrcpyControl:
    """scrcpy server started control-only; injects input over its socket."""

    def __init__(self, serial):
        self.serial = serial
        self._proc = None
        self._sock = None
        self._port = None
        self._scid = os.getpid() & 0x7fffffff
        self._screen = None

    def _adb(self, *args):
        return subprocess.run(['adb', '-s', self.serial, *args],
                              capture_output=True, text=True)

    def _ensure(self):
        if self._sock is not None and self._proc and self._proc.poll() is None:
            return
        self._teardown()
        jar = next((j for j in _SCRCPY_JAR_CANDIDATES if os.path.exists(j)), None)
        if jar is None:
            raise RuntimeError('scrcpy-server jar not found on host')
        self._adb('push', jar, _DEVICE_JAR)
        self._proc = subprocess.Popen(
            ['adb', '-s', self.serial, 'shell',
             'CLASSPATH=' + _DEVICE_JAR, 'app_process', '/',
             'com.genymobile.scrcpy.Server', _SCRCPY_VERSION,
             'scid=%08x' % self._scid, 'tunnel_forward=true',
             'video=false', 'audio=false', 'control=true',
             'send_device_meta=false', 'send_dummy_byte=true',
             'clipboard_autosync=false', 'stay_awake=true',
             'log_level=warn'],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        out = self._adb('forward', 'tcp:0',
                        'localabstract:scrcpy_%08x' % self._scid)
        self._port = int(out.stdout.strip())
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                s = socket.create_connection(('127.0.0.1', self._port), timeout=1)
                s.settimeout(3)
                if s.recv(1) == b'\x00':
                    s.settimeout(None)
                    self._sock = s
                    return
                s.close()
            except OSError:
                pass
            if self._proc.poll() is not None:
                raise RuntimeError('scrcpy server died: ' +
                                   (self._proc.stdout.read() or '')[:500])
            time.sleep(0.1)
        raise RuntimeError('scrcpy control socket connect timed out')

    def _send(self, payload):
        for attempt in range(2):
            self._ensure()
            try:
                self._sock.sendall(payload)
                return
            except OSError:
                self._teardown()
                if attempt:
                    raise

    def key(self, keycode):
        self._send(struct.pack('>BBiii', 0, 0, keycode, 0, 0) +
                   struct.pack('>BBiii', 0, 1, keycode, 0, 0))

    def text(self, s):
        raw = s.encode('utf-8')
        self._send(struct.pack('>BI', 1, len(raw)) + raw)

    def screen_size(self, shell):
        if self._screen is None:
            out = shell.run('wm size')
            m = (re.search(r'Override size:\s*(\d+)x(\d+)', out) or
                 re.search(r'Physical size:\s*(\d+)x(\d+)', out))
            self._screen = (int(m.group(1)), int(m.group(2))) if m else (1920, 1080)
        return self._screen

    def tap(self, x, y, w, h):
        def touch(action, pressure):
            return struct.pack('>BBqiiHHHii', 2, action, 0, x, y, w, h,
                               pressure, 1, 1 if action == 0 else 0)
        self._send(touch(0, 0xffff))  # ACTION_DOWN
        time.sleep(0.04)
        self._send(touch(1, 0))       # ACTION_UP

    def _teardown(self):
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None
        if self._proc is not None:
            if self._proc.poll() is None:
                self._proc.terminate()
            self._proc = None
        if self._port is not None:
            self._adb('forward', '--remove', 'tcp:%d' % self._port)
            self._port = None

    def close(self):
        self._teardown()


class Device:
    def __init__(self, serial, package='org.jellyfin.androidtv'):
        self.serial = serial
        self.package = package
        self._sh = _PersistentShell(serial)
        self._ctl = _ScrcpyControl(serial)
        self._u2 = None
        atexit.register(self.close)

    def close(self):
        self._ctl.close()
        self._sh.close()

    # -- plumbing -----------------------------------------------------------

    def adb(self, *args, **kw):
        return subprocess.run(['adb', '-s', self.serial, *args],
                              capture_output=True, text=True, **kw)

    def shell(self, *args):
        # single string or argv pieces, like atv_driver usage
        return self._sh.run(' '.join(args))

    def _ui(self):
        if self._u2 is None:
            self._u2 = u2.connect(self.serial)
        return self._u2

    # -- power / lifecycle --------------------------------------------------

    def ensure_awake(self):
        power = self._sh.run('dumpsys power | grep -m1 mWakefulness=')
        if 'mWakefulness=Awake' not in power:
            self._ctl.key(KEY_WAKEUP)
            for _ in range(20):
                time.sleep(0.25)
                power = self._sh.run('dumpsys power | grep -m1 mWakefulness=')
                if 'mWakefulness=Awake' in power:
                    break

    def keep_awake_during_run(self):
        self._sh.run('settings put global stay_on_while_plugged_in 7')

    def launch(self, wait=8):
        """Launch and poll until the app owns the resumed activity (bounded by
        `wait` seconds) instead of sleeping the full fixed interval."""
        self.ensure_awake()
        self._sh.run('monkey -p %s -c android.intent.category.LEANBACK_LAUNCHER 1'
                     % self.package)
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline:
            if self.in_app():
                break
            time.sleep(0.3)
        # settle: dumps are cheap, so poll until the UI actually has content
        # instead of sleeping a fixed interval
        while time.monotonic() < deadline:
            if self.texts():
                return
            time.sleep(0.3)

    def stop(self):
        self._sh.run('am force-stop ' + self.package)

    def foreground(self):
        out = self._sh.run('dumpsys activity activities | grep -m1 ResumedActivity:')
        m = re.search(r'ResumedActivity: ActivityRecord\{\S+ u0 (\S+)', out)
        return m.group(1) if m else None

    def in_app(self):
        return self.package + '/' in (self.foreground() or '')

    # -- input --------------------------------------------------------------

    def key(self, code, delay=0.35):
        self._ctl.key(code)
        time.sleep(delay)

    def type_text(self, text, delay=0.5):
        self._ctl.text(text)
        time.sleep(delay)

    def tap(self, x, y, delay=1.0):
        w, h = self._ctl.screen_size(self._sh)
        self._ctl.tap(int(x), int(y), w, h)
        time.sleep(delay)

    # -- dump handling ------------------------------------------------------

    def dump_xml(self, retries=4):
        """Raw hierarchy XML string via the persistent uiautomator2 server."""
        for attempt in range(retries):
            try:
                xml = self._ui().dump_hierarchy()
                if xml and '<node' in xml:
                    return xml
            except Exception:
                # server hiccup: drop the cached session, wake, reconnect
                self._u2 = None
                self.ensure_awake()
                time.sleep(0.3 * (attempt + 1))
        return None

    def dump_tree(self, retries=4):
        xml = self.dump_xml(retries)
        if xml is None:
            return None
        with open(UI_XML, 'w', encoding='utf-8') as f:
            f.write(xml)
        try:
            return ET.fromstring(xml)
        except ET.ParseError:
            return None

    def texts(self, limit=200):
        root = self.dump_tree()
        if root is None:
            return []
        out = []
        for node in root.iter('node'):
            t = node.get('text', '')
            if t.strip():
                out.append(t)
        return out[:limit]

    def has_text(self, *needles):
        texts = self.texts()
        return any(any(n in t for t in texts) for n in needles)

    def long_press(self, code=KEY_CENTER, delay=1.2):
        """Long-press a key. scrcpy's control protocol has no press-and-hold
        for a single key, so this goes through the persistent shell."""
        self._sh.run('input keyevent --longpress %d' % code)
        time.sleep(delay)

    def wait_text(self, *needles, timeout=10):
        """Poll (cheap now) until any needle appears. Extra helper - dumps are
        fast enough that polling replaces fixed sleeps."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.has_text(*needles):
                return True
            time.sleep(0.2)
        return False

    def bounds_of(self, text):
        root = self.dump_tree()
        if root is None:
            return None
        for node in root.iter('node'):
            if node.get('text') == text:
                m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return ((x1 + x2) // 2, (y1 + y2) // 2)
        return None

    def tap_text(self, text, dy=0, delay=1.0):
        p = self.bounds_of(text)
        if not p:
            return False
        self.tap(p[0], p[1] + dy, delay)
        return True

    def focused_texts(self):
        root = self.dump_tree()
        if root is None:
            return []

        def collect(node):
            out = []
            t = node.get('text', '')
            d = node.get('content-desc', '')
            if t.strip():
                out.append(t)
            if d.strip():
                out.append(d)
            for child in node:
                out.extend(collect(child))
            return out

        for node in root.iter('node'):
            if node.get('focused') == 'true':
                return collect(node)
        return []

    # -- dpad navigation ----------------------------------------------------

    def dpad_until(self, predicate, key=KEY_DOWN, max_steps=12, delay=0.35):
        for _ in range(max_steps + 1):
            texts = self.focused_texts()
            if predicate(texts):
                return True
            self.key(key, delay)
        return predicate(self.focused_texts())

    def focus_row_and_pick(self, row_header, item_predicate=None, max_rows=20, max_cols=15):
        def on_row(_texts):
            return self._focused_row_header() == row_header

        if not self.dpad_until(on_row, KEY_DOWN, max_rows):
            return None
        if item_predicate is None:
            return self.focused_texts()
        for _ in range(max_cols):
            texts = self.focused_texts()
            if item_predicate(texts):
                return texts
            self.key(KEY_RIGHT)
        return None

    def _focused_row_header(self):
        root = self.dump_tree()
        if root is None:
            return None
        focused_top = None
        for node in root.iter('node'):
            if node.get('focused') == 'true':
                m = re.match(r'\[(\d+),(\d+)\]', node.get('bounds', ''))
                if m:
                    focused_top = int(m.group(2))
                break
        if focused_top is None:
            return None
        best = (None, -1)
        for node in root.iter('node'):
            t = node.get('text', '')
            if not t.strip():
                continue
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            if x1 < 200 and y2 <= focused_top and y1 > best[1]:
                best = (t, y1)
        return best[0]

    # -- diagnostics --------------------------------------------------------

    def crash_log(self):
        blocks = []
        out = self.adb('logcat', '-d', '-b', 'crash').stdout
        for block in out.split('FATAL EXCEPTION')[1:]:
            if 'rikka' in block or 'shizuku' in block.lower():
                continue
            blocks.append('FATAL EXCEPTION' + block[:3000])
        # ACRA-caught crashes never reach the crash buffer. Capture the
        # exception *message* and the frames that follow, not just the type:
        # two unrelated bugs can share a type, and a bare type name once led a
        # regression to be misread as a known crash.
        main = self.adb('logcat', '-d').stdout
        lines = main.splitlines()
        pattern = re.compile(r'ACRA caught an? (\w+) for ' + re.escape(self.package) + r'\b')
        for index, line in enumerate(lines):
            match = pattern.search(line)
            if not match:
                continue
            detail = [l.split(': ', 1)[-1] for l in lines[index:index + 14]]
            blocks.append('ACRA: %s\n%s' % (match.group(1), '\n'.join(detail[1:])))
        return blocks

    def requests(self, needle):
        out = self.adb('logcat', '-d').stdout
        return [l for l in out.splitlines() if 'http' in l and needle in l
                and (' GET ' in l or ' POST ' in l)]

    def clear_logs(self):
        self.adb('logcat', '-c')
        self.adb('logcat', '-c', '-b', 'crash')
