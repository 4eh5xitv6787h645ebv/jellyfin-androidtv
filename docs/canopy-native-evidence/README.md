# Canopy native Android TV evidence

This directory retains the Android UIAutomator hierarchies and a machine-checkable result for the first bounded Canopy consumer. The host-offline hierarchy is explicitly sanitized because the account screen exposed private disposable runtime identifiers; every other hierarchy is raw. Run `bash docs/canopy-native-evidence/verify.sh` from any checkout with `rg`, `jq`, and `sha256sum` available.

No token, password, provider URL, provider key, or physical-device output is stored here. Every Android command targeted `adb -s emulator-5560`.

## Immutable environments

The feature run used Android head `ccbc6dc711cde555ebb585ca06c508e67da69ca2` (tree `b0136ae6bf7c1508c85e2c890c827cc61856a522`, APK SHA-256 `06a0995ae4f4cb622c281699948776b9f5995546b59bcd6c238da9082764c15d`) and Canopy head `81d6fff706f1386b0a87574a18e48c098d3106b9` (tree `5624e46aca7249e15b6cfe93bbb8527f3e49f4da`, DLL SHA-256 `37359b268bb2219bc1f9cad541883d44634e2ec07034d6d13d8cb1213a58c603`).

The independent evidence review found that seven contributions left no additive family headroom. The only subsequent production changes raised the shared fail-closed cap from 7 to 12. Final Android head `828f88f29c47b50ab259b7e69cb690aec7cc1962` (tree `a79313933ac2f5b443c7890d1e490b7ce398a4a5`, APK SHA-256 `5ce05dda88560bab89294b28b1fe8d1e8fe517ac2c24043572ec5ed73afdcfef`) and final Canopy head `2a1a1666ffc18ccba2d6b5758bf8db42fa43fcc4` (tree `cd395908c1042047431911612338f5dd83e1363f`, DLL SHA-256 `ceb26bdf3ac3f25aae4fd52a5e66f11e563c42d40e2fb3e260413e1e98c2b656`) retain five bounded contribution slots beyond the complete seven-contribution pilot. Both sides still reject 13.

The final pair reran the live four-test Jellyfin 12 pilot and the final generic Android catalog capture. The cap-only patch does not change the three feature owners, UI renderer, action flow, authentication, or lifecycle code exercised in the feature run.

The emulator was Android API 34, x86, 1920x1080 at density 320. Jellyfin was 12.0.0 from `jellyfin/jellyfin:unstable@sha256:f961d7bd9f38457b2bce2aea3a22f120ab50b3885573b184abf46e892dd59119`.

## Reproduction protocol

Start from a seeded disposable Jellyfin 12 server with the exact Canopy DLL, configure the Android debug app for its emulator-visible URL, and sign in as the seeded test administrator. Keep credentials in environment variables and never print the authentication response.

```sh
ANDROID_SERIAL=emulator-5560
APP_ID=org.jellyfin.androidtv.debug
APK=app/build/outputs/apk/debug/jellyfin-androidtv-v0.0.0-dev.1-debug.apk

./gradlew test detekt lint assembleDebug
adb -s "$ANDROID_SERIAL" install -r "$APK"
adb -s "$ANDROID_SERIAL" shell am start \
  -n "$APP_ID/org.jellyfin.androidtv.ui.startup.StartupActivity" \
  --es ItemId "$SEEDED_ITEM_ID" --ez HideSplash true
adb -s "$ANDROID_SERIAL" shell uiautomator dump /sdcard/canopy-evidence.xml
adb -s "$ANDROID_SERIAL" pull /sdcard/canopy-evidence.xml .
```

The D-pad sequences below begin with native Play/Play all focused after opening a fresh item detail. `CENTER` means `KEYCODE_DPAD_CENTER`.

| Scenario | Exact interaction |
|---|---|
| Open Spoiler Guard | `DOWN, CENTER` |
| Enable/disable one-boolean form | `CENTER, DOWN, RIGHT, CENTER` |
| Open Hidden Content | `DOWN, RIGHT, CENTER` |
| Hide/unhide with global scope retained | `CENTER, DOWN x5, RIGHT, CENTER` |
| Open standard Seerr request | `DOWN, RIGHT x2, CENTER` |
| Submit required confirmation | `CENTER, DOWN, RIGHT, CENTER` |
| Cancel a form | open it, then `KEYCODE_BACK` |

Send keys with `adb -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_DPAD_DOWN` and the corresponding Right/Center/Back codes. Dump the hierarchy after each mutation and after ordinary Jellyfin content refresh. The retained files show:

- Hidden form focus and bounded scope options; hide removes Alpha from ordinary Home, and unhide restores it.
- Spoiler enable changes the row to protected and ordinary Next Up replaces `The Secret of Chapter 1.1` with the server-protected `Season 1, Episode 1`; disable refreshes the original title back and restores the initial state.
- Standard Seerr confirmation changes the row to pending and removes the duplicate request action.
- Provider outage removes only Seerr; recovery restores it.
- Platform disable removes the complete Canopy row while Play remains; re-enable restores it.
- Back dismisses the native form without changing Hidden state.
- Host outage degrades to account/server selection; restart plus relaunch restores the exact catalog.
- Replacing the clean base APK (`204d667cf110b9933da1a0af60260d48548413d9`, SHA-256 `d2df5d0d4857c0d53dc43d1ac6358910e819db2d8e2e4e2f07e14e4032e729d9`) with the final APK through `adb install -r` preserves the session and adds the generic Canopy row. The two APKs have the same package, version code, and signing certificate.

Provider and platform outage steps used shell traps that always restarted the disposable provider/Jellyfin containers and restored the original authenticated Platform configuration. The host-offline step stopped only the named disposable Jellyfin container. The exact final server proof was:

```text
4 passed (17.0s)
admin disable/re-enable and old-authority revocation
all three feature families through generic opaque actions
unsupported protocol/input failure
live parental-policy revocation of prepared Seerr authority
```

That checked-in server test is the evidence for actor isolation and post-prepare revocation. The manual emulator run used one account; it did not claim Android account-switch coverage. The emulator used the standard Seerr request path; 4K and denied/error mappings are covered by server and Android unit tests, not by these manual UI artifacts.

The final disposable state was restored except for the intentional Delta standard-request pending fixture: Platform enabled, provider and Jellyfin healthy, Alpha visible, and Guard Test Show unprotected.
