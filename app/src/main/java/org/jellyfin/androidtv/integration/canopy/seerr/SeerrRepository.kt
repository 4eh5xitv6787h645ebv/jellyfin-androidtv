package org.jellyfin.androidtv.integration.canopy.seerr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

	private val capabilitiesMutex = Mutex()
	private var cachedCapabilities: SeerrCapabilities? = null
	private var cachedCapabilitiesAt: Long = 0L
	private var capabilitiesUserId: String? = null

	private val _availability = MutableStateFlow<Boolean?>(null)

	/**
	 * Last known Seerr availability for the current session; null until the
	 * first [capabilities] resolution. Lets UI (e.g. the toolbar Discover
	 * button) omit Seerr surfaces gracefully instead of showing dead ends.
	 */
	val availability: StateFlow<Boolean?> = _availability.asStateFlow()

	/**
	 * Resolves whether Seerr is configured, reachable and linked for the
	 * current user. A positive answer is sticky for the session; a negative
	 * answer expires after [NEGATIVE_STATUS_TTL_MS] so a transient outage does
	 * not permanently hide the integration.
	 */
	suspend fun capabilities(): SeerrCapabilities = capabilitiesMutex.withLock {
		val currentUser = apiClient.accessToken
		val cached = cachedCapabilities
		if (cached != null && capabilitiesUserId == currentUser) {
			if (cached.available) return cached
			if (System.currentTimeMillis() - cachedCapabilitiesAt < NEGATIVE_STATUS_TTL_MS) return cached
		}

		val resolved = try {
			val status = fetch<SeerrUserStatusDto>("/JellyfinCanopy/seerr/user-status")
			SeerrCapabilities(
				available = status.active && status.userFound,
				canRequest4kMovie = status.canRequest4kMovie,
				canRequest4kTv = status.canRequest4kTv,
			)
		} catch (error: ApiClientException) {
			Timber.d(error, "Seerr user-status unavailable")
			UNAVAILABLE
		} catch (error: SerializationException) {
			// Never log the throwable: serialization messages can embed
			// server-controlled response fragments.
			Timber.w("Seerr user-status returned an unexpected shape")
			UNAVAILABLE
		}

		cachedCapabilities = resolved
		cachedCapabilitiesAt = System.currentTimeMillis()
		capabilitiesUserId = currentUser
		_availability.value = resolved.available
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
	suspend fun watchlist(): List<SeerrDiscoverItem> = discover("/JellyfinCanopy/seerr/watchlist")
	suspend fun moreFromStudio(studioId: Long): List<SeerrDiscoverItem> =
		discover("/JellyfinCanopy/seerr/discover/movies/studio/$studioId")
	suspend fun moreFromNetwork(networkId: Long): List<SeerrDiscoverItem> =
		discover("/JellyfinCanopy/seerr/discover/tv/network/$networkId")
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
		fetch<List<SeerrGenreDto>>(path, mapOf("language" to language())).mapNotNull { genre ->
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
		Timber.w("Seerr genre slider returned an unexpected shape")
		emptyList()
	}

	/** One page of discover results for a genre; null on transport failure so
	 * callers can distinguish an error (retryable) from the end of results. */
	suspend fun discoverByGenre(mediaType: SeerrMediaType, genreId: Long, page: Int): SeerrPage? {
		val path = when (mediaType) {
			SeerrMediaType.MOVIE -> "/JellyfinCanopy/seerr/discover/movies/genre/$genreId"
			SeerrMediaType.TV -> "/JellyfinCanopy/seerr/discover/tv/genre/$genreId"
		}

		return try {
			val response = fetch<SeerrListResponseDto>(path, mapOf("page" to page, "language" to language()))
			SeerrPage(
				items = response.results.mapNotNull { it.toDiscoverItem() },
				page = response.page,
				hasMore = response.page < response.totalPages,
			)
		} catch (error: ApiClientException) {
			Timber.d(error, "Seerr genre discover failed")
			null
		} catch (error: SerializationException) {
			Timber.w("Seerr genre discover returned an unexpected shape")
			null
		}
	}

	/** Movies belonging to a collection, e.g. all parts of a film series. */
	suspend fun collectionParts(collectionId: Long): List<SeerrDiscoverItem> = try {
		fetch<SeerrCollectionDto>("/JellyfinCanopy/seerr/collection/$collectionId", mapOf("language" to language())).parts.mapNotNull { it.toDiscoverItem() }.take(MAX_ROW_RESULTS)
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr collection failed for %d", collectionId)
		emptyList()
	} catch (error: SerializationException) {
		Timber.w("Seerr collection returned an unexpected shape")
		emptyList()
	}

	/** Combined critic/audience ratings; null members when a source is absent. */
	suspend fun ratings(item: SeerrDiscoverItem): SeerrRatings = try {
		when (item.mediaType) {
			SeerrMediaType.MOVIE -> {
				val dto = fetch<SeerrRatingsCombinedDto>("/JellyfinCanopy/seerr/movie/${item.tmdbId}/ratingscombined")
				SeerrRatings(
					rtCritics = dto.rt?.criticsScore?.toInt(),
					rtAudience = dto.rt?.audienceScore?.toInt(),
					imdb = dto.imdb?.criticsScore,
				)
			}

			SeerrMediaType.TV -> {
				val dto = fetch<SeerrRatingSourceDto>("/JellyfinCanopy/seerr/tv/${item.tmdbId}/ratings")
				SeerrRatings(
					rtCritics = dto.criticsScore?.toInt(),
					rtAudience = dto.audienceScore?.toInt(),
					imdb = null,
				)
			}
		}
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr ratings failed for %d", item.tmdbId)
		EMPTY_RATINGS
	} catch (error: SerializationException) {
		Timber.w("Seerr ratings returned an unexpected shape")
		EMPTY_RATINGS
	}

	/** Remaining request quota for one media type; null when unlimited/unknown. */
	suspend fun quota(mediaType: SeerrMediaType): SeerrQuotaBucket? = try {
		val dto = fetch<SeerrQuotaDto>("/JellyfinCanopy/seerr/quota")
		val bucket = if (mediaType == SeerrMediaType.MOVIE) dto.movie else dto.tv
		bucket?.takeIf { it.restricted }?.let { SeerrQuotaBucket(it.remaining, it.restricted) }
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr quota unavailable")
		null
	} catch (error: SerializationException) {
		Timber.w("Seerr quota returned an unexpected shape")
		null
	}

	/** Whether the Seerr server allows per-season (partial) series requests. */
	suspend fun partialRequestsEnabled(): Boolean = try {
		fetch<SeerrRequestSettingsDto>("/JellyfinCanopy/seerr/settings/partial-requests").partialRequestsEnabled
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr request settings unavailable")
		true
	} catch (error: SerializationException) {
		Timber.w("Seerr request settings returned an unexpected shape")
		true
	}

	suspend fun similar(item: SeerrDiscoverItem): List<SeerrDiscoverItem> =
		discover("/JellyfinCanopy/seerr/${item.mediaType.wireValue}/${item.tmdbId}/similar")

	suspend fun recommendations(item: SeerrDiscoverItem): List<SeerrDiscoverItem> =
		discover("/JellyfinCanopy/seerr/${item.mediaType.wireValue}/${item.tmdbId}/recommendations")

	/** Full details for a movie or series, or null when unavailable. */
	suspend fun details(mediaType: SeerrMediaType, tmdbId: Long): SeerrItemDetails? = try {
		val path = "/JellyfinCanopy/seerr/${mediaType.wireValue}/$tmdbId"
		val dto = fetch<SeerrMediaDetailsDto>(path, mapOf("language" to language()))
		dto.toItemDetails(mediaType)
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr details failed for %s/%d", mediaType.wireValue, tmdbId)
		null
	} catch (error: SerializationException) {
		Timber.w("Seerr details returned an unexpected shape")
		null
	}

	/** Person details, or null when unavailable. */
	suspend fun person(personId: Long): SeerrPersonDetails? = try {
		val dto = fetch<SeerrPersonDetailsDto>("/JellyfinCanopy/seerr/person/$personId", mapOf("language" to language()))
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
		Timber.w("Seerr person returned an unexpected shape")
		null
	}

	/** A person's movie and series credits, newest first, deduplicated. */
	suspend fun personCredits(personId: Long): List<SeerrDiscoverItem> = try {
		fetch<SeerrPersonCreditsDto>(
			"/JellyfinCanopy/seerr/person/$personId/combined_credits",
			mapOf("language" to language()),
		)
			.cast
			.mapNotNull { it.toDiscoverItem() }
			.distinctBy { it.mediaType to it.tmdbId }
			.sortedByDescending { it.year ?: Int.MIN_VALUE }
			.take(MAX_CREDITS_RESULTS)
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr person credits failed for %d", personId)
		emptyList()
	} catch (error: SerializationException) {
		Timber.w("Seerr person credits returned an unexpected shape")
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
		fetch<SeerrListResponseDto>(path, query)
			.results
			.mapNotNull(map)
	} catch (error: ApiClientException) {
		Timber.d(error, "Seerr list request failed for %s", path)
		emptyList()
	} catch (error: SerializationException) {
		Timber.w(error, "Seerr list request returned an unexpected shape for %s", path)
		emptyList()
	}

	// The SDK's raw request() executes on the calling dispatcher and callers
	// all run on the main dispatcher (lifecycle/viewModel scopes), so both the
	// HTTP round trip and the JSON decode happen on IO.
	private suspend inline fun <reified T> fetch(path: String, query: Map<String, Any> = emptyMap()): T =
		withContext(Dispatchers.IO) {
			json.decodeFromString<T>(
				apiClient.request(HttpMethod.GET, path, emptyMap(), query, null).body.decodeToString(),
			)
		}

	private fun language(): String = Locale.getDefault().language.ifBlank { "en" }

	private fun SeerrSearchResultDto.toDiscoverItem(): SeerrDiscoverItem? {
		// Watchlist entries carry tmdbId instead of id.
		val resolvedId = id ?: tmdbId ?: return null
		val type = SeerrMediaType.fromWire(mediaType) ?: return null
		val name = (if (type == SeerrMediaType.MOVIE) title else name)?.takeIf { it.isNotBlank() }
			?: title?.takeIf { it.isNotBlank() }
			?: this.name?.takeIf { it.isNotBlank() }
			?: return null

		return SeerrDiscoverItem(
			tmdbId = resolvedId,
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
			collection = collection?.toNamedRef(),
			studio = productionCompanies.firstNotNullOfOrNull { it.toNamedRef() },
			network = networks.firstNotNullOfOrNull { it.toNamedRef() },
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

	private fun SeerrCollectionRefDto.toNamedRef(): SeerrNamedRef? {
		val refId = id ?: return null
		val refName = name?.takeIf { it.isNotBlank() } ?: return null
		return SeerrNamedRef(refId, refName)
	}

	private fun SeerrCompanyDto.toNamedRef(): SeerrNamedRef? {
		val refId = id ?: return null
		val refName = name?.takeIf { it.isNotBlank() } ?: return null
		return SeerrNamedRef(refId, refName)
	}

	private fun String.toTmdbImageUrl(base: String): String? =
		takeIf { TMDB_PATH_PATTERN.matches(it) }?.let { base + it }

	companion object {
		private const val MAX_ROW_RESULTS = 20
		private const val MAX_CREDITS_RESULTS = 50
		private const val YEAR_LENGTH = 4
		private const val CONFLICT_STATUS = 409
		private const val NEGATIVE_STATUS_TTL_MS = 60_000L
		private const val PERSON_MEDIA_TYPE = "person"
		private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w400"
		private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
		private val TMDB_PATH_PATTERN = Regex("^/[A-Za-z0-9._-]+\\.(?:jpg|jpeg|png|webp)$")
		private val EMPTY_RATINGS = SeerrRatings(null, null, null)
		private val UNAVAILABLE = SeerrCapabilities(
			available = false,
			canRequest4kMovie = false,
			canRequest4kTv = false,
		)
	}
}
