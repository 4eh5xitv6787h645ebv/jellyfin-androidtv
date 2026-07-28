package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.delete
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post
import java.util.UUID

/**
 * Talks to the Jellyfin Canopy plugin's Spoiler Guard endpoints.
 *
 * These are NOT part of the Jellyfin SDK — they are plugin routes under
 * `/JellyfinCanopy/`. [ApiClient.request] takes an arbitrary path template and still
 * applies the server base URL and the `Authorization` header, so no separate HTTP
 * client or token handling is needed.
 *
 * Spoiler Guard needs two things to have any visible effect, and this trips people up:
 *
 *  1. the title must be **enrolled** (`spoiler-blur/series/{id}`), and
 *  2. the user's **preferences** must say what to hide (`spoiler-blur/user-prefs`).
 *
 * Enrolling with no preferences set does nothing at all. [enable] therefore seeds a
 * sensible default preference set when the user has none, which is what someone
 * turning on a thing called "spoiler mode" expects.
 *
 * The actual hiding happens server-side: Canopy registers global MVC filters that
 * strip fields and blur images on Jellyfin's own responses, so this client sees the
 * effect in ordinary `/Shows/{id}/Episodes` results without rendering anything itself.
 */
interface CanopySpoilerRepository {
	/** Whether Canopy is installed and its Spoiler Guard endpoints answer. */
	suspend fun isAvailable(): Boolean

	/** Whether [seriesId] is currently enrolled for the signed-in user. */
	suspend fun isEnabled(seriesId: UUID): Boolean

	/** Enrols [seriesId], seeding default preferences if the user has none. */
	suspend fun enable(seriesId: UUID)

	/** Removes [seriesId] from Spoiler Guard. Preferences are left alone. */
	suspend fun disable(seriesId: UUID)
}

@Serializable
internal data class SpoilerBlurState(
	@SerialName("Series") val series: Map<String, JsonIgnored> = emptyMap(),
	@SerialName("Prefs") val prefs: SpoilerBlurPrefs? = null,
)

/** The per-entry payload is irrelevant here; only the key set matters. */
@Serializable
internal class JsonIgnored

@Serializable
internal data class SpoilerBlurPrefs(
	@SerialName("Revision") val revision: Long = 0,
	@SerialName("HideEpisodeDescriptions") val hideEpisodeDescriptions: Boolean? = null,
	@SerialName("ReplaceEpisodeTitles") val replaceEpisodeTitles: Boolean? = null,
	@SerialName("HideTaglines") val hideTaglines: Boolean? = null,
) {
	/** True when the user has never expressed a preference, so enrolling alone would be a no-op. */
	val isUnset: Boolean
		get() = hideEpisodeDescriptions == null && replaceEpisodeTitles == null && hideTaglines == null
}

class CanopySpoilerRepositoryImpl(
	private val api: ApiClient,
) : CanopySpoilerRepository {
	private companion object {
		const val STATE_PATH = "/JellyfinCanopy/spoiler-blur/series"
		const val SERIES_PATH = "/JellyfinCanopy/spoiler-blur/series/{seriesId}"
		const val PREFS_PATH = "/JellyfinCanopy/spoiler-blur/user-prefs"
	}

	override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
		runCatching { api.get<SpoilerBlurState>(STATE_PATH) }.isSuccess
	}

	override suspend fun isEnabled(seriesId: UUID): Boolean = withContext(Dispatchers.IO) {
		// Canopy keys its map by the dashless GUID form Jellyfin uses internally.
		api.get<SpoilerBlurState>(STATE_PATH).content.series.keys
			.any { it.equals(seriesId.toStringUnhyphenated(), ignoreCase = true) }
	}

	override suspend fun enable(seriesId: UUID) = withContext(Dispatchers.IO) {
		// Order matters: seed preferences first, so the enrolment takes visible effect
		// immediately rather than on some later change.
		val state = api.get<SpoilerBlurState>(STATE_PATH).content
		val prefs = state.prefs
		if (prefs == null || prefs.isUnset) {
			api.post<Unit>(
				pathTemplate = PREFS_PATH,
				requestBody = SpoilerBlurPrefs(
					revision = prefs?.revision ?: 0,
					hideEpisodeDescriptions = true,
					replaceEpisodeTitles = true,
					hideTaglines = true,
				),
			)
		}

		api.post<Unit>(
			pathTemplate = SERIES_PATH,
			pathParameters = mapOf("seriesId" to seriesId),
		)
		Unit
	}

	override suspend fun disable(seriesId: UUID) = withContext(Dispatchers.IO) {
		api.delete<Unit>(
			pathTemplate = SERIES_PATH,
			pathParameters = mapOf("seriesId" to seriesId),
		)
		Unit
	}
}

private fun UUID.toStringUnhyphenated() = toString().replace("-", "")
