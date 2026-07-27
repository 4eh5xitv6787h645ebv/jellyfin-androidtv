package org.jellyfin.androidtv.fork.startup

import android.content.Context
import androidx.startup.Initializer
import org.jellyfin.androidtv.di.KoinInitializer
import org.jellyfin.androidtv.fork.di.forkModule
import org.koin.core.context.loadKoinModules
import timber.log.Timber

/**
 * Loads the fork's Koin module on top of upstream's.
 *
 * This class lives in the `fork` flavor source set rather than in `src/main`, and is registered
 * through `app/src/fork/AndroidManifest.xml`. Both are files upstream does not have, so wiring the
 * fork into app startup costs zero lines of upstream diff.
 *
 * Because Koin allows overriding by default, definitions in [forkModule] replace upstream
 * definitions of the same type.
 *
 * Ordering caveat: this runs after [KoinInitializer], but androidx.startup does not guarantee it
 * runs before upstream's `SessionInitializer`, which resolves `SessionRepository` during startup.
 * Overriding a binding that is consumed during startup needs the escape hatch documented in
 * FORK.md.
 */
class ForkInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		Timber.i("Loading fork Koin module")
		loadKoinModules(forkModule)
	}

	override fun dependencies() = listOf(KoinInitializer::class.java)
}
