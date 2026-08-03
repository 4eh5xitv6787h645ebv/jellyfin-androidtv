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
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrCapabilities
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrDiscoverItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrItemDetails
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrQuotaBucket
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRatings
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRepository
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrRequestOutcome
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrSeason
import org.jellyfin.androidtv.ui.TextUnderButton
import org.jellyfin.androidtv.ui.canopy.CanopyQuickActions
import org.jellyfin.androidtv.ui.itemdetail.MyDetailsOverviewRow
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.MyDetailsOverviewRowPresenter
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import java.util.UUID

/**
 * Native details screen for a Seerr title that is not (yet) in the library.
 * Mirrors the layout of [org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment]:
 * a details overview row with request actions, followed by cast, collection,
 * similar, recommendation and more-from rows served by the Canopy Seerr proxy.
 *
 * The details row renders as soon as the item details arrive; ratings, 4K
 * capability and quota enrich the same row afterwards via an in-place rebind
 * ([MyDetailsOverviewRowPresenter.viewHolder]) so the row is never removed
 * while it may hold focus.
 */
class SeerrItemFragment : Fragment() {
	companion object {
		const val ARG_TMDB_ID = "TmdbId"
		const val ARG_MEDIA_TYPE = "MediaType"

		private const val TICKS_PER_MINUTE = 600_000_000L
		private const val BUTTON_SIZE_DP = 40
	}

	private val seerrRepository by inject<SeerrRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val markdownRenderer by inject<MarkdownRenderer>()
	private val apiClient by inject<ApiClient>()
	private val quickActions by lazy { CanopyQuickActions(this, apiClient, onChanged = ::refreshDetails) }

	private var rowsFragment: RowsSupportFragment? = null
	private var rowsAdapter: MutableObjectAdapter<Row>? = null
	private var dorPresenter: MyDetailsOverviewRowPresenter? = null
	private var detailsRow: MyDetailsOverviewRow? = null
	private var details: SeerrItemDetails? = null
	private var ratings: SeerrRatings? = null
	private var capabilities: SeerrCapabilities? = null
	private var quota: SeerrQuotaBucket? = null
	private var requestButton: TextUnderButton? = null
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
		quickActions.stop()
		requestButton = null
		rowsFragment = null
		rowsAdapter = null
		dorPresenter = null
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
			showDetails(loaded)
			loadAdditionalRows(loaded)
			enrichDetails(loaded)
		}
	}

	/** First paint: the details row renders before any secondary requests. */
	private fun showDetails(details: SeerrItemDetails) {
		val fragment = rowsFragment ?: return

		val presenter = dorPresenter ?: MyDetailsOverviewRowPresenter(markdownRenderer).also { dorPresenter = it }
		val adapter = rowsAdapter ?: MutableObjectAdapter<Row>(
			ClassPresenterSelector().apply {
				addClassPresenter(MyDetailsOverviewRow::class.java, presenter)
				addClassPresenter(ListRow::class.java, CustomListRowPresenter(Utils.convertDpToPixel(requireContext(), 10)))
			},
		).also {
			rowsAdapter = it
			fragment.adapter = it
		}

		val row = MyDetailsOverviewRow(
			item = details.toDisplayItem(),
			imageDrawable = details.item.posterUrl,
			summary = details.overview,
		)
		applyRowState(row, details)
		detailsRow = row
		adapter.add(0, row)

		// The previous screen's focused view is detached after the fragment
		// replace; leanback will not reclaim focus for content that arrived
		// asynchronously, and a d-pad event on a detached focus crashes
		// FocusFinder (#4). Claim focus explicitly once content exists.
		fragment.view?.post { fragment.view?.requestFocus() }
	}

	/** Ratings, 4K capability and quota arrive late and rebind the row in place. */
	private fun enrichDetails(details: SeerrItemDetails) {
		lifecycleScope.launch {
			ratings = seerrRepository.ratings(details.item).takeUnless { it.isEmpty }
			capabilities = seerrRepository.capabilities()
			if (requestable(details)) quota = seerrRepository.quota(details.item.mediaType)
			if (!isAdded) return@launch

			rebindDetailsRow()
		}
	}

	/** Applies status, ratings and actions to the row from current state. */
	private fun applyRowState(row: MyDetailsOverviewRow, details: SeerrItemDetails) {
		row.infoItem1 = seerrStatusLabelRes(details.item.status)?.let { statusRes ->
			InfoItem(getString(R.string.canopy_seerr_status), getString(statusRes))
		}
		row.infoItem2 = ratings?.let { InfoItem(getString(R.string.canopy_seerr_ratings), it.displayText()) }

		val context = requireContext()
		val buttonSize = Utils.convertDpToPixel(context, BUTTON_SIZE_DP)
		row.clearActions()

		if (requestable(details)) {
			val label = quota?.remaining?.let { getString(R.string.canopy_seerr_request_with_quota, it) }
				?: getString(R.string.canopy_seerr_request)
			// Reuse the existing view so a late quota only relabels the button
			// instead of swapping it out from under the user's focus.
			val button = requestButton
				?: TextUnderButton.create(context, R.drawable.ic_add, buttonSize, 2, label) {
					onRequestClicked(is4k = false)
				}.also { requestButton = it }
			button.setLabel(label)
			row.addAction(button)
		}

		val canRequest4k = when (details.item.mediaType) {
			SeerrMediaType.MOVIE -> capabilities?.canRequest4kMovie == true
			SeerrMediaType.TV -> capabilities?.canRequest4kTv == true
		}
		if (canRequest4k && details.item.status4k.requestable) {
			row.addAction(
				TextUnderButton.create(context, R.drawable.ic_add, buttonSize, 2, getString(R.string.canopy_seerr_request_4k)) {
					onRequestClicked(is4k = true)
				},
			)
		}

		details.item.jellyfinMediaId?.let { libraryId ->
			row.addAction(
				TextUnderButton.create(context, R.drawable.ic_folder, buttonSize, 2, getString(R.string.canopy_seerr_open_in_library)) {
					navigationRepository.navigate(Destinations.itemDetails(libraryId))
				},
			)
			// Spoiler Guard / Hidden Content apply to the library item, so they
			// are offered here for titles the library already has.
			row.addAction(
				TextUnderButton.create(context, R.drawable.ic_masks, buttonSize, 2, getString(R.string.canopy_manage)) {
					quickActions.show(libraryId)
				},
			)
		}
	}

	/**
	 * Applies late-arriving state (ratings, 4K capability, quota) without
	 * rebuilding the row: [MyDetailsOverviewRowPresenter.ViewHolder.setItem]
	 * clears and re-adds every button, which flashes the action row and moves
	 * buttons under the user. Info columns are updated in place and only
	 * genuinely new buttons are appended.
	 */
	private fun rebindDetailsRow() {
		val row = detailsRow ?: return
		val details = details ?: return
		val holder = dorPresenter?.viewHolder
		val existing = row.actions.toList()

		applyRowState(row, details)

		if (holder == null) return
		holder.setInfoItems(row)
		existing.filterNot { it in row.actions }.forEach(holder::removeActionView)
		row.actions.forEachIndexed { index, button ->
			if (button !in existing) holder.addActionView(button, index)
		}
	}

	private fun requestable(details: SeerrItemDetails): Boolean =
		details.item.status.requestable || hasRequestableSeasons(details)

	private fun loadAdditionalRows(details: SeerrItemDetails) {
		val adapter = rowsAdapter ?: return
		val longPress = canopyLongPress(quickActions)

		if (details.cast.isNotEmpty()) {
			adapter.add(seerrListRow(getString(R.string.canopy_seerr_cast), details.cast, longPress))
		}

		lifecycleScope.launch {
			val self = details.item
			fun SeerrDiscoverItem.isSelf() = tmdbId == self.tmdbId && mediaType == self.mediaType

			// Load everything first, then append rows in one pass: incremental
			// adapter mutations while the user is already navigating invite
			// leanback focus races.
			val rows = buildList {
				details.collection?.let { collection ->
					val parts = seerrRepository.collectionParts(collection.id).filterNot { it.isSelf() }
					if (parts.isNotEmpty()) add(seerrListRow(getString(R.string.canopy_seerr_part_of, collection.name), parts))
				}

				val similar = seerrRepository.similar(self)
				if (similar.isNotEmpty()) add(seerrListRow(getString(R.string.canopy_seerr_similar), similar, longPress))

				val recommended = seerrRepository.recommendations(self)
				if (recommended.isNotEmpty()) add(seerrListRow(getString(R.string.canopy_seerr_recommended), recommended, longPress))

				val moreFrom = details.studio ?: details.network
				if (moreFrom != null) {
					val entries = when {
						details.studio != null -> seerrRepository.moreFromStudio(moreFrom.id)
						else -> seerrRepository.moreFromNetwork(moreFrom.id)
					}.filterNot { it.isSelf() }
						.sortedByDescending { it.popularity ?: Double.MIN_VALUE }
					// Split by kind so a studio's films and series are separate
					// rows, each most-popular first.
					val movies = entries.filter { it.mediaType == SeerrMediaType.MOVIE }
					val series = entries.filter { it.mediaType == SeerrMediaType.TV }
					if (movies.isNotEmpty()) {
						add(
							seerrListRow(
								getString(R.string.canopy_seerr_more_from_kind, moreFrom.name, getString(R.string.canopy_seerr_movies)),
								movies,
							),
						)
					}
					if (series.isNotEmpty()) {
						add(
							seerrListRow(
								getString(R.string.canopy_seerr_more_from_kind, moreFrom.name, getString(R.string.canopy_seerr_series_group)),
								series,
							),
						)
					}
				}
			}

			if (!isAdded) return@launch
			rows.forEach(adapter::add)
		}
	}

	private fun SeerrRatings.displayText(): String = buildList {
		rtCritics?.let { add(getString(R.string.canopy_seerr_rating_critics, it)) }
		rtAudience?.let { add(getString(R.string.canopy_seerr_rating_audience, it)) }
		imdb?.let { add(getString(R.string.canopy_seerr_rating_imdb, it)) }
	}.joinToString(separator = " · ")

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
				val partialRequests = seerrRepository.partialRequestsEnabled()
				if (!isAdded) return@launch
				if (partialRequests) showSeasonPicker(details, is4k)
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

	/** Reloads the item and rebinds the existing row in place; the row is
	 * never removed, so focus stays where the user left it. */
	private fun refreshDetails() {
		val current = details ?: return
		lifecycleScope.launch {
			val reloaded = seerrRepository.details(current.item.mediaType, current.item.tmdbId)
			if (!isAdded || reloaded == null) return@launch
			details = reloaded
			if (requestable(reloaded)) quota = seerrRepository.quota(reloaded.item.mediaType)
			if (!isAdded) return@launch
			rebindDetailsRow()
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
