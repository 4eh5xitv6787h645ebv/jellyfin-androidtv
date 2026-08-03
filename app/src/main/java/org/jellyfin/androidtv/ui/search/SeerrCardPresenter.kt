package org.jellyfin.androidtv.ui.search

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
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
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrDiscoverItem
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrMediaType
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.getActivity

/**
 * Renders Seerr discover results with the same card composition the standard
 * [org.jellyfin.androidtv.ui.presentation.CardPresenter] uses for library
 * items, so the row is visually indistinguishable from native search rows.
 */
class SeerrCardPresenter : Presenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true

			setOnLongClickListener {
				context.getActivity()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) ?: false
			}
		}

		return SeerrCardViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is SeerrCardViewHolder) return
		if (item !is SeerrDiscoverItem) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is SeerrCardViewHolder) return

		viewHolder.unbind()
	}

	private class SeerrCardViewHolder(composeView: ComposeView) : ViewHolder(composeView) {
		private val _item = MutableStateFlow<SeerrDiscoverItem?>(null)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.setContent {
				val item by _item.collectAsState()
				val focused by _focused.collectAsState()

				SeerrCardContent(
					item = item,
					focused = focused,
				)
			}

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = { _, focused -> _focused.value = focused }
		}

		fun bind(item: SeerrDiscoverItem) {
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
	item: SeerrDiscoverItem?,
	focused: Boolean,
) {
	if (item == null) return

	val aspectRatio = ImageHelper.ASPECT_RATIO_2_3.toFloat()
	val size = DpSize(150.dp * aspectRatio, 150.dp)

	val focusModifier = if (focused) Modifier.basicMarquee(
		iterations = Int.MAX_VALUE,
		initialDelayMillis = 0,
	) else Modifier

	ItemPreview(
		card = {
			ItemCard(
				image = {
					AsyncImage(
						url = item.posterUrl,
						aspectRatio = aspectRatio,
						modifier = Modifier.fillMaxSize(),
					)
				},
				modifier = Modifier.size(size),
			)
		},
		title = {
			Text(
				text = item.title,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				textAlign = TextAlign.Center,
				modifier = Modifier.then(focusModifier),
			)
		},
		subtitle = {
			Text(
				text = seerrCardSubtitle(item),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				textAlign = TextAlign.Center,
				modifier = Modifier.then(focusModifier),
			)
		},
	)
}

@Composable
private fun seerrCardSubtitle(item: SeerrDiscoverItem): String {
	val kind = stringResource(
		when (item.mediaType) {
			SeerrMediaType.MOVIE -> R.string.canopy_seerr_movie
			SeerrMediaType.TV -> R.string.canopy_seerr_series
		},
	)
	val parts = listOfNotNull(
		item.year?.toString() ?: kind,
		seerrStatusLabel(item.status),
	)
	return parts.joinToString(separator = " · ")
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
