package org.jellyfin.androidtv.ui.seerr

import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent

/**
 * Java-friendly bridge so [FullDetailsFragment] can route clicks on Seerr
 * entries (cards in the Seerr filmography row) to their native destinations.
 */
object SeerrClickBridge {
	@JvmStatic
	fun handle(navigationRepository: NavigationRepository, item: Any?): Boolean =
		navigationRepository.navigateToSeerrEntry(item)
}

/**
 * Adds a Seerr-powered "More from <name>" row to the native person details
 * screen. The library only knows the person's items it owns; this row shows
 * their full filmography from Seerr so the rest can be discovered and
 * requested. Applies graceful omission: no Canopy/Seerr, no row.
 */
fun addSeerrPersonCreditsRow(fragment: FullDetailsFragment, adapter: MutableObjectAdapter<Row>, item: BaseItemDto) {
	if (item.type != BaseItemKind.PERSON) return

	val userPreferences = KoinJavaComponent.get<UserPreferences>(UserPreferences::class.java)
	if (!userPreferences[UserPreferences.canopySeerrSearchEnabled]) return

	val personName = item.name?.takeIf { it.isNotBlank() } ?: return
	val repository = KoinJavaComponent.get<SeerrRepository>(SeerrRepository::class.java)

	fragment.lifecycleScope.launch {
		if (!repository.capabilities().available) return@launch

		val personId = item.providerIds?.entries
			?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.toLongOrNull()
			?: repository.findPersonId(personName)
			?: return@launch

		val credits = repository.personCredits(personId)
		if (!fragment.isAdded || credits.isEmpty()) return@launch

		val movies = credits.filter { it.mediaType == SeerrMediaType.MOVIE }
		val series = credits.filter { it.mediaType == SeerrMediaType.TV }
		if (movies.isNotEmpty()) {
			adapter.add(
				seerrListRow(
					fragment.getString(R.string.canopy_seerr_more_from_kind, personName, fragment.getString(R.string.canopy_seerr_movies)),
					movies,
				),
			)
		}
		if (series.isNotEmpty()) {
			adapter.add(
				seerrListRow(
					fragment.getString(R.string.canopy_seerr_more_from_kind, personName, fragment.getString(R.string.canopy_seerr_series_group)),
					series,
				),
			)
		}
	}
}
