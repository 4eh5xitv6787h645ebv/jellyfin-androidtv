package org.jellyfin.androidtv.ui.seerr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.InfoItem
import org.jellyfin.androidtv.databinding.FragmentFullDetailsBinding
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrPersonDetails
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRepository
import org.jellyfin.androidtv.ui.itemdetail.MyDetailsOverviewRow
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.MyDetailsOverviewRowPresenter
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import java.util.UUID

/**
 * Native details screen for an actor or other person from Seerr, with their
 * movie and series credits as browsable rows.
 */
class SeerrPersonFragment : Fragment() {
	companion object {
		const val ARG_PERSON_ID = "PersonId"
	}

	private val seerrRepository by inject<SeerrRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val markdownRenderer by inject<MarkdownRenderer>()

	private var rowsFragment: RowsSupportFragment? = null

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val binding = FragmentFullDetailsBinding.inflate(layoutInflater, container, false)

		val fragment = RowsSupportFragment()
		rowsFragment = fragment
		childFragmentManager.beginTransaction().replace(R.id.rowsFragment, fragment).commit()

		fragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
			navigationRepository.navigateToSeerrEntry(item)
		}

		loadPerson()

		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()
		rowsFragment = null
	}

	private fun loadPerson() {
		val personId = arguments?.getLong(ARG_PERSON_ID) ?: return

		lifecycleScope.launch {
			val person = seerrRepository.person(personId)
			if (!isAdded) return@launch
			if (person == null) {
				Toast.makeText(requireContext(), R.string.canopy_seerr_unavailable, Toast.LENGTH_LONG).show()
				return@launch
			}

			val credits = seerrRepository.personCredits(personId)
			if (!isAdded) return@launch

			showPerson(person, credits)
		}
	}

	private fun showPerson(person: SeerrPersonDetails, credits: List<org.jellyfin.androidtv.integration.canopy.seerr.SeerrDiscoverItem>) {
		val fragment = rowsFragment ?: return

		val selector = ClassPresenterSelector().apply {
			addClassPresenter(MyDetailsOverviewRow::class.java, MyDetailsOverviewRowPresenter(markdownRenderer))
			addClassPresenter(ListRow::class.java, CustomListRowPresenter(Utils.convertDpToPixel(requireContext(), 10)))
		}
		val adapter = MutableObjectAdapter<Row>(selector)
		fragment.adapter = adapter

		adapter.add(
			MyDetailsOverviewRow(
				item = BaseItemDto(
					id = UUID.nameUUIDFromBytes("seerr:person:${person.person.personId}".toByteArray()),
					type = BaseItemKind.PERSON,
					mediaType = MediaType.UNKNOWN,
					name = person.person.name,
					overview = person.biography,
				),
				imageDrawable = person.person.profileUrl,
				summary = person.biography,
				infoItem1 = person.knownFor?.let { InfoItem(getString(R.string.canopy_seerr_known_for), it) },
			),
		)

		val movies = credits.filter { it.mediaType == SeerrMediaType.MOVIE }
		val series = credits.filter { it.mediaType == SeerrMediaType.TV }
		if (movies.isNotEmpty()) adapter.add(seerrListRow(getString(R.string.canopy_seerr_movies), movies))
		if (series.isNotEmpty()) adapter.add(seerrListRow(getString(R.string.canopy_seerr_series_group), series))
	}
}
