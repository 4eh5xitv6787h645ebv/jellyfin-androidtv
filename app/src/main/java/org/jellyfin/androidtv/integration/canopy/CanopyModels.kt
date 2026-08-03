package org.jellyfin.androidtv.integration.canopy

import java.time.Instant

internal data class CanopyDiscovery(
	val protocolMinimum: Int,
	val protocolMaximum: Int,
)

internal data class CanopyNegotiation(
	val compatible: Boolean,
	val protocol: Int?,
	val hostProtocolMinimum: Int,
	val hostProtocolMaximum: Int,
)

internal data class CanopyResolvedSurface(
	val catalogRevision: String,
	val contributions: List<CanopyContribution>,
)

internal sealed interface CanopyContribution {
	val id: String
	val label: String

	data class Action(
		override val id: String,
		override val label: String,
		val description: String?,
		val icon: CanopySemanticIcon,
		val enabled: Boolean,
		val prepareHandle: CanopyPrepareHandle?,
	) : CanopyContribution

	data class Status(
		override val id: String,
		override val label: String,
		val tone: CanopyTone,
	) : CanopyContribution
}

internal enum class CanopySemanticIcon {
	DEFAULT,
	SHIELD,
	VISIBILITY_OFF,
	ADD,
	CHECK,
	SETTINGS,
}

internal enum class CanopyTone {
	NEUTRAL,
	POSITIVE,
	WARNING,
	NEGATIVE,
}

internal data class CanopyPreparedAction(
	val capability: CanopyCapability,
	val expiresAt: Instant,
	val title: String,
	val submitLabel: String,
	val cancelLabel: String,
	val fields: List<CanopyField>,
)

internal sealed interface CanopyField {
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

internal data class CanopyOption(
	val id: String,
	val label: String,
	val description: String?,
	val disabled: Boolean,
)

internal sealed interface CanopyAnswer {
	val fieldId: String

	data class Confirmation(override val fieldId: String, val checked: Boolean) : CanopyAnswer
	data class BooleanValue(override val fieldId: String, val checked: Boolean) : CanopyAnswer
	data class SingleSelect(override val fieldId: String, val optionId: String) : CanopyAnswer
	data class MultiSelect(override val fieldId: String, val optionIds: List<String>) : CanopyAnswer
}

internal data class CanopyActionResult(
	val outcome: CanopyActionOutcome,
	val message: CanopyMessage?,
	val catalogRevision: String?,
	val refreshTargets: Set<CanopyRefreshTarget>,
)

internal enum class CanopyActionOutcome {
	SUCCEEDED,
}

internal data class CanopyMessage(
	val text: String,
	val tone: CanopyTone,
)

internal enum class CanopyRefreshTarget {
	JELLYFIN_ITEM,
	ITEM_DETAIL_SURFACE,
}

internal data class CanopyPlatformError(
	val code: String,
	val message: String,
	val retryable: Boolean,
	val correlationId: String,
)

internal sealed interface CanopyCallResult<out T> {
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

internal enum class CanopyFailureKind {
	TRANSPORT,
	HTTP,
	INVALID_RESPONSE,
	BUFFERED_RESPONSE_TOO_LARGE,
	UNSUPPORTED_CONTRACT,
}

internal class CanopyPrepareHandle internal constructor(
	private val value: String,
) {
	internal fun wireValue(): String = value

	override fun equals(other: Any?): Boolean = other is CanopyPrepareHandle && value == other.value
	override fun hashCode(): Int = value.hashCode()
	override fun toString(): String = "CanopyPrepareHandle(<redacted>)"
}

internal class CanopyCapability internal constructor(
	private val value: String,
) {
	internal fun wireValue(): String = value

	override fun equals(other: Any?): Boolean = other is CanopyCapability && value == other.value
	override fun hashCode(): Int = value.hashCode()
	override fun toString(): String = "CanopyCapability(<redacted>)"
}
