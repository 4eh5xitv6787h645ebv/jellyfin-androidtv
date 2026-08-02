package org.jellyfin.androidtv.integration.canopy

import java.time.Instant

data class CanopyDiscovery(
	val protocolMinimum: Int,
	val protocolMaximum: Int,
)

data class CanopyNegotiation(
	val compatible: Boolean,
	val protocol: Int?,
	val hostProtocolMinimum: Int,
	val hostProtocolMaximum: Int,
)

data class CanopyResolvedSurface(
	val catalogRevision: String,
	val contributions: List<CanopyContribution>,
)

sealed interface CanopyContribution {
	val id: String
	val label: String

	data class Action(
		override val id: String,
		override val label: String,
		val description: String?,
		val icon: CanopySemanticIcon,
		val enabled: Boolean,
		val prepareHandle: String?,
	) : CanopyContribution

	data class Status(
		override val id: String,
		override val label: String,
		val tone: CanopyTone,
	) : CanopyContribution
}

enum class CanopySemanticIcon {
	DEFAULT,
	SHIELD,
	VISIBILITY_OFF,
	ADD,
	CHECK,
	SETTINGS,
}

enum class CanopyTone {
	NEUTRAL,
	POSITIVE,
	WARNING,
	NEGATIVE,
}

data class CanopyPreparedAction(
	val capability: String,
	val expiresAt: Instant,
	val title: String,
	val submitLabel: String,
	val cancelLabel: String,
	val fields: List<CanopyField>,
)

sealed interface CanopyField {
	val id: String
	val label: String
	val description: String?
	val required: Boolean

	data class Confirmation(
		override val id: String,
		override val label: String,
		override val description: String?,
		override val required: Boolean,
		val defaultChecked: Boolean,
	) : CanopyField

	data class BooleanValue(
		override val id: String,
		override val label: String,
		override val description: String?,
		override val required: Boolean,
		val defaultChecked: Boolean,
	) : CanopyField

	data class SingleSelect(
		override val id: String,
		override val label: String,
		override val description: String?,
		override val required: Boolean,
		val options: List<CanopyOption>,
		val defaultOptionId: String?,
	) : CanopyField

	data class MultiSelect(
		override val id: String,
		override val label: String,
		override val description: String?,
		override val required: Boolean,
		val options: List<CanopyOption>,
		val defaultOptionIds: Set<String>,
		val minimumSelections: Int,
		val maximumSelections: Int,
	) : CanopyField
}

data class CanopyOption(
	val id: String,
	val label: String,
	val description: String?,
	val disabled: Boolean,
)

sealed interface CanopyAnswer {
	val fieldId: String

	data class Confirmation(override val fieldId: String, val checked: Boolean) : CanopyAnswer
	data class BooleanValue(override val fieldId: String, val checked: Boolean) : CanopyAnswer
	data class SingleSelect(override val fieldId: String, val optionId: String) : CanopyAnswer
	data class MultiSelect(override val fieldId: String, val optionIds: Set<String>) : CanopyAnswer
}

data class CanopyActionResult(
	val outcome: CanopyActionOutcome,
	val message: CanopyMessage?,
	val catalogRevision: String?,
	val refreshTargets: Set<CanopyRefreshTarget>,
)

enum class CanopyActionOutcome {
	SUCCEEDED,
}

data class CanopyMessage(
	val text: String,
	val tone: CanopyTone,
)

enum class CanopyRefreshTarget {
	JELLYFIN_ITEM,
	ITEM_DETAIL_SURFACE,
}

data class CanopyPlatformError(
	val code: String,
	val message: String,
	val retryable: Boolean,
	val correlationId: String,
)

sealed interface CanopyCallResult<out T> {
	data class Success<T>(
		val value: T,
		val etag: String? = null,
	) : CanopyCallResult<T>

	data object Absent : CanopyCallResult<Nothing>
	data object Unauthorized : CanopyCallResult<Nothing>
	data object Forbidden : CanopyCallResult<Nothing>

	data class Failure(
		val kind: CanopyFailureKind,
		val status: Int? = null,
		val error: CanopyPlatformError? = null,
	) : CanopyCallResult<Nothing>
}

enum class CanopyFailureKind {
	TRANSPORT,
	HTTP,
	INVALID_RESPONSE,
	RESPONSE_TOO_LARGE,
	UNSUPPORTED_CONTRACT,
}
