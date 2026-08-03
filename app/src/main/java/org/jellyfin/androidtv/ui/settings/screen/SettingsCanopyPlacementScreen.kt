package org.jellyfin.androidtv.ui.settings.screen

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.CanopyActionsPlacement
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsCanopyPlacementScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var placement by rememberPreference(userPreferences, UserPreferences.canopyActionsPlacement)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.canopy_settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.canopy_pref_actions_placement)) },
			)
		}

		items(CanopyActionsPlacement.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = placement == entry) },
				onClick = {
					placement = entry
					router.back()
				},
				modifier = Modifier.focusKey("canopy_placement_${entry.name}")
			)
		}
	}
}
