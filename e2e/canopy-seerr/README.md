# Canopy / Seerr native surface E2E

Scripted end-to-end pass over every user-facing surface added by the Canopy
integration: the Discover screen, the Seerr search row, the Seerr item detail
screen (including the Request flow and season picker), the person screen, the
genre grid entry points, the Canopy settings toggles, and the Canopy item-detail
Actions row on library items.

## Requirements

- A device or emulator with the app installed and **signed in** to a Jellyfin
  server running the Jellyfin Canopy plugin with Seerr configured and the
  signed-in user linked in Seerr.
- `adb` on PATH and the device connected (`adb devices`).
- Python 3.9+.

## Running

```
python3 e2e_seerr_surfaces.py <serial> [package] [options]
# emulator, debug build:
python3 e2e_seerr_surfaces.py emulator-5554 org.jellyfin.androidtv.debug
# TV device, release build:
python3 e2e_seerr_surfaces.py 192.168.0.42:5555 org.jellyfin.androidtv
```

Options:

| Option | Effect |
|---|---|
| `--only a,b` | run just those scenarios — verify one change without paying for the suite |
| `--list` | list scenario names |
| `--cold` | force-stop and relaunch before every scenario (old behavior; for benchmarking or isolating cross-scenario interference) |

Scenarios: `toolbar`, `discover`, `person`, `search`, `long-press`, `library`,
`settings`. Each step prints PASS/FAIL and saves the raw UI dump under
`e2e-evidence/`; exit code 0 means everything selected passed.

Scenarios share one app session, returning Home by unwinding the back stack
instead of restarting. Measured on the emulator, whole suite:

| Mode | Time |
|---|---:|
| `--cold` (restart per scenario) | 150.2 s |
| shared session (default) | 115.9 s |
| `--only settings` | 15.5 s |

Steps whose preconditions the connected server cannot satisfy (an item with no
cast, no Seerr result linked to the library) report as passed with a
`skipped` note rather than failing — check the note before treating such a run
as full coverage.

**Note:** the Request step submits a real request to the connected Seerr
instance. Point the server at a development Seerr instance.

## Fast driver (`atv_driver_fast.py`) — recommended

`e2e_seerr_surfaces.py` imports `atv_driver_fast` when available and silently
falls back to `atv_driver`. The fast driver is a drop-in replacement with the
same API, measured on a Shield TV over WiFi adb:

| Operation | `atv_driver` | `atv_driver_fast` | speedup |
|---|---:|---:|---:|
| UI read (dump + parse) | 2.23 s | 0.13 s | ~17x |
| Key event | 86 ms | 0.1 ms | ~800x |
| Full 15-step suite | ~35 min | ~6 min | ~6x |

How it gets there:

- **Reads** use uiautomator2's persistent on-device accessibility server
  instead of spawning `uiautomator dump` per read. This also eliminates the
  `null root node` flake entirely.
- **Input** goes through scrcpy's control socket (stock server jar, started
  control-only) as small binary messages, instead of one `adb shell input`
  process per keypress. No root required.
- **Shell** commands reuse a single persistent `adb shell` with sentinel
  framing.
- Cheap reads make polling affordable, so fixed `sleep()`s were replaced with
  `wait_text()`-style waits — which is where most of the wall-clock win comes
  from.

Setup (one time):

```
python3 -m venv speedvenv
./speedvenv/bin/pip install uiautomator2
./speedvenv/bin/python e2e_seerr_surfaces.py <serial> [package]
```

Plain `python3` also works if `uiautomator2` is importable; the driver adds a
sibling `speedvenv` to `sys.path` automatically when present.

`bench_fast.py` reproduces the table above; `REPORT.md` records the full
investigation, including measured dead ends (raw `exec-out` dumps, `cmd input`
over a persistent shell, evdev injection — SELinux-blocked without root).

## Baseline driver (`atv_driver.py`)

Reusable dpad-first UI driver:

- All navigation walks focus with key events and reads the focused node's
  subtree from `uiautomator` dumps; blind coordinate taps are used only for
  Compose toolbar buttons, which respond to touch reliably.
- Dumps are hardened against staleness (device + local files deleted before
  every dump; the flaky `null root node` condition is retried) and against
  device sleep (a sleeping TV returns null accessibility roots, so the driver
  wakes the device first).
- `crash_log()` includes ACRA-caught crashes, which never reach logcat's
  crash buffer, and filters out unrelated system noise.
- `requests()` matches HTTP log lines by URL because R8 renames the SDK's
  logger tag in release builds.
