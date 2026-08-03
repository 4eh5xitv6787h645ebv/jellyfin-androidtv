package org.jellyfin.androidtv.integration.canopy

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
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
		val route = when (val claim = requestRegistry.claim(chain.call(), request)) {
			is CanopyRequestRegistry.Claim.Bound -> claim.route
			CanopyRequestRegistry.Claim.Rejected -> throw CanopyUnregisteredRequestException()
			CanopyRequestRegistry.Claim.Unregistered -> if (
				CanopyPlatformRoutes.hasReviewedTerminal(request.method, request.url.encodedPath)
			) {
				throw CanopyUnregisteredRequestException()
			} else {
				return chain.proceed(request)
			}
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
 * A coroutine-scoped registration is bound synchronously to the exact OkHttp
 * [Call] identity when the SDK invokes OkHttpClient.newCall(). The interceptor
 * claims only that identity and rechecks the fully canonical URL and method. An
 * identical unregistered request therefore cannot steal a response bound, and no
 * marker is placed in a URL, header, body or log.
 */
internal class CanopyRequestRegistry {
	internal sealed interface Claim {
		data class Bound(val route: CanopyPlatformRoute) : Claim
		data object Rejected : Claim
		data object Unregistered : Claim
	}

	private class Registration(
		val method: String,
		val url: HttpUrl,
		val route: CanopyPlatformRoute,
	) {
		var closed = false
		var callCreated = false
		val calls: MutableSet<Call> = Collections.newSetFromMap(IdentityHashMap())
	}

	private data class Binding(val registration: Registration, val accepted: Boolean)

	private val monitor = Any()
	private val currentRegistration = ThreadLocal<Registration?>()
	private val bindings = IdentityHashMap<Call, Binding>()

	suspend fun <T> withRegistration(
		method: HttpMethod,
		url: HttpUrl,
		route: CanopyPlatformRoute,
		block: suspend () -> T,
	): T {
		check(currentRegistration.get() == null) { "Nested Canopy request registration" }
		val registration = Registration(method.name, url, route)
		return try {
			withContext(currentRegistration.asContextElement(registration)) { block() }
		} finally {
			close(registration)
		}
	}

	fun eventListenerFactory(
		delegate: EventListener.Factory = EventListener.Factory { EventListener.NONE },
	): EventListener.Factory = EventListener.Factory { call ->
		bindCurrentRegistration(call)
		delegate.create(call)
	}

	fun claim(call: Call, request: Request): Claim = synchronized(monitor) {
		val binding = bindings.remove(call) ?: return@synchronized Claim.Unregistered
		binding.registration.calls.remove(call)
		if (
			!binding.accepted ||
			binding.registration.closed ||
			request.method != binding.registration.method ||
			request.url != binding.registration.url
		) {
			Claim.Rejected
		} else {
			Claim.Bound(binding.registration.route)
		}
	}

	private fun bindCurrentRegistration(call: Call) {
		val registration = currentRegistration.get() ?: return
		val request = call.request()
		synchronized(monitor) {
			val accepted = !registration.closed &&
				!registration.callCreated &&
				request.method == registration.method &&
				request.url == registration.url
			registration.callCreated = true
			bindings[call] = Binding(registration, accepted)
			registration.calls.add(call)
		}
	}

	private fun close(registration: Registration) = synchronized(monitor) {
		registration.closed = true
		registration.calls.forEach(bindings::remove)
		registration.calls.clear()
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
