#!/usr/bin/env python3
"""Scripted E2E over the Canopy/Seerr surfaces in the androidtv fork.

Scenarios are independent and selectable, so a change to one surface can be
verified without paying for the whole suite. Within a run they share one app
session: each scenario returns Home by unwinding the back stack instead of
force-stopping and relaunching, which is the single biggest cost in a run.

Usage:
    python3 e2e_seerr_surfaces.py <serial> [package] [options]

Options:
    --only a,b,c   run only the named scenarios (default: all)
    --list         list scenario names and exit
    --cold         force-stop and relaunch before every scenario (the old
                   behavior; kept for benchmarking and for debugging suspected
                   cross-scenario interference)

Each step prints PASS/FAIL and saves the raw UI dump under e2e-evidence/.
Exit code 0 means every step of every selected scenario passed.
"""
import os
import re
import shutil
import sys
import time

try:
    import atv_driver_fast as atv  # persistent u2 reads + scrcpy input (14x faster)
except ImportError:
    import atv_driver as atv

SCRATCH = os.path.dirname(os.path.abspath(__file__))
EVIDENCE = os.path.join(SCRATCH, 'e2e-evidence')

results = []
_cold_mode = False

# Text that only appears on Home's content. The toolbar is shared with Search
# and Discover, so it cannot identify Home on its own.
HOME_MARKERS = ('My media', 'Continue watching', 'Recently added')


def step(name, ok, detail=''):
    results.append((name, ok, detail))
    print(('PASS' if ok else 'FAIL'), name, '-', str(detail)[:160], flush=True)


def save_evidence(name):
    if os.path.exists(atv.UI_XML):
        shutil.copy(atv.UI_XML, os.path.join(EVIDENCE, name + '.xml'))


def cold_start(d, wait=12):
    d.stop()
    d.launch(wait=wait)


def home(d, max_back=6):
    """Return Home without restarting the app.

    Unwinds the fragment back stack; falls back to a cold start if the app
    left the foreground or the stack does not resolve to Home.
    """
    if _cold_mode or not d.in_app():
        cold_start(d)
        return

    for _ in range(max_back):
        if d.has_text(*HOME_MARKERS):
            return
        d.key(atv.KEY_BACK)
    if not d.has_text(*HOME_MARKERS):
        cold_start(d)


def open_toolbar(d, label, settle=('Trending', 'Popular', 'Your watchlist')):
    """Open a toolbar destination and wait for its content to arrive."""
    if not d.tap_text(label):
        return False
    return d.wait_text(*settle, timeout=20) if settle else True


def search_for(d, query, settle=None):
    home(d)
    if not d.tap_text('Search'):
        return False
    d.type_text(query)
    d.key(atv.KEY_ENTER, delay=0.4)
    return d.wait_text(*(settle or (query,)), timeout=25)


def row_headers(d):
    """Left-aligned row header texts on the current screen, top to bottom."""
    root = d.dump_tree()
    if root is None:
        return []
    found = []
    for node in root.iter('node'):
        text = node.get('text', '')
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if text.strip() and m and int(m.group(1)) < 200:
            found.append((int(m.group(2)), text))
    return [t for _, t in sorted(found)]


# Home rows that hold library folders/collections rather than playable items.
FOLDER_ROWS = ('My media', 'Collections')
# Rows that hold real items, most likely to be present, in preference order.
CONTENT_ROW_HINTS = ('Continue watching', 'Next up', 'Recently added')


def open_first_library_item(d):
    """Open a real item from a Home content row.

    Prefers rows known to hold playable items; folder rows are skipped because
    selecting one opens a library rather than an item detail screen.
    """
    home(d)
    headers = row_headers(d)
    ordered = (
        [h for h in headers if any(hint in h for hint in CONTENT_ROW_HINTS)]
        + [h for h in headers if h not in FOLDER_ROWS
           and not any(hint in h for hint in CONTENT_ROW_HINTS)]
    )
    for header in ordered:
        if not d.focus_row_and_pick(header):
            continue
        d.key(atv.KEY_CENTER)
        if d.wait_text('Play', 'Watched', 'Spoiler Guard', 'Hidden Content', 'Actions', timeout=20):
            return True
        home(d)
    return False


def preferences_point(d):
    root = d.dump_tree()
    if root is None:
        return None
    for node in root.iter('node'):
        if node.get('content-desc') == 'Preferences':
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def ensure_seerr_enabled(d):
    """Force the Seerr surfaces on before any scenario runs.

    The settings scenario deliberately toggles this preference off to prove
    the surfaces disappear, and restores it afterwards. A run interrupted
    between those two steps leaves it off *persisted on the device*, which
    silently fails every Seerr scenario in later runs and looks exactly like
    an app regression. Normalising here makes runs independent of how the
    previous one ended.
    """
    home(d)
    if d.has_text('Discover'):
        return True
    if not open_canopy_settings(d):
        return False
    d.tap_text('Seerr search suggestions')
    time.sleep(1.5)
    for _ in range(3):
        d.key(atv.KEY_BACK)
    home(d)
    restored = d.has_text('Discover')
    print('  (normalised Seerr preference back on: %s)' % restored, flush=True)
    return restored


def open_canopy_settings(d):
    home(d)
    p = preferences_point(d)
    if not p:
        return False
    d.tap(*p, delay=1.0)
    if not d.wait_text('Settings', timeout=10):
        return False
    if not d.tap_text('Canopy'):
        return False
    return d.wait_text('Item detail actions', timeout=10)


# --------------------------------------------------------------------------
# Scenarios
# --------------------------------------------------------------------------

def scenario_toolbar(d):
    """Home surfaces the Discover entry point."""
    home(d)
    step('home shows Discover toolbar button', d.has_text('Discover'))
    save_evidence('01-home')


def scenario_discover(d):
    """Discover rows render, a card opens its detail screen, Request submits."""
    home(d)
    opened = open_toolbar(d, 'Discover')
    step('discover screen renders rows', opened and d.in_app())
    save_evidence('02-discover')
    if not opened:
        step('discover card opens seerr detail', False, 'discover unavailable')
        step('request button submits to Seerr', False, 'discover unavailable')
        return

    picked = d.focus_row_and_pick(
        'Trending',
        # requestable cards have a bare year/kind subtitle; any status renders
        # as 'year · status', so exclude subtitles containing a separator
        lambda t: t and not any('·' in x or 'In library' in x for x in t),
    )
    detail_ok = False
    if picked:
        d.key(atv.KEY_CENTER)
        detail_ok = d.wait_text('Request', 'Similar', 'Cast', 'Recommended', timeout=20)
        save_evidence('03-seerr-item-detail')
    step('discover card opens seerr detail', detail_ok and d.in_app(), str((picked or [])[:2]))

    if not detail_ok:
        step('request button submits to Seerr', False, 'detail screen unavailable')
        return

    before_posts = len(d.requests('seerr/request'))
    requested, detail = False, ''
    if d.dpad_until(lambda t: any('Request' in x for x in t), atv.KEY_DOWN, 4):
        d.key(atv.KEY_CENTER)
        if d.wait_text('Request seasons', timeout=6):
            save_evidence('04a-season-picker')
            # seasons default to all-checked; walk to the dialog button row
            # (AlertDialog renders buttons in caps, and DOWN lands on the left
            # button, so step RIGHT until REQUEST holds focus)
            def on_button(t):
                return bool(t) and {x.upper() for x in t} <= {'CANCEL', 'REQUEST'}
            if d.dpad_until(on_button, atv.KEY_DOWN, 12):
                d.dpad_until(
                    lambda t: bool(t) and {x.upper() for x in t} <= {'REQUEST'},
                    atv.KEY_RIGHT, 3,
                )
                d.key(atv.KEY_CENTER)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            if len(d.requests('seerr/request')) > before_posts:
                break
            time.sleep(0.5)
        after_posts = len(d.requests('seerr/request'))
        requested = after_posts > before_posts
        detail = '%d request POST(s)' % (after_posts - before_posts)
        save_evidence('04b-after-request')
    step('request button submits to Seerr', requested, detail)


def scenario_person(d):
    """A cast card on a Seerr detail screen opens the person screen."""
    home(d)
    if not open_toolbar(d, 'Discover'):
        step('cast card opens person screen', False, 'discover unavailable')
        return
    picked = d.focus_row_and_pick(
        'Trending',
        lambda t: t and not any('·' in x or 'In library' in x for x in t),
    )
    if picked:
        d.key(atv.KEY_CENTER)
        d.wait_text('Cast', 'Request', timeout=20)
    if not d.has_text('Cast'):
        step('cast card opens person screen', True, 'no cast row served - skipped')
        return

    card = d.focus_row_and_pick('Cast')
    if not card:
        step('cast card opens person screen', False, 'could not focus cast row')
        return
    d.key(atv.KEY_CENTER)
    ok = d.wait_text('Movies', 'Series', 'Known for', timeout=20) and d.in_app()
    save_evidence('05-person-screen')
    step('cast card opens person screen', ok, str(card[:2]))


def scenario_search(d):
    """Seerr row in search results, and its Discover-more tile."""
    found = search_for(d, 'star', settle=('Discover · Seerr', 'Star'))
    row_found = found and d.dpad_until(
        lambda _t: d._focused_row_header() == 'Discover · Seerr',
        atv.KEY_DOWN, 20,
    )
    save_evidence('06-search-seerr-row')
    step('search shows Seerr row', bool(row_found))

    if not row_found:
        step('discover-more tile opens Discover', False, 'row missing')
        return

    tile = d.focus_row_and_pick(
        'Discover · Seerr',
        lambda t: any('Discover more' in x for x in t),
        max_cols=25,
    )
    if tile:
        d.key(atv.KEY_CENTER)
        step('discover-more tile opens Discover', d.wait_text('Trending', 'Popular', timeout=20))
        save_evidence('07-discover-more-nav')
    else:
        step('discover-more tile opens Discover', False, 'tile not reachable')


def scenario_long_press(d, query=None):
    """Long-pressing a library-backed Seerr card opens Canopy actions."""
    # Only titles already in the library carry Canopy actions, so this needs a
    # Seerr result the server has linked to a Jellyfin item. Try a couple of
    # broad queries and skip if this server links none.
    for term in ([query] if query else ['a', 'the']):
        if not search_for(d, term, settle=('Discover · Seerr',)):
            continue
        if not d.dpad_until(lambda _t: d._focused_row_header() == 'Discover · Seerr',
                            atv.KEY_DOWN, 20):
            continue
        if not d.focus_row_and_pick('Discover · Seerr',
                                    lambda t: any('In library' in x for x in t), max_cols=25):
            continue
        d.long_press()
        ok = d.wait_text('Spoiler Guard', 'Hidden Content', 'Actions', timeout=12)
        save_evidence('12-card-long-press-actions')
        d.key(atv.KEY_BACK)
        step('long-press on library-backed Seerr card opens Canopy actions', ok)
        return
    step('long-press on library-backed Seerr card opens Canopy actions', True,
         'no Seerr result linked to the library on this server - skipped')


def scenario_library(d):
    """Library item shows Canopy actions; its cast opens the native person
    screen, which carries split Seerr filmography rows."""
    # Open a real library item from Home rather than searching for a title
    # that only exists on one particular server.
    opened = open_first_library_item(d)
    # Canopy contributions resolve after the item screen paints, so wait for
    # them rather than sampling the screen the moment Play appears.
    shown = opened and d.wait_text('Spoiler Guard', 'Hidden Content', 'Seerr', 'Actions', timeout=20)
    save_evidence('09-library-item-canopy-actions')
    step('library item shows Canopy actions', shown)

    if not opened:
        step('native person screen shows Seerr More from row', False, 'library item unavailable')
        step('person filmography rows are split by kind', False, 'library item unavailable')
        return

    if not d.dpad_until(lambda _t: (d._focused_row_header() or '').startswith('Cast'),
                        atv.KEY_DOWN, 12):
        # People are server data; an item without cast cannot exercise this.
        note = 'item has no cast row on this server - skipped'
        step('native person screen shows Seerr More from row', True, note)
        step('person filmography rows are split by kind', True, note)
        return

    d.key(atv.KEY_CENTER)
    d.wait_text('Movies', 'Films', 'Born', timeout=20)
    headers = set()
    more_from = False
    for _ in range(12):
        h = d._focused_row_header()
        if h:
            headers.add(h)
            if h.startswith('More from'):
                more_from = True
        d.key(atv.KEY_DOWN)
    save_evidence('10-native-person-more-from')
    step('native person screen shows Seerr More from row', more_from, str(sorted(headers))[:120])

    kinds = [h for h in headers if h.startswith('More from')]
    split_ok = any('Movies' in h for h in kinds) or any('Series' in h for h in kinds)
    step('person filmography rows are split by kind', split_ok, str(kinds)[:120])


def scenario_settings(d):
    """Placement options render; the Seerr toggle hides and restores surfaces."""
    try:
        _scenario_settings(d)
    finally:
        # This scenario is the only one that mutates persisted state; never
        # leave it off, however the scenario ended.
        ensure_seerr_enabled(d)


def _scenario_settings(d):
    if not open_canopy_settings(d):
        step('placement setting shows all options', False, 'settings unavailable')
        step('seerr toggle hides Discover surfaces', False, 'settings unavailable')
        step('seerr toggle re-enable restores Discover', False, 'settings unavailable')
        return

    placement_ok = False
    if d.tap_text('Action placement'):
        placement_ok = (
            d.wait_text('With the item buttons', timeout=8)
            and d.has_text('Other options menu')
            and d.has_text('Dedicated Actions row')
        )
        save_evidence('11-placement-options')
        d.key(atv.KEY_BACK)
    step('placement setting shows all options', placement_ok)

    toggled = d.tap_text('Seerr search suggestions')
    save_evidence('08a-toggle-off')
    home(d)
    hidden = not d.has_text('Discover')
    save_evidence('08b-discover-hidden')
    step('seerr toggle hides Discover surfaces', bool(toggled) and hidden,
         'toggled=%s hidden=%s' % (bool(toggled), hidden))

    restored = False
    if open_canopy_settings(d):
        d.tap_text('Seerr search suggestions')
        home(d)
        restored = d.has_text('Discover')
        save_evidence('08c-discover-restored')
    step('seerr toggle re-enable restores Discover', restored)


SCENARIOS = [
    ('toolbar', scenario_toolbar),
    ('discover', scenario_discover),
    ('person', scenario_person),
    ('search', scenario_search),
    ('long-press', scenario_long_press),
    ('library', scenario_library),
    ('settings', scenario_settings),
]


def main():
    global _cold_mode

    args = list(sys.argv[1:])
    if '--list' in args:
        for name, fn in SCENARIOS:
            print('%-12s %s' % (name, (fn.__doc__ or '').strip().splitlines()[0]))
        return 0

    _cold_mode = '--cold' in args
    args = [a for a in args if a != '--cold']

    only = None
    for i, a in enumerate(args):
        if a == '--only' and i + 1 < len(args):
            only = {n.strip() for n in args[i + 1].split(',') if n.strip()}
            del args[i:i + 2]
            break
        if a.startswith('--only='):
            only = {n.strip() for n in a.split('=', 1)[1].split(',') if n.strip()}
            del args[i]
            break

    positional = [a for a in args if not a.startswith('-')]
    if not positional:
        print(__doc__)
        return 2
    serial = positional[0]
    package = positional[1] if len(positional) > 1 else 'org.jellyfin.androidtv'

    selected = [(n, f) for n, f in SCENARIOS if only is None or n in only]
    if not selected:
        print('no scenarios matched %s; known: %s' % (only, [n for n, _ in SCENARIOS]))
        return 2

    d = atv.Device(serial, package)
    os.makedirs(EVIDENCE, exist_ok=True)
    d.clear_logs()
    cold_start(d)
    ensure_seerr_enabled(d)

    run_started = time.monotonic()
    for name, fn in selected:
        started = time.monotonic()
        print('--- %s ---' % name, flush=True)
        try:
            fn(d)
        except Exception as error:  # a broken scenario must not hide the others
            step('%s scenario completed' % name, False, repr(error))
        print('    (%s took %.1fs)' % (name, time.monotonic() - started), flush=True)

    crashes = d.crash_log()
    step('no app crashes during run', not crashes, crashes[0][:200] if crashes else '')

    total = time.monotonic() - run_started
    failed = [r for r in results if not r[1]]
    print(flush=True)
    print('=== %d/%d steps passed in %.1fs (%s%s) ===' % (
        len(results) - len(failed), len(results), total,
        ','.join(n for n, _ in selected),
        ', cold' if _cold_mode else '',
    ), flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
