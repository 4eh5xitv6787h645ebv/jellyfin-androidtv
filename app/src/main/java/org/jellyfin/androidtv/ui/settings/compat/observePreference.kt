package org.jellyfin.androidtv.ui.settings.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.preference.Preference

/**
 * Read-only counterpart to [rememberPreference] that reflects writes made
 * anywhere in the app.
 *
 * [rememberPreference] deliberately keeps a *local* snapshot, so a composable
 * that merely reads a preference (rather than owning its editor) keeps showing
 * a stale value after the settings screen changes it — e.g. the toolbar would
 * keep offering a surface the user just disabled until the app restarted.
 */
@Composable
fun <T : Any> observePreference(
	userPreferences: UserPreferences,
	preference: Preference<T>,
): State<T> {
	val state = remember(preference) { mutableStateOf(userPreferences[preference]) }

	DisposableEffect(preference) {
		val listener = UserPreferences.OnChangeListener { changed ->
			if (changed == preference.key) state.value = userPreferences[preference]
		}
		userPreferences.addOnChangeListener(listener)
		// Re-read on (re)subscribe: the value may have changed while detached.
		state.value = userPreferences[preference]
		onDispose { userPreferences.removeOnChangeListener(listener) }
	}

	return state
}
