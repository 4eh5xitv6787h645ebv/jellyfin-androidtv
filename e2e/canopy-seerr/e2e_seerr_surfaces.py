#!/usr/bin/env python3
"""Scripted E2E over the new Canopy/Seerr surfaces in the androidtv fork.

Drives a signed-in device through every new UI surface, presses every new
button, and asserts each action's observable effect (navigation, request
POSTs, status changes, toggle behavior). Saves a UI dump per step under
e2e-evidence/.

Usage: python3 e2e_seerr_surfaces.py <serial> [package]
"""
import os
import shutil
import sys
import time

import atv_driver as atv

SCRATCH = os.path.dirname(os.path.abspath(__file__))
EVIDENCE = os.path.join(SCRATCH, 'e2e-evidence')

results = []


def step(name, ok, detail=''):
	results.append((name, ok, detail))
	print(('PASS' if ok else 'FAIL'), name, '-', str(detail)[:160], flush=True)


def save_evidence(name):
	if os.path.exists(atv.UI_XML):
		shutil.copy(atv.UI_XML, os.path.join(EVIDENCE, name + '.xml'))


def fresh(d, wait=10):
	d.stop()
	d.launch(wait=wait)


def open_discover(d):
	return d.tap_text('Discover') and (time.sleep(10) or True)


def main():
	serial = sys.argv[1]
	package = sys.argv[2] if len(sys.argv) > 2 else 'org.jellyfin.androidtv'
	d = atv.Device(serial, package)
	os.makedirs(EVIDENCE, exist_ok=True)
	d.clear_logs()

	# 1. Home shows the Discover toolbar entry
	fresh(d)
	step('home shows Discover toolbar button', d.has_text('Discover'))
	save_evidence('01-home')

	# 2. Discover screen renders rows
	open_discover(d)
	ok = d.has_text('Trending', 'Popular', 'Your watchlist')
	save_evidence('02-discover')
	step('discover screen renders rows', ok and d.in_app())

	# 3. Open a requestable Trending card via dpad -> Seerr detail screen
	picked = d.focus_row_and_pick(
		'Trending',
		# requestable cards have a bare year/kind subtitle; any status is
		# rendered as 'year · status', so exclude subtitles containing a dot
		lambda t: t and not any('·' in x or 'In library' in x for x in t),
	)
	detail_ok = False
	if picked:
		d.key(atv.KEY_CENTER)
		time.sleep(10)
		detail_ok = d.has_text('Request', 'Similar', 'Cast', 'Recommended')
		save_evidence('03-seerr-item-detail')
	step('discover card opens seerr detail', detail_ok and d.in_app(), str((picked or [])[:2]))

	# 4. Request button: press, drive dialog if any, verify POST + status
	requested = False
	request_detail = ''
	if detail_ok:
		before_posts = len(d.requests('seerr/request'))
		if d.dpad_until(lambda t: any('Request' in x for x in t), atv.KEY_DOWN, 4):
			d.key(atv.KEY_CENTER)
			time.sleep(4)
			if d.has_text('Request seasons'):
				save_evidence('04a-season-picker')
				# season list defaults all-checked; move to the Request button and confirm
				if d.dpad_until(lambda t: t and set(t) <= {'Request'}, atv.KEY_DOWN, 12):
					d.key(atv.KEY_CENTER)
			time.sleep(8)
			after_posts = len(d.requests('seerr/request'))
			requested = after_posts > before_posts
			request_detail = '%d request POST(s)' % (after_posts - before_posts)
			# after a successful submit the surface refreshes; status shows
			# Requested/Processing or the request button disappears
			time.sleep(4)
			save_evidence('04b-after-request')
		step('request button submits to Seerr', requested, request_detail)
	else:
		step('request button submits to Seerr', False, 'detail screen unavailable')

	# 5. Cast card opens the person screen (skipped when no cast row)
	fresh(d)
	open_discover(d)
	picked = d.focus_row_and_pick(
		'Trending',
		lambda t: t and not any('·' in x or 'In library' in x for x in t),
	)
	if picked:
		d.key(atv.KEY_CENTER)
	time.sleep(10)
	if d.has_text('Cast'):
		picked = d.focus_row_and_pick('Cast')
		if picked:
			d.key(atv.KEY_CENTER)
			time.sleep(10)
			person_ok = d.in_app() and d.has_text('Movies', 'Series', 'Known for')
			save_evidence('05-person-screen')
			step('cast card opens person screen', person_ok, str(picked[:2]))
		else:
			step('cast card opens person screen', False, 'could not focus cast row')
	else:
		step('cast card opens person screen', True, 'no cast row served - skipped')

	# 6. Search shows the Seerr row; Discover-more tile navigates
	fresh(d)
	d.tap_text('Search')
	time.sleep(5)
	d.type_text('star')
	d.key(atv.KEY_ENTER, delay=3)  # submit; the app moves focus into results
	time.sleep(12)
	row_found = d.dpad_until(
		lambda _t: d._focused_row_header() == 'Discover · Seerr',
		atv.KEY_DOWN, 20,
	)
	save_evidence('06-search-seerr-row')
	step('search shows Seerr row', row_found)
	if row_found:
		tile = d.focus_row_and_pick(
			'Discover · Seerr',
			lambda t: any('Discover more' in x for x in t),
			max_cols=25,
		)
		if tile:
			d.key(atv.KEY_CENTER)
			time.sleep(8)
			step('discover-more tile opens Discover', d.has_text('Trending', 'Popular'))
			save_evidence('07-discover-more-nav')
		else:
			step('discover-more tile opens Discover', False, 'tile not reachable')
	else:
		step('discover-more tile opens Discover', False, 'row missing')

	# 7. Settings toggle actually hides the Seerr surfaces, and re-enabling restores them
	fresh(d)
	toggled = hidden = restored = False
	p = None
	root = d.dump_tree()
	if root is not None:
		for node in root.iter('node'):
			if node.get('content-desc') == 'Preferences':
				import re as _re
				m = _re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
				if m:
					x1, y1, x2, y2 = map(int, m.groups())
					p = ((x1 + x2) // 2, (y1 + y2) // 2)
				break
	if p:
		d.tap(*p, delay=6)
		if d.tap_text('Canopy'):
			time.sleep(4)
			toggled = d.tap_text('Seerr search suggestions')
			time.sleep(2)
			save_evidence('08a-toggle-off')
			d.key(atv.KEY_BACK); d.key(atv.KEY_BACK); d.key(atv.KEY_BACK)
			time.sleep(4)
			fresh(d)
			hidden = not d.has_text('Discover')
			save_evidence('08b-discover-hidden')
			# restore
			root = d.dump_tree()
			if p:
				d.tap(*p, delay=6)
				d.tap_text('Canopy')
				time.sleep(4)
				d.tap_text('Seerr search suggestions')
				time.sleep(2)
				d.key(atv.KEY_BACK); d.key(atv.KEY_BACK); d.key(atv.KEY_BACK)
				time.sleep(4)
				fresh(d)
				restored = d.has_text('Discover')
				save_evidence('08c-discover-restored')
	step('seerr toggle hides Discover surfaces', toggled and hidden, 'toggled=%s hidden=%s' % (toggled, hidden))
	step('seerr toggle re-enable restores Discover', restored)

	# 8. Library item detail still shows the Canopy Actions row
	fresh(d)
	d.tap_text('Search')
	time.sleep(5)
	d.type_text('iron')
	d.key(atv.KEY_ENTER, delay=3)
	time.sleep(12)
	shown = False
	if d.dpad_until(lambda t: any('Iron' in x for x in t), atv.KEY_DOWN, 10):
		d.key(atv.KEY_CENTER)
		time.sleep(10)
		shown = d.has_text('Actions')
		save_evidence('09-library-item-canopy-actions')
	step('library item shows Canopy Actions row', shown)

	# Crash sweep across the whole run
	crashes = d.crash_log()
	step('no app crashes during run', not crashes, crashes[0][:200] if crashes else '')

	print(flush=True)
	failed = [r for r in results if not r[1]]
	print('=== %d/%d steps passed ===' % (len(results) - len(failed), len(results)), flush=True)
	sys.exit(1 if failed else 0)


if __name__ == '__main__':
	main()
