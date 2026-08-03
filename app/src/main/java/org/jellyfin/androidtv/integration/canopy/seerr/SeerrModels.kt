package org.jellyfin.androidtv.integration.canopy.seerr

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wire models for the Jellyfin Canopy Seerr proxy (`/JellyfinCanopy/seerr/*`).
 *
 * These are the same authorized routes the Canopy web client uses. They are a
 * plain server proxy to Seerr, so property names follow Seerr's camelCase JSON.
 * Unknown properties are ignored; only the members the TV UI needs are modeled.
 */
@Serializable
internal data class SeerrSearchResponseDto(
	val page: Int = 1,
	val totalPages: Int = 1,
	val totalResults: Int = 0,
	val results: List<SeerrSearchResultDto> = emptyList(),
)

@Serializable
internal data class SeerrSearchResultDto(
	val id: Long? = null,
	val mediaType: String? = null,
	val title: String? = null,
	val name: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val posterPath: String? = null,
	val mediaInfo: SeerrMediaInfoDto? = null,
)

@Serializable
internal data class SeerrMediaInfoDto(
	val status: Int? = null,
	val status4k: Int? = null,
	val jellyfinMediaId: String? = null,
	val jellyfinMediaId4k: String? = null,
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

/** A single Seerr search result, mapped for native rendering. */
internal data class SeerrDiscoverItem(
	val tmdbId: Long,
	val mediaType: SeerrMediaType,
	val title: String,
	val year: Int?,
	val posterUrl: String?,
	val status: SeerrMediaStatus,
	val status4k: SeerrMediaStatus,
	val jellyfinMediaId: UUID?,
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
