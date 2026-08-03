package org.jellyfin.androidtv.integration.canopy.seerr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.Locale

/**
 * Client for the Canopy Seerr proxy routes (`/JellyfinCanopy/seerr/…`).
 *
 * The native extension platform deliberately has no search or discovery
 * surface in protocol v1, so these consume the same authorized proxy routes
 * the Canopy web client uses. All requests ride the session-bound SDK
 * [ApiClient]: base URL, token and TLS are SDK-owned, and no credential is
 * ever accepted from a caller. Every read failure degrades to graceful
 * omission - rows simply do not appear.
 */
internal class SeerrRepository(
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
	 * not permanently hide the integration.
	 */
	suspend fun capabilities(): SeerrCapabilities {
		val currentUser = apiClient.accessToken
		val cached = cachedCapabilities
		if (cached != null && capabilitiesUserId == currentUser) {
			if (cached.available) return cached
			if (System.currentTimeMillis() - cachedCapabilitiesAt < NEGATIVE_STATUS_TTL_MS) return cached
		}

		val resolved = try {
			val status = json.decodeFromString<SeerrUserStatusDto>(
				get("/JellyfinCanopy/seerr/user-status").decodeToString(),
			)
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
	 * Searches Seerr. Returns movie, series and person entries in upstream
	 * order; collections are omitted.
	 */
	suspend fun search(query: String): List<SeerrEntry> {
		if (query.isBlank()) return emptyList()

		return list(
			path = "/JellyfinCanopy/seerr/search",
			query = mapOf(
				"query" to query,
				"page" to 1,
				"language" to language(),
			),
		) { result ->
			when (result.mediaType) {
				PERSON_MEDIA_TYPE -> result.toPersonItem()
				else -> result.toDiscoverItem()
			}
		}.take(MAX_ROW_RESULTS)
	}

	suspend fun trending(): List<SeerrDiscoverItem> = discover("/JellyfinCanopy/seerr/discover/trending")
	suspend fun popularMovies(): List<SeerrDiscoverItem> = discover("/JellyfinCanopy/seerr/discover/movies")
	suspend fun upcomingMovies(): List<SeerrDiscoverItem> = discover("/JellyfinCanopy/seerr/discover/movies/upcoming")
	suspend fun popularSeries(): List<SeerrDiscoverItem> = discover("/JellyfinCanopy/seerr/discover/tv")
	suspend fun upcomingSeries(): List<SeerrDiscoverItem> = discover("/JellyfinCanopy/seerr/discover/tv/upcoming")

	/** Genre tiles with backdrops for one media type. */
	suspend fun genres(mediaType: SeerrMediaType): List<SeerrGenreItem> = try {
		val path = when (mediaType) {
			SeerrMediaType.MOVIE -> "/JellyfinCanopy/seerr/discover/genreslider/movie"
			SeerrMediaType.TV -> "/JellyfinCanopy/seerr/discover/genreslider/tv"
		}
		json.decodeFromString<List<SeerrGenreDto>>(
			get(path, mapOf("language" to language())).decodeToString(),
		).mapNotNull { genre ->
			val id = genre.id ?: return@mapNotNull null
			val name = genre.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			SeerrGenreItem(
				genreId = id,
				name = name,
				mediaType = mediaType,
				backdropUrl = genre.backdrops.firstOrNull()?.toTmdbImageUrl(TMDB_BACKDROP_BASE),
			)
		}
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr genre slider failed")
		emptyList()
	} catch (error: SerializationException) {
		Timber.w(error, "Seerr genre slider returned an unexpected shape")
		emptyList()
	}

	/** One page of discover results for a genre. */
	suspend fun discoverByGenre(mediaType: SeerrMediaType, genreId: Long, page: Int): SeerrPage {
		val path = when (mediaType) {
			SeerrMediaType.MOVIE -> "/JellyfinCanopy/seerr/discover/movies/genre/$genreId"
			SeerrMediaType.TV -> "/JellyfinCanopy/seerr/discover/tv/genre/$genreId"
		}

		return try {
			val response = json.decodeFromString<SeerrListResponseDto>(
				get(path, mapOf("page" to page, "language" to language())).decodeToString(),
			)
			SeerrPage(
				items = response.results.mapNotNull { it.toDiscoverItem() },
				page = response.page,
				hasMore = response.page < response.totalPages,
			)
		} catch (error: ApiClientException) {
			Timber.d(error, "Seerr genre discover failed")
			SeerrPage(emptyList(), page, hasMore = false)
		} catch (error: SerializationException) {
			Timber.w(error, "Seerr genre discover returned an unexpected shape")
			SeerrPage(emptyList(), page, hasMore = false)
		}
	}

	suspend fun similar(item: SeerrDiscoverItem): List<SeerrDiscoverItem> =
		discover("/JellyfinCanopy/seerr/${item.mediaType.wireValue}/${item.tmdbId}/similar")

	suspend fun recommendations(item: SeerrDiscoverItem): List<SeerrDiscoverItem> =
		discover("/JellyfinCanopy/seerr/${item.mediaType.wireValue}/${item.tmdbId}/recommendations")

	/** Full details for a movie or series, or null when unavailable. */
	suspend fun details(mediaType: SeerrMediaType, tmdbId: Long): SeerrItemDetails? = try {
		val path = "/JellyfinCanopy/seerr/${mediaType.wireValue}/$tmdbId"
		val dto = json.decodeFromString<SeerrMediaDetailsDto>(
			get(path, mapOf("language" to language())).decodeToString(),
		)
		dto.toItemDetails(mediaType)
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr details failed for %s/%d", mediaType.wireValue, tmdbId)
		null
	} catch (error: SerializationException) {
		Timber.w(error, "Seerr details returned an unexpected shape")
		null
	}

	/** Person details, or null when unavailable. */
	suspend fun person(personId: Long): SeerrPersonDetails? = try {
		val dto = json.decodeFromString<SeerrPersonDetailsDto>(
			get("/JellyfinCanopy/seerr/person/$personId", mapOf("language" to language())).decodeToString(),
		)
		val id = dto.id
		val name = dto.name?.takeIf { it.isNotBlank() }
		if (id == null || name == null) null
		else SeerrPersonDetails(
			person = SeerrPersonItem(
				personId = id,
				name = name,
				profileUrl = dto.profilePath?.toTmdbImageUrl(TMDB_POSTER_BASE),
			),
			biography = dto.biography?.takeIf { it.isNotBlank() },
			knownFor = dto.knownForDepartment?.takeIf { it.isNotBlank() },
		)
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr person failed for %d", personId)
		null
	} catch (error: SerializationException) {
		Timber.w(error, "Seerr person returned an unexpected shape")
		null
	}

	/** A person's movie and series credits, newest first, deduplicated. */
	suspend fun personCredits(personId: Long): List<SeerrDiscoverItem> = try {
		json.decodeFromString<SeerrPersonCreditsDto>(
			get(
				"/JellyfinCanopy/seerr/person/$personId/combined_credits",
				mapOf("language" to language()),
			).decodeToString(),
		)
			.cast
			.mapNotNull { it.toDiscoverItem() }
			.distinctBy { it.mediaType to it.tmdbId }
			.sortedByDescending { it.year ?: Int.MIN_VALUE }
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr person credits failed for %d", personId)
		emptyList()
	} catch (error: SerializationException) {
		Timber.w(error, "Seerr person credits returned an unexpected shape")
		emptyList()
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

		return submit("/JellyfinCanopy/seerr/request", body)
	}

	/** Submits a request for specific seasons of a series. */
	suspend fun submitSeasonRequest(tmdbId: Long, seasons: List<Int>, is4k: Boolean): SeerrRequestOutcome {
		val body = buildJsonObject {
			put("mediaType", SeerrMediaType.TV.wireValue)
			put("mediaId", tmdbId)
			putJsonArray("seasons") { seasons.forEach { add(it) } }
			if (is4k) put("is4k", true)
		}

		return submit("/JellyfinCanopy/seerr/request", body)
	}

	private suspend fun submit(path: String, body: Any): SeerrRequestOutcome = try {
		withContext(Dispatchers.IO) {
			apiClient.request(HttpMethod.POST, path, emptyMap(), emptyMap(), body)
		}
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

	private suspend fun discover(path: String): List<SeerrDiscoverItem> = list(
		path = path,
		query = mapOf("language" to language()),
	) { it.toDiscoverItem() }
		.filterIsInstance<SeerrDiscoverItem>()
		.take(MAX_ROW_RESULTS)

	private suspend fun list(
		path: String,
		query: Map<String, Any>,
		map: (SeerrSearchResultDto) -> SeerrEntry?,
	): List<SeerrEntry> = try {
		json.decodeFromString<SeerrListResponseDto>(get(path, query).decodeToString())
			.results
			.mapNotNull(map)
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr list request failed for %s", path)
		emptyList()
	} catch (error: SerializationException) {
		Timber.w(error, "Seerr list request returned an unexpected shape for %s", path)
		emptyList()
	}

	// The SDK's raw request() executes on the calling dispatcher, so hop to IO
	// here; callers all run on the main dispatcher (lifecycle/viewModel scopes).
	private suspend fun get(path: String, query: Map<String, Any> = emptyMap()): ByteArray =
		withContext(Dispatchers.IO) {
			apiClient.request(HttpMethod.GET, path, emptyMap(), query, null).body
		}

	private fun language(): String = Locale.getDefault().language.ifBlank { "en" }

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
			posterUrl = posterPath?.toTmdbImageUrl(TMDB_POSTER_BASE),
			status = SeerrMediaStatus.fromWire(mediaInfo?.status),
			status4k = SeerrMediaStatus.fromWire(mediaInfo?.status4k),
			jellyfinMediaId = (mediaInfo?.jellyfinMediaId ?: mediaInfo?.jellyfinMediaId4k)?.toUUIDOrNull(),
		)
	}

	private fun SeerrSearchResultDto.toPersonItem(): SeerrPersonItem? {
		val personId = id ?: return null
		val personName = name?.takeIf { it.isNotBlank() } ?: return null

		return SeerrPersonItem(
			personId = personId,
			name = personName,
			profileUrl = profilePath?.toTmdbImageUrl(TMDB_POSTER_BASE),
		)
	}

	private fun SeerrMediaDetailsDto.toItemDetails(mediaType: SeerrMediaType): SeerrItemDetails? {
		val tmdbId = id ?: return null
		val itemTitle = (if (mediaType == SeerrMediaType.MOVIE) title else name)?.takeIf { it.isNotBlank() }
			?: title?.takeIf { it.isNotBlank() }
			?: name?.takeIf { it.isNotBlank() }
			?: return null

		val seasonStatuses = mediaInfo?.seasons.orEmpty()
			.mapNotNull { season ->
				season.seasonNumber?.let { it to SeerrMediaStatus.fromWire(season.status) }
			}
			.toMap()

		return SeerrItemDetails(
			item = SeerrDiscoverItem(
				tmdbId = tmdbId,
				mediaType = mediaType,
				title = itemTitle,
				year = (if (mediaType == SeerrMediaType.MOVIE) releaseDate else firstAirDate)
					?.take(YEAR_LENGTH)?.toIntOrNull(),
				posterUrl = posterPath?.toTmdbImageUrl(TMDB_POSTER_BASE),
				status = SeerrMediaStatus.fromWire(mediaInfo?.status),
				status4k = SeerrMediaStatus.fromWire(mediaInfo?.status4k),
				jellyfinMediaId = (mediaInfo?.jellyfinMediaId ?: mediaInfo?.jellyfinMediaId4k)?.toUUIDOrNull(),
			),
			overview = overview?.takeIf { it.isNotBlank() },
			genres = genres.mapNotNull { it.name?.takeIf(String::isNotBlank) },
			runtimeMinutes = runtime ?: episodeRunTime.firstOrNull(),
			communityRating = voteAverage?.toFloat(),
			backdropUrl = backdropPath?.toTmdbImageUrl(TMDB_BACKDROP_BASE),
			seasons = seasons.mapNotNull { season ->
				val number = season.seasonNumber ?: return@mapNotNull null
				if (number < 1) return@mapNotNull null
				SeerrSeason(
					number = number,
					name = season.name?.takeIf { it.isNotBlank() } ?: "Season $number",
					episodeCount = season.episodeCount,
					status = seasonStatuses[number] ?: SeerrMediaStatus.NOT_REQUESTED,
				)
			},
			cast = credits?.cast.orEmpty().mapNotNull { member ->
				val personId = member.id ?: return@mapNotNull null
				val personName = member.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				SeerrPersonItem(
					personId = personId,
					name = personName,
					profileUrl = member.profilePath?.toTmdbImageUrl(TMDB_POSTER_BASE),
					role = member.character?.takeIf { it.isNotBlank() },
				)
			}.take(MAX_ROW_RESULTS),
		)
	}

	private fun String.toTmdbImageUrl(base: String): String? =
		takeIf { TMDB_PATH_PATTERN.matches(it) }?.let { base + it }

	companion object {
		private const val MAX_ROW_RESULTS = 20
		private const val YEAR_LENGTH = 4
		private const val CONFLICT_STATUS = 409
		private const val NEGATIVE_STATUS_TTL_MS = 60_000L
		private const val PERSON_MEDIA_TYPE = "person"
		private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w400"
		private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
		private val TMDB_PATH_PATTERN = Regex("^/[A-Za-z0-9._-]+\\.(?:jpg|jpeg|png|webp)$")
		private val UNAVAILABLE = SeerrCapabilities(
			available = false,
			canRequest4kMovie = false,
			canRequest4kTv = false,
		)
	}
}
