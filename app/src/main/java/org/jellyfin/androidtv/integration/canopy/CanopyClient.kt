package org.jellyfin.androidtv.integration.canopy

import java.nio.charset.CharacterCodingException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import timber.log.Timber

internal enum class CanopyBodyReadMode {
	/** Jellyfin SDK 1.8.x materialized the complete body before Canopy could inspect its size. */
	SDK_BUFFERED_BEFORE_LIMIT_CHECK,

	/** The SDK reported a non-success status without exposing its response body. */
	SDK_STATUS_ONLY,

	/** The shared SDK client bounded this exact Platform response while reading. */
	BOUNDED_DURING_READ,
}

internal data class CanopyHttpResponse(
	val status: Int,
	val body: ByteArray,
	val headers: Map<String, List<String>>,
	val bodyReadMode: CanopyBodyReadMode,
)

internal fun interface CanopyTransport {
	suspend fun request(
		method: HttpMethod,
		path: String,
		query: Map<String, Any>,
		body: JsonElement?,
		maximumResponseBytes: Int,
	): CanopyHttpResponse
}

internal class ApiClientCanopyTransport(
	private val apiClient: ApiClient,
	private val requestRegistry: CanopyRequestRegistry = CanopyRequestRegistry.shared,
) : CanopyTransport {
	override suspend fun request(
		method: HttpMethod,
		path: String,
		query: Map<String, Any>,
		body: JsonElement?,
		maximumResponseBytes: Int,
	): CanopyHttpResponse {
		val route = CanopyPlatformRoutes.exactRelative(method, path)
			?.takeIf { it.maximumResponseBytes == maximumResponseBytes }
			?: throw IllegalArgumentException(UNREVIEWED_ROUTE_MESSAGE)
		val requestUrl = apiClient.createUrl(path, emptyMap(), query).toHttpUrl()
		val registration = requestRegistry.register(method, requestUrl, route)
		return try {
			val response = apiClient.request(method, path, emptyMap(), query, body)
			CanopyHttpResponse(
				status = response.status,
				body = response.body,
				headers = response.headers,
				bodyReadMode = if (response.wasBoundedDuringRead()) {
					CanopyBodyReadMode.BOUNDED_DURING_READ
				} else {
					CanopyBodyReadMode.SDK_BUFFERED_BEFORE_LIMIT_CHECK
				},
			)
		} catch (error: InvalidStatusException) {
			CanopyHttpResponse(
				status = error.status,
				body = byteArrayOf(),
				headers = emptyMap(),
				bodyReadMode = CanopyBodyReadMode.SDK_STATUS_ONLY,
			)
		} catch (error: ApiClientException) {
			val bounded = error.exactCanopyBoundedResponseOrNull() ?: throw error
			CanopyHttpResponse(
				status = bounded.status,
				body = bounded.body,
				headers = bounded.headers,
				bodyReadMode = CanopyBodyReadMode.BOUNDED_DURING_READ,
			)
		} finally {
			registration.close()
		}
	}

	private fun org.jellyfin.sdk.api.client.RawResponse.wasBoundedDuringRead() = headers.entries
		.firstOrNull { (name) -> name.equals(CanopyResponseBoundingInterceptor.BOUNDED_HEADER, ignoreCase = true) }
		?.value
		?.singleOrNull() == CanopyResponseBoundingInterceptor.BOUNDED_HEADER_VALUE

	private companion object {
		const val UNREVIEWED_ROUTE_MESSAGE = "Unreviewed Canopy route"
	}
}

/**
 * Feature-neutral client for the size-rejecting native item-detail contract.
 *
 * It deliberately uses the session-bound Jellyfin [ApiClient] rather than owning
 * a second HTTP or authentication stack. It also never accepts a URL, provider
 * operation, acting user, or access token from a caller.
 *
 * The shared SDK factory installs a route-exact interceptor that enforces contract
 * byte limits while reading, before SDK 1.8.x materializes its RawResponse.
 */
internal interface CanopyGateway {
	suspend fun discover(): CanopyCallResult<CanopyDiscovery>
	suspend fun negotiate(protocolMinimum: Int = 1, protocolMaximum: Int = 1): CanopyCallResult<CanopyNegotiation>
	suspend fun resolveItemDetail(itemId: UUID, locale: Locale = Locale.getDefault()): CanopyCallResult<CanopyResolvedSurface>
	suspend fun prepare(prepareHandle: CanopyPrepareHandle): CanopyCallResult<CanopyPreparedAction>
	suspend fun invoke(
		preparedAction: CanopyPreparedAction,
		idempotencyKey: UUID,
		answers: List<CanopyAnswer>,
	): CanopyCallResult<CanopyActionResult>
}

internal class CanopyClient internal constructor(
	private val transport: CanopyTransport,
	private val json: Json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	},
) : CanopyGateway {
	constructor(apiClient: ApiClient) : this(ApiClientCanopyTransport(apiClient))

	override suspend fun discover(): CanopyCallResult<CanopyDiscovery> {
		val result = get<CanopyDiscoveryWire, CanopyDiscoveryWire>(
			path = CanopyPlatformRoutes.discovery.encodedPath,
			maximumBytes = CanopyPlatformRoutes.discovery.maximumResponseBytes,
			emptyNotFoundIsAbsent = true,
			mapper = { it },
		)
		return when (result) {
			is CanopyCallResult.Success -> if (!result.value.available) {
				CanopyCallResult.Absent
			} else {
				try {
					CanopyCallResult.Success(CanopyContractMapper.discovery(result.value), result.etag)
				} catch (_: CanopyContractException) {
					invalidResponse(HTTP_OK)
				}
			}
			CanopyCallResult.Absent -> CanopyCallResult.Absent
			CanopyCallResult.Unauthorized -> CanopyCallResult.Unauthorized
			CanopyCallResult.Forbidden -> CanopyCallResult.Forbidden
			is CanopyCallResult.Failure -> result
		}
	}

	override suspend fun negotiate(
		protocolMinimum: Int,
		protocolMaximum: Int,
	): CanopyCallResult<CanopyNegotiation> {
		if (protocolMinimum <= 0 || protocolMaximum < protocolMinimum) {
			return invalidResponse()
		}
		return get(
			path = CanopyPlatformRoutes.negotiate.encodedPath,
			query = mapOf(
				"protocolMinimum" to protocolMinimum,
				"protocolMaximum" to protocolMaximum,
			),
			maximumBytes = CanopyPlatformRoutes.negotiate.maximumResponseBytes,
			mapper = { wire: CanopyNegotiationWire ->
				CanopyContractMapper.negotiation(wire).also { negotiation ->
					if (negotiation.compatible && negotiation.protocol !in protocolMinimum..protocolMaximum) {
						throw CanopyContractException(message = "Negotiated protocol was outside the client range")
					}
				}
			},
		)
	}

	override suspend fun resolveItemDetail(
		itemId: UUID,
		locale: Locale,
	): CanopyCallResult<CanopyResolvedSurface> {
		val request = CanopyResolveRequestWire(
			protocol = PROTOCOL_VERSION,
			surfaceSchema = ITEM_DETAIL_SCHEMA,
			item = CanopyItemReferenceWire(itemId.toString().lowercase(Locale.ROOT)),
			client = CanopyClientCapabilitiesWire(
				contributionKinds = listOf("action", "status"),
				fieldKinds = listOf("confirmation", "boolean", "single_select", "multi_select"),
				inputModes = listOf("dpad"),
				accessibility = listOf("screen_reader"),
				locale = locale.toLanguageTag(),
			),
		)
		return post(
			path = CanopyPlatformRoutes.resolveItemDetail.encodedPath,
			request = json.encodeToJsonElement(request),
			maximumBytes = CanopyPlatformRoutes.resolveItemDetail.maximumResponseBytes,
			mapper = CanopyContractMapper::resolvedSurface,
		)
	}

	override suspend fun prepare(prepareHandle: CanopyPrepareHandle): CanopyCallResult<CanopyPreparedAction> {
		return post(
			path = CanopyPlatformRoutes.prepare.encodedPath,
			request = json.encodeToJsonElement(CanopyPrepareRequestWire(prepareHandle.wireValue())),
			maximumBytes = CanopyPlatformRoutes.prepare.maximumResponseBytes,
			mapper = CanopyContractMapper::preparedAction,
		)
	}

	override suspend fun invoke(
		preparedAction: CanopyPreparedAction,
		idempotencyKey: UUID,
		answers: List<CanopyAnswer>,
	): CanopyCallResult<CanopyActionResult> {
		// Deliberate draft-contract dependency: Jellyfin SDK 1.8.12 cannot attach a custom
		// idempotency header. The future /actions/invoke schema must therefore freeze the
		// body-carried IdempotencyKey parsed by the transport-neutral Canopy #522 work.
		val request = try {
			CanopyContractMapper.invokeRequest(preparedAction, idempotencyKey.toString(), answers)
		} catch (_: CanopyContractException) {
			return invalidResponse()
		}
		return post(
			path = CanopyPlatformRoutes.invoke.encodedPath,
			request = json.encodeToJsonElement(request),
			maximumBytes = CanopyPlatformRoutes.invoke.maximumResponseBytes,
			mapper = CanopyContractMapper::invokeResult,
		)
	}

	private suspend inline fun <reified W, T> get(
		path: String,
		query: Map<String, Any> = emptyMap(),
		maximumBytes: Int,
		emptyNotFoundIsAbsent: Boolean = false,
		crossinline mapper: (W) -> T,
	): CanopyCallResult<T> = execute(
		HttpMethod.GET,
		path,
		query,
		null,
		maximumBytes,
		emptyNotFoundIsAbsent,
		mapper,
	)

	private suspend inline fun <reified W, T> post(
		path: String,
		request: JsonElement,
		maximumBytes: Int,
		crossinline mapper: (W) -> T,
	): CanopyCallResult<T> = execute(HttpMethod.POST, path, emptyMap(), request, maximumBytes, false, mapper)

	private suspend inline fun <reified W, T> execute(
		method: HttpMethod,
		path: String,
		query: Map<String, Any>,
		body: JsonElement?,
		maximumBytes: Int,
		emptyNotFoundIsAbsent: Boolean,
		crossinline mapper: (W) -> T,
	): CanopyCallResult<T> {
		val response = try {
			transport.request(method, path, query, body, maximumBytes)
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			return CanopyCallResult.Failure(CanopyFailureKind.TRANSPORT)
		}

		if (response.status == HTTP_UNAUTHORIZED) return CanopyCallResult.Unauthorized
		if (response.status == HTTP_FORBIDDEN) return CanopyCallResult.Forbidden
		if (response.status == HTTP_NOT_FOUND && response.body.isEmpty() && emptyNotFoundIsAbsent) {
			return CanopyCallResult.Absent
		}
		if (response.body.size > maximumBytes) {
			return CanopyCallResult.Failure(CanopyFailureKind.BUFFERED_RESPONSE_TOO_LARGE, response.status)
		}
		if (response.status != HTTP_OK) {
			return if (response.status in HTTP_SUCCESS_MINIMUM..HTTP_SUCCESS_MAXIMUM) {
				invalidResponse(response.status)
			} else {
				failure(response)
			}
		}
		if (response.body.isEmpty()) return invalidResponse(response.status)
		if (!response.hasJsonContentType()) return invalidResponse(response.status)
		val responseText = try {
			response.body.decodeToString(throwOnInvalidSequence = true)
		} catch (_: CharacterCodingException) {
			return invalidResponse(response.status)
		}
		if (!responseText.hasBoundedJsonNesting() || !CanopyDuplicateKeyGuard.accepts(responseText)) {
			return invalidResponse(response.status)
		}

		return try {
			val wire = json.decodeFromString<W>(responseText)
			CanopyCallResult.Success(mapper(wire), response.etag())
		} catch (error: CanopyContractException) {
			// Never attach the exception: serialization/contract exception messages can
			// include attacker-controlled response fragments or opaque capabilities.
			Timber.w("Canopy response violated the supported contract")
			CanopyCallResult.Failure(
				kind = if (error.unsupported) CanopyFailureKind.UNSUPPORTED_CONTRACT else CanopyFailureKind.INVALID_RESPONSE,
				status = response.status,
			)
		} catch (_: SerializationException) {
			Timber.w("Canopy response could not be decoded")
			invalidResponse(response.status)
		} catch (_: IllegalArgumentException) {
			Timber.w("Canopy response was invalid")
			invalidResponse(response.status)
		}
	}

	private fun failure(response: CanopyHttpResponse): CanopyCallResult.Failure {
		val error = runCatching {
			response.body.decodeToString(throwOnInvalidSequence = true)
		}.getOrNull()
			?.takeIf { it.hasBoundedJsonNesting() && CanopyDuplicateKeyGuard.accepts(it) }
			?.let {
				try {
					CanopyContractMapper.platformError(json.decodeFromString<CanopyErrorWire>(it))
				} catch (_: RuntimeException) {
					null
				}
			}
		return CanopyCallResult.Failure(CanopyFailureKind.HTTP, response.status, error)
	}

	private fun String.hasBoundedJsonNesting(): Boolean {
		var depth = 0
		var inString = false
		var escaped = false
		for (character in this) {
			if (inString) {
				when {
					escaped -> escaped = false
					character == '\\' -> escaped = true
					character == '"' -> inString = false
				}
			} else {
				when (character) {
					'"' -> inString = true
					'{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
					'}', ']' -> if (--depth < 0) return false
				}
			}
		}
		return depth == 0 && !inString
	}

	private fun invalidResponse(status: Int? = null) =
		CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, status)

	private fun CanopyHttpResponse.singleHeaderValue(name: String): String? = headers.entries
		.filter { (headerName) -> headerName.equals(name, ignoreCase = true) }
		.singleOrNull()
		?.value
		?.singleOrNull()

	private fun CanopyHttpResponse.etag(): String? = singleHeaderValue("ETag")?.takeIf(STRONG_ETAG::matches)

	private fun CanopyHttpResponse.hasJsonContentType(): Boolean = singleHeaderValue("Content-Type")
		?.substringBefore(';')
		?.trim()
		?.equals("application/json", ignoreCase = true) == true

	private companion object {
		const val HTTP_OK = 200
		const val HTTP_SUCCESS_MINIMUM = 200
		const val HTTP_SUCCESS_MAXIMUM = 299
		const val HTTP_UNAUTHORIZED = 401
		const val HTTP_FORBIDDEN = 403
		const val HTTP_NOT_FOUND = 404
		const val MAX_JSON_DEPTH = 8
		const val PROTOCOL_VERSION = 1
		const val ITEM_DETAIL_SCHEMA = 1
		val STRONG_ETAG = Regex("^\"sha256-[0-9a-f]{64}\"$")
	}
}
