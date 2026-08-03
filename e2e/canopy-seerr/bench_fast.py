#!/usr/bin/env python3
"""Benchmark atv_driver (baseline) vs atv_driver_fast on the same device.

Measures, per driver:
  1. 20 UI reads (dump + text extraction)
  2. 20 key events (raw primitive cost, delay=0)
  3. one full "open search, type, read results" flow, driven the way each
     driver is meant to be driven (baseline: fixed sleeps as in
     e2e_seerr_surfaces.py; fast: cheap polling)

Usage: bench_fast.py [serial] [package] [baseline|fast|flow-baseline|flow-fast|all]
"""
import statistics
import sys
import time

sys.path.insert(0, '/tmp/claude-1000/-home-jake/2f15395f-220f-41fe-95ee-50912ee56074/scratchpad')

SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5560'
PKG = sys.argv[2] if len(sys.argv) > 2 else 'org.jellyfin.androidtv.debug'
WHAT = sys.argv[3] if len(sys.argv) > 3 else 'all'
N = 20
QUERY, EXPECT = 'alpha', 'Alpha Adventure'


def bench(name, fn, n):
    times, fails = [], 0
    for _ in range(n):
        t0 = time.monotonic()
        ok = fn()
        times.append(time.monotonic() - t0)
        if not ok:
            fails += 1
    print('%s: n=%d median=%.4fs mean=%.4fs min=%.4fs max=%.4fs fails=%d' % (
        name, n, statistics.median(times), statistics.fmean(times),
        min(times), max(times), fails), flush=True)
    return times, fails


def primitives(mod, tag):
    d = mod.Device(SERIAL, PKG)
    d.ensure_awake()
    if not d.in_app():
        d.launch(wait=10)
    # warm up lazy helpers (u2 session, scrcpy server) so the timed loops
    # measure steady-state cost; the one-time setup is reported separately
    t0 = time.monotonic()
    d.texts()
    d.key(mod.KEY_RIGHT, delay=0.3)
    d.key(mod.KEY_LEFT, delay=0.3)
    print('%s warmup (first read+keys): %.2fs' % (tag, time.monotonic() - t0), flush=True)
    bench(tag + ' ui-read', lambda: bool(d.texts()), N)
    it = iter([mod.KEY_RIGHT, mod.KEY_LEFT] * (N // 2))

    def press():
        d.key(next(it), delay=0)
        return True
    bench(tag + ' key', press, N)
    return d


def flow_baseline():
    import atv_driver as atv
    d = atv.Device(SERIAL, PKG)
    d.stop()
    d.launch(wait=10)
    for _ in range(5):                 # home fully rendered before timing
        if d.has_text('Search'):
            break
    t0 = time.monotonic()
    ok = d.tap_text('Search')          # dump + tap, sleep 2.5
    time.sleep(5)                      # e2e pacing after opening search
    d.type_text(QUERY)                 # input text, sleep 1.5
    d.key(atv.KEY_ENTER, delay=3)      # submit, e2e pacing
    time.sleep(12)                     # e2e pacing for results
    found = d.has_text(EXPECT)
    dt = time.monotonic() - t0
    print('baseline flow: %.2fs  search-opened=%s  result-found=%s' % (dt, ok, found), flush=True)
    d.stop()
    return dt, found


def flow_fast():
    import atv_driver_fast as atv
    d = atv.Device(SERIAL, PKG)
    d.stop()
    d.launch(wait=10)
    d.wait_text('Search', timeout=10)  # home fully rendered before timing
    t0 = time.monotonic()
    ok = d.tap_text('Search')
    d.wait_text('?123', timeout=8)   # search screen keyboard visible
    d.type_text(QUERY)
    d.key(atv.KEY_ENTER, delay=0.3)
    found = d.wait_text(EXPECT, timeout=15)
    dt = time.monotonic() - t0
    print('fast flow: %.2fs  search-opened=%s  result-found=%s' % (dt, ok, found), flush=True)
    d.stop()
    return dt, found


def main():
    if WHAT in ('baseline', 'all'):
        import atv_driver as atv
        primitives(atv, 'baseline')
    if WHAT in ('fast', 'all'):
        import atv_driver_fast as fast
        primitives(fast, 'fast')
    if WHAT in ('flow-baseline', 'all'):
        flow_baseline()
    if WHAT in ('flow-fast', 'all'):
        flow_fast()


if __name__ == '__main__':
    main()
