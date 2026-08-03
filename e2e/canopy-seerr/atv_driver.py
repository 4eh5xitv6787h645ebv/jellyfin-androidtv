#!/usr/bin/env python3
"""Reusable UI driver for the jellyfin-androidtv fork (emulator + Shield).

Dpad-first: TV UIs are focus-driven, so navigation walks focus with key
events and reads the focused node's subtree text instead of tapping blind
coordinates. Taps remain available for Compose toolbar buttons, which respond
to touch reliably.
"""
import re
import subprocess
import time

try:
	# Prefer the hardened parser when available; the stdlib one is acceptable
	# here only because the XML comes from uiautomator dumps of devices this
	# harness controls, and modern CPython refuses external entities anyway.
	import defusedxml.ElementTree as ET
except ImportError:
	import xml.etree.ElementTree as ET

import tempfile
UI_XML = tempfile.gettempdir() + '/atv-ui.xml'

KEY_BACK = 4
KEY_UP = 19
KEY_DOWN = 20
KEY_LEFT = 21
KEY_RIGHT = 22
KEY_CENTER = 23
KEY_ENTER = 66


class Device:
	def __init__(self, serial, package='org.jellyfin.androidtv'):
		self.serial = serial
		self.package = package

	def adb(self, *args, **kw):
		return subprocess.run(['adb', '-s', self.serial, *args], capture_output=True, text=True, **kw)

	def shell(self, *args):
		return self.adb('shell', *args).stdout

	def ensure_awake(self):
		"""TVs sleep aggressively; a sleeping display returns null
		accessibility roots and swallows input, so wake before any step."""
		power = self.shell('dumpsys', 'power')
		if 'mWakefulness=Awake' not in power:
			self.shell('input', 'keyevent', '224')  # KEYCODE_WAKEUP
			time.sleep(3)

	def keep_awake_during_run(self):
		"""Best-effort: keep the screen on while attached over adb/USB power."""
		self.shell('settings', 'put', 'global', 'stay_on_while_plugged_in', '7')

	def launch(self, wait=8):
		self.ensure_awake()
		self.shell('monkey', '-p', self.package, '-c', 'android.intent.category.LEANBACK_LAUNCHER', '1')
		time.sleep(wait)

	def stop(self):
		self.shell('am', 'force-stop', self.package)

	def foreground(self):
		out = self.shell('dumpsys', 'activity', 'activities')
		m = re.search(r'ResumedActivity: ActivityRecord\{\S+ u0 (\S+)', out)
		return m.group(1) if m else None

	def in_app(self):
		return self.package + '/' in (self.foreground() or '')

	def key(self, code, delay=1.2):
		self.shell('input', 'keyevent', str(code))
		time.sleep(delay)

	def type_text(self, text, delay=1.5):
		self.shell('input', 'text', text)
		time.sleep(delay)

	def tap(self, x, y, delay=2.5):
		self.shell('input', 'tap', str(x), str(y))
		time.sleep(delay)

	# -- dump handling ------------------------------------------------------

	def dump_tree(self, retries=4):
		"""Fresh accessibility dump. Deletes the on-device and local files
		first so a failed dump can never be silently read as stale state, and
		retries the flaky 'null root node' condition."""
		import os
		for attempt in range(retries):
			self.shell('rm', '-f', '/sdcard/ui.xml')
			if os.path.exists(UI_XML):
				os.remove(UI_XML)
			out = self.shell('uiautomator', 'dump', '/sdcard/ui.xml')
			if 'ERROR' in out:
				self.ensure_awake()
				time.sleep(2 + attempt)
				continue
			self.adb('pull', '/sdcard/ui.xml', UI_XML)
			try:
				return ET.parse(UI_XML).getroot()
			except (ET.ParseError, FileNotFoundError):
				time.sleep(2 + attempt)
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

	def tap_text(self, text, dy=0, delay=2.5):
		p = self.bounds_of(text)
		if not p:
			return False
		self.tap(p[0], p[1] + dy, delay)
		return True

	def focused_texts(self):
		"""All text/content-desc inside the currently focused node's subtree."""
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

	def dpad_until(self, predicate, key=KEY_DOWN, max_steps=12, delay=1.5):
		"""Press `key` until predicate(focused_texts) is true. Checks before
		the first press. Returns True when found."""
		for _ in range(max_steps + 1):
			texts = self.focused_texts()
			if predicate(texts):
				return True
			self.key(key, delay)
		return predicate(self.focused_texts())

	def focus_row_and_pick(self, row_header, item_predicate=None, max_rows=20, max_cols=15):
		"""Walk DOWN until the screen row under `row_header` gains focus, then
		RIGHT until item_predicate matches the focused card (default: first
		card). Returns the focused card's texts, or None."""
		def on_row(_texts):
			# focused card belongs to the target row when the header is the
			# closest header above the focused node
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
		"""Header text of the row containing the focused node: the nearest
		text node fully above the focused bounds and left-aligned."""
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
		"""App crashes since last clear_logs, ignoring the device's broken
		Shizuku spam. Includes ACRA-caught crashes, which never reach the
		crash buffer."""
		blocks = []
		out = self.adb('logcat', '-d', '-b', 'crash').stdout
		for block in out.split('FATAL EXCEPTION')[1:]:
			if 'rikka' in block or 'shizuku' in block.lower():
				continue
			blocks.append('FATAL EXCEPTION' + block[:3000])
		main = self.adb('logcat', '-d').stdout
		for m in re.finditer(r'ACRA caught a (\w+) for ' + re.escape(self.package) + r'\b', main):
			blocks.append('ACRA: ' + m.group(1))
		return blocks

	def requests(self, needle):
		"""HTTP request log lines containing needle. Matches on the URL, not
		the logger tag - R8 renames the SDK's tag in release builds."""
		out = self.adb('logcat', '-d').stdout
		return [l for l in out.splitlines() if 'http' in l and needle in l and (' GET ' in l or ' POST ' in l)]

	def clear_logs(self):
		self.adb('logcat', '-c')
		self.adb('logcat', '-c', '-b', 'crash')
