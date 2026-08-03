package org.jellyfin.androidtv.integration.canopy

import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

internal data class CanopyItemDetailSurface(
	val itemId: UUID,
	val actions: List<CanopyContribution.Action>,
	val statuses: List<CanopyContribution.Status>,
)

internal data class CanopyPreparedForm(
	internal val generation: Long,
	internal val idempotencyKey: UUID,
	val action: CanopyPreparedAction,
	val form: CanopyActionForm,
)

private data class CanopySubmissionSnapshot(
	val prepared: CanopyPreparedForm,
	val answers: List<CanopyAnswer>,
)

private data class CanopyResolvedSurfaceSnapshot(
	val value: CanopyResolvedSurface,
	val etag: String?,
	val projected: CanopyItemDetailSurface,
)

internal data class CanopyActionLayout(
	val direct: List<CanopyContribution.Action>,
	val overflow: List<CanopyContribution.Action>,
) {
	val visibleCount: Int get() = direct.size + if (overflow.isEmpty()) 0 else 1

	companion object {
		private const val MAX_VISIBLE = 5

		fun create(actions: List<CanopyContribution.Action>): CanopyActionLayout = if (actions.size <= MAX_VISIBLE) {
			CanopyActionLayout(actions, emptyList())
		} else {
			CanopyActionLayout(actions.take(MAX_VISIBLE - 1), actions.drop(MAX_VISIBLE - 1))
		}
	}
}

internal sealed interface CanopyItemDetailEvent {
	data class Surface(val value: CanopyItemDetailSurface?) : CanopyItemDetailEvent
	data class Form(val value: CanopyPreparedForm) : CanopyItemDetailEvent
	data object InvalidateForm : CanopyItemDetailEvent
	data object Submitting : CanopyItemDetailEvent
	data class InvalidForm(val fieldIds: Set<String>) : CanopyItemDetailEvent
	data class Message(val text: String?, val tone: CanopyTone, val fallback: Fallback) : CanopyItemDetailEvent {
		enum class Fallback { ACTION_SUCCEEDED, ACTION_UNAVAILABLE, ACTION_EXPIRED }
	}
	data class Refresh(val itemId: UUID, val targets: Set<CanopyRefreshTarget>) : CanopyItemDetailEvent
}

/** Immutable, UI-neutral form state. All values originate from a bounded prepared action. */
@ConsistentCopyVisibility
internal data class CanopyActionForm private constructor(
	val preparedAction: CanopyPreparedAction,
	private val checked: Map<String, Boolean>,
	private val singleSelections: Map<String, String?>,
	private val multiSelections: Map<String, Set<String>>,
) {
	fun isChecked(fieldId: String): Boolean = checked[fieldId] == true
	fun singleSelection(fieldId: String): String? = singleSelections[fieldId]
	fun multiSelection(fieldId: String): Set<String> = multiSelections[fieldId].orEmpty()

	fun setChecked(fieldId: String, value: Boolean): CanopyActionForm = when (
		preparedAction.fields.find { it.id == fieldId }
	) {
		is CanopyField.Confirmation, is CanopyField.BooleanValue -> copy(checked = checked + (fieldId to value))
		else -> this
	}

	fun selectOne(fieldId: String, optionId: String?): CanopyActionForm {
		val field = preparedAction.fields.find { it.id == fieldId } as? CanopyField.SingleSelect ?: return this
		if (optionId != null && field.options.none { it.id == optionId && !it.disabled }) return this
		return copy(singleSelections = singleSelections + (fieldId to optionId))
	}

	fun setSelected(fieldId: String, optionId: String, selected: Boolean): CanopyActionForm {
		val field = preparedAction.fields.find { it.id == fieldId } as? CanopyField.MultiSelect ?: return this
		if (field.options.none { it.id == optionId && !it.disabled }) return this
		val values = multiSelection(fieldId).toMutableSet().apply {
			if (selected) add(optionId) else remove(optionId)
		}
		return copy(multiSelections = multiSelections + (fieldId to values))
	}

	fun validationErrors(): Set<String> = preparedAction.fields.mapNotNullTo(linkedSetOf()) { field ->
		when (field) {
			is CanopyField.Confirmation -> field.id.takeIf { field.required && !isChecked(field.id) }
			is CanopyField.BooleanValue -> null
			is CanopyField.SingleSelect -> {
				val selected = singleSelection(field.id)
				field.id.takeIf {
					(field.required && selected == null) ||
					(selected != null && field.options.none { option -> option.id == selected && !option.disabled })
				}
			}
			is CanopyField.MultiSelect -> field.id.takeIf {
				val values = multiSelection(field.id)
				values.size !in field.minimumSelections..field.maximumSelections ||
					values.any { id -> field.options.none { option -> option.id == id && !option.disabled } }
			}
		}
	}

	fun answers(): List<CanopyAnswer> {
		require(validationErrors().isEmpty())
		return preparedAction.fields.mapNotNull { field ->
			when (field) {
				is CanopyField.Confirmation -> CanopyAnswer.Confirmation(field.id, isChecked(field.id))
				is CanopyField.BooleanValue -> CanopyAnswer.BooleanValue(field.id, isChecked(field.id))
				is CanopyField.SingleSelect -> singleSelection(field.id)?.let { CanopyAnswer.SingleSelect(field.id, it) }
				is CanopyField.MultiSelect -> CanopyAnswer.MultiSelect(field.id, multiSelection(field.id).sorted())
			}
		}
	}

	companion object {
		fun create(preparedAction: CanopyPreparedAction): CanopyActionForm = CanopyActionForm(
			preparedAction = preparedAction,
			checked = preparedAction.fields.mapNotNull { field ->
				when (field) {
					is CanopyField.Confirmation -> field.id to field.defaultChecked
					is CanopyField.BooleanValue -> field.id to field.defaultChecked
					else -> null
				}
			}.toMap(),
			singleSelections = preparedAction.fields.filterIsInstance<CanopyField.SingleSelect>()
				.associate { it.id to it.defaultOptionId },
			multiSelections = preparedAction.fields.filterIsInstance<CanopyField.MultiSelect>()
				.associate { it.id to it.defaultOptionIds },
		)
	}
}

/**
 * Owns the item-detail request generation and action submission gate.
 *
 * The UI never receives handles or capabilities separately from their mapped objects. Cancelling a
 * generation and checking it after every suspension prevents a late response from repopulating a
 * different item or a stopped Fragment.
 */
internal class CanopyItemDetailCoordinator(
	private val scope: CoroutineScope,
	private val gateway: CanopyGateway,
	private val clock: Clock = Clock.systemUTC(),
	private val newIdempotencyKey: () -> UUID = UUID::randomUUID,
	private val onEvent: (CanopyItemDetailEvent) -> Unit,
) {
	private var generation = 0L
	private var surfaceJob: Job? = null
	private var actionJob: Job? = null
	private var itemId: UUID? = null
	private var cachedSurface: CanopyItemDetailSurface? = null
	private var resolvedSurfaceSnapshot: CanopyResolvedSurfaceSnapshot? = null
	private var actions = emptyMap<String, CanopyContribution.Action>()
	private var activeForm: CanopyPreparedForm? = null
	private var submissionSnapshot: CanopySubmissionSnapshot? = null
	private var submitting = false
	private var refreshQueued = false

	fun bind(newItemId: UUID, force: Boolean = false) {
		if (!force && itemId == newItemId && (cachedSurface != null || surfaceJob?.isActive == true)) {
			// Recycler/Fragment reattachment is not an authority invalidation. Re-emit the row
			// without replacing the generation that owns a live native form.
			cachedSurface?.let { onEvent(CanopyItemDetailEvent.Surface(it)) }
			return
		}
		val preserveSurface = force && itemId == newItemId
		val previousSnapshot = resolvedSurfaceSnapshot.takeIf { preserveSurface }
		val requestGeneration = reset(newItemId, preserveSurface)
		surfaceJob = scope.launch {
			val discovery = gateway.discover()
			if (!isCurrent(requestGeneration, newItemId)) return@launch
			if (discovery.requiresAuthorityRevocation()) {
				revokeSurface()
				return@launch
			}
			if (discovery !is CanopyCallResult.Success) return@launch

			val negotiation = gateway.negotiate()
			if (!isCurrent(requestGeneration, newItemId)) return@launch
			if (negotiation.requiresAuthorityRevocation() ||
				negotiation is CanopyCallResult.Success && !negotiation.value.compatible
			) {
				revokeSurface()
				return@launch
			}
			if (negotiation !is CanopyCallResult.Success) return@launch

			val resolvedResult = gateway.resolveItemDetail(newItemId)
			if (!isCurrent(requestGeneration, newItemId)) return@launch
			if (resolvedResult.requiresAuthorityRevocation()) {
				revokeSurface()
				return@launch
			}
			if (resolvedResult !is CanopyCallResult.Success) return@launch
			val resolved = resolvedResult.value

			val availableActions = resolved.contributions.filterIsInstance<CanopyContribution.Action>()
				.filter { it.enabled && it.prepareHandle != null }
			actions = availableActions.associateBy { it.id }
			val surface = CanopyItemDetailSurface(
				itemId = newItemId,
				actions = availableActions,
				statuses = resolved.contributions.filterIsInstance<CanopyContribution.Status>(),
			)
			val snapshot = CanopyResolvedSurfaceSnapshot(resolved, resolvedResult.etag, surface)
			cachedSurface = surface
			resolvedSurfaceSnapshot = snapshot
			// Resolve is a POST and cannot use a conditional request. Suppress row churn only
			// when both the complete mapped representation (including opaque handles and the
			// catalog revision) and its response ETag are exactly unchanged. Regardless, the
			// authoritative action map and snapshot above are always replaced.
			val representationUnchanged = previousSnapshot?.let {
				snapshot.value == it.value && snapshot.etag == it.etag
			} == true
			if (!representationUnchanged) {
				onEvent(CanopyItemDetailEvent.Surface(snapshot.projected))
			}
		}
	}

	fun refreshSurface() {
		itemId?.let { bind(it, force = true) }
	}

	/** Queue generic external invalidation while a form owns a prepared capability. */
	fun requestSurfaceRefresh() {
		if (activeForm != null || submitting) {
			refreshQueued = true
			return
		}
		refreshSurface()
	}

	/** Release only the exact form dismissed by the native dialog. */
	fun dismissForm(prepared: CanopyPreparedForm) {
		if (activeForm !== prepared) return
		activeForm = null
		if (!submitting) {
			submissionSnapshot = null
			drainQueuedSurfaceRefresh()
		}
	}

	fun prepare(actionId: String) {
		if (submitting) return
		val selected = actions[actionId] ?: return
		val handle = selected.prepareHandle ?: return
		val requestGeneration = generation
		actionJob?.cancel()
		activeForm = null
		submissionSnapshot = null
		submitting = false
		actionJob = scope.launch {
			when (val result = gateway.prepare(handle)) {
				is CanopyCallResult.Success -> {
					if (requestGeneration != generation) return@launch
					if (!clock.instant().isBefore(result.value.expiresAt)) {
						onEvent(expiredMessage())
						return@launch
					}
					val prepared = CanopyPreparedForm(
						generation = requestGeneration,
						idempotencyKey = newIdempotencyKey(),
						action = result.value,
						form = CanopyActionForm.create(result.value),
					)
					activeForm = prepared
					onEvent(CanopyItemDetailEvent.Form(prepared))
				}
				else -> if (requestGeneration == generation) {
					if (result is CanopyCallResult.Failure) {
						Timber.w("Canopy action prepare failed (%s, HTTP %s)", result.kind, result.status)
					}
					onEvent(unavailableMessage(result.serverMessage()))
				}
			}
		}
	}

	fun submit(prepared: CanopyPreparedForm, form: CanopyActionForm) {
		if (submitting || activeForm !== prepared || prepared.generation != generation) return
		if (!clock.instant().isBefore(prepared.action.expiresAt)) {
			activeForm = null
			submissionSnapshot = null
			onEvent(expiredMessage())
			drainQueuedSurfaceRefresh()
			return
		}
		val answers = answersForSubmission(prepared, form) ?: return

		submitting = true
		onEvent(CanopyItemDetailEvent.Submitting)
		val requestGeneration = generation
		actionJob = scope.launch {
			when (val result = gateway.invoke(prepared.action, prepared.idempotencyKey, answers)) {
				is CanopyCallResult.Success -> if (requestGeneration == generation) {
					activeForm = null
					submissionSnapshot = null
					onEvent(
						CanopyItemDetailEvent.Message(
							text = result.value.message?.text,
							tone = result.value.message?.tone ?: CanopyTone.POSITIVE,
							fallback = CanopyItemDetailEvent.Message.Fallback.ACTION_SUCCEEDED,
						),
					)
					val responseRefreshesSurface = CanopyRefreshTarget.ITEM_DETAIL_SURFACE in result.value.refreshTargets
					if (responseRefreshesSurface) refreshQueued = false
					if (result.value.refreshTargets.isNotEmpty()) {
						onEvent(CanopyItemDetailEvent.Refresh(checkNotNull(itemId), result.value.refreshTargets))
					}
					if (!responseRefreshesSurface) drainQueuedSurfaceRefresh()
				}
				else -> if (requestGeneration == generation) {
					onEvent(unavailableMessage(result.serverMessage()))
				}
			}
			if (requestGeneration == generation) {
				submitting = false
				if (activeForm == null) submissionSnapshot = null
				drainQueuedSurfaceRefresh()
			}
		}
	}

	fun stop() {
		reset(null)
	}

	private fun answersForSubmission(prepared: CanopyPreparedForm, form: CanopyActionForm): List<CanopyAnswer>? {
		val snapshot = submissionSnapshot
		if (snapshot != null) return snapshot.answers.takeIf { snapshot.prepared === prepared }
		val errors = form.validationErrors()
		if (errors.isNotEmpty()) {
			onEvent(CanopyItemDetailEvent.InvalidForm(errors))
			return null
		}
		return form.answers().also { submissionSnapshot = CanopySubmissionSnapshot(prepared, it) }
	}

	private fun reset(newItemId: UUID?, preserveSurface: Boolean = false): Long {
		generation++
		surfaceJob?.cancel()
		actionJob?.cancel()
		itemId = newItemId
		if (!preserveSurface) {
			cachedSurface = null
			resolvedSurfaceSnapshot = null
			actions = emptyMap()
		}
		activeForm = null
		submissionSnapshot = null
		submitting = false
		refreshQueued = false
		if (!preserveSurface) {
			onEvent(CanopyItemDetailEvent.InvalidateForm)
			onEvent(CanopyItemDetailEvent.Surface(null))
		}
		return generation
	}

	private fun drainQueuedSurfaceRefresh() {
		if (!refreshQueued || activeForm != null || submitting) return
		refreshQueued = false
		refreshSurface()
	}

	/** Explicit authority loss or an unsafe representation revokes previously usable opaque handles. */
	private fun revokeSurface() {
		val hadCachedAuthority = cachedSurface != null || resolvedSurfaceSnapshot != null || actions.isNotEmpty()
		cachedSurface = null
		resolvedSurfaceSnapshot = null
		actions = emptyMap()
		activeForm = null
		submissionSnapshot = null
		submitting = false
		refreshQueued = false
		if (hadCachedAuthority) {
			onEvent(CanopyItemDetailEvent.InvalidateForm)
			onEvent(CanopyItemDetailEvent.Surface(null))
		}
	}

	private fun isCurrent(requestGeneration: Long, requestItemId: UUID) =
		requestGeneration == generation && requestItemId == itemId

	private fun unavailableMessage(text: String?) = CanopyItemDetailEvent.Message(
		text = text,
		tone = CanopyTone.NEGATIVE,
		fallback = CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE,
	)

	private fun expiredMessage() = CanopyItemDetailEvent.Message(
		text = null,
		tone = CanopyTone.WARNING,
		fallback = CanopyItemDetailEvent.Message.Fallback.ACTION_EXPIRED,
	)

	private fun CanopyCallResult<*>.requiresAuthorityRevocation(): Boolean = when (this) {
		CanopyCallResult.Absent, CanopyCallResult.Unauthorized, CanopyCallResult.Forbidden -> true
		is CanopyCallResult.Failure -> when (kind) {
			CanopyFailureKind.UNSUPPORTED_CONTRACT,
			CanopyFailureKind.INVALID_RESPONSE,
			CanopyFailureKind.BUFFERED_RESPONSE_TOO_LARGE,
			-> true
			CanopyFailureKind.HTTP -> status.isAuthoritativeClientFailure()
			CanopyFailureKind.TRANSPORT -> false
		}
		is CanopyCallResult.Success -> false
	}
	private fun CanopyCallResult<*>.serverMessage(): String? = (this as? CanopyCallResult.Failure)?.error?.message
}

private fun Int?.isAuthoritativeClientFailure() = when (this) {
	null, HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS -> false
	else -> this in HTTP_CLIENT_ERROR_MINIMUM..HTTP_CLIENT_ERROR_MAXIMUM
}

private const val HTTP_CLIENT_ERROR_MINIMUM = 400
private const val HTTP_CLIENT_ERROR_MAXIMUM = 499
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
