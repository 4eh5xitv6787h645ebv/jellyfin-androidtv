package org.jellyfin.androidtv.integration.canopy

import java.io.IOException
import java.nio.charset.StandardCharsets
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Bounds only the five reviewed Platform v1 responses before SDK 1.8.12 calls
 * ResponseBody.bytes(). This interceptor is installed on the existing SDK
 * OkHttpFactory base, so URL construction, authentication, TLS, timeouts and the
 * connection pool remain owned by the one Jellyfin SDK network stack.
 */
internal class CanopyResponseBoundingInterceptor : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val route = CanopyPlatformRoutes.exact(request.method, request.url.encodedPath)
			?: return chain.proceed(request)
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
		header(HEADER_CONTENT_TYPE)?.takeIf { it.hasBoundedUtf8(MAX_CONTENT_TYPE_BYTES) }?.let {
			add(HEADER_CONTENT_TYPE, it)
		}
		header(HEADER_ETAG)?.takeIf { it.hasBoundedUtf8(MAX_ETAG_BYTES) }?.let {
			add(HEADER_ETAG, it)
		}
	}.build()

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
