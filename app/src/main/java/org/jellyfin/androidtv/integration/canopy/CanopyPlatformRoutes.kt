package org.jellyfin.androidtv.integration.canopy

import java.net.URLDecoder
import org.jellyfin.sdk.api.client.HttpMethod

internal data class CanopyPlatformRoute(
	val method: HttpMethod,
	val encodedPath: String,
	val maximumResponseBytes: Int,
)

/**
 * The complete native pilot HTTP allowlist.
 *
 * Both the protocol client and the response-bounding interceptor use this table so
 * adding a request cannot silently bypass the pre-allocation response limit.
 */
internal object CanopyPlatformRoutes {
	private const val PREFIX = "/JellyfinCanopy/Platform/v1"

	val discovery = CanopyPlatformRoute(HttpMethod.GET, "$PREFIX/discovery", CanopyContractBounds.MAX_ACTION_BYTES)
	val negotiate = CanopyPlatformRoute(HttpMethod.GET, "$PREFIX/negotiate", CanopyContractBounds.MAX_ACTION_BYTES)
	val resolveItemDetail = CanopyPlatformRoute(
		HttpMethod.POST,
		"$PREFIX/surfaces/item-detail/resolve",
		CanopyContractBounds.MAX_RESOLVE_BYTES,
	)
	val prepare = CanopyPlatformRoute(HttpMethod.POST, "$PREFIX/actions/prepare", CanopyContractBounds.MAX_ACTION_BYTES)
	val invoke = CanopyPlatformRoute(HttpMethod.POST, "$PREFIX/actions/invoke", CanopyContractBounds.MAX_ACTION_BYTES)

	val all = listOf(discovery, negotiate, resolveItemDetail, prepare, invoke)

	fun exact(method: String, encodedPath: String): CanopyPlatformRoute? = all.singleOrNull { route ->
		route.method.name == method && encodedPath.hasExactTerminalRoute(route.encodedPath)
	}

	private fun String.hasExactTerminalRoute(routePath: String): Boolean {
		if (this == routePath) return true
		if (!endsWith(routePath)) return false
		return dropLast(routePath.length).isValidBasePathPrefix()
	}

	private fun String.isValidBasePathPrefix(): Boolean {
		if (!startsWith('/') || endsWith('/')) return false
		return drop(1).split('/').all { it.isValidBasePathSegment() }
	}

	private fun String.isValidBasePathSegment(): Boolean {
		val decoded = runCatching {
			// URLDecoder is form-oriented; preserve literal path '+' before decoding.
			URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8.name())
		}.getOrNull() ?: return false
		if (decoded.isEmpty() || decoded == "." || decoded == "..") return false
		if (decoded.equals(CANOPY_ROOT_SEGMENT, ignoreCase = true)) return false
		return '/' !in decoded && '\\' !in decoded
	}

	private const val CANOPY_ROOT_SEGMENT = "JellyfinCanopy"
}
