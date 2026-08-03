package org.jellyfin.androidtv.ui.canopy

import android.app.AlertDialog
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.canopy.CanopyClient
import org.jellyfin.androidtv.integration.canopy.CanopyContribution
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailCoordinator
import org.jellyfin.androidtv.integration.canopy.CanopyItemDetailEvent
import org.jellyfin.androidtv.ui.itemdetail.CanopyFormPresenter
import org.jellyfin.sdk.api.client.ApiClient
import java.util.UUID

/**
 * Shows the Canopy actions available for an item (Spoiler Guard, Hidden
 * Content, Seerr) as a standalone chooser, without needing the item's details
 * screen. Used for long-press on a card and from the Seerr item screen for
 * titles already in the library.
 *
 * The catalog is resolved per invocation and the surface is whatever the
 * server offers for that item; nothing feature-specific is assumed here.
 */
class CanopyQuickActions(
	private val fragment: Fragment,
	apiClient: ApiClient,
	private val onChanged: () -> Unit = {},
) {
	private val forms = CanopyFormPresenter(fragment)
	private var chooser: AlertDialog? = null
	private var pendingActions: List<CanopyContribution.Action> = emptyList()

	private val coordinator = CanopyItemDetailCoordinator(
		scope = fragment.lifecycleScope,
		gateway = CanopyClient(apiClient),
		onEvent = ::handleEvent,
	)

	/** Resolves the item's Canopy actions and shows the chooser. */
	fun show(itemId: UUID) {
		coordinator.bind(itemId, force = true)
	}

	fun stop() {
		coordinator.stop()
		chooser?.dismiss()
		chooser = null
		forms.dismiss()
	}

	private fun handleEvent(event: CanopyItemDetailEvent) {
		when (event) {
			is CanopyItemDetailEvent.Surface -> showChooser(event.value?.actions.orEmpty())

			is CanopyItemDetailEvent.Form -> forms.showForm(
				prepared = event.value,
				onSubmit = coordinator::submit,
				onDismissed = coordinator::dismissForm,
			)

			CanopyItemDetailEvent.InvalidateForm -> forms.dismiss()
			CanopyItemDetailEvent.Submitting -> forms.setSubmitting()
			is CanopyItemDetailEvent.InvalidForm -> forms.setInvalid()

			is CanopyItemDetailEvent.Message -> {
				val terminal = event.fallback != CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE
				if (terminal) forms.dismiss() else forms.setFailed()
				val context = fragment.context ?: return
				val fallback = when (event.fallback) {
					CanopyItemDetailEvent.Message.Fallback.ACTION_SUCCEEDED -> R.string.canopy_action_succeeded
					CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE -> R.string.canopy_action_unavailable
					CanopyItemDetailEvent.Message.Fallback.ACTION_EXPIRED -> R.string.canopy_action_expired
				}
				Toast.makeText(context, event.text ?: fragment.getString(fallback), Toast.LENGTH_LONG).show()
			}

			is CanopyItemDetailEvent.Refresh -> onChanged()
		}
	}

	private fun showChooser(actions: List<CanopyContribution.Action>) {
		if (!fragment.isAdded) return
		if (actions.isEmpty()) {
			Toast.makeText(fragment.requireContext(), R.string.canopy_no_actions, Toast.LENGTH_SHORT).show()
			return
		}
		// Only the first surface resolution opens the chooser; later refreshes
		// (e.g. after an action) must not re-open it over the user.
		if (chooser != null || pendingActions.isNotEmpty()) return

		pendingActions = actions
		val created = AlertDialog.Builder(fragment.requireContext())
			.setTitle(R.string.canopy_actions)
			.setItems(actions.map { it.label }.toTypedArray()) { _, index ->
				actions.getOrNull(index)?.let { coordinator.prepare(it.id) }
			}
			.setNegativeButton(R.string.btn_cancel, null)
			.create()
		created.setOnDismissListener {
			if (chooser === created) chooser = null
			pendingActions = emptyList()
		}
		chooser = created
		created.show()
	}
}
