package org.jellyfin.androidtv.integration.canopy

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.HttpMethod

class CanopyClientTests : FunSpec({
	test("discovery tolerates additive properties") {
		val transport = FixtureTransport(response(200, fixture("discovery.200.json")))
		val result = CanopyClient(transport).discover()

		result shouldBe CanopyCallResult.Success(CanopyDiscovery(1, 1))
		transport.lastRequest?.path shouldBe "/JellyfinCanopy/Platform/v1/discovery"
		transport.lastRequest?.method shouldBe HttpMethod.GET
	}

	test("a platform reporting itself unavailable is absent") {
		val unavailable = fixture("discovery.200.json").replace("\"Available\": true", "\"Available\": false")
		CanopyClient(FixtureTransport(response(200, unavailable))).discover() shouldBe CanopyCallResult.Absent
	}

	test("negotiation uses the existing authenticated GET contract") {
		val transport = FixtureTransport(response(200, fixture("negotiate.compatible.200.json")))
		val result = CanopyClient(transport).negotiate()

		result shouldBe CanopyCallResult.Success(CanopyNegotiation(true, 1, 1, 1))
		transport.lastRequest!!.query shouldContain ("protocolMinimum" to 1)
		transport.lastRequest!!.query shouldContain ("protocolMaximum" to 1)
	}

	test("negotiation rejects a selected protocol outside the offered client range") {
		val invalid = fixture("negotiate.compatible.200.json")
			.replace("\"Protocol\": 1", "\"Protocol\": 2")
			.replace("\"HostProtocolMaximum\": 1", "\"HostProtocolMaximum\": 2")
		val result = CanopyClient(FixtureTransport(response(200, invalid))).negotiate(1, 1)

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
	}

	test("resolve omits unknown contributions and safely falls back unknown presentation values") {
		val transport = FixtureTransport(response(200, fixture("resolve.action-status.200.json"), mapOf("etag" to listOf("\"revision-7\""))))
		val result = CanopyClient(transport).resolveItemDetail(
			UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
			Locale.forLanguageTag("en-AU"),
		)

		val success = result as CanopyCallResult.Success
		success.etag shouldBe "\"revision-7\""
		success.value.contributions.size shouldBe 2
		(success.value.contributions[0] as CanopyContribution.Action).icon shouldBe CanopySemanticIcon.DEFAULT
		(success.value.contributions[1] as CanopyContribution.Status).tone shouldBe CanopyTone.NEUTRAL

		val body = transport.lastRequest!!.body!!.jsonObject
		body["Protocol"]!!.jsonPrimitive.content shouldBe "1"
		body["Item"]!!.jsonObject["Id"]!!.jsonPrimitive.content shouldBe "01234567-89ab-cdef-0123-456789abcdef"
		body["Client"]!!.jsonObject["FieldKinds"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
			listOf("confirmation", "boolean", "single_select", "multi_select")
	}

	test("resolve rejects duplicate contribution ids") {
		val duplicate = fixture("resolve.action-status.200.json")
			.replace("\"Id\": \"status-1\"", "\"Id\": \"action-1\"")
		val result = CanopyClient(FixtureTransport(response(200, duplicate))).resolveItemDetail(
			UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
		)

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
	}

	test("prepare maps all four bounded field kinds") {
		val transport = FixtureTransport(response(200, fixture("prepare.all-field-kinds.200.json")))
		val result = CanopyClient(transport).prepare("opaque-prepare-handle")

		val action = (result as CanopyCallResult.Success).value
		action.fields.map { it::class } shouldContainExactly listOf(
			CanopyField.Confirmation::class,
			CanopyField.BooleanValue::class,
			CanopyField.SingleSelect::class,
			CanopyField.MultiSelect::class,
		)
		(action.fields[2] as CanopyField.SingleSelect).defaultOptionId shouldBe "a"
		(action.fields[3] as CanopyField.MultiSelect).maximumSelections shouldBe 2
	}

	test("an unknown field kind disables the prepared action") {
		val fixture = fixture("prepare.all-field-kinds.200.json")
			.replace("\"confirmation\"", "\"future_field\"")
		val result = CanopyClient(FixtureTransport(response(200, fixture))).prepare("opaque-prepare-handle")

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.UNSUPPORTED_CONTRACT, 200)
	}

	test("invoke puts idempotency and typed answers in the JSON body") {
		val transport = FixtureTransport(response(200, fixture("invoke.success.200.json")))
		val idempotencyKey = UUID.fromString("9e30bb75-916e-48ac-984d-e65509cd2850")
		val result = CanopyClient(transport).invoke(
			capability = "opaque-invoke-capability",
			idempotencyKey = idempotencyKey,
			answers = listOf(
				CanopyAnswer.Confirmation("confirm", true),
				CanopyAnswer.SingleSelect("quality", "a"),
				CanopyAnswer.MultiSelect("targets", linkedSetOf("y", "x")),
			),
		)

		val success = result as CanopyCallResult.Success
		success.value.refreshTargets shouldBe setOf(
			CanopyRefreshTarget.JELLYFIN_ITEM,
			CanopyRefreshTarget.ITEM_DETAIL_SURFACE,
		)
		val body = transport.lastRequest!!.body!!.jsonObject
		body["IdempotencyKey"]!!.jsonPrimitive.content shouldBe idempotencyKey.toString()
		body["Answers"]!!.jsonArray.size shouldBe 3
		val optionIds = body["Answers"]!!.jsonArray[2].jsonObject["OptionIds"]!!.jsonArray.map { it.jsonPrimitive.content }
		optionIds shouldContainExactly
			listOf("x", "y")
		transport.lastRequest?.path shouldBe "/JellyfinCanopy/Platform/v1/actions/invoke"
	}

	test("zero-byte authorization failures are never decoded") {
		CanopyClient(FixtureTransport(response(401))).discover() shouldBe CanopyCallResult.Unauthorized
		CanopyClient(FixtureTransport(response(403))).discover() shouldBe CanopyCallResult.Forbidden
	}

	test("a zero-byte 404 means the optional platform is absent") {
		CanopyClient(FixtureTransport(response(404))).discover() shouldBe CanopyCallResult.Absent
	}

	test("a structured HTTP failure remains machine readable") {
		val result = CanopyClient(FixtureTransport(response(503, fixture("error.503.json")))).discover()
		result shouldBe CanopyCallResult.Failure(
			kind = CanopyFailureKind.HTTP,
			status = 503,
			error = CanopyPlatformError(
				code = "unavailable",
				message = "Temporarily unavailable.",
				retryable = true,
				correlationId = "0123456789abcdef0123456789abcdef",
			),
		)
	}

	test("oversized bodies are rejected before JSON decoding") {
		val body = ByteArray(CanopyContractBounds.MAX_ACTION_BYTES + 1) { 'x'.code.toByte() }
		val result = CanopyClient(FixtureTransport(CanopyHttpResponse(200, body, emptyMap()))).discover()
		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.RESPONSE_TOO_LARGE, 200)
	}

	test("deeply nested additive JSON is rejected before contract decoding") {
		val body = """{"Available":true,"ProtocolMinimum":1,"ProtocolMaximum":1,"Future":[[[[[[[[[]]]]]]]]]}"""
		val result = CanopyClient(FixtureTransport(response(200, body))).discover()

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
	}

	test("transport failures do not escape into the item screen") {
		val transport = CanopyTransport { _, _, _, _ -> error("offline") }
		CanopyClient(transport).discover() shouldBe CanopyCallResult.Failure(CanopyFailureKind.TRANSPORT)
	}
})

private data class RecordedRequest(
	val method: HttpMethod,
	val path: String,
	val query: Map<String, Any>,
	val body: JsonElement?,
)

private class FixtureTransport(
	private val response: CanopyHttpResponse,
) : CanopyTransport {
	var lastRequest: RecordedRequest? = null

	override suspend fun request(
		method: HttpMethod,
		path: String,
		query: Map<String, Any>,
		body: JsonElement?,
	): CanopyHttpResponse {
		lastRequest = RecordedRequest(method, path, query, body)
		return response
	}
}

private fun response(
	status: Int,
	body: String = "",
	headers: Map<String, List<String>> = emptyMap(),
) = CanopyHttpResponse(status, body.encodeToByteArray(), headers)

private fun fixture(name: String): String = checkNotNull(
	CanopyClientTests::class.java.getResource("/canopy/$name"),
) { "Missing fixture $name" }.readText()
