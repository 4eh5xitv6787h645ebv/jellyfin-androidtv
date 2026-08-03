package org.jellyfin.androidtv.ui.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsCanopyScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.canopy_settings)) },
				captionContent = { Text(stringResource(R.string.canopy_settings_description)) },
			)
		}

		item {
			var itemActionsEnabled by rememberPreference(userPreferences, UserPreferences.canopyItemActionsEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.canopy_pref_item_actions)) },
				trailingContent = { Checkbox(checked = itemActionsEnabled) },
				captionContent = { Text(stringResource(R.string.canopy_pref_item_actions_description)) },
				onClick = { itemActionsEnabled = !itemActionsEnabled },
				modifier = Modifier.focusKey("canopy_item_actions_enabled")
			)
		}

		item {
			var placement by rememberPreference(userPreferences, UserPreferences.canopyActionsPlacement)

			ListButton(
				headingContent = { Text(stringResource(R.string.canopy_pref_actions_placement)) },
				captionContent = { Text(stringResource(placement.nameRes)) },
				onClick = { router.push(Routes.CANOPY_ACTIONS_PLACEMENT) },
				modifier = Modifier.focusKey(Routes.CANOPY_ACTIONS_PLACEMENT)
			)
		}

		item {
			var seerrSearchEnabled by rememberPreference(userPreferences, UserPreferences.canopySeerrSearchEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.canopy_pref_seerr_search)) },
				trailingContent = { Checkbox(checked = seerrSearchEnabled) },
				captionContent = { Text(stringResource(R.string.canopy_pref_seerr_search_description)) },
				onClick = { seerrSearchEnabled = !seerrSearchEnabled },
				modifier = Modifier.focusKey("canopy_seerr_search_enabled")
			)
		}
	}
}
