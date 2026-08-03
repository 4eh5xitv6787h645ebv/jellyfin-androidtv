package org.jellyfin.androidtv.integration.canopy

import java.time.Instant

internal object CanopyContractBounds {
	const val MAX_RESOLVE_BYTES = 65_536
	const val MAX_ACTION_BYTES = 32_768
	const val MAX_CONTRIBUTIONS = 7
	const val MAX_FIELDS = 8
	const val MAX_OPTIONS = 32
	const val MAX_REFRESH_TARGETS = 8
	const val MAX_ID_BYTES = 128
	const val MAX_LABEL_BYTES = 96
	const val MAX_TEXT_BYTES = 512
	const val MAX_OPAQUE_BYTES = 4_096
}

internal class CanopyContractException(
	val unsupported: Boolean = false,
	message: String,
) : IllegalArgumentException(message)

internal object CanopyContractMapper {
	fun discovery(wire: CanopyDiscoveryWire): CanopyDiscovery {
		requireContract(wire.protocolMinimum > 0, "Protocol minimum must be positive")
		requireContract(wire.protocolMaximum >= wire.protocolMinimum, "Protocol range was reversed")
		return CanopyDiscovery(wire.protocolMinimum, wire.protocolMaximum)
	}

	fun negotiation(wire: CanopyNegotiationWire): CanopyNegotiation {
		requireContract(wire.hostProtocolMinimum > 0, "Host protocol minimum must be positive")
		requireContract(wire.hostProtocolMaximum >= wire.hostProtocolMinimum, "Host protocol range was reversed")
		if (wire.compatible) {
			requireContract(wire.protocol != null, "Compatible negotiation omitted the protocol")
			requireContract(wire.protocol in wire.hostProtocolMinimum..wire.hostProtocolMaximum, "Negotiated protocol was outside the host range")
		} else {
			requireContract(wire.protocol == null, "Incompatible negotiation supplied a protocol")
		}
		return CanopyNegotiation(wire.compatible, wire.protocol, wire.hostProtocolMinimum, wire.hostProtocolMaximum)
	}

	fun resolvedSurface(wire: CanopyResolveResponseWire): CanopyResolvedSurface {
		requireText(wire.catalogRevision, CanopyContractBounds.MAX_ID_BYTES, "Catalog revision")
		requireContract(wire.contributions.size <= CanopyContractBounds.MAX_CONTRIBUTIONS, "Too many contributions")

		val contributions = wire.contributions.mapNotNull { contribution ->
			// Unknown contribution kinds are an additive extension and are omitted safely.
			when (contribution.kind) {
				"action" -> mapAction(contribution)
				"status" -> mapStatus(contribution)
				else -> null
			}
		}
		val seen = mutableSetOf<String>()
		requireContract(contributions.all { seen.add(it.id) }, "Duplicate contribution id")

		return CanopyResolvedSurface(wire.catalogRevision, contributions)
	}

	fun preparedAction(wire: CanopyPrepareResponseWire): CanopyPreparedAction {
		requireText(wire.capability, CanopyContractBounds.MAX_OPAQUE_BYTES, "Capability")
		requireText(wire.title, CanopyContractBounds.MAX_LABEL_BYTES, "Action title")
		requireText(wire.submitLabel, CanopyContractBounds.MAX_LABEL_BYTES, "Submit label")
		requireText(wire.cancelLabel, CanopyContractBounds.MAX_LABEL_BYTES, "Cancel label")
		requireContract(wire.fields.size <= CanopyContractBounds.MAX_FIELDS, "Too many action fields")

		requireContract(
			CANONICAL_UTC_INSTANT.matches(wire.expiresAtUtc),
			"Capability expiry was not canonical UTC",
		)
		val expiresAt = try {
			// Android's desugared java.time rejects fractional seconds combined with
			// the contract-valid +00:00 spelling. The regex above has already proved
			// the offset is exactly UTC, so normalize only that spelling before parsing.
			Instant.parse(normalizeValidatedCanonicalUtcInstant(wire.expiresAtUtc))
		} catch (_: RuntimeException) {
			throw CanopyContractException(message = "Capability expiry was not canonical UTC")
		}

		val ids = mutableSetOf<String>()
		val fields = wire.fields.map { field ->
			requireContract(ids.add(field.id), "Duplicate field id")
			mapField(field)
		}
		return CanopyPreparedAction(
			capability = CanopyCapability(wire.capability),
			expiresAt = expiresAt,
			title = wire.title,
			submitLabel = wire.submitLabel,
			cancelLabel = wire.cancelLabel,
			fields = fields,
		)
	}

	fun invokeResult(wire: CanopyInvokeResponseWire): CanopyActionResult {
		val outcome = when (wire.outcome) {
			"succeeded" -> CanopyActionOutcome.SUCCEEDED
			else -> throw CanopyContractException(unsupported = true, message = "Unsupported action outcome")
		}
		val message = wire.message?.let {
			requireText(it.text, CanopyContractBounds.MAX_TEXT_BYTES, "Result message")
			CanopyMessage(it.text, tone(it.tone))
		}
		wire.refresh?.catalogRevision?.let { requireText(it, CanopyContractBounds.MAX_ID_BYTES, "Catalog revision") }
		requireContract(
			wire.refresh?.targets.orEmpty().size <= CanopyContractBounds.MAX_REFRESH_TARGETS,
			"Too many refresh targets",
		)
		val refreshTargets = wire.refresh?.targets.orEmpty().mapNotNullTo(mutableSetOf()) {
			when (it) {
				"jellyfin_item" -> CanopyRefreshTarget.JELLYFIN_ITEM
				"item_detail_surface" -> CanopyRefreshTarget.ITEM_DETAIL_SURFACE
				else -> null
			}
		}
		return CanopyActionResult(outcome, message, wire.refresh?.catalogRevision, refreshTargets)
	}

	fun platformError(wire: CanopyErrorWire): CanopyPlatformError {
		requireContract(wire.error, "Error envelope did not identify itself as an error")
		requireText(wire.code, CanopyContractBounds.MAX_ID_BYTES, "Error code")
		requireText(wire.message, CanopyContractBounds.MAX_TEXT_BYTES, "Error message")
		requireContract(Regex("^[0-9a-f]{32}$").matches(wire.correlationId), "Invalid correlation id")
		return CanopyPlatformError(wire.code, wire.message, wire.retryable, wire.correlationId)
	}

	fun prepareHandle(value: String): CanopyPrepareHandle = value.let {
		requireText(it, CanopyContractBounds.MAX_OPAQUE_BYTES, "Prepare handle")
		CanopyPrepareHandle(it)
	}

	fun invokeRequest(
		preparedAction: CanopyPreparedAction,
		idempotencyKey: String,
		answers: List<CanopyAnswer>,
	): CanopyInvokeRequestWire {
		val capability = preparedAction.capability.wireValue()
		requireText(capability, CanopyContractBounds.MAX_OPAQUE_BYTES, "Capability")
		requireText(idempotencyKey, CanopyContractBounds.MAX_ID_BYTES, "Idempotency key")
		requireContract(answers.size <= CanopyContractBounds.MAX_FIELDS, "Too many answers")
		val fieldsById = preparedAction.fields.associateBy { it.id }
		requireContract(fieldsById.size == preparedAction.fields.size, "Prepared action had duplicate field ids")
		val answeredFieldIds = mutableSetOf<String>()
		val mapped = answers.map { answer ->
			val fieldId = answer.fieldId.checkedId()
			requireContract(answeredFieldIds.add(fieldId), "Duplicate answer field id")
			val field = fieldsById[fieldId]
				?: throw CanopyContractException(message = "Answer referenced an unknown field")
			this.answer(field, answer, fieldId)
		}
		requireContract(
			preparedAction.fields.none { it.required && it.id !in answeredFieldIds },
			"A required field was unanswered",
		)
		return CanopyInvokeRequestWire(capability, idempotencyKey, mapped)
	}

	private fun answer(field: CanopyField, answer: CanopyAnswer, fieldId: String): CanopyAnswerWire = when {
		field is CanopyField.Confirmation && answer is CanopyAnswer.Confirmation -> {
			requireContract(!field.required || answer.checked, "Required confirmation was not accepted")
			CanopyAnswerWire(fieldId, booleanValue = answer.checked)
		}
		field is CanopyField.BooleanValue && answer is CanopyAnswer.BooleanValue ->
			CanopyAnswerWire(fieldId, booleanValue = answer.checked)
		field is CanopyField.SingleSelect && answer is CanopyAnswer.SingleSelect -> {
			val optionId = answer.optionId.checkedId()
			requireEnabledOptions(field.options, listOf(optionId))
			CanopyAnswerWire(fieldId, optionIds = listOf(optionId))
		}
		field is CanopyField.MultiSelect && answer is CanopyAnswer.MultiSelect -> {
			requireContract(answer.optionIds.size <= CanopyContractBounds.MAX_OPTIONS, "Too many selected options")
			val optionIds = answer.optionIds.map { it.checkedId() }
			requireContract(optionIds.distinct().size == optionIds.size, "Selected options were duplicated")
			requireContract(
				optionIds.size in field.minimumSelections..field.maximumSelections,
				"Selected options violated field cardinality",
			)
			requireEnabledOptions(field.options, optionIds)
			CanopyAnswerWire(fieldId, optionIds = optionIds.sorted())
		}
		else -> throw CanopyContractException(message = "Answer kind did not match its field")
	}

	private fun requireEnabledOptions(options: List<CanopyOption>, selectedIds: List<String>) {
		val optionsById = options.associateBy { it.id }
		requireContract(optionsById.size == options.size, "Field options were duplicated")
		requireContract(
			selectedIds.all { optionId -> optionsById[optionId]?.disabled == false },
			"Answer selected an unknown or disabled option",
		)
	}

	private fun mapAction(wire: CanopyContributionWire): CanopyContribution.Action? = runCatching {
		val enabled = wire.enabled ?: false
		wire.prepareHandle?.let { requireText(it, CanopyContractBounds.MAX_OPAQUE_BYTES, "Prepare handle") }
		if (enabled) requireText(wire.prepareHandle, CanopyContractBounds.MAX_OPAQUE_BYTES, "Prepare handle")
		CanopyContribution.Action(
			id = wire.id.checkedId(),
			label = wire.label.checkedLabel(),
			description = wire.description.checkedDescription(),
			icon = icon(wire.semanticIcon),
			enabled = enabled,
			prepareHandle = wire.prepareHandle?.takeIf { it.isNotBlank() }?.let(::CanopyPrepareHandle),
		)
	}.getOrNull()

	private fun mapStatus(wire: CanopyContributionWire): CanopyContribution.Status? = runCatching {
		CanopyContribution.Status(
			id = wire.id.checkedId(),
			label = wire.label.checkedLabel(),
			tone = tone(wire.tone),
		)
	}.getOrNull()

	private fun mapField(wire: CanopyFieldWire): CanopyField {
		val id = wire.id.checkedId()
		val label = wire.label.checkedLabel()
		val description = wire.description.checkedDescription()
		return when (wire.kind) {
			"confirmation" -> {
				requireContract(wire.options.isEmpty() && wire.defaultOptionIds.isEmpty(), "Confirmation field carried options")
				CanopyField.Confirmation(id, label, description, wire.required, wire.defaultChecked ?: false)
			}
			"boolean" -> {
				requireContract(wire.options.isEmpty() && wire.defaultOptionIds.isEmpty(), "Boolean field carried options")
				CanopyField.BooleanValue(id, label, description, wire.required, wire.defaultChecked ?: false)
			}
			"single_select" -> {
				val options = mapOptions(wire.options)
				requireContract(options.isNotEmpty(), "Single-select field had no options")
				requireContract(wire.defaultOptionIds.size <= 1, "Single-select field had multiple defaults")
				val default = wire.defaultOptionIds.singleOrNull()
				requireContract(
					default == null || options.any { it.id == default && !it.disabled },
					"Single-select default was not an enabled option",
				)
				CanopyField.SingleSelect(id, label, description, wire.required, options, default)
			}
			"multi_select" -> {
				val options = mapOptions(wire.options)
				requireContract(options.isNotEmpty(), "Multi-select field had no options")
				requireContract(
					wire.defaultOptionIds.size <= CanopyContractBounds.MAX_OPTIONS,
					"Too many multi-select defaults",
				)
				val defaults = wire.defaultOptionIds.toSet()
				requireContract(defaults.size == wire.defaultOptionIds.size, "Multi-select defaults were duplicated")
				requireContract(
					defaults.all { default -> options.any { it.id == default && !it.disabled } },
					"Multi-select default was not an enabled option",
				)
				val minimum = wire.minimumSelections ?: 0
				val maximum = wire.maximumSelections ?: options.size
				requireContract(minimum >= 0 && maximum >= minimum && maximum <= options.size, "Invalid multi-select range")
				requireContract(defaults.size in minimum..maximum, "Multi-select defaults violated its range")
				CanopyField.MultiSelect(id, label, description, wire.required, options, defaults, minimum, maximum)
			}
			else -> throw CanopyContractException(unsupported = true, message = "Unsupported field kind")
		}
	}

	private fun mapOptions(wire: List<CanopyOptionWire>): List<CanopyOption> {
		requireContract(wire.size <= CanopyContractBounds.MAX_OPTIONS, "Too many options")
		val ids = mutableSetOf<String>()
		return wire.map { option ->
			val id = option.id.checkedId()
			requireContract(ids.add(id), "Duplicate option id")
			CanopyOption(id, option.label.checkedLabel(), option.description.checkedDescription(), option.disabled)
		}
	}

	private fun icon(value: String?): CanopySemanticIcon = when (value) {
		"shield" -> CanopySemanticIcon.SHIELD
		"visibility_off" -> CanopySemanticIcon.VISIBILITY_OFF
		"add" -> CanopySemanticIcon.ADD
		"check" -> CanopySemanticIcon.CHECK
		"settings" -> CanopySemanticIcon.SETTINGS
		else -> throw CanopyContractException(unsupported = true, message = "Unsupported semantic icon")
	}

	private fun tone(value: String?): CanopyTone = when (value) {
		"neutral" -> CanopyTone.NEUTRAL
		"positive" -> CanopyTone.POSITIVE
		"warning" -> CanopyTone.WARNING
		"negative" -> CanopyTone.NEGATIVE
		else -> throw CanopyContractException(unsupported = true, message = "Unsupported tone")
	}

	private fun String?.checkedId(): String = requireCheckedText(this, CanopyContractBounds.MAX_ID_BYTES, "Id")
	private fun String?.checkedLabel(): String = requireCheckedText(this, CanopyContractBounds.MAX_LABEL_BYTES, "Label")
	private fun String?.checkedDescription(): String? = apply {
		if (this != null) requireText(this, CanopyContractBounds.MAX_TEXT_BYTES, "Description")
	}

	private fun requireText(value: String?, maximumBytes: Int, name: String) {
		requireContract(!value.isNullOrBlank(), "$name was blank")
		requireContract(value!!.encodeToByteArray().size <= maximumBytes, "$name exceeded its bound")
	}

	private fun requireCheckedText(value: String?, maximumBytes: Int, name: String): String {
		requireText(value, maximumBytes, name)
		return checkNotNull(value)
	}

	private fun requireContract(condition: Boolean, message: String) {
		if (!condition) throw CanopyContractException(message = message)
	}

	private val CANONICAL_UTC_INSTANT = Regex(
		"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,7})?(?:Z|\\+00:00)$",
	)
}

/** Requires the caller to validate [value] with [CANONICAL_UTC_INSTANT] first. */
internal fun normalizeValidatedCanonicalUtcInstant(value: String) = if (value.endsWith("+00:00")) {
	value.dropLast("+00:00".length) + "Z"
} else {
	value
}
