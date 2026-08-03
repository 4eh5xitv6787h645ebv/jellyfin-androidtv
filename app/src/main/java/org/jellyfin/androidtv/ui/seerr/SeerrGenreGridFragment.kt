package org.jellyfin.androidtv.ui.seerr

import android.os.Bundle
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrDiscoverItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRepository
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.search.SeerrCardPresenter
import org.koin.android.ext.android.inject

/**
 * Paged vertical grid of Seerr discover results for a single genre.
 */
class SeerrGenreGridFragment : VerticalGridSupportFragment() {
	companion object {
		const val ARG_GENRE_ID = "GenreId"
		const val ARG_MEDIA_TYPE = "MediaType"
		const val ARG_GENRE_NAME = "GenreName"

		private const val COLUMNS = 6
		private const val PAGE_AHEAD_THRESHOLD = COLUMNS * 2
	}

	private val seerrRepository by inject<SeerrRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	private val gridAdapter = ArrayObjectAdapter(SeerrCardPresenter())
	private var nextPage = 1
	private var hasMore = true
	private var loading = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		title = arguments?.getString(ARG_GENRE_NAME).orEmpty()
		// Match BrowseGridFragment's presenter configuration (zoom, no dimmer)
		setGridPresenter(VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_LARGE, false).apply { numberOfColumns = COLUMNS })
		adapter = gridAdapter

		setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
			navigationRepository.navigateToSeerrEntry(item)
		})
		setOnItemViewSelectedListener(
			OnItemViewSelectedListener { _, item, _, _ ->
				val index = (item as? SeerrDiscoverItem)?.let { gridAdapter.indexOf(it) } ?: return@OnItemViewSelectedListener
				if (index >= gridAdapter.size() - PAGE_AHEAD_THRESHOLD) loadNextPage()
			},
		)

		loadNextPage()
	}

	private fun loadNextPage() {
		if (loading || !hasMore) return
		val genreId = arguments?.getLong(ARG_GENRE_ID) ?: return
		val mediaType = SeerrMediaType.fromWire(arguments?.getString(ARG_MEDIA_TYPE)) ?: return

		loading = true
		lifecycleScope.launch {
			val page = seerrRepository.discoverByGenre(mediaType, genreId, nextPage)
			loading = false
			if (!isAdded) return@launch
			// null = transport failure; keep hasMore so scrolling retries
			if (page == null) return@launch

			hasMore = page.hasMore && page.items.isNotEmpty()
			nextPage = page.page + 1
			page.items.forEach { item ->
				if (gridAdapter.indexOf(item) < 0) gridAdapter.add(item)
			}
		}
	}
}
