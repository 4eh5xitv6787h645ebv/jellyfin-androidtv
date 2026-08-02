package org.jellyfin.androidtv.integration.canopy

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

class CanopyClientTests : FunSpec({
	test("discovery tolerates additive properties") {
		val transport = FixtureTransport(response(200, fixture("discovery.200.json")))
		val result = CanopyClient(transport).discover()

		result shouldBe CanopyCallResult.Success(CanopyDiscovery(1, 1))
		transport.lastRequest?.path shouldBe "/JellyfinCanopy/Platform/v1/discovery"
		transport.lastRequest?.method shouldBe HttpMethod.GET
		transport.lastRequest?.maximumResponseBytes shouldBe CanopyContractBounds.MAX_ACTION_BYTES
	}

	test("a platform reporting itself unavailable is absent") {
		val unavailable = fixture("discovery.200.json").replace("\"Available\": true", "\"Available\": false")
		CanopyClient(FixtureTransport(response(200, unavailable))).discover() shouldBe CanopyCallResult.Absent
	}

	test("ApiClient transport reuses the session client and passes JsonElement for SDK application json encoding") {
		val apiClient = mockk<ApiClient>()
		val payload = FIXTURE_JSON.parseToJsonElement("""{"PrepareHandle":"opaque"}""")
		coEvery {
			apiClient.request(HttpMethod.POST, "/bounded", emptyMap(), emptyMap(), payload)
		} returns RawResponse("{}".encodeToByteArray(), 200, jsonHeaders())
		val transport = ApiClientCanopyTransport(apiClient)

		val result = transport.request(HttpMethod.POST, "/bounded", emptyMap(), payload, 32)

		result.bodyReadMode shouldBe CanopyBodyReadMode.SDK_BUFFERED_BEFORE_LIMIT_CHECK
		coVerify(exactly = 1) {
			apiClient.request(HttpMethod.POST, "/bounded", emptyMap(), emptyMap(), payload)
		}
	}

	test("ApiClient status exceptions retain status without becoming transport failures") {
		val apiClient = mockk<ApiClient>()
		coEvery {
			apiClient.request(HttpMethod.GET, any(), emptyMap(), emptyMap(), null)
		} throws InvalidStatusException(404)

		CanopyClient(apiClient).discover() shouldBe CanopyCallResult.Absent
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
		val strongEtag = "\"sha256-${"0".repeat(64)}\""
		val transport = FixtureTransport(response(200, fixture("resolve.action-status.200.json"), mapOf("etag" to listOf(strongEtag))))
		val result = CanopyClient(transport).resolveItemDetail(
			UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
			Locale.forLanguageTag("en-AU"),
		)

		val success = result as CanopyCallResult.Success
		success.etag shouldBe strongEtag
		success.value.contributions.size shouldBe 2
		(success.value.contributions[0] as CanopyContribution.Action).icon shouldBe CanopySemanticIcon.DEFAULT
		(success.value.contributions[1] as CanopyContribution.Status).tone shouldBe CanopyTone.NEUTRAL

		val body = transport.lastRequest!!.body!!.jsonObject
		body["Protocol"]!!.jsonPrimitive.content shouldBe "1"
		body["Item"]!!.jsonObject["Id"]!!.jsonPrimitive.content shouldBe "01234567-89ab-cdef-0123-456789abcdef"
		body["Client"]!!.jsonObject["FieldKinds"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
			listOf("confirmation", "boolean", "single_select", "multi_select")
	}

	test("resolve retains only a canonical strong catalog etag") {
		val valid = "\"sha256-${"0".repeat(64)}\""
		listOf(
			mapOf("ETag" to listOf("\"revision-7\"")),
			mapOf("ETag" to listOf("W/$valid")),
			mapOf("ETag" to listOf("\"sha256-${"G".repeat(64)}\"")),
			mapOf("ETag" to listOf(valid, "\"forged\"")),
			linkedMapOf("ETag" to listOf(valid), "etag" to listOf(valid)),
		).forEach { ambiguousOrInvalid ->
			val result = CanopyClient(
				FixtureTransport(response(200, fixture("resolve.action-status.200.json"), ambiguousOrInvalid)),
			).resolveItemDetail(TEST_ITEM_ID) as CanopyCallResult.Success

			result.etag shouldBe null
		}
	}

	test("duplicate object properties fail closed for every successful response family") {
		val duplicateDiscovery = fixture("discovery.200.json")
			.replace("\"Available\": true", "\"Available\": true, \"Available\": false")
		val duplicateResolve = fixture("resolve.action-status.200.json")
			.replace("\"CatalogRevision\": \"revision-7\"", "\"CatalogRevision\": \"revision-7\", \"CatalogRevision\": \"forged\"")
		val duplicatePrepare = fixture("prepare.all-field-kinds.200.json")
			.replace("\"Title\": \"Apply settings\"", "\"Title\": \"Apply settings\", \"Title\": \"Forged\"")
		val duplicateInvoke = fixture("invoke.success.200.json")
			.replace("\"Outcome\": \"succeeded\"", "\"Outcome\": \"succeeded\", \"Outcome\": \"forged\"")

		CanopyClient(FixtureTransport(response(200, duplicateDiscovery))).discover() shouldBe invalidResponse()
		CanopyClient(FixtureTransport(response(200, duplicateResolve))).resolveItemDetail(TEST_ITEM_ID) shouldBe invalidResponse()
		CanopyClient(FixtureTransport(response(200, duplicatePrepare))).prepare(prepareHandle()) shouldBe invalidResponse()
		CanopyClient(FixtureTransport(response(200, duplicateInvoke))).invoke(
			preparedAction(),
			UUID.randomUUID(),
			requiredAnswers(),
		) shouldBe invalidResponse()
	}

	test("duplicate nested contribution field option message and refresh properties fail closed") {
		val duplicateContribution = fixture("resolve.action-status.200.json")
			.replace("\"Label\": \"Apply choice\"", "\"Label\": \"Apply choice\", \"Label\": \"Forged\"")
		val duplicateField = fixture("prepare.all-field-kinds.200.json")
			.replace("\"Label\": \"Continue?\"", "\"Label\": \"Continue?\", \"Label\": \"Forged\"")
		val duplicateOption = fixture("prepare.all-field-kinds.200.json")
			.replace("{\"Id\": \"a\", \"Label\": \"Option A\"}", "{\"Id\": \"a\", \"Id\": \"forged\", \"Label\": \"Option A\"}")
		val duplicateMessage = fixture("invoke.success.200.json")
			.replace("\"Text\": \"Applied\"", "\"Text\": \"Applied\", \"Text\": \"Forged\"")
		val duplicateRefresh = fixture("invoke.success.200.json")
			.replace("\"CatalogRevision\": \"revision-8\"", "\"CatalogRevision\": \"revision-8\", \"CatalogRevision\": \"forged\"")

		CanopyClient(FixtureTransport(response(200, duplicateContribution))).resolveItemDetail(TEST_ITEM_ID) shouldBe invalidResponse()
		listOf(duplicateField, duplicateOption).forEach { body ->
			CanopyClient(FixtureTransport(response(200, body))).prepare(prepareHandle()) shouldBe invalidResponse()
		}
		listOf(duplicateMessage, duplicateRefresh).forEach { body ->
			CanopyClient(FixtureTransport(response(200, body))).invoke(
				preparedAction(),
				UUID.randomUUID(),
				requiredAnswers(),
			) shouldBe invalidResponse()
		}
	}

	test("duplicate error properties discard the ambiguous structured envelope") {
		val duplicate = fixture("error.503.json")
			.replace("\"Code\": \"unavailable\"", "\"Code\": \"unavailable\", \"Code\": \"forged\"")

		CanopyClient(FixtureTransport(response(503, duplicate))).discover() shouldBe
			CanopyCallResult.Failure(CanopyFailureKind.HTTP, 503, error = null)
	}

	test("duplicate guard distinguishes sibling keys and string content but normalizes escaped keys") {
		CanopyDuplicateKeyGuard.accepts(
			"""{"Future":{"Id":"one"},"Peers":[{"Id":"two"},{"Id":"three"}],"Text":"\\\"Id\\\":not-a-key"}""",
		) shouldBe true
		CanopyDuplicateKeyGuard.accepts(
			"""{"Available":true,"\u0041vailable":false,"ProtocolMinimum":1,"ProtocolMaximum":1}""",
		) shouldBe false
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
		val result = CanopyClient(transport).prepare(prepareHandle())

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
		val result = CanopyClient(FixtureTransport(response(200, fixture))).prepare(prepareHandle())

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.UNSUPPORTED_CONTRACT, 200)
	}

	test("prepare accepts only canonical UTC expiry values with up to seven fractional digits") {
		val original = "2026-08-02T16:30:00Z"
		listOf(
			"2026-08-02T16:30:00Z",
			"2026-08-02T16:30:00.1234567Z",
			"2026-08-02T16:30:00+00:00",
		).forEach { expiry ->
			val body = fixture("prepare.all-field-kinds.200.json").replace(original, expiry)
			val result = CanopyClient(FixtureTransport(response(200, body))).prepare(prepareHandle())
			result::class shouldBe CanopyCallResult.Success::class
		}
	}

	test("prepare rejects noncanonical expiry forms") {
		val original = "2026-08-02T16:30:00Z"
		listOf(
			"2026-08-02 16:30:00Z",
			"2026-08-02t16:30:00z",
			"2026-08-02T16:30:00+01:00",
			"2026-08-02T16:30:00.12345678Z",
			"2026-08-02T16:30:00.123456789Z",
		).forEach { expiry ->
			val body = fixture("prepare.all-field-kinds.200.json").replace(original, expiry)
			val result = CanopyClient(FixtureTransport(response(200, body))).prepare(prepareHandle())
			result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
		}
	}

	test("multi-select defaults are count-bounded before set conversion") {
		val defaults = (0..CanopyContractBounds.MAX_OPTIONS).joinToString(",") { "\"$it\"" }
		val body = fixture("prepare.all-field-kinds.200.json")
			.replace("\"DefaultOptionIds\": [\"x\"]", "\"DefaultOptionIds\": [$defaults]")
		val result = CanopyClient(FixtureTransport(response(200, body))).prepare(prepareHandle())

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
	}

	test("opaque handles and capabilities redact string and generated data APIs") {
		val handle = prepareHandle()
		val capability = preparedAction().capability

		handle.toString().contains("opaque-prepare-handle") shouldBe false
		capability.toString().contains("opaque-invoke-capability") shouldBe false
		listOf(handle::class.java, capability::class.java).forEach { type ->
			type.declaredMethods.any { it.name == "copy" || it.name.startsWith("component") } shouldBe false
		}
	}

	test("opaque handle bounds use UTF-8 bytes at the exact boundary") {
		val exact = "é".repeat(CanopyContractBounds.MAX_OPAQUE_BYTES / 2)
		CanopyContractMapper.prepareHandle(exact).wireValue() shouldBe exact
		shouldThrow<CanopyContractException> {
			CanopyContractMapper.prepareHandle(exact + "a")
		}
	}

	test("SDK 1.8.12 draft invoke contract carries IdempotencyKey in body for transport-neutral 522 parser") {
		val transport = FixtureTransport(response(200, fixture("invoke.success.200.json")))
		val idempotencyKey = UUID.fromString("9e30bb75-916e-48ac-984d-e65509cd2850")
		val result = CanopyClient(transport).invoke(
			preparedAction = preparedAction(),
			idempotencyKey = idempotencyKey,
			answers = listOf(
				CanopyAnswer.Confirmation("confirm", true),
				CanopyAnswer.BooleanValue("enabled", true),
				CanopyAnswer.SingleSelect("quality", "a"),
				CanopyAnswer.MultiSelect("targets", listOf("y", "x")),
			),
		)

		val success = result as CanopyCallResult.Success
		success.value.refreshTargets shouldBe setOf(
			CanopyRefreshTarget.JELLYFIN_ITEM,
			CanopyRefreshTarget.ITEM_DETAIL_SURFACE,
		)
		val body = transport.lastRequest!!.body!!.jsonObject
		body["IdempotencyKey"]!!.jsonPrimitive.content shouldBe idempotencyKey.toString()
		body["Answers"]!!.jsonArray.size shouldBe 4
		val optionIds = body["Answers"]!!.jsonArray[3].jsonObject["OptionIds"]!!.jsonArray.map { it.jsonPrimitive.content }
		optionIds shouldContainExactly
			listOf("x", "y")
		transport.lastRequest?.path shouldBe "/JellyfinCanopy/Platform/v1/actions/invoke"
	}

	test("invoke rejects missing, mismatched, unknown, disabled, duplicate, and over-cardinality answers") {
		val valid = requiredAnswers()
		val invalidCases = listOf(
			valid.filterNot { it.fieldId == "quality" },
			valid.map { if (it.fieldId == "confirm") CanopyAnswer.BooleanValue("confirm", true) else it },
			valid.map { if (it.fieldId == "confirm") CanopyAnswer.Confirmation("confirm", false) else it },
			valid.map { if (it.fieldId == "quality") CanopyAnswer.SingleSelect("quality", "missing") else it },
			valid.map { if (it.fieldId == "quality") CanopyAnswer.SingleSelect("quality", "b") else it },
			valid + CanopyAnswer.BooleanValue("unknown", true),
			valid + CanopyAnswer.Confirmation("confirm", false),
			valid + CanopyAnswer.MultiSelect("targets", listOf("x", "x")),
			valid + CanopyAnswer.MultiSelect("targets", listOf("x", "y", "z")),
		)

		invalidCases.forEach { answers ->
			val transport = FixtureTransport(response(200, fixture("invoke.success.200.json")))
			val result = CanopyClient(transport).invoke(preparedAction(), UUID.randomUUID(), answers)
			result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE)
			transport.lastRequest shouldBe null
		}
	}

	test("invoke accepts exact multi-select minimum and maximum cardinalities") {
		listOf(emptyList(), listOf("x", "y")).forEach { selected ->
			val answers = requiredAnswers() + CanopyAnswer.MultiSelect("targets", selected)
			val result = CanopyClient(
				FixtureTransport(response(200, fixture("invoke.success.200.json"))),
			).invoke(preparedAction(), UUID.randomUUID(), answers)
			result::class shouldBe CanopyCallResult.Success::class
		}
	}

	test("refresh targets are explicitly count-bounded") {
		val targets = (0..CanopyContractBounds.MAX_REFRESH_TARGETS).joinToString(",") { "\"future_$it\"" }
		val body = fixture("invoke.success.200.json")
			.replace("\"jellyfin_item\", \"item_detail_surface\", \"future_target\"", targets)
		val result = CanopyClient(FixtureTransport(response(200, body))).invoke(
			preparedAction(),
			UUID.randomUUID(),
			requiredAnswers(),
		)

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
	}

	test("zero-byte authorization failures are never decoded") {
		CanopyClient(FixtureTransport(response(401))).discover() shouldBe CanopyCallResult.Unauthorized
		CanopyClient(FixtureTransport(response(403))).discover() shouldBe CanopyCallResult.Forbidden
	}

	test("a zero-byte 404 means the optional platform is absent") {
		CanopyClient(FixtureTransport(response(404))).discover() shouldBe CanopyCallResult.Absent
	}

	test("zero-byte 404 is HTTP failure for negotiate resolve prepare and invoke") {
		CanopyClient(FixtureTransport(response(404))).negotiate() shouldBe
			CanopyCallResult.Failure(CanopyFailureKind.HTTP, 404)
		CanopyClient(FixtureTransport(response(404))).resolveItemDetail(TEST_ITEM_ID) shouldBe
			CanopyCallResult.Failure(CanopyFailureKind.HTTP, 404)
		CanopyClient(FixtureTransport(response(404))).prepare(prepareHandle()) shouldBe
			CanopyCallResult.Failure(CanopyFailureKind.HTTP, 404)
		CanopyClient(FixtureTransport(response(404))).invoke(preparedAction(), UUID.randomUUID(), requiredAnswers()) shouldBe
			CanopyCallResult.Failure(CanopyFailureKind.HTTP, 404)
	}

	test("success requires exact 200 and application json with optional parameters") {
		CanopyClient(FixtureTransport(response(206, fixture("discovery.200.json")))).discover() shouldBe
			CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 206)
		CanopyClient(
			FixtureTransport(response(200, fixture("discovery.200.json"), jsonHeaders("text/plain"))),
		).discover() shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
		CanopyClient(
			FixtureTransport(response(200, fixture("discovery.200.json"), jsonHeaders("Application/JSON; charset=utf-8"))),
		).discover() shouldBe CanopyCallResult.Success(CanopyDiscovery(1, 1))
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

	test("an oversized SDK-buffered body is rejected before JSON decoding but after allocation") {
		val body = ByteArray(CanopyContractBounds.MAX_ACTION_BYTES + 1) { 'x'.code.toByte() }
		val result = CanopyClient(
			FixtureTransport(
				CanopyHttpResponse(
					status = 200,
					body = body,
					headers = jsonHeaders(),
					bodyReadMode = CanopyBodyReadMode.SDK_BUFFERED_BEFORE_LIMIT_CHECK,
				),
			),
		).discover()
		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.BUFFERED_RESPONSE_TOO_LARGE, 200)
	}

	test("an exactly capped buffered body remains decodable") {
		val body = discoveryBodyOfSize(CanopyContractBounds.MAX_ACTION_BYTES)
		body.encodeToByteArray().size shouldBe CanopyContractBounds.MAX_ACTION_BYTES

		CanopyClient(FixtureTransport(response(200, body))).discover() shouldBe
			CanopyCallResult.Success(CanopyDiscovery(1, 1))
	}

	test("deeply nested additive JSON is rejected before contract decoding") {
		val body = """{"Available":true,"ProtocolMinimum":1,"ProtocolMaximum":1,"Future":[[[[[[[[[]]]]]]]]]}"""
		val result = CanopyClient(FixtureTransport(response(200, body))).discover()

		result shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
	}

	test("transport failures do not escape into the item screen") {
		val transport = CanopyTransport { _, _, _, _, _ -> error("offline") }
		CanopyClient(transport).discover() shouldBe CanopyCallResult.Failure(CanopyFailureKind.TRANSPORT)
	}
})

private data class RecordedRequest(
	val method: HttpMethod,
	val path: String,
	val query: Map<String, Any>,
	val body: JsonElement?,
	val maximumResponseBytes: Int,
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
		maximumResponseBytes: Int,
	): CanopyHttpResponse {
		lastRequest = RecordedRequest(method, path, query, body, maximumResponseBytes)
		return response
	}
}

private fun response(
	status: Int,
	body: String = "",
	headers: Map<String, List<String>> = emptyMap(),
) = CanopyHttpResponse(
	status = status,
	body = body.encodeToByteArray(),
	headers = if (body.isNotEmpty() && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
		headers + jsonHeaders()
	} else {
		headers
	},
	bodyReadMode = CanopyBodyReadMode.SDK_BUFFERED_BEFORE_LIMIT_CHECK,
)

private fun jsonHeaders(value: String = "application/json") = mapOf("Content-Type" to listOf(value))

private fun invalidResponse() = CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)

private fun prepareHandle() = CanopyContractMapper.prepareHandle("opaque-prepare-handle")

private fun preparedAction(): CanopyPreparedAction = CanopyContractMapper.preparedAction(
	FIXTURE_JSON.decodeFromString<CanopyPrepareResponseWire>(fixture("prepare.all-field-kinds.200.json")),
)

private fun requiredAnswers(): List<CanopyAnswer> = listOf(
	CanopyAnswer.Confirmation("confirm", true),
	CanopyAnswer.BooleanValue("enabled", true),
	CanopyAnswer.SingleSelect("quality", "a"),
)

private fun discoveryBodyOfSize(size: Int): String {
	val prefix = """{"Available":true,"ProtocolMinimum":1,"ProtocolMaximum":1,"Future":""""
	val suffix = "\"}"
	return prefix + "x".repeat(size - prefix.length - suffix.length) + suffix
}

private val TEST_ITEM_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
private val FIXTURE_JSON = Json { ignoreUnknownKeys = true }

private fun fixture(name: String): String = checkNotNull(
	CanopyClientTests::class.java.getResource("/canopy/$name"),
) { "Missing fixture $name" }.readText()
