package org.jellyfin.androidtv.integration.canopy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CanopyDiscoveryWire(
	@SerialName("Available") val available: Boolean,
	@SerialName("ProtocolMinimum") val protocolMinimum: Int,
	@SerialName("ProtocolMaximum") val protocolMaximum: Int,
)

@Serializable
internal data class CanopyNegotiationWire(
	@SerialName("Compatible") val compatible: Boolean,
	@SerialName("Protocol") val protocol: Int? = null,
	@SerialName("HostProtocolMinimum") val hostProtocolMinimum: Int,
	@SerialName("HostProtocolMaximum") val hostProtocolMaximum: Int,
)

@Serializable
internal data class CanopyItemReferenceWire(
	@SerialName("Id") val id: String,
)

@Serializable
internal data class CanopyClientCapabilitiesWire(
	@SerialName("ContributionKinds") val contributionKinds: List<String>,
	@SerialName("FieldKinds") val fieldKinds: List<String>,
	@SerialName("InputModes") val inputModes: List<String>,
	@SerialName("Accessibility") val accessibility: List<String>,
	@SerialName("Locale") val locale: String,
)

@Serializable
internal data class CanopyResolveRequestWire(
	@SerialName("Protocol") val protocol: Int,
	@SerialName("SurfaceSchema") val surfaceSchema: Int,
	@SerialName("Item") val item: CanopyItemReferenceWire,
	@SerialName("Client") val client: CanopyClientCapabilitiesWire,
)

@Serializable
internal data class CanopyResolveResponseWire(
	@SerialName("CatalogRevision") val catalogRevision: String,
	@SerialName("Contributions") val contributions: List<CanopyContributionWire>,
)

/**
 * Flat on purpose: unknown discriminator values can be omitted without asking the
 * serializer to instantiate an unknown polymorphic subtype.
 */
@Serializable
internal data class CanopyContributionWire(
	@SerialName("Id") val id: String? = null,
	@SerialName("Kind") val kind: String,
	@SerialName("Label") val label: String? = null,
	@SerialName("Description") val description: String? = null,
	@SerialName("SemanticIcon") val semanticIcon: String? = null,
	@SerialName("Enabled") val enabled: Boolean? = null,
	@SerialName("PrepareHandle") val prepareHandle: String? = null,
	@SerialName("Tone") val tone: String? = null,
)

@Serializable
internal data class CanopyPrepareRequestWire(
	@SerialName("PrepareHandle") val prepareHandle: String,
)

@Serializable
internal data class CanopyPrepareResponseWire(
	@SerialName("Capability") val capability: String,
	@SerialName("ExpiresAtUtc") val expiresAtUtc: String,
	@SerialName("Title") val title: String,
	@SerialName("SubmitLabel") val submitLabel: String,
	@SerialName("CancelLabel") val cancelLabel: String,
	@SerialName("Fields") val fields: List<CanopyFieldWire>,
)

/** See [CanopyContributionWire] for why this is a flat wire model. */
@Serializable
internal data class CanopyFieldWire(
	@SerialName("Id") val id: String,
	@SerialName("Kind") val kind: String,
	@SerialName("Label") val label: String,
	@SerialName("Description") val description: String? = null,
	@SerialName("Required") val required: Boolean = false,
	@SerialName("DefaultChecked") val defaultChecked: Boolean? = null,
	@SerialName("Options") val options: List<CanopyOptionWire> = emptyList(),
	@SerialName("DefaultOptionIds") val defaultOptionIds: List<String> = emptyList(),
	@SerialName("MinimumSelections") val minimumSelections: Int? = null,
	@SerialName("MaximumSelections") val maximumSelections: Int? = null,
)

@Serializable
internal data class CanopyOptionWire(
	@SerialName("Id") val id: String,
	@SerialName("Label") val label: String,
	@SerialName("Description") val description: String? = null,
	@SerialName("Disabled") val disabled: Boolean = false,
)

@Serializable
internal data class CanopyInvokeRequestWire(
	@SerialName("Capability") val capability: String,
	@SerialName("IdempotencyKey") val idempotencyKey: String,
	@SerialName("Answers") val answers: List<CanopyAnswerWire>,
)

@Serializable
internal data class CanopyAnswerWire(
	@SerialName("FieldId") val fieldId: String,
	@SerialName("BooleanValue") val booleanValue: Boolean? = null,
	@SerialName("OptionIds") val optionIds: List<String>? = null,
)

@Serializable
internal data class CanopyInvokeResponseWire(
	@SerialName("Outcome") val outcome: String,
	@SerialName("Message") val message: CanopyMessageWire? = null,
	@SerialName("Refresh") val refresh: CanopyRefreshWire? = null,
)

@Serializable
internal data class CanopyMessageWire(
	@SerialName("Text") val text: String,
	@SerialName("Tone") val tone: String? = null,
)

@Serializable
internal data class CanopyRefreshWire(
	@SerialName("CatalogRevision") val catalogRevision: String? = null,
	@SerialName("Targets") val targets: List<String> = emptyList(),
)

@Serializable
internal data class CanopyErrorWire(
	@SerialName("Error") val error: Boolean,
	@SerialName("Code") val code: String,
	@SerialName("Message") val message: String,
	@SerialName("Retryable") val retryable: Boolean,
	@SerialName("CorrelationId") val correlationId: String,
)
