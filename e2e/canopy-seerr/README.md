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
python3 e2e_seerr_surfaces.py <serial> [package]
# e.g. against a TV device running the release build:
python3 e2e_seerr_surfaces.py 192.168.0.42:5555 org.jellyfin.androidtv
# or an emulator running the debug build:
python3 e2e_seerr_surfaces.py emulator-5554 org.jellyfin.androidtv.debug
```

Each step prints PASS/FAIL and saves the raw UI dump under `e2e-evidence/`.
Exit code 0 means every step passed.

**Note:** the Request step submits a real request to the connected Seerr
instance. Point the server at a development Seerr instance.

## Driver (`atv_driver.py`)

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
