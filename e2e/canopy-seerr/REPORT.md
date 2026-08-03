# Fast Android TV adb driver — results

Goal: replace the slow primitives in `atv_driver.py` (one-shot `uiautomator
dump` + pull, one `adb shell input` process per event) with something at least
5x faster for UI reads and 3x faster for input, as a drop-in for
`e2e_seerr_surfaces.py`.

All numbers measured on **emulator-5560** (Android 14 x86 TV image,
`org.jellyfin.androidtv.debug`). The Shield ran a foreign E2E for the entire
session (`pgrep -f e2e_seerr_surfaces` was never empty), so per the ground
rules it was **not touched**; see "Shield portability" below.

## Final numbers

| Metric (20 reps each)              | baseline `atv_driver` | `atv_driver_fast` | speedup |
|------------------------------------|----------------------:|------------------:|--------:|
| UI read (dump + text extraction), median | 2.233 s        | **0.150 s**       | **14.9x** |
| UI read, max / fails               | 2.316 s / 0           | 0.189 s / 0       | — |
| key event (raw primitive, delay=0), median | 0.086 s      | **0.0001 s**      | ~800x |
| key event incl. read-verified focus move   | ~1.3 s (0.086 + 1.2 s mandated pacing) | **0.61 s** (0.25 s pacing + verify dump) | — |
| full flow: open search → type "alpha" → results confirmed | 29.2 s | **3.8 s** | **7.6x** |

Reliability gates, all passed on the emulator:
- 20 consecutive UI reads: 0 failures (baseline's "null root node" flake never
  appears — the persistent accessibility snapshot doesn't have that failure mode).
- 20 consecutive key presses, each **verified by a fresh dump showing the
  focused node actually moved**: 20/20.
- Fast search flow repeated 5x back-to-back: 3.72–3.99 s, found the expected
  result every time.
- e2e drop-in smoke (home → Discover → `focus_row_and_pick('Trending')` →
  `_focused_row_header()`): all primitives behave identically to the old
  driver, evidence XML written to the same `UI_XML` path.

## What the fast driver uses

`atv_driver_fast.py` (same conceptual API: `dump_tree/texts/focused_texts/
key/tap/type_text/launch/foreground/...`, same module constants):

1. **UI reads — uiautomator2 v3** (`pip install uiautomator2`, no host daemon).
   Keeps one instrumentation/accessibility server alive on the device and
   serves hierarchy snapshots over a forwarded socket: **150 ms** vs 2.3 s.
   The XML schema is identical to `uiautomator dump`, so every tree-walking
   helper carried over unchanged.
2. **Input — scrcpy 4.1 control socket.** The stock scrcpy server jar
   (`/usr/share/scrcpy/scrcpy-server`) is pushed and started control-only
   (`video=false audio=false`), then key/text/tap are injected as 14–32 byte
   messages on the socket (`InputManager.injectInputEvent`, async). A key
   press costs a socket write (~0.1 ms); injection itself is a few ms
   on-device. Verified functionally, not just by timing (focus-move checks).
3. **Everything else — one persistent `adb shell`** with sentinel framing
   (foreground/app lifecycle/dumpsys), ~20 ms per command instead of a new
   adb client process each time.
4. Cheap reads enable **polling instead of fixed sleeps**: `launch()` polls
   until the UI has content; new helper `wait_text(*needles, timeout)`
   replaces `sleep(12); has_text(...)` patterns. This is where most of the
   flow-level 7.6x comes from.

Setup/teardown: both helpers start lazily (one-time ~1.9 s warmup on first
read+key) and are torn down atexit (server proc killed, forward removed).

## Drop-in usage

```python
# e2e_seerr_surfaces.py — the only required change:
import atv_driver_fast as atv
```

Works under plain `python3` too: the module auto-appends
`speedvenv/lib/python3.14/site-packages` if `uiautomator2` isn't importable.
Default pacing delays were reduced (key 1.2→0.35 s, tap 2.5→1.0 s,
type 1.5→0.5 s); the e2e's own explicit `time.sleep()` calls are untouched,
so behavior stays conservative. For bigger wins, replace fixed sleeps with
`d.wait_text(...)` opportunistically.

## Running log — what was tried, timing, verdict

1. **Baseline** (`bench_baseline.py`): dump 2.233 s median, `input keyevent`
   0.086 s, adb round-trip 0.021 s. Note: on the emulator adb is local, so
   the baseline is already far faster than the Shield-over-WiFi numbers in
   the task brief; speedups here are measured against this stricter baseline.
2. **`adb exec-out uiautomator dump /dev/tty`**: 2.273 s median — no gain.
   The cost is the one-shot uiautomator process bootstrapping an
   accessibility connection, not the file pull. Dead end.
3. **uiautomator2**: dump 0.149 s (15x — winner for reads). But `d.press()`
   is 0.139 s — *slower* than baseline `input keyevent` (its injection is
   synchronous). Verdict: use for reads only, not input.
4. **Persistent `adb shell` pipe** (`proto_evdev.py`): round-trip ~1 ms.
   `input keyevent` through it: 53 ms (still spawns the on-device Java
   `input`/`cmd` process). `cmd input keyevent`: 43 ms median with outliers
   to 118 ms — real but misses the 3x bar (needs ≤29 ms). Kept as the
   general shell-command channel, rejected for input.
5. **Raw evdev injection** (`sendevent` / batched `printf` into
   `/dev/input/event2`): 0.7 ms and looked perfect — but writes are denied
   by SELinux (`u:r:shell:s0` → `input_device`); the earlier "success" was
   an unread error in the pipe. Only works after `adb root`, which the
   Shield doesn't have. Dead end (portability).
6. **scrcpy 4.1 control socket** (`proto_scrcpy.py`): protocol verified
   against the v4.1 server source (big-endian; keycode msg = type u8, action
   u8, keycode i32, repeat i32, meta i32). Key inject ~0.01 ms socket write;
   balanced 20-key rapid burst left focus exactly where it started (nothing
   dropped); text injection typed into the real search box. Winner for
   key/text/tap.
7. **Assembled `atv_driver_fast.py`** and fixed two integration issues found
   by benchmarking: launch settle raced UI composition (now polls for
   content), and flow benches previously backed out to the TV launcher,
   leaving foreign apps (Plethorafin) foregrounded for the next run (flows
   now end with `stop()`).

## Shield portability (verification still pending)

Not run — a foreign E2E occupied the Shield all session. The design avoids
everything Shield-hostile: no root (evdev was rejected for exactly this),
uiautomator2 and scrcpy both run on production devices via plain adb, and the
network-adb latency the Shield adds (~0.3–1 s per adb process spawn) is
precisely what the persistent-socket design eliminates, so relative gains
there should be **larger** than on the emulator. Suggested one-liner once the
Shield is free:
`./speedvenv/bin/python bench_fast.py 192.168.0.133:5555 org.jellyfin.androidtv fast`
(after re-checking `pgrep -f e2e_seerr_surfaces` and
`adb -s 192.168.0.133:5555 shell pidof org.jellyfin.androidtv`).

## Files

- `atv_driver_fast.py` — the drop-in fast driver
- `bench_baseline.py` / `bench_baseline.log` — baseline primitive timings
- `bench_fast.py` — before/after benchmark (primitives + search flow; args:
  `[serial] [package] [baseline|fast|flow-baseline|flow-fast|all]`)
- `proto_evdev.py`, `proto_scrcpy.py` — measurement prototypes for the
  rejected/accepted input paths
- `speedvenv/` — venv with `uiautomator2`
