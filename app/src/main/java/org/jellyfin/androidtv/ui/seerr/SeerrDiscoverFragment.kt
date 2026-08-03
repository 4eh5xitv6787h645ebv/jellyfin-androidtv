package org.jellyfin.androidtv.ui.seerr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.FragmentFullDetailsBinding
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRepository
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.Utils
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

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val binding = FragmentFullDetailsBinding.inflate(layoutInflater, container, false)

		val rowsFragment = RowsSupportFragment()
		childFragmentManager.beginTransaction().replace(R.id.rowsFragment, rowsFragment).commit()

		rowsFragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
			navigationRepository.navigateToSeerrEntry(item)
		}

		val adapter = MutableObjectAdapter<Row>(CustomListRowPresenter(Utils.convertDpToPixel(requireContext(), 10)))
		rowsFragment.adapter = adapter

		loadRows(adapter)

		return binding.root
	}

	private fun loadRows(adapter: MutableObjectAdapter<Row>) {
		lifecycleScope.launch {
			if (!seerrRepository.capabilities().available) {
				if (isAdded) {
					adapter.add(
						seerrStatusRow(
							getString(R.string.canopy_seerr_discover),
							getString(R.string.canopy_seerr_unavailable),
						),
					)
				}
				return@launch
			}

			val rows = listOf(
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
				if (entries.isNotEmpty()) adapter.add(seerrListRow(getString(labelRes), entries))
			}

			if (isAdded && adapter.size() == 0) {
				adapter.add(
					seerrStatusRow(
						getString(R.string.canopy_seerr_discover),
						getString(R.string.canopy_seerr_unavailable),
					),
				)
			}
		}
	}
}
