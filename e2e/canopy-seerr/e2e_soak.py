#!/usr/bin/env python3
"""Long-running navigation soak for the Canopy/Seerr surfaces.

Where e2e_seerr_surfaces.py asserts that each surface works once, this walks
the app the way a person does — in and out of screens, back and forth, at
speed — looking for the failures that only appear after real navigation:
crashes, focus traversal blowing up on a detached view, screens that go blank
or get stuck, and memory that climbs and never comes back.

Every move is recorded, so a failure reports the exact path that produced it,
and the run is seeded so that path can be replayed.

Usage:
    python3 e2e_soak.py <serial> [package] [options]

Options:
    --minutes N     run for N minutes (default 20)
    --steps N       stop after N moves instead of a time limit
    --seed N        seed the walk (default: derived from the clock, printed)
    --keep-going    keep walking after a failure instead of stopping
    --quiet         only print failures, milestones and the summary
"""
import os
import random
import re
import shutil
import sys
import time

try:
    import atv_driver_fast as atv
except ImportError:
    import atv_driver as atv

SCRATCH = os.path.dirname(os.path.abspath(__file__))
EVIDENCE = os.path.join(SCRATCH, 'e2e-evidence', 'soak')

# Screens are identified by text that is unique to them. Order matters: the
# first match wins, so more specific screens come first.
SCREEN_MARKERS = [
    ('settings-placement', ('With the item buttons',)),
    ('settings-canopy', ('Item detail actions',)),
    ('settings', ('Change the app to your own liking',)),
    ('action-dialog', ('Protect this item', 'Hide this item', 'Request seasons')),
    ('action-chooser', ('Configure Spoiler Guard', 'Configure Hidden Content')),
    ('seerr-detail', ('Similar', 'Recommended', 'Part of')),
    ('person', ('Known for', 'Born')),
    ('discover', ('Trending', 'Popular movies', 'Your watchlist')),
    ('search', ('Discover · Seerr',)),
    ('item-detail', ('Play', 'Watched', 'Favorite')),
    ('home', ('My media', 'Continue watching', 'Recently added')),
]

SEARCH_TERMS = ['a', 'star', 'the', 'iron', 'man', 'e', 'life', 'day']


class Soak:
    def __init__(self, d, rng, quiet=False):
        self.d = d
        self.rng = rng
        self.quiet = quiet
        self.path = []
        self.failures = []
        self.steps = 0
        self.memory = []

    # -- observation ----------------------------------------------------

    def screen(self):
        texts = self.d.texts(200)
        for name, markers in SCREEN_MARKERS:
            if any(any(m in t for t in texts) for m in markers):
                return name, texts
        return ('unknown', texts)

    def note(self, move):
        self.path.append(move)
        del self.path[:-40]  # keep the recent trail only
        if not self.quiet:
            print('  %4d %s' % (self.steps, move), flush=True)

    def fail(self, kind, detail='', full=None):
        trail = ' > '.join(self.path[-12:])
        self.failures.append((kind, detail, trail))
        print('FAIL [%s] %s\n      path: %s' % (kind, str(detail)[:300], trail), flush=True)
        name = 'fail-%02d-%s' % (len(self.failures), kind)
        self.save_evidence(name)
        # Keep the whole stack and the path that produced it: the crash buffer
        # rotates, and a truncated trace cannot be diagnosed later.
        os.makedirs(EVIDENCE, exist_ok=True)
        with open(os.path.join(EVIDENCE, name + '.txt'), 'w') as handle:
            handle.write('kind: %s\nseed path: %s\n\n%s\n' % (kind, trail, full or detail))

    def save_evidence(self, name):
        os.makedirs(EVIDENCE, exist_ok=True)
        if os.path.exists(atv.UI_XML):
            shutil.copy(atv.UI_XML, os.path.join(EVIDENCE, name + '.xml'))

    # -- invariants -----------------------------------------------------

    def check(self):
        """Invariants that must hold after every move.

        Transitions are asynchronous, so a single bad observation proves
        nothing: both liveness checks re-read before reporting, and only a
        state that persists counts as a failure.
        """
        crashes = self.d.crash_log()
        if crashes:
            self.fail('crash', crashes[0][:400], full='\n\n'.join(crashes))
            self.d.clear_logs()
            self.recover()
            return False

        if not self.d.in_app():
            # Backing out of Home exits the app; that is the platform's
            # behavior, not a defect. Anything else leaving the foreground is.
            # A Back anywhere in the recent trail can be what exited the app;
            # the exit may only surface a move or two later.
            backed_out = any(m.startswith('back') for m in self.path[-3:])
            if backed_out:
                self.note('|app exited via back, relaunching|')
                self.d.launch(wait=15)
                return True
            time.sleep(1.5)
            if not self.d.in_app():
                self.fail('left-app', self.d.foreground())
                self.recover()
                return False

        if self.blank_for(seconds=4):
            self.fail('blank-screen', 'screen stayed empty for 4s')
            self.recover()
            return False
        return True

    def blank_for(self, seconds):
        """True only if the screen reports no text for the whole window."""
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self.d.texts(20):
                return False
            time.sleep(0.5)
        return not self.d.texts(20)

    def recover(self):
        self.d.stop()
        self.d.launch(wait=15)
        self.path.append('|recovered|')

    def sample_memory(self):
        out = self.d.shell('dumpsys meminfo %s' % self.d.package)
        m = re.search(r'TOTAL(?:\s+PSS)?:\s*(\d+)', out)
        if m:
            self.memory.append((self.steps, int(m.group(1))))

    # -- moves ----------------------------------------------------------

    def go(self, label, fn):
        self.steps += 1
        self.note(label)
        fn()

    def move_back(self):
        self.go('back', lambda: self.d.key(atv.KEY_BACK))

    def move_back_burst(self):
        # Rapid repeated Back is what a real remote produces when held, and is
        # the shape that has historically broken focus traversal.
        n = self.rng.randint(2, 5)
        def burst():
            for _ in range(n):
                self.d.key(atv.KEY_BACK, delay=0.12)
        self.go('back-burst(%d)' % n, burst)

    def move_dpad(self):
        key = self.rng.choice([atv.KEY_DOWN, atv.KEY_UP, atv.KEY_LEFT, atv.KEY_RIGHT])
        n = self.rng.randint(1, 6)
        names = {atv.KEY_DOWN: 'down', atv.KEY_UP: 'up', atv.KEY_LEFT: 'left', atv.KEY_RIGHT: 'right'}
        def walk():
            for _ in range(n):
                self.d.key(key, delay=0.12)
        self.go('%s x%d' % (names[key], n), walk)

    def move_select(self):
        self.go('select', lambda: self.d.key(atv.KEY_CENTER, delay=0.8))

    def move_long_press(self):
        self.go('long-press', lambda: self.d.long_press())

    def move_enter_content(self):
        """Descend from a row screen into an item, the way a viewer does."""
        def enter():
            for _ in range(self.rng.randint(1, 3)):
                self.d.key(atv.KEY_DOWN, delay=0.12)
            for _ in range(self.rng.randint(0, 4)):
                self.d.key(atv.KEY_RIGHT, delay=0.12)
            self.d.key(atv.KEY_CENTER, delay=1.2)
        self.go('enter-content', enter)

    def move_toolbar(self, label):
        self.go('toolbar:%s' % label, lambda: self.d.tap_text(label))

    def move_search(self):
        term = self.rng.choice(SEARCH_TERMS)
        def search():
            if self.d.tap_text('Search'):
                self.d.type_text(term)
                self.d.key(atv.KEY_ENTER, delay=0.4)
        self.go('search:%s' % term, search)

    def move_settings(self):
        def settings():
            root = self.d.dump_tree()
            if root is None:
                return
            for node in root.iter('node'):
                if node.get('content-desc') == 'Preferences':
                    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
                    if m:
                        x1, y1, x2, y2 = map(int, m.groups())
                        self.d.tap((x1 + x2) // 2, (y1 + y2) // 2, delay=1.0)
                    return
        self.go('settings', settings)

    # -- the walk -------------------------------------------------------

    def next_move(self, screen):
        """Pick a plausible next move for the screen we are on.

        Weighted to keep descending into content: an unweighted walk spends
        almost all its time on Home backing in and out, which exercises none
        of the screens this suite exists to stress.
        """
        if screen == 'home':
            return self.rng.choice([
                self.move_enter_content, self.move_enter_content, self.move_enter_content,
                lambda: self.move_toolbar('Discover'),
                lambda: self.move_toolbar('Discover'),
                self.move_search, self.move_search,
                self.move_dpad,
                self.move_settings,
            ])
        if screen in ('discover', 'search'):
            return self.rng.choice([
                self.move_enter_content, self.move_enter_content, self.move_enter_content,
                self.move_dpad, self.move_dpad,
                self.move_long_press,
                self.move_back,
                self.move_back_burst,
                lambda: self.move_toolbar('Home'),
            ])
        if screen in ('seerr-detail', 'item-detail', 'person'):
            return self.rng.choice([
                self.move_dpad, self.move_dpad, self.move_dpad,
                self.move_enter_content, self.move_enter_content,
                self.move_select,
                self.move_back,
                self.move_back_burst,
            ])
        if screen in ('action-dialog', 'action-chooser'):
            # Never submit: a soak must not spam real requests or mutate state
            # in ways that make later runs behave differently.
            return self.move_back
        if screen.startswith('settings'):
            return self.rng.choice([self.move_dpad, self.move_dpad, self.move_back])
        return self.rng.choice([self.move_back, self.move_dpad])

    def run(self, deadline, max_steps, keep_going):
        self.d.clear_logs()
        self.d.stop()
        self.d.launch(wait=15)
        self.sample_memory()
        visited = {}

        while time.monotonic() < deadline and (max_steps is None or self.steps < max_steps):
            screen, _ = self.screen()
            visited[screen] = visited.get(screen, 0) + 1
            move = self.next_move(screen)
            try:
                move()
            except Exception as error:
                self.fail('driver-error', repr(error))
                self.recover()
                if not keep_going:
                    break
                continue

            if not self.check() and not keep_going:
                break

            if self.steps % 25 == 0:
                self.sample_memory()
                elapsed = time.monotonic() - started
                print('  ... %d moves, %d failures, %.0fs elapsed' % (
                    self.steps, len(self.failures), elapsed), flush=True)

        return visited


started = 0.0


def main():
    global started
    args = list(sys.argv[1:])

    def opt(name, cast, default):
        if name in args:
            i = args.index(name)
            value = cast(args[i + 1])
            del args[i:i + 2]
            return value
        return default

    minutes = opt('--minutes', float, 20.0)
    max_steps = opt('--steps', int, None)
    seed = opt('--seed', int, int(time.time()))
    keep_going = '--keep-going' in args
    quiet = '--quiet' in args
    args = [a for a in args if not a.startswith('-')]

    if not args:
        print(__doc__)
        return 2
    serial = args[0]
    package = args[1] if len(args) > 1 else 'org.jellyfin.androidtv'

    print('soak: serial=%s package=%s seed=%d %s' % (
        serial, package, seed,
        ('steps=%d' % max_steps) if max_steps else ('minutes=%g' % minutes)), flush=True)

    d = atv.Device(serial, package)
    soak = Soak(d, random.Random(seed), quiet=quiet)

    started = time.monotonic()
    deadline = started + minutes * 60
    visited = soak.run(deadline, max_steps, keep_going)
    elapsed = time.monotonic() - started

    print(flush=True)
    print('=== soak finished: %d moves in %.0fs, %d failures (seed %d) ===' % (
        soak.steps, elapsed, len(soak.failures), seed), flush=True)
    print('screens visited: %s' % ', '.join(
        '%s=%d' % (k, v) for k, v in sorted(visited.items(), key=lambda kv: -kv[1])), flush=True)
    if soak.memory:
        first, last = soak.memory[0][1], soak.memory[-1][1]
        peak = max(v for _, v in soak.memory)
        print('memory PSS: start %d kB, end %d kB, peak %d kB (%+.0f%%)' % (
            first, last, peak, (last - first) * 100.0 / max(first, 1)), flush=True)
    for kind, detail, trail in soak.failures:
        print('  %-12s %s | path: %s' % (kind, str(detail)[:160], trail), flush=True)

    return 1 if soak.failures else 0


if __name__ == '__main__':
    sys.exit(main())
