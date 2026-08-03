package org.jellyfin.androidtv.ui.seerr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRepository
import org.jellyfin.androidtv.ui.canopy.CanopyQuickActions
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject

/**
 * Native Seerr discovery screen: trending, popular and upcoming rows plus
 * browsable genre tiles, all served by the Canopy Seerr proxy. Rows load
 * sequentially to keep request pressure on the server low; empty rows are
 * omitted entirely.
 */
class SeerrDiscoverFragment : Fragment() {
	private val seerrRepository by inject<SeerrRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val apiClient by inject<ApiClient>()
	private val quickActions by lazy { CanopyQuickActions(this, apiClient) }

	private val rowsAdapter by lazy {
		MutableObjectAdapter<Row>(CustomListRowPresenter(Utils.convertDpToPixel(requireContext(), 10)))
	}
	private var loaded = false

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		Column {
			MainToolbar(MainToolbarActiveButton.Discover)

			// The leanback code has its own awful focus handling that doesn't work properly with Compose view interop. To work around
			// this we add custom behavior that only allows focus exit when the current selected row is the first one, matching the
			// Home and Search screens.
			var rowsSupportFragment by remember { mutableStateOf<RowsSupportFragment?>(null) }
			AndroidFragment<RowsSupportFragment>(
				modifier = Modifier
					.focusGroup()
					.focusProperties {
						onExit = {
							val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
							if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
								cancelFocusChange()
							} else {
								rowsSupportFragment?.selectedPosition = 0
								rowsSupportFragment?.verticalGridView?.clearFocus()
							}
						}
					}
					.padding(top = 5.dp)
					.fillMaxSize(),
				onUpdate = { fragment ->
					rowsSupportFragment = fragment
					fragment.adapter = rowsAdapter
					fragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
						navigationRepository.navigateToSeerrEntry(item)
					}
					loadRows()
				}
			)
		}
	}

	private fun loadRows() {
		if (loaded) return
		loaded = true

		lifecycleScope.launch {
			if (!seerrRepository.capabilities().available) {
				if (isAdded) {
					rowsAdapter.add(
						seerrStatusRow(
							getString(R.string.canopy_seerr_discover),
							getString(R.string.canopy_seerr_unavailable),
						),
					)
				}
				return@launch
			}

			val rows = listOf(
				R.string.canopy_seerr_watchlist to suspend { seerrRepository.watchlist() },
				R.string.canopy_seerr_trending to suspend { seerrRepository.trending() },
				R.string.canopy_seerr_popular_movies to suspend { seerrRepository.popularMovies() },
				R.string.canopy_seerr_upcoming_movies to suspend { seerrRepository.upcomingMovies() },
				R.string.canopy_seerr_popular_series to suspend { seerrRepository.popularSeries() },
				R.string.canopy_seerr_upcoming_series to suspend { seerrRepository.upcomingSeries() },
				R.string.canopy_seerr_movie_genres to suspend { seerrRepository.genres(SeerrMediaType.MOVIE) },
				R.string.canopy_seerr_series_genres to suspend { seerrRepository.genres(SeerrMediaType.TV) },
			)

			for ((labelRes, load) in rows) {
				val entries = load()
				if (!isAdded) return@launch
				if (entries.isNotEmpty()) rowsAdapter.add(seerrListRow(getString(labelRes), entries, canopyLongPress(quickActions)))
			}

			if (isAdded && rowsAdapter.size() == 0) {
				rowsAdapter.add(
					seerrStatusRow(
						getString(R.string.canopy_seerr_discover),
						getString(R.string.canopy_seerr_unavailable),
					),
				)
			}
		}
	}
}
