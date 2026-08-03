package org.jellyfin.androidtv.ui.itemdetail

import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
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
import org.jellyfin.androidtv.integration.canopy.CanopyActionForm
import org.jellyfin.androidtv.integration.canopy.CanopyActionLayout
import org.jellyfin.androidtv.integration.canopy.CanopyClient
import org.jellyfin.androidtv.integration.canopy.CanopyContribution
import org.jellyfin.androidtv.integration.canopy.CanopyField
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailCoordinator
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailEvent
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailInvalidation
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailSurface
import org.jellyfin.androidtv.integration.canopy.CanopyPreparedForm
import org.jellyfin.androidtv.integration.canopy.CanopyRefreshTarget
import org.jellyfin.androidtv.integration.canopy.CanopySemanticIcon
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.util.dp
import org.jellyfin.sdk.api.client.ApiClient
import java.time.Instant
import java.util.UUID

internal class CanopyItemDetailController(
	private val fragment: FullDetailsFragment,
	apiClient: ApiClient,
	private val dataRefreshService: DataRefreshService,
) {
	private val coordinator = CanopyItemDetailCoordinator(
		scope = fragment.lifecycleScope,
		gateway = CanopyClient(apiClient),
		onEvent = ::handleEvent,
	)
	private var dialog: AlertDialog? = null
	private var submitButton: TextView? = null
	private var formContent: View? = null
	private var formInteractionState = CanopyFormInteractionState.EDITING
	private var activeSubmitLabel: String? = null
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
			is CanopyItemDetailEvent.Form -> showForm(event.value)
			CanopyItemDetailEvent.InvalidateForm -> dismissDialog()
			CanopyItemDetailEvent.Submitting -> {
				formInteractionState = formInteractionState.submitting()
				applyCanopyFormInteractionState(
					formContent,
					submitButton,
					formInteractionState,
					activeSubmitLabel,
					fragment.getString(R.string.loading),
					announceLoading = true,
				)
			}
			is CanopyItemDetailEvent.InvalidForm -> {
				applyCanopyFormInteractionState(
					formContent,
					submitButton,
					formInteractionState,
					activeSubmitLabel,
					fragment.getString(R.string.loading),
				)
				val context = fragment.context ?: return
				Toast.makeText(context, R.string.canopy_complete_required_fields, Toast.LENGTH_LONG).show()
			}
			is CanopyItemDetailEvent.Message -> {
				val terminal = event.fallback != CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE
				if (terminal) {
					dismissDialog()
				} else {
					formInteractionState = formInteractionState.failed()
					applyCanopyFormInteractionState(
						formContent,
						submitButton,
						formInteractionState,
						activeSubmitLabel,
						fragment.getString(R.string.loading),
					)
				}
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
		if (value == null || (value.actions.isEmpty() && value.statuses.isEmpty())) {
			overflowActions = emptyList()
			fragment.setCanopyActionRow(null)
			return
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

	private fun showOverflow() {
		if (overflowActions.isEmpty() || !fragment.isAdded) return
		dialog?.dismiss()
		formContent = null
		formInteractionState = CanopyFormInteractionState.EDITING
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

	private fun showForm(prepared: CanopyPreparedForm) {
		if (!fragment.isAdded) {
			coordinator.dismissForm(prepared)
			return
		}
		var form = startForm(prepared)
		activeSubmitLabel = prepared.action.submitLabel
		val fieldViews = mutableMapOf<String, View>()
		val content = LinearLayout(fragment.requireContext()).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(24.dp(context))
		}

		prepared.action.fields.forEach { field ->
			val fieldView = when (field) {
				is CanopyField.Confirmation -> checkboxField(field, form) { checked ->
					form = form.setChecked(field.id, checked)
				}
				is CanopyField.BooleanValue -> checkboxField(field, form) { checked ->
					form = form.setChecked(field.id, checked)
				}
				is CanopyField.SingleSelect -> selectOneField(field, form) { optionId ->
					form = form.selectOne(field.id, optionId)
				}
				is CanopyField.MultiSelect -> selectManyField(field, form) { optionId, checked ->
					form = form.setSelected(field.id, optionId, checked)
				}
			}
			fieldViews[field.id] = fieldView.firstEnabledFocusable() ?: fieldView
			content.addView(fieldView)
		}

		val scroll = ScrollView(fragment.requireContext()).apply { addView(content) }.also { formContent = content }
		val created = AlertDialog.Builder(fragment.requireContext())
			.setTitle(prepared.action.title)
			.setView(scroll)
			.setPositiveButton(prepared.action.submitLabel, null)
			.setNegativeButton(prepared.action.cancelLabel) { _, _ -> }
			.create()
		created.setOnShowListener {
			submitButton = created.getButton(DialogInterface.BUTTON_POSITIVE).apply {
				setOnClickListener {
					val errors = form.validationErrors()
					if (errors.isNotEmpty()) {
						fieldViews[errors.first()]?.requestFocus()
						Toast.makeText(context, R.string.canopy_complete_required_fields, Toast.LENGTH_LONG).show()
					} else {
						coordinator.submit(prepared, form)
					}
				}
			}
			applyCanopyFormInteractionState(
				formContent,
				submitButton,
				formInteractionState,
				activeSubmitLabel,
				fragment.getString(R.string.loading),
			)
			fieldViews.values.firstOrNull()?.requestFocus()
		}
		created.setOnDismissListener {
			if (dialog === created) {
				dialog = null
				submitButton = null
				formContent = null
				formInteractionState = CanopyFormInteractionState.EDITING
				activeSubmitLabel = null
				coordinator.dismissForm(prepared)
			}
		}
		dialog = created
		created.show()
	}

	private fun checkboxField(
		field: CanopyField,
		form: CanopyActionForm,
		onChecked: (Boolean) -> Unit,
	): View = LinearLayout(fragment.requireContext()).apply {
		orientation = LinearLayout.VERTICAL
		addView(CheckBox(context).apply {
			text = requiredLabel(fragment, field)
			isChecked = form.isChecked(field.id)
			contentDescription = describedLabel(requiredLabel(fragment, field), field.description)
			setOnCheckedChangeListener { _, checked -> onChecked(checked) }
		})
		field.description?.let { addView(descriptionView(context, it)) }
	}

	private fun selectOneField(
		field: CanopyField.SingleSelect,
		form: CanopyActionForm,
		onSelected: (String?) -> Unit,
	): View = fieldGroup(field).apply {
		val radioGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
		field.options.forEachIndexed { index, option ->
			radioGroup.addView(RadioButton(context).apply {
				id = View.generateViewId()
				tag = option.id
				text = option.description?.let { "${option.label}\n$it" } ?: option.label
				isEnabled = !option.disabled
				isChecked = form.singleSelection(field.id) == option.id
				contentDescription = text
				if (index == 0 && isChecked) requestFocus()
			})
		}
		radioGroup.setOnCheckedChangeListener { group, checkedId ->
			onSelected(group.findViewById<RadioButton>(checkedId)?.tag as? String)
		}
		addView(radioGroup)
	}

	private fun selectManyField(
		field: CanopyField.MultiSelect,
		form: CanopyActionForm,
		onSelected: (String, Boolean) -> Unit,
	): View = fieldGroup(field).apply {
		field.options.forEach { option ->
			addView(CheckBox(context).apply {
				text = option.description?.let { "${option.label}\n$it" } ?: option.label
				isEnabled = !option.disabled
				isChecked = option.id in form.multiSelection(field.id)
				contentDescription = text
				setOnCheckedChangeListener { _, checked -> onSelected(option.id, checked) }
			})
		}
	}

	private fun fieldGroup(field: CanopyField): LinearLayout = LinearLayout(fragment.requireContext()).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(0, 8.dp(context), 0, 12.dp(context))
		addView(TextView(context).apply {
			text = requiredLabel(fragment, field)
			setTypeface(typeface, Typeface.BOLD)
			contentDescription = describedLabel(requiredLabel(fragment, field), field.description)
		})
		field.description?.let { addView(descriptionView(context, it)) }
	}

	private fun View.firstEnabledFocusable(): View? {
		if (isEnabled && isFocusable) return this
		if (this !is ViewGroup) return null
		return (0 until childCount).firstNotNullOfOrNull { getChildAt(it).firstEnabledFocusable() }
	}

	private fun startForm(prepared: CanopyPreparedForm): CanopyActionForm {
		dialog?.dismiss()
		formInteractionState = CanopyFormInteractionState.EDITING
		return prepared.form
	}

	private fun dismissDialog() {
		dialog?.dismiss()
		dialog = null
		submitButton = null
		formContent = null
		formInteractionState = CanopyFormInteractionState.EDITING
		activeSubmitLabel = null
	}

	private fun refresh(event: CanopyItemDetailEvent.Refresh) {
		dispatchCanopyRefresh(
			targets = event.targets,
			onJellyfinItem = {
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

internal enum class CanopyFormInteractionState(
	val formEditable: Boolean,
	val submitEnabled: Boolean,
) {
	EDITING(formEditable = true, submitEnabled = true),
	SUBMITTING(formEditable = false, submitEnabled = false),
	// Retry is deliberately an exact replay of the first answers and idempotency key.
	// Dismiss and prepare again to edit or obtain a new capability/key.
	RETRY(formEditable = false, submitEnabled = true),
	;

	fun submitting() = if (this == EDITING || this == RETRY) SUBMITTING else this
	fun failed() = if (this == SUBMITTING) RETRY else this
}

private fun applyCanopyFormInteractionState(
	formContent: View?,
	submitButton: TextView?,
	state: CanopyFormInteractionState,
	submitLabel: String?,
	loadingLabel: String,
	announceLoading: Boolean = false,
) {
	formContent?.setCanopyFormControlsEnabled(state.formEditable)
	submitButton?.apply {
		isEnabled = state.submitEnabled
		val presentationLabel = canopySubmitButtonLabel(state, submitLabel, loadingLabel)
		text = presentationLabel
		contentDescription = presentationLabel
		if (announceLoading && state == CanopyFormInteractionState.SUBMITTING) {
			announceCanopyLoading(presentationLabel)
		}
	}
}

@Suppress("DEPRECATION")
private fun View.announceCanopyLoading(text: CharSequence) = announceForAccessibility(text)

internal fun canopySubmitButtonLabel(
	state: CanopyFormInteractionState,
	submitLabel: String?,
	loadingLabel: String,
): String = if (state == CanopyFormInteractionState.SUBMITTING) loadingLabel else submitLabel.orEmpty()

private fun View.setCanopyFormControlsEnabled(enabled: Boolean) {
	if (isFocusable || isClickable) isEnabled = enabled
	if (this is ViewGroup) {
		for (index in 0 until childCount) getChildAt(index).setCanopyFormControlsEnabled(enabled)
	}
}

private fun describedLabel(label: String, description: String?) = listOfNotNull(label, description).joinToString(". ")

private fun requiredLabel(fragment: FullDetailsFragment, field: CanopyField) = if (field.required) {
	fragment.getString(R.string.canopy_required_label, field.label)
} else {
	field.label
}

private fun descriptionView(context: android.content.Context, value: String) = TextView(context).apply {
	text = value
	contentDescription = value
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

private const val MAX_CANOPY_TILE_IDS = 5
private const val CANOPY_ACTION_ID_BASE = Int.MIN_VALUE + 100
private const val STATUS_TEXT_SIZE_SP = 18f

internal fun canopyActionTileId(index: Int): Int {
	require(index in 0 until MAX_CANOPY_TILE_IDS)
	return CANOPY_ACTION_ID_BASE + index
}
