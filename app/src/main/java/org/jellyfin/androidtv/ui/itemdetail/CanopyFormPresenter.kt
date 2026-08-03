package org.jellyfin.androidtv.ui.itemdetail

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
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
import androidx.fragment.app.Fragment
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.canopy.CanopyActionForm
import org.jellyfin.androidtv.integration.canopy.CanopyField
import org.jellyfin.androidtv.integration.canopy.CanopyPreparedForm
import org.jellyfin.androidtv.util.dp

/**
 * Renders a server-defined Canopy action form as a native dialog and tracks
 * its interaction state. Shared by the item-detail Actions surface and the
 * standalone quick-actions chooser so both render identical forms.
 */
internal class CanopyFormPresenter(
	private val fragment: Fragment,
) {
	private var dialog: AlertDialog? = null
	private var submitButton: TextView? = null
	private var formContent: View? = null
	private var state = CanopyFormInteractionState.EDITING
	private var activeSubmitLabel: String? = null

	fun showForm(
		prepared: CanopyPreparedForm,
		onSubmit: (CanopyPreparedForm, CanopyActionForm) -> Unit,
		onDismissed: (CanopyPreparedForm) -> Unit,
	) {
		if (!fragment.isAdded) {
			onDismissed(prepared)
			return
		}

		dialog?.dismiss()
		state = CanopyFormInteractionState.EDITING
		var form = prepared.form
		activeSubmitLabel = prepared.action.submitLabel

		val context = fragment.requireContext()
		val fieldViews = mutableMapOf<String, View>()
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(24.dp(context))
		}

		prepared.action.fields.forEach { field ->
			val fieldView = when (field) {
				is CanopyField.Confirmation -> checkboxField(context, field, form) { checked ->
					form = form.setChecked(field.id, checked)
				}

				is CanopyField.BooleanValue -> checkboxField(context, field, form) { checked ->
					form = form.setChecked(field.id, checked)
				}

				is CanopyField.SingleSelect -> selectOneField(context, field, form) { optionId ->
					form = form.selectOne(field.id, optionId)
				}

				is CanopyField.MultiSelect -> selectManyField(context, field, form) { optionId, checked ->
					form = form.setSelected(field.id, optionId, checked)
				}
			}
			fieldViews[field.id] = fieldView.firstEnabledFocusable() ?: fieldView
			content.addView(fieldView)
		}

		val scroll = ScrollView(context).apply { addView(content) }.also { formContent = content }
		val created = AlertDialog.Builder(context)
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
						onSubmit(prepared, form)
					}
				}
			}
			applyState()
			fieldViews.values.firstOrNull()?.requestFocus()
		}
		created.setOnDismissListener {
			if (dialog === created) {
				reset()
				onDismissed(prepared)
			}
		}
		dialog = created
		created.show()
	}

	fun setSubmitting() {
		state = state.submitting()
		applyState(announceLoading = true)
	}

	fun setFailed() {
		state = state.failed()
		applyState()
	}

	fun setInvalid() {
		applyState()
		val context = fragment.context ?: return
		Toast.makeText(context, R.string.canopy_complete_required_fields, Toast.LENGTH_LONG).show()
	}

	fun dismiss() {
		dialog?.dismiss()
		reset()
	}

	private fun reset() {
		dialog = null
		submitButton = null
		formContent = null
		state = CanopyFormInteractionState.EDITING
		activeSubmitLabel = null
	}

	private fun applyState(announceLoading: Boolean = false) = applyCanopyFormInteractionState(
		formContent,
		submitButton,
		state,
		activeSubmitLabel,
		fragment.getString(R.string.loading),
		announceLoading,
	)

	private fun checkboxField(
		context: Context,
		field: CanopyField,
		form: CanopyActionForm,
		onChecked: (Boolean) -> Unit,
	): View = LinearLayout(context).apply {
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
		context: Context,
		field: CanopyField.SingleSelect,
		form: CanopyActionForm,
		onSelected: (String?) -> Unit,
	): View = fieldGroup(context, field).apply {
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
		context: Context,
		field: CanopyField.MultiSelect,
		form: CanopyActionForm,
		onSelected: (String, Boolean) -> Unit,
	): View = fieldGroup(context, field).apply {
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

	private fun fieldGroup(context: Context, field: CanopyField): LinearLayout = LinearLayout(context).apply {
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

internal fun applyCanopyFormInteractionState(
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

internal fun describedLabel(label: String, description: String?) =
	listOfNotNull(label, description).joinToString(". ")

internal fun requiredLabel(fragment: Fragment, field: CanopyField) = if (field.required) {
	fragment.getString(R.string.canopy_required_label, field.label)
} else {
	field.label
}

private fun descriptionView(context: Context, value: String) = TextView(context).apply {
	text = value
	contentDescription = value
}
