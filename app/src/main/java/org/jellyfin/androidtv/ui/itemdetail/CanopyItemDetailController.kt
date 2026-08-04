package org.jellyfin.androidtv.ui.itemdetail

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.integration.canopy.CanopyActionLayout
import org.jellyfin.androidtv.integration.canopy.CanopyImageCache
import org.jellyfin.androidtv.integration.canopy.CanopyClient
import org.jellyfin.androidtv.integration.canopy.CanopyContribution
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailCoordinator
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailEvent
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailInvalidation
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailSurface
import org.jellyfin.androidtv.integration.canopy.CanopyPreparedForm
import org.jellyfin.androidtv.integration.canopy.CanopyRefreshTarget
import org.jellyfin.androidtv.integration.canopy.CanopySemanticIcon
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.CanopyActionsPlacement
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.TextUnderButton
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.dp
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.java.KoinJavaComponent
import java.time.Instant
import java.util.UUID

internal class CanopyItemDetailController(
	private val fragment: FullDetailsFragment,
	apiClient: ApiClient,
	private val dataRefreshService: DataRefreshService,
	private val userPreferences: UserPreferences? = null,
) {
	private val coordinator = CanopyItemDetailCoordinator(
		scope = fragment.lifecycleScope,
		gateway = CanopyClient(apiClient),
		onEvent = ::handleEvent,
	)
	private val forms = CanopyFormPresenter(fragment)
	private val imageCache: CanopyImageCache by lazy {
		KoinJavaComponent.get(CanopyImageCache::class.java)
	}
	private var dialog: AlertDialog? = null
	private var overflowActions = emptyList<CanopyContribution.Action>()

	init {
		fragment.lifecycleScope.launch {
			fragment.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				CanopyItemDetailInvalidation(apiClient.webSocket).signals().collect {
					coordinator.requestSurfaceRefresh()
				}
			}
		}
	}

	fun bind(itemId: UUID) = coordinator.bind(itemId)

	fun stop() {
		coordinator.stop()
		dismissDialog()
	}

	fun handleClick(item: Any?): Boolean = when (item) {
		is CanopyActionButton -> {
			coordinator.prepare(item.actionId)
			true
		}
		is CanopyOverflowButton -> {
			showOverflow()
			true
		}
		else -> false
	}

	private fun handleEvent(event: CanopyItemDetailEvent) {
		when (event) {
			is CanopyItemDetailEvent.Surface -> showSurface(event.value)
			is CanopyItemDetailEvent.Form -> forms.showForm(
				prepared = event.value,
				onSubmit = coordinator::submit,
				onDismissed = coordinator::dismissForm,
			)
			CanopyItemDetailEvent.InvalidateForm -> dismissDialog()
			CanopyItemDetailEvent.Submitting -> forms.setSubmitting()
			is CanopyItemDetailEvent.InvalidForm -> forms.setInvalid()
			is CanopyItemDetailEvent.Message -> {
				val terminal = event.fallback != CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE
				if (terminal) dismissDialog() else forms.setFailed()
				val context = fragment.context ?: return
				val fallback = when (event.fallback) {
					CanopyItemDetailEvent.Message.Fallback.ACTION_SUCCEEDED -> R.string.canopy_action_succeeded
					CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE -> R.string.canopy_action_unavailable
					CanopyItemDetailEvent.Message.Fallback.ACTION_EXPIRED -> R.string.canopy_action_expired
				}
				Toast.makeText(context, event.text ?: fragment.getString(fallback), Toast.LENGTH_LONG).show()
			}
			is CanopyItemDetailEvent.Refresh -> refresh(event)
		}
	}

	private fun showSurface(value: CanopyItemDetailSurface?) {
		val placement = userPreferences?.get(UserPreferences.canopyActionsPlacement) ?: CanopyActionsPlacement.ROW

		if (value == null || (value.actions.isEmpty() && value.statuses.isEmpty())) {
			overflowActions = emptyList()
			fragment.setCanopyActionRow(null)
			fragment.setCanopyActions(emptyList(), false)
			return
		}

		when (placement) {
			CanopyActionsPlacement.BUTTONS -> {
				fragment.setCanopyActionRow(null)
				showInlineButtons(value)
				return
			}

			CanopyActionsPlacement.OTHER_OPTIONS -> {
				fragment.setCanopyActionRow(null)
				showMenuActions(value)
				return
			}

			CanopyActionsPlacement.ROW -> fragment.setCanopyActions(emptyList(), false)
		}

		val layout = CanopyActionLayout.create(value.actions)
		val statusText = value.statuses.joinToString(separator = " · ") { it.label }
		val heading = if (statusText.isEmpty()) {
			fragment.getString(R.string.canopy_actions)
		} else {
			fragment.getString(R.string.canopy_actions_with_status, statusText)
		}
		if (value.actions.isEmpty()) {
			overflowActions = emptyList()
			val statusAdapter = ArrayObjectAdapter(CanopyStatusPresenter()).apply {
				add(CanopyStatusText(statusText))
			}
			fragment.setCanopyActionRow(ListRow(HeaderItem(heading), statusAdapter))
			return
		}

		val adapter = ArrayObjectAdapter(GridButtonPresenter(width = 155, imageHeight = 110))
		layout.direct.forEachIndexed { index, action ->
			adapter.add(
				CanopyActionButton(
					id = canopyActionTileId(index),
					text = action.label,
					imageRes = action.icon.drawable,
					actionId = action.id,
					contentDescription = describedLabel(action.label, action.description),
				),
			)
		}
		overflowActions = layout.overflow
		if (overflowActions.isNotEmpty()) {
			adapter.add(CanopyOverflowButton(fragment.getString(R.string.lbl_other_options)))
		}

		fragment.setCanopyActionRow(ListRow(HeaderItem(heading), adapter))
	}

	/**
	 * Renders actions as native detail buttons next to Play/Watched. Status
	 * contributions carry no interaction; their state is visible in the
	 * action dialogs, so they are only exposed via accessibility here.
	 */
	/**
	 * Hands the actions to the details screen, which places as many as fit in
	 * the button row and sends the rest to "Other options".
	 */
	private fun showInlineButtons(value: CanopyItemDetailSurface) {
		overflowActions = emptyList()
		fragment.setCanopyActions(canopyActions(value), false)
	}

	/** Renders every action as an entry in the "Other options" popup menu. */
	private fun showMenuActions(value: CanopyItemDetailSurface) {
		overflowActions = emptyList()
		fragment.setCanopyActions(canopyActions(value), true)
	}

	private fun canopyActions(value: CanopyItemDetailSurface): List<CanopyMenuAction> {
		val statusDescription = value.statuses.joinToString(separator = " · ") { it.label }
		return value.actions.map { action ->
			CanopyMenuAction(
				label = action.label,
				iconRes = action.icon.drawable,
				contentDescription = describedLabel(
					describedLabel(action.label, action.description),
					statusDescription.takeIf { it.isNotEmpty() },
				),
			) { coordinator.prepare(action.id) }
		}
	}

	private fun showOverflow() {
		if (overflowActions.isEmpty() || !fragment.isAdded) return
		dialog?.dismiss()
		val created = AlertDialog.Builder(fragment.requireContext())
			.setTitle(R.string.lbl_other_options)
			.setItems(overflowActions.map { it.label }.toTypedArray()) { _, index ->
				overflowActions.getOrNull(index)?.let { coordinator.prepare(it.id) }
			}
			.setNegativeButton(R.string.btn_cancel, null)
			.create()
		created.setOnDismissListener {
			if (dialog === created) dialog = null
		}
		dialog = created
		created.show()
	}

	private fun dismissDialog() {
		dialog?.dismiss()
		dialog = null
		forms.dismiss()
	}

	private fun refresh(event: CanopyItemDetailEvent.Refresh) {
		dispatchCanopyRefresh(
			targets = event.targets,
			onJellyfinItem = {
				// Spoiler Guard blurs artwork behind the same image URL and
				// tag, so cached bitmaps would keep showing the unprotected
				// image until evicted.
				fragment.lifecycleScope.launch { imageCache.invalidate() }
				dataRefreshService.lastLibraryChange = Instant.now()
				fragment.refreshCanopyItem(event.itemId)
			},
			onItemDetailSurface = coordinator::refreshSurface,
		)
	}

}

private class CanopyActionButton(
	id: Int,
	text: String,
	imageRes: Int,
	val actionId: String,
	contentDescription: String,
) : GridButton(id, text, imageRes, contentDescription)

private class CanopyOverflowButton(text: String) : GridButton(Int.MIN_VALUE, text, R.drawable.ic_more)

internal data class CanopyStatusText(
	val text: String,
	val contentDescription: String = text,
) {
	val focusable: Boolean get() = false
	val consumesClick: Boolean get() = false
}

private class CanopyStatusPresenter : Presenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(
		TextView(parent.context).apply {
			layoutParams = ViewGroup.LayoutParams(400.dp(context), 80.dp(context))
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
		val status = item as? CanopyStatusText ?: return
		(viewHolder.view as? TextView)?.apply {
			text = status.text
			contentDescription = status.contentDescription
			isFocusable = status.focusable
			isFocusableInTouchMode = status.focusable
			isClickable = status.consumesClick
		}
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}

private val CanopySemanticIcon.drawable: Int
	get() = when (this) {
		CanopySemanticIcon.SHIELD, CanopySemanticIcon.VISIBILITY_OFF -> R.drawable.ic_masks
		CanopySemanticIcon.ADD -> R.drawable.ic_add
		CanopySemanticIcon.CHECK -> R.drawable.ic_check
		CanopySemanticIcon.SETTINGS -> R.drawable.ic_settings
		CanopySemanticIcon.DEFAULT -> R.drawable.ic_jellyfin
	}

internal fun dispatchCanopyRefresh(
	targets: Set<CanopyRefreshTarget>,
	onJellyfinItem: () -> Unit,
	onItemDetailSurface: () -> Unit,
) {
	if (CanopyRefreshTarget.JELLYFIN_ITEM in targets) onJellyfinItem()
	if (CanopyRefreshTarget.ITEM_DETAIL_SURFACE in targets) onItemDetailSurface()
}

/**
 * A Canopy action the details screen can present either as a button or as an
 * entry in the overflow menu. The screen decides which, because only it knows
 * how much room the button row has left.
 */
internal class CanopyMenuAction(
	val label: String,
	@androidx.annotation.DrawableRes val iconRes: Int,
	val contentDescription: String,
	private val onSelected: () -> Unit,
) {
	fun run() = onSelected()
}

private const val CANOPY_BUTTON_SIZE_DP = 40
private const val MAX_CANOPY_TILE_IDS = 5
private const val CANOPY_ACTION_ID_BASE = Int.MIN_VALUE + 100
private const val STATUS_TEXT_SIZE_SP = 18f

internal fun canopyActionTileId(index: Int): Int {
	require(index in 0 until MAX_CANOPY_TILE_IDS)
	return CANOPY_ACTION_ID_BASE + index
}
