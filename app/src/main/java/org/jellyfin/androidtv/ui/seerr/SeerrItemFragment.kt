package org.jellyfin.androidtv.ui.seerr

import android.app.AlertDialog
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
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrItemDetails
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRatings
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRepository
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRequestOutcome
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrSeason
import org.jellyfin.androidtv.ui.TextUnderButton
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
 * Native details screen for a Seerr title that is not (yet) in the library.
 * Mirrors the layout of [org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment]:
 * a details overview row with request actions, followed by cast, similar and
 * recommendation rows served by the Canopy Seerr proxy.
 */
class SeerrItemFragment : Fragment() {
	companion object {
		const val ARG_TMDB_ID = "TmdbId"
		const val ARG_MEDIA_TYPE = "MediaType"

		private const val TICKS_PER_MINUTE = 600_000_000L
	}

	private val seerrRepository by inject<SeerrRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val markdownRenderer by inject<MarkdownRenderer>()

	private var rowsFragment: RowsSupportFragment? = null
	private var rowsAdapter: MutableObjectAdapter<Row>? = null
	private var detailsRow: MyDetailsOverviewRow? = null
	private var details: SeerrItemDetails? = null
	private var ratings: SeerrRatings? = null
	private var requestInFlight = false

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val binding = FragmentFullDetailsBinding.inflate(layoutInflater, container, false)

		val fragment = RowsSupportFragment()
		rowsFragment = fragment
		childFragmentManager.beginTransaction().replace(R.id.rowsFragment, fragment).commit()

		fragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
			navigationRepository.navigateToSeerrEntry(item)
		}

		loadItem()

		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()
		rowsFragment = null
		rowsAdapter = null
		detailsRow = null
	}

	private fun loadItem() {
		val tmdbId = arguments?.getLong(ARG_TMDB_ID) ?: return
		val mediaType = SeerrMediaType.fromWire(arguments?.getString(ARG_MEDIA_TYPE)) ?: return

		lifecycleScope.launch {
			val loaded = seerrRepository.details(mediaType, tmdbId)
			if (!isAdded) return@launch
			if (loaded == null) {
				Toast.makeText(requireContext(), R.string.canopy_seerr_unavailable, Toast.LENGTH_LONG).show()
				return@launch
			}

			details = loaded
			ratings = seerrRepository.ratings(loaded.item).takeUnless { it.isEmpty }
			if (!isAdded) return@launch
			showDetails(loaded)
			loadAdditionalRows(loaded)
		}
	}

	private fun showDetails(details: SeerrItemDetails) {
		val fragment = rowsFragment ?: return

		val selector = ClassPresenterSelector().apply {
			addClassPresenter(MyDetailsOverviewRow::class.java, MyDetailsOverviewRowPresenter(markdownRenderer))
			addClassPresenter(ListRow::class.java, CustomListRowPresenter(Utils.convertDpToPixel(requireContext(), 10)))
		}

		val adapter = rowsAdapter ?: MutableObjectAdapter<Row>(selector).also {
			rowsAdapter = it
			fragment.adapter = it
		}

		val row = MyDetailsOverviewRow(
			item = details.toDisplayItem(),
			imageDrawable = details.item.posterUrl,
			summary = details.overview,
			infoItem1 = seerrStatusLabelRes(details.item.status)?.let { statusRes ->
				InfoItem(getString(R.string.canopy_seerr_status), getString(statusRes))
			},
			infoItem2 = ratings?.let { InfoItem(getString(R.string.canopy_seerr_ratings), it.displayText()) },
		)
		buildActions(row, details)

		val previous = detailsRow
		detailsRow = row
		if (previous != null) adapter.remove(previous)
		adapter.add(0, row)
	}

	private fun loadAdditionalRows(details: SeerrItemDetails) {
		val adapter = rowsAdapter ?: return

		if (details.cast.isNotEmpty()) {
			adapter.add(seerrListRow(getString(R.string.canopy_seerr_cast), details.cast))
		}

		lifecycleScope.launch {
			val self = details.item

			details.collection?.let { collection ->
				val parts = seerrRepository.collectionParts(collection.id)
					.filterNot { it.tmdbId == self.tmdbId && it.mediaType == self.mediaType }
				if (!isAdded) return@launch
				if (parts.isNotEmpty()) {
					adapter.add(seerrListRow(getString(R.string.canopy_seerr_part_of, collection.name), parts))
				}
			}

			val similar = seerrRepository.similar(self)
			if (!isAdded) return@launch
			if (similar.isNotEmpty()) {
				adapter.add(seerrListRow(getString(R.string.canopy_seerr_similar), similar))
			}

			val recommended = seerrRepository.recommendations(self)
			if (!isAdded) return@launch
			if (recommended.isNotEmpty()) {
				adapter.add(seerrListRow(getString(R.string.canopy_seerr_recommended), recommended))
			}

			val moreFrom = details.studio ?: details.network
			if (moreFrom != null) {
				val entries = when {
					details.studio != null -> seerrRepository.moreFromStudio(moreFrom.id)
					else -> seerrRepository.moreFromNetwork(moreFrom.id)
				}.filterNot { it.tmdbId == self.tmdbId && it.mediaType == self.mediaType }
				if (!isAdded) return@launch
				if (entries.isNotEmpty()) {
					adapter.add(seerrListRow(getString(R.string.canopy_seerr_more_from, moreFrom.name), entries))
				}
			}
		}
	}

	private fun SeerrRatings.displayText(): String = buildList {
		rtCritics?.let { add(getString(R.string.canopy_seerr_rating_critics, it)) }
		rtAudience?.let { add(getString(R.string.canopy_seerr_rating_audience, it)) }
		imdb?.let { add(getString(R.string.canopy_seerr_rating_imdb, it)) }
	}.joinToString(separator = " · ")

	private fun buildActions(row: MyDetailsOverviewRow, details: SeerrItemDetails) {
		val context = requireContext()
		val buttonSize = Utils.convertDpToPixel(context, 40)

		if (details.item.status.requestable || hasRequestableSeasons(details)) {
			val requestButton = TextUnderButton.create(
				context,
				R.drawable.ic_add,
				buttonSize,
				2,
				getString(R.string.canopy_seerr_request),
			) {
				onRequestClicked(is4k = false)
			}
			row.addAction(requestButton)

			lifecycleScope.launch {
				val quota = seerrRepository.quota(details.item.mediaType)
				val remaining = quota?.remaining
				if (remaining != null && isAdded) {
					requestButton.setLabel(getString(R.string.canopy_seerr_request_with_quota, remaining))
				}
			}
		}

		lifecycleScope.launch {
			val capabilities = seerrRepository.capabilities()
			val canRequest4k = when (details.item.mediaType) {
				SeerrMediaType.MOVIE -> capabilities.canRequest4kMovie
				SeerrMediaType.TV -> capabilities.canRequest4kTv
			}
			if (canRequest4k && details.item.status4k.requestable && isAdded) {
				row.addAction(
					TextUnderButton.create(context, R.drawable.ic_add, buttonSize, 2, getString(R.string.canopy_seerr_request_4k)) {
						onRequestClicked(is4k = true)
					},
				)
			}
		}
	}

	private fun hasRequestableSeasons(details: SeerrItemDetails): Boolean =
		details.item.mediaType == SeerrMediaType.TV && details.seasons.any { it.status.requestable }

	private fun onRequestClicked(is4k: Boolean) {
		val details = details ?: return
		if (requestInFlight) return

		when (details.item.mediaType) {
			SeerrMediaType.MOVIE -> submit { seerrRepository.submitRequest(details.item, is4k) }
			SeerrMediaType.TV -> lifecycleScope.launch {
				// Season selection is only offered when the Seerr server allows
				// partial series requests; otherwise request the whole series.
				if (seerrRepository.partialRequestsEnabled() && isAdded) showSeasonPicker(details, is4k)
				else submit { seerrRepository.submitRequest(details.item, is4k) }
			}
		}
	}

	private fun showSeasonPicker(details: SeerrItemDetails, is4k: Boolean) {
		val requestable = details.seasons.filter { it.status.requestable }
		if (requestable.isEmpty()) {
			// 4K season state isn't tracked per season here; request everything.
			if (is4k) submit { seerrRepository.submitRequest(details.item, true) }
			else Toast.makeText(requireContext(), R.string.canopy_seerr_no_requestable_seasons, Toast.LENGTH_LONG).show()
			return
		}

		val checked = BooleanArray(requestable.size) { true }
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.canopy_seerr_request_seasons_title)
			.setMultiChoiceItems(
				requestable.map { it.pickerLabel() }.toTypedArray(),
				checked,
			) { _, index, isChecked -> checked[index] = isChecked }
			.setPositiveButton(R.string.canopy_seerr_request) { _, _ ->
				val seasons = requestable.filterIndexed { index, _ -> checked[index] }.map(SeerrSeason::number)
				if (seasons.isEmpty()) {
					Toast.makeText(requireContext(), R.string.canopy_seerr_no_requestable_seasons, Toast.LENGTH_LONG).show()
				} else {
					submit { seerrRepository.submitSeasonRequest(details.item.tmdbId, seasons, is4k) }
				}
			}
			.setNegativeButton(R.string.btn_cancel, null)
			.show()
	}

	private fun SeerrSeason.pickerLabel(): String = when (val count = episodeCount) {
		null -> name
		else -> getString(R.string.canopy_seerr_season_episodes, name, count)
	}

	private fun submit(request: suspend () -> SeerrRequestOutcome) {
		requestInFlight = true
		lifecycleScope.launch {
			val outcome = request()
			requestInFlight = false
			if (!isAdded) return@launch

			val message = when (outcome) {
				SeerrRequestOutcome.Submitted -> getString(R.string.canopy_seerr_request_submitted)
				SeerrRequestOutcome.AlreadyRequested -> getString(R.string.canopy_seerr_request_already)
				is SeerrRequestOutcome.Failed -> outcome.message ?: getString(R.string.canopy_seerr_request_failed)
			}
			Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

			if (outcome !is SeerrRequestOutcome.Failed) refreshDetails()
		}
	}

	private fun refreshDetails() {
		val current = details ?: return
		lifecycleScope.launch {
			val reloaded = seerrRepository.details(current.item.mediaType, current.item.tmdbId)
			if (!isAdded || reloaded == null) return@launch
			details = reloaded
			showDetails(reloaded)
		}
	}

	private fun SeerrItemDetails.toDisplayItem(): BaseItemDto = BaseItemDto(
		id = UUID.nameUUIDFromBytes("seerr:${item.mediaType.wireValue}:${item.tmdbId}".toByteArray()),
		type = when (item.mediaType) {
			SeerrMediaType.MOVIE -> BaseItemKind.MOVIE
			SeerrMediaType.TV -> BaseItemKind.SERIES
		},
		mediaType = MediaType.UNKNOWN,
		name = item.title,
		overview = overview,
		genres = genres,
		productionYear = item.year,
		communityRating = communityRating,
		runTimeTicks = runtimeMinutes?.let { it * TICKS_PER_MINUTE },
	)
}
