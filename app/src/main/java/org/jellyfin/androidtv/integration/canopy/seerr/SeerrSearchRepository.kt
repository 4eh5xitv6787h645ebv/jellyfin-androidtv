package org.jellyfin.androidtv.integration.canopy.seerr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.Locale

/**
 * Client for the Canopy Seerr proxy routes (`/JellyfinCanopy/seerr/*`).
 *
 * The native extension platform deliberately has no search surface in protocol
 * v1, so discovery-style search consumes the same authorized proxy routes the
 * Canopy web client uses. All requests ride the session-bound SDK [ApiClient]:
 * base URL, token and TLS are SDK-owned, and no credential is ever accepted
 * from a caller. Every failure degrades to graceful omission - the search row
 * simply does not appear.
 */
internal class SeerrSearchRepository(
	private val apiClient: ApiClient,
) {
	private val json = Json { ignoreUnknownKeys = true }

	private var cachedCapabilities: SeerrCapabilities? = null
	private var cachedCapabilitiesAt: Long = 0L
	private var capabilitiesUserId: String? = null

	/**
	 * Resolves whether Seerr is configured, reachable and linked for the
	 * current user. A positive answer is sticky for the session; a negative
	 * answer expires after [NEGATIVE_STATUS_TTL_MS] so a transient outage does
	 * not permanently hide search results.
	 */
	suspend fun capabilities(): SeerrCapabilities {
		val currentUser = apiClient.userId?.toString()
		val cached = cachedCapabilities
		if (cached != null && capabilitiesUserId == currentUser) {
			if (cached.available) return cached
			if (System.currentTimeMillis() - cachedCapabilitiesAt < NEGATIVE_STATUS_TTL_MS) return cached
		}

		val resolved = try {
			val response = request(HttpMethod.GET, "/JellyfinCanopy/seerr/user-status")
			val status = json.decodeFromString<SeerrUserStatusDto>(response.decodeToString())
			SeerrCapabilities(
				available = status.active && status.userFound,
				canRequest4kMovie = status.canRequest4kMovie,
				canRequest4kTv = status.canRequest4kTv,
			)
		} catch (error: ApiClientException) {
			Timber.d(error, "Seerr user-status unavailable")
			UNAVAILABLE
		} catch (error: SerializationException) {
			Timber.w(error, "Seerr user-status returned an unexpected shape")
			UNAVAILABLE
		}

		cachedCapabilities = resolved
		cachedCapabilitiesAt = System.currentTimeMillis()
		capabilitiesUserId = currentUser
		return resolved
	}

	/**
	 * Searches Seerr. Person and collection results are omitted - the native
	 * row renders only movie and series cards. Returns an empty list on any
	 * failure.
	 */
	suspend fun search(query: String): List<SeerrDiscoverItem> {
		if (query.isBlank()) return emptyList()

		return try {
			val response = request(
				method = HttpMethod.GET,
				path = "/JellyfinCanopy/seerr/search",
				query = mapOf(
					"query" to query,
					"page" to 1,
					"language" to Locale.getDefault().language.ifBlank { "en" },
				),
			)
			json.decodeFromString<SeerrSearchResponseDto>(response.decodeToString())
				.results
				.mapNotNull { it.toDiscoverItem() }
				.take(MAX_RESULTS)
		} catch (error: ApiClientException) {
			Timber.d(error, "Seerr search failed")
			emptyList()
		} catch (error: SerializationException) {
			Timber.w(error, "Seerr search returned an unexpected shape")
			emptyList()
		}
	}

	/**
	 * Submits a request for [item]. Series requests cover all seasons,
	 * matching the web client's card-level request behavior.
	 */
	suspend fun submitRequest(item: SeerrDiscoverItem, is4k: Boolean): SeerrRequestOutcome {
		val body = buildJsonObject {
			put("mediaType", item.mediaType.wireValue)
			put("mediaId", item.tmdbId)
			if (item.mediaType == SeerrMediaType.TV) put("seasons", "all")
			if (is4k) put("is4k", true)
		}

		return try {
			request(HttpMethod.POST, "/JellyfinCanopy/seerr/request", body = body)
			SeerrRequestOutcome.Submitted
		} catch (error: InvalidStatusException) {
			Timber.d(error, "Seerr request rejected with status %d", error.status)
			when (error.status) {
				CONFLICT_STATUS -> SeerrRequestOutcome.AlreadyRequested
				else -> SeerrRequestOutcome.Failed(null)
			}
		} catch (error: ApiClientException) {
			Timber.w(error, "Seerr request failed")
			SeerrRequestOutcome.Failed(null)
		}
	}

	private suspend fun request(
		method: HttpMethod,
		path: String,
		query: Map<String, Any> = emptyMap(),
		body: Any? = null,
	): ByteArray = apiClient.request(method, path, emptyMap(), query, body).body

	private fun SeerrSearchResultDto.toDiscoverItem(): SeerrDiscoverItem? {
		val tmdbId = id ?: return null
		val type = SeerrMediaType.fromWire(mediaType) ?: return null
		val name = (if (type == SeerrMediaType.MOVIE) title else name)?.takeIf { it.isNotBlank() }
			?: title?.takeIf { it.isNotBlank() }
			?: this.name?.takeIf { it.isNotBlank() }
			?: return null

		return SeerrDiscoverItem(
			tmdbId = tmdbId,
			mediaType = type,
			title = name,
			year = (if (type == SeerrMediaType.MOVIE) releaseDate else firstAirDate)
				?.take(YEAR_LENGTH)?.toIntOrNull(),
			posterUrl = posterPath
				?.takeIf { POSTER_PATH_PATTERN.matches(it) }
				?.let { TMDB_POSTER_BASE + it },
			status = SeerrMediaStatus.fromWire(mediaInfo?.status),
			status4k = SeerrMediaStatus.fromWire(mediaInfo?.status4k),
			jellyfinMediaId = (mediaInfo?.jellyfinMediaId ?: mediaInfo?.jellyfinMediaId4k)?.toUUIDOrNull(),
		)
	}

	companion object {
		private const val MAX_RESULTS = 20
		private const val YEAR_LENGTH = 4
		private const val CONFLICT_STATUS = 409
		private const val NEGATIVE_STATUS_TTL_MS = 60_000L
		private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w400"
		private val POSTER_PATH_PATTERN = Regex("^/[A-Za-z0-9._-]+\\.(?:jpg|jpeg|png|webp)$")
		private val UNAVAILABLE = SeerrCapabilities(
			available = false,
			canRequest4kMovie = false,
			canRequest4kTv = false,
		)
	}
}
