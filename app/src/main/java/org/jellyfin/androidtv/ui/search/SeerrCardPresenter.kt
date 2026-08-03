package org.jellyfin.androidtv.ui.search

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrBrowseMoreItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrDiscoverItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrEntry
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrGenreItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrPersonItem
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.getActivity

/**
 * Renders Seerr entries (media, people, genres and the browse tile) with the
 * same card composition the standard
 * [org.jellyfin.androidtv.ui.presentation.CardPresenter] uses for library
 * items, so Seerr rows are visually indistinguishable from native rows.
 */
internal class SeerrCardPresenter(
	/**
	 * Invoked on long-press (or MENU) over a card, mirroring how native cards
	 * open their context actions. Returns true when handled.
	 */
	private val onLongPress: ((SeerrEntry) -> Boolean)? = null,
) : Presenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val holder = SeerrCardViewHolder(
			ComposeView(parent.context).apply {
				setParentCompositionContext(parent.findViewTreeCompositionContext())
				setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
				setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
				isFocusable = true
				isFocusableInTouchMode = true
			},
		)

		holder.view.setOnLongClickListener {
			val entry = holder.entry
			when {
				entry != null && onLongPress?.invoke(entry) == true -> true
				else -> holder.view.context.getActivity()
					?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) ?: false
			}
		}

		return holder
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is SeerrCardViewHolder) return
		if (item !is SeerrEntry) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is SeerrCardViewHolder) return

		viewHolder.unbind()
	}

	private class SeerrCardViewHolder(composeView: ComposeView) : ViewHolder(composeView) {
		val entry: SeerrEntry? get() = _item.value
		private val _item = MutableStateFlow<SeerrEntry?>(null)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.setContent {
				val item by _item.collectAsState()
				val focused by _focused.collectAsState()

				SeerrCardContent(
					entry = item,
					focused = focused,
				)
			}

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = { _, focused -> _focused.value = focused }
		}

		fun bind(item: SeerrEntry) {
			_item.value = item
			_focused.value = view.isFocused
		}

		fun unbind() {
			_item.value = null
			_focused.value = false
		}
	}
}

@Composable
private fun SeerrCardContent(
	entry: SeerrEntry?,
	focused: Boolean,
) {
	when (entry) {
		null -> Unit

		is SeerrDiscoverItem -> SeerrPreviewCard(
			imageUrl = entry.posterUrl,
			fallbackIconRes = when (entry.mediaType) {
				SeerrMediaType.MOVIE -> R.drawable.ic_clapperboard
				SeerrMediaType.TV -> R.drawable.ic_tv
			},
			aspectRatio = ImageHelper.ASPECT_RATIO_2_3.toFloat(),
			title = entry.title,
			subtitle = seerrMediaSubtitle(entry),
			focused = focused,
		)

		is SeerrPersonItem -> SeerrPreviewCard(
			imageUrl = entry.profileUrl,
			fallbackIconRes = R.drawable.ic_user,
			aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
			title = entry.name,
			subtitle = entry.role,
			focused = focused,
		)

		is SeerrGenreItem -> SeerrPreviewCard(
			imageUrl = entry.backdropUrl,
			fallbackIconRes = R.drawable.ic_grid,
			aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
			title = entry.name,
			subtitle = stringResource(
				when (entry.mediaType) {
					SeerrMediaType.MOVIE -> R.string.canopy_seerr_movies
					SeerrMediaType.TV -> R.string.canopy_seerr_series_group
				},
			),
			focused = focused,
		)

		SeerrBrowseMoreItem -> SeerrPreviewCard(
			imageUrl = null,
			fallbackIconRes = R.drawable.ic_search,
			aspectRatio = ImageHelper.ASPECT_RATIO_2_3.toFloat(),
			title = stringResource(R.string.canopy_seerr_browse_more),
			subtitle = stringResource(R.string.canopy_seerr_discover),
			focused = focused,
		)
	}
}

@Composable
private fun SeerrPreviewCard(
	imageUrl: String?,
	fallbackIconRes: Int,
	aspectRatio: Float,
	title: String,
	subtitle: String?,
	focused: Boolean,
) {
	// Match CardPresenter: landscape cards use a smaller base height
	val baseHeight = if (aspectRatio > 1f) 130.dp else 150.dp
	val size = DpSize(baseHeight * aspectRatio, baseHeight)

	val focusModifier = if (focused) Modifier.basicMarquee(
		iterations = Int.MAX_VALUE,
		initialDelayMillis = 0,
	) else Modifier

	ItemPreview(
		card = {
			ItemCard(
				image = {
					if (imageUrl != null) {
						AsyncImage(
							url = imageUrl,
							aspectRatio = aspectRatio,
							modifier = Modifier.fillMaxSize(),
						)
					} else {
						FallbackIcon(fallbackIconRes)
					}
				},
				modifier = Modifier.size(size),
			)
		},
		title = {
			Text(
				text = title,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				textAlign = TextAlign.Center,
				modifier = Modifier.then(focusModifier),
			)
		},
		subtitle = subtitle?.let { text ->
			{
				Text(
					text = text,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					textAlign = TextAlign.Center,
					modifier = Modifier.then(focusModifier),
				)
			}
		},
	)
}

@Composable
private fun BoxScope.FallbackIcon(iconRes: Int) {
	Image(
		painter = painterResource(iconRes),
		contentDescription = null,
		modifier = Modifier
			.fillMaxSize(0.4f)
			.align(Alignment.Center),
	)
}

@Composable
private fun seerrMediaSubtitle(item: SeerrDiscoverItem): String {
	val kind = stringResource(
		when (item.mediaType) {
			SeerrMediaType.MOVIE -> R.string.canopy_seerr_movie
			SeerrMediaType.TV -> R.string.canopy_seerr_series
		},
	)
	return listOfNotNull(
		item.year?.toString() ?: kind,
		seerrStatusLabel(item.status),
	).joinToString(separator = " · ")
}

@Composable
internal fun seerrStatusLabel(status: SeerrMediaStatus): String? = when (status) {
	SeerrMediaStatus.NOT_REQUESTED -> null
	SeerrMediaStatus.PENDING -> stringResource(R.string.canopy_seerr_status_pending)
	SeerrMediaStatus.PROCESSING -> stringResource(R.string.canopy_seerr_status_processing)
	SeerrMediaStatus.PARTIALLY_AVAILABLE -> stringResource(R.string.canopy_seerr_status_partially_available)
	SeerrMediaStatus.AVAILABLE -> stringResource(R.string.canopy_seerr_status_available)
	SeerrMediaStatus.BLOCKED -> stringResource(R.string.canopy_seerr_status_blocked)
}
