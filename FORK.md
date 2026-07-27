# Fork policy

This repository is a fork of [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv)
that stays permanently mergeable with upstream.

The governing rule: **merge cost is proportional to the number of upstream lines we modify, not to
the amount of code we add.** Every decision below follows from that. Adding 50,000 lines in
`fork/` costs nothing at merge time; changing 50 lines in `app/src/main/` costs something forever.

## Branch model

| Branch | Contents |
| --- | --- |
| `master` | Pristine mirror of `upstream/master`. Never commit here. |
| `fork` | This client. Upstream is merged *into* this branch. |

```sh
git remote -v
# origin    git@github.com:4eh5xitv6787h645ebv/jellyfin-androidtv.git
# upstream  git@github.com:jellyfin/jellyfin-androidtv.git
```

Merge, never rebase. A long-lived branch rebased onto a moving upstream re-resolves the same
conflicts on every sync. `rerere` is enabled locally so each conflict is resolved once:

```sh
git config rerere.enabled true
git config merge.conflictstyle zdiff3
```

## Territory

Every path falls into exactly one of three categories.

### Fork-owned — upstream changes here are ignored

* `fork/` — the `:fork` Gradle module, where nearly all fork code belongs.
* `app/src/fork/` — the `fork` product flavor source set.
* `FORK.md`, `scripts/fork/`, `.github/workflows/fork-*.yml`

New files, so they can never conflict.

### Upstream-owned — merged verbatim, never edited

Everything else. `app/src/main/`, `playback/`, `design/`, `preference/`, `buildSrc/`.

Note that `.github/workflows/` (other than `fork-*.yml`) and `fastlane/` are upstream's release
plumbing, publishing to Jellyfin's own channels. They are not used by this fork and are not worth
maintaining — take upstream's version on every merge and ignore the fact that they reference
build tasks that the flavor split renamed.

### Seam — small, marked, budgeted edits

A handful of upstream lines we do modify. **Every one carries a `// FORK:` comment** and is listed
in the ledger below. Run `scripts/fork/diff-budget.sh` to see the current cost.

## The three seams

### 1. Product flavors (zero upstream diff)

`app/build.gradle.kts` declares a `distribution` dimension with `vanilla` and `fork` flavors.
Android merges source sets with priority **buildType > flavor > main**, so anything placed in
`app/src/fork/` overrides `app/src/main/` without editing it:

* `app/src/fork/res/` — colors, themes, strings, drawables, icons, the app name.
* `app/src/fork/AndroidManifest.xml` — merged into upstream's manifest.
* `app/src/fork/java/` — new classes that can see both `app/src/main/` and the `:fork` module.

Limitation: a flavor source set **cannot replace a class whose fully-qualified name already exists
in `main`** — that is a duplicate-class error. Use seam 2 for that.

Build with `assembleForkDebug` / `assembleForkRelease`. The `vanilla` flavor builds upstream's code
paths without the fork's DI overrides or resource skin — useful for A/B comparison.

### 2. Koin overrides (zero upstream diff)

Upstream boots Koin from an `androidx.startup` `Initializer`
(`app/src/main/java/org/jellyfin/androidtv/di/KoinInitializer.kt`). Rather than editing its
`modules(...)` list, the fork registers **its own** initializer:

* `app/src/fork/java/org/jellyfin/androidtv/fork/startup/ForkInitializer.kt`
* declared in `app/src/fork/AndroidManifest.xml`, merged into upstream's `InitializationProvider`

It calls `loadKoinModules(forkModule)` after upstream's modules are loaded. Koin allows overriding
by default, so any definition in `fork/src/main/kotlin/.../di/ForkModule.kt` **replaces** upstream's
binding of the same type:

```kotlin
val forkModule = module {
    single<UserViewsRepository> { ForkUserViewsRepository(get(), get()) }
}
```

This is the main lever for changing behavior. Prefer it over editing upstream classes, always.

Upstream also has a `getAll()` multibinding for `ExternalPlayerApi` (`AppModule.kt:151`); extra
implementations declared in `forkModule` are picked up automatically.

**Ordering caveat.** `ForkInitializer` runs after `KoinInitializer`, but androidx.startup does not
guarantee it runs before upstream's `SessionInitializer`, which resolves `SessionRepository` during
startup. To override a binding consumed *during* startup, either:

1. remove and re-add upstream's initializer in `app/src/fork/AndroidManifest.xml`, ordering ours
   first (still zero upstream diff):
   ```xml
   <meta-data android:name="org.jellyfin.androidtv.SessionInitializer" tools:node="remove" />
   <meta-data android:name="org.jellyfin.androidtv.fork.startup.ForkInitializer" android:value="androidx.startup" />
   <meta-data android:name="org.jellyfin.androidtv.SessionInitializer" android:value="androidx.startup" />
   ```
2. or add `forkModule` to the `modules(...)` list in `KoinInitializer.kt` — a one-line, trivially
   resolvable conflict. Add it to the ledger if you do.

### 3. Marked edits, with a budget

When neither seam works, edit upstream — but mark it, keep it to the fewest possible lines, and
record it below. Target: **under 20 upstream files touched.** Past that, the fork is drifting and
should be re-examined for a seam it is missing.

```sh
scripts/fork/diff-budget.sh
```

## Ledger of upstream files modified

| File | Change | Why a seam could not be used |
| --- | --- | --- |
| `settings.gradle.kts` | +1 line: `include(":fork")` | Gradle module list is not extensible from outside. |
| `app/build.gradle.kts` | flavor block, `implementation(projects.fork)`, `androidComponents` resValue fix | Flavors and dependencies must be declared in the module's own build script. |
| `app/src/main/java/org/jellyfin/androidtv/auth/repository/ServerRepository.kt` | +1 import, 1 line: `minimumServerVersion` | It is a `val` in a `companion object`, not a DI binding, so it cannot be overridden at runtime. |

### Why the 10.12 floor is one line and not a deletion

This client supports **Jellyfin 10.12 and newer only**. The tempting implementation — deleting
upstream's compatibility code for older servers — is the single most merge-hostile change possible,
because every future upstream edit to those files conflicts against the deletion.

Instead we raise the floor upstream already checks against
(`ForkConfig.MINIMUM_SERVER_VERSION` → `ServerRepository.minimumServerVersion`). That one value
propagates to `Server.versionSupported`, the login gate in `AuthenticationRepository`, the SDK's
server discovery in `AppModule`, and the outdated-server notification. Legacy paths stay in the
tree, unreachable, and merge cleanly forever.

## Syncing with upstream

```sh
scripts/fork/sync-upstream.sh --list           # recent upstream tags
scripts/fork/sync-upstream.sh                  # newest upstream tag, prereleases included
scripts/fork/sync-upstream.sh v0.20.0-beta.1   # a specific tag
scripts/fork/sync-upstream.sh master           # upstream/master tip
```

**Sync on every upstream tag, betas and release candidates included.** Upstream publishes
prereleases as real tags (`v0.19.0-beta.7`), and they carry the bulk of a cycle's change. Merging
each one keeps every merge small and keeps `rerere` fed with resolutions; waiting for the next
stable release means merging an entire cycle at once, which is exactly where conflicts pile up.

Note that the newest tag is picked by tag *date*, not semver order — semver sorts
`v0.19.0-beta.7` before `v0.19.0`, but chronologically the beta is what upstream published next.

The `fork-upstream-sync` GitHub Actions workflow trial-merges `upstream/master` nightly and builds
it, so conflicts and breakage surface weeks before a real sync.

After any sync:

1. `scripts/fork/diff-budget.sh` — confirm the seam did not grow silently.
2. `./gradlew :app:assembleForkDebug :fork:test` — confirm it still builds.
3. Check whether upstream added anything that makes one of our marked edits unnecessary.

## Adding fork features

1. Code goes in `fork/` (the `:fork` module) by default.
2. Resources, manifest entries, and classes that need to see `app/src/main/` go in `app/src/fork/`.
3. Behavior changes go through `forkModule` DI overrides.
4. Only if none of that works, edit upstream — with a `// FORK:` marker and a ledger entry.

## Licensing

Upstream is GPL-2.0. This fork is GPL-2.0. Distributing builds means offering the corresponding
source, including everything in `fork/`.
