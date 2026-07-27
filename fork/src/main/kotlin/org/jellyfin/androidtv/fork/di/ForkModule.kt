package org.jellyfin.androidtv.fork.di

import org.koin.dsl.module

/**
 * Koin definitions contributed by the fork.
 *
 * This module is loaded *after* upstream's modules by `ForkInitializer` (in the `fork` flavor source
 * set of `:app`). Koin allows overriding by default, so a definition declared here for a type
 * upstream already binds replaces upstream's. That is the primary mechanism for changing upstream
 * behavior without editing upstream files:
 *
 * ```kotlin
 * // Replaces upstream's binding entirely -- no edit to AppModule.kt required.
 * single<UserViewsRepository> { ForkUserViewsRepository(get(), get()) }
 * ```
 *
 * Upstream also exposes a `getAll()`-based multibinding for `ExternalPlayerApi`, so additional
 * implementations declared here are picked up with no upstream edit at all.
 *
 * Prefer this over modifying upstream classes. See FORK.md for the full policy.
 */
val forkModule = module {
	// Fork definitions go here.
}
