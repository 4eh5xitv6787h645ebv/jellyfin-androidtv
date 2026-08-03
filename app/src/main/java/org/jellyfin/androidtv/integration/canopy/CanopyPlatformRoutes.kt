package org.jellyfin.androidtv.integration.canopy

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

	fun exactRelative(method: HttpMethod, encodedPath: String): CanopyPlatformRoute? = all.singleOrNull { route ->
		route.method == method && route.encodedPath == encodedPath
	}

	fun hasReviewedTerminal(method: String, encodedPath: String): Boolean = all.any { route ->
		route.method.name == method && encodedPath.endsWith(route.encodedPath)
	}
}
