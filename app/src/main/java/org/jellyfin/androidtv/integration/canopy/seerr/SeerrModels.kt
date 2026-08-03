package org.jellyfin.androidtv.integration.canopy.seerr

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wire models for the Jellyfin Canopy Seerr proxy (`/JellyfinCanopy/seerr/…`).
 *
 * These are the same authorized routes the Canopy web client uses. They are a
 * plain server proxy to Seerr, so property names follow Seerr's camelCase JSON.
 * Unknown properties are ignored; only the members the TV UI needs are modeled.
 */
@Serializable
internal data class SeerrListResponseDto(
	val page: Int = 1,
	val totalPages: Int = 1,
	val totalResults: Int = 0,
	val results: List<SeerrSearchResultDto> = emptyList(),
)

@Serializable
internal data class SeerrSearchResultDto(
	val id: Long? = null,
	val tmdbId: Long? = null,
	val mediaType: String? = null,
	val title: String? = null,
	val name: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val posterPath: String? = null,
	val profilePath: String? = null,
	val popularity: Double? = null,
	val mediaInfo: SeerrMediaInfoDto? = null,
)

@Serializable
internal data class SeerrMediaInfoDto(
	val status: Int? = null,
	val status4k: Int? = null,
	val jellyfinMediaId: String? = null,
	val jellyfinMediaId4k: String? = null,
	val seasons: List<SeerrMediaInfoSeasonDto> = emptyList(),
)

@Serializable
internal data class SeerrMediaInfoSeasonDto(
	val seasonNumber: Int? = null,
	val status: Int? = null,
)

@Serializable
internal data class SeerrUserStatusDto(
	val active: Boolean = false,
	val userFound: Boolean = false,
	val reason: String? = null,
	val message: String? = null,
	val canRequest4kMovie: Boolean = false,
	val canRequest4kTv: Boolean = false,
)

@Serializable
internal data class SeerrGenreDto(
	val id: Long? = null,
	val name: String? = null,
	val backdrops: List<String> = emptyList(),
)

@Serializable
internal data class SeerrSeasonDto(
	val seasonNumber: Int? = null,
	val name: String? = null,
	val episodeCount: Int? = null,
)

@Serializable
internal data class SeerrCastMemberDto(
	val id: Long? = null,
	val name: String? = null,
	val character: String? = null,
	val profilePath: String? = null,
)

@Serializable
internal data class SeerrCreditsDto(
	val cast: List<SeerrCastMemberDto> = emptyList(),
)

@Serializable
internal data class SeerrCompanyDto(
	val id: Long? = null,
	val name: String? = null,
)

@Serializable
internal data class SeerrCollectionRefDto(
	val id: Long? = null,
	val name: String? = null,
)

@Serializable
internal data class SeerrCollectionDto(
	val id: Long? = null,
	val name: String? = null,
	val parts: List<SeerrSearchResultDto> = emptyList(),
)

@Serializable
internal data class SeerrRatingSourceDto(
	val criticsScore: Double? = null,
	val audienceScore: Double? = null,
)

@Serializable
internal data class SeerrRatingsCombinedDto(
	val rt: SeerrRatingSourceDto? = null,
	val imdb: SeerrRatingSourceDto? = null,
)

@Serializable
internal data class SeerrQuotaBucketDto(
	val limit: Int? = null,
	val remaining: Int? = null,
	val restricted: Boolean = false,
)

@Serializable
internal data class SeerrQuotaDto(
	val movie: SeerrQuotaBucketDto? = null,
	val tv: SeerrQuotaBucketDto? = null,
)

@Serializable
internal data class SeerrRequestSettingsDto(
	val partialRequestsEnabled: Boolean = true,
)

/**
 * Structured request outcome introduced by Canopy for native clients
 * (Jellyfin-Canopy #627). Older servers return the raw Seerr response
 * instead; absence of [outcome] means the envelope is not in use.
 */
@Serializable
internal data class SeerrRequestOutcomeDto(
	val outcome: String? = null,
	val submitted: Boolean = false,
	val retryable: Boolean = false,
	val message: String? = null,
)

@Serializable
internal data class SeerrMediaDetailsDto(
	val id: Long? = null,
	val title: String? = null,
	val name: String? = null,
	val overview: String? = null,
	val posterPath: String? = null,
	val backdropPath: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val runtime: Int? = null,
	val episodeRunTime: List<Int> = emptyList(),
	val voteAverage: Double? = null,
	val genres: List<SeerrGenreDto> = emptyList(),
	val seasons: List<SeerrSeasonDto> = emptyList(),
	val credits: SeerrCreditsDto? = null,
	val mediaInfo: SeerrMediaInfoDto? = null,
	val collection: SeerrCollectionRefDto? = null,
	val productionCompanies: List<SeerrCompanyDto> = emptyList(),
	val networks: List<SeerrCompanyDto> = emptyList(),
)

@Serializable
internal data class SeerrPersonDetailsDto(
	val id: Long? = null,
	val name: String? = null,
	val biography: String? = null,
	val profilePath: String? = null,
	val knownForDepartment: String? = null,
)

@Serializable
internal data class SeerrPersonCreditsDto(
	val cast: List<SeerrSearchResultDto> = emptyList(),
)

internal enum class SeerrMediaType(val wireValue: String) {
	MOVIE("movie"),
	TV("tv");

	companion object {
		fun fromWire(value: String?): SeerrMediaType? = entries.firstOrNull { it.wireValue == value }
	}
}

/**
 * Seerr media status enum, matching Seerr's `server/constants/media.ts`.
 * UNKNOWN and DELETED render (and behave) as not requested.
 */
internal enum class SeerrMediaStatus {
	NOT_REQUESTED,
	PENDING,
	PROCESSING,
	PARTIALLY_AVAILABLE,
	AVAILABLE,
	BLOCKED;

	val requestable: Boolean get() = this == NOT_REQUESTED

	companion object {
		fun fromWire(value: Int?): SeerrMediaStatus = when (value) {
			2 -> PENDING
			3 -> PROCESSING
			4 -> PARTIALLY_AVAILABLE
			5 -> AVAILABLE
			6 -> BLOCKED
			else -> NOT_REQUESTED
		}
	}
}

/** Any entry the Seerr rows can render. */
internal sealed interface SeerrEntry

/** A movie or series, either requestable or already known to the library. */
internal data class SeerrDiscoverItem(
	val tmdbId: Long,
	val mediaType: SeerrMediaType,
	val title: String,
	val year: Int?,
	val posterUrl: String?,
	val status: SeerrMediaStatus,
	val status4k: SeerrMediaStatus,
	val jellyfinMediaId: UUID?,
	val popularity: Double? = null,
) : SeerrEntry

/** An actor or other person from search results or credits. */
internal data class SeerrPersonItem(
	val personId: Long,
	val name: String,
	val profileUrl: String?,
	val role: String? = null,
) : SeerrEntry

/** A browsable genre tile. */
internal data class SeerrGenreItem(
	val genreId: Long,
	val name: String,
	val mediaType: SeerrMediaType,
	val backdropUrl: String?,
) : SeerrEntry

/** Tail card in the search row linking to the Discover screen. */
internal data object SeerrBrowseMoreItem : SeerrEntry

/** One requestable page of discover results. */
internal data class SeerrPage(
	val items: List<SeerrDiscoverItem>,
	val page: Int,
	val hasMore: Boolean,
)

internal data class SeerrSeason(
	val number: Int,
	val name: String,
	val episodeCount: Int?,
	val status: SeerrMediaStatus,
)

/** A named reference used for collection / studio / network follow-up rows. */
internal data class SeerrNamedRef(
	val id: Long,
	val name: String,
)

internal data class SeerrRatings(
	val rtCritics: Int?,
	val rtAudience: Int?,
	val imdb: Double?,
) {
	val isEmpty: Boolean get() = rtCritics == null && rtAudience == null && imdb == null
}

internal data class SeerrQuotaBucket(
	val remaining: Int?,
	val restricted: Boolean,
)

internal data class SeerrItemDetails(
	val item: SeerrDiscoverItem,
	val overview: String?,
	val genres: List<String>,
	val runtimeMinutes: Int?,
	val communityRating: Float?,
	val backdropUrl: String?,
	val seasons: List<SeerrSeason>,
	val cast: List<SeerrPersonItem>,
	val collection: SeerrNamedRef? = null,
	val studio: SeerrNamedRef? = null,
	val network: SeerrNamedRef? = null,
)

internal data class SeerrPersonDetails(
	val person: SeerrPersonItem,
	val biography: String?,
	val knownFor: String?,
)

internal sealed interface SeerrRequestOutcome {
	data object Submitted : SeerrRequestOutcome
	data object AlreadyRequested : SeerrRequestOutcome
	data class Failed(val message: String?) : SeerrRequestOutcome
}

internal data class SeerrCapabilities(
	val available: Boolean,
	val canRequest4kMovie: Boolean,
	val canRequest4kTv: Boolean,
)
