package org.jellyfin.androidtv.integration.canopy

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.api.client.HttpMethod

/**
 * Bounds only the five reviewed Platform v1 responses before SDK 1.8.12 calls
 * ResponseBody.bytes(). This interceptor is installed on the existing SDK
 * OkHttpFactory base, so URL construction, authentication, TLS, timeouts and the
 * connection pool remain owned by the one Jellyfin SDK network stack.
 */
internal class CanopyResponseBoundingInterceptor(
	private val requestRegistry: CanopyRequestRegistry = CanopyRequestRegistry.shared,
) : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val route = requestRegistry.claim(request.method, request.url)
			?: if (CanopyPlatformRoutes.hasReviewedTerminal(request.method, request.url.encodedPath)) {
				throw CanopyUnregisteredRequestException()
			} else {
				return chain.proceed(request)
			}
		val response = chain.proceed(request)
		val boundedHeaders = response.boundedProtocolHeaders()
		val originalBody = response.body
		val contentType = originalBody?.contentType()
		val boundedBody = originalBody?.use { body ->
			body.source().use { source ->
				val sink = okio.Buffer()
				var remaining = route.maximumResponseBytes.toLong() + 1L
				while (remaining > 0L) {
					val read = source.read(sink, remaining)
					if (read == -1L) break
					remaining -= read
				}
				sink.readByteArray()
			}
		} ?: byteArrayOf()

		val oversized = boundedBody.size > route.maximumResponseBytes
		if (oversized || !response.isSuccessful) {
			throw CanopyBoundedResponseException(
				status = response.code,
				headers = boundedHeaders.toMultimap(),
				body = boundedBody,
			)
		}

		return response.newBuilder()
			.headers(boundedHeaders.newBuilder().add(BOUNDED_HEADER, BOUNDED_HEADER_VALUE).build())
			.body(boundedBody.toResponseBody(contentType))
			.build()
	}

	private fun Response.boundedProtocolHeaders(): Headers = Headers.Builder().apply {
		addAllBounded(HEADER_CONTENT_TYPE, headers.values(HEADER_CONTENT_TYPE), MAX_CONTENT_TYPE_BYTES)
		addAllBounded(HEADER_ETAG, headers.values(HEADER_ETAG), MAX_ETAG_BYTES)
	}.build()

	private fun Headers.Builder.addAllBounded(name: String, values: List<String>, maximumBytes: Int) {
		// Preserve cardinality so the contract layer can reject ambiguous repeated fields.
		// If any value exceeds the carrier bound, omit the entire field rather than
		// collapsing an invalid repeated field into an apparently valid singleton.
		if (values.any { !it.hasBoundedUtf8(maximumBytes) }) return
		values.forEach { add(name, it) }
	}

	private fun String.hasBoundedUtf8(maximumBytes: Int) =
		isNotEmpty() && toByteArray(StandardCharsets.UTF_8).size <= maximumBytes

	companion object {
		internal const val BOUNDED_HEADER = "X-Jellyfin-Canopy-Body-Bounded"
		internal const val BOUNDED_HEADER_VALUE = "1"
		private const val HEADER_CONTENT_TYPE = "Content-Type"
		private const val HEADER_ETAG = "ETag"
		private const val MAX_CONTENT_TYPE_BYTES = 256
		private const val MAX_ETAG_BYTES = 128
	}
}

/** Prevents a Platform-shaped request from ever reaching SDK-wide buffering. */
internal class CanopyUnregisteredRequestException : IOException(MESSAGE) {
	companion object {
		internal const val MESSAGE = "Unregistered Canopy request"
	}
}

/**
 * Carries exact request provenance from [ApiClientCanopyTransport] to the shared
 * OkHttp interceptor without adding a wire-visible marker or another HTTP stack.
 *
 * Registrations use the fully canonical SDK URL, including its configured base
 * path and query. This makes literal-percent and other valid base paths safe while
 * unrelated terminal-path lookalikes remain outside the interceptor. Identical
 * concurrent requests each own one queued registration.
 */
internal class CanopyRequestRegistry {
	private data class RequestKey(val method: String, val url: HttpUrl)

	private val monitor = Any()
	private val pending = mutableMapOf<RequestKey, ArrayDeque<Registration>>()

	fun register(method: HttpMethod, url: HttpUrl, route: CanopyPlatformRoute): Registration = synchronized(monitor) {
		val registration = Registration(method.name, url, route)
		pending.getOrPut(RequestKey(method.name, url), ::ArrayDeque).addLast(registration)
		registration
	}

	fun claim(method: String, url: HttpUrl): CanopyPlatformRoute? = synchronized(monitor) {
		val key = RequestKey(method, url)
		val queue = pending[key] ?: return@synchronized null
		val registration = queue.pollFirst() ?: return@synchronized null
		registration.closedOrClaimed = true
		if (queue.isEmpty()) pending.remove(key)
		registration.route
	}

	private fun cancel(registration: Registration) = synchronized(monitor) {
		if (registration.closedOrClaimed) return@synchronized
		registration.closedOrClaimed = true
		val key = RequestKey(registration.method, registration.url)
		val queue = pending[key] ?: return@synchronized
		queue.remove(registration)
		if (queue.isEmpty()) pending.remove(key)
	}

	internal inner class Registration(
		internal val method: String,
		internal val url: HttpUrl,
		internal val route: CanopyPlatformRoute,
	) : AutoCloseable {
		internal var closedOrClaimed = false

		override fun close() = cancel(this)
	}

	companion object {
		val shared = CanopyRequestRegistry()
	}
}

/**
 * Private response carrier used only across the SDK 1.8.12 IOException wrapper.
 * Throwable rendering is constant and never includes response bytes, headers or URLs.
 */
internal class CanopyBoundedResponseException(
	val status: Int,
	val headers: Map<String, List<String>>,
	val body: ByteArray,
) : IOException(MESSAGE) {
	companion object {
		internal const val MESSAGE = "Bounded Canopy response"
	}
}

internal fun Throwable.exactCanopyBoundedResponseOrNull(): CanopyBoundedResponseException? {
	var candidate: Throwable? = this
	repeat(MAX_CAUSE_DEPTH) {
		if (candidate is CanopyBoundedResponseException) return candidate
		val next = candidate?.cause
		if (next == null || next === candidate) return null
		candidate = next
	}
	return null
}

private const val MAX_CAUSE_DEPTH = 4
