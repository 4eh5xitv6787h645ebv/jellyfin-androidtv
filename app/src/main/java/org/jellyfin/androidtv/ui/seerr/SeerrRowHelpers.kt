package org.jellyfin.androidtv.ui.seerr

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrBrowseMoreItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrDiscoverItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrEntry
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrGenreItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrPersonItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.search.SeerrCardPresenter
import org.jellyfin.androidtv.util.dp

/** Builds a native list row of Seerr entries. */
internal fun seerrListRow(header: String, entries: List<SeerrEntry>): ListRow = ListRow(
	HeaderItem(header),
	ArrayObjectAdapter(SeerrCardPresenter()).apply { entries.forEach(::add) },
)

/**
 * Routes a click on any Seerr entry to its native destination. Media already
 * in the library opens the regular Jellyfin details screen; everything else
 * opens the matching Seerr screen. Returns false for non-Seerr items.
 */
internal fun NavigationRepository.navigateToSeerrEntry(entry: Any?): Boolean = when (entry) {
	is SeerrDiscoverItem -> {
		val libraryId = entry.jellyfinMediaId
		if (libraryId != null) navigate(Destinations.itemDetails(libraryId))
		else navigate(Destinations.seerrItem(entry.tmdbId, entry.mediaType.wireValue))
		true
	}

	is SeerrPersonItem -> {
		navigate(Destinations.seerrPerson(entry.personId))
		true
	}

	is SeerrGenreItem -> {
		navigate(Destinations.seerrGenre(entry.genreId, entry.mediaType.wireValue, entry.name))
		true
	}

	SeerrBrowseMoreItem -> {
		navigate(Destinations.seerrDiscover)
		true
	}

	else -> false
}

/** Non-focusable single-line status text row, used for empty states. */
internal data class SeerrStatusText(val text: String)

internal class SeerrStatusTextPresenter : Presenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(
		TextView(parent.context).apply {
			layoutParams = ViewGroup.LayoutParams(STATUS_WIDTH_DP.dp(context), STATUS_HEIGHT_DP.dp(context))
			isFocusable = false
			isFocusableInTouchMode = false
			isClickable = false
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
			setTextColor(Color.WHITE)
			gravity = Gravity.CENTER_VERTICAL
			textSize = STATUS_TEXT_SIZE_SP
		},
	)

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		val status = item as? SeerrStatusText ?: return
		(viewHolder.view as? TextView)?.apply {
			text = status.text
			contentDescription = status.text
		}
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit

	companion object {
		private const val STATUS_WIDTH_DP = 500
		private const val STATUS_HEIGHT_DP = 80
		private const val STATUS_TEXT_SIZE_SP = 18f
	}
}

internal fun seerrStatusRow(header: String, text: String): ListRow = ListRow(
	HeaderItem(header),
	ArrayObjectAdapter(SeerrStatusTextPresenter()).apply { add(SeerrStatusText(text)) },
)

/** Non-compose status label, null when the item is simply not requested. */
@StringRes
internal fun seerrStatusLabelRes(status: SeerrMediaStatus): Int? = when (status) {
	SeerrMediaStatus.NOT_REQUESTED -> null
	SeerrMediaStatus.PENDING -> R.string.canopy_seerr_status_pending
	SeerrMediaStatus.PROCESSING -> R.string.canopy_seerr_status_processing
	SeerrMediaStatus.PARTIALLY_AVAILABLE -> R.string.canopy_seerr_status_partially_available
	SeerrMediaStatus.AVAILABLE -> R.string.canopy_seerr_status_available
	SeerrMediaStatus.BLOCKED -> R.string.canopy_seerr_status_blocked
}
