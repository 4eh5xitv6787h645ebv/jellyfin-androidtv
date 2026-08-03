package org.jellyfin.androidtv.integration.canopy

import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drops cached artwork after a Canopy action changed how the server renders
 * it.
 *
 * Spoiler Guard blurs images and strips metadata server-side, but the blurred
 * image is served from the *same* URL and image tag as the unblurred one, so
 * Coil keeps serving the stale bitmap from its memory and disk caches until
 * something evicts it. Canopy state also affects related items (protecting a
 * series changes its episodes' thumbnails), and the cache cannot be queried
 * by item, so both caches are cleared wholesale.
 *
 * This is deliberately a blunt instrument: these actions are rare and
 * user-initiated, and the alternative — the user clearing app storage by hand
 * — is worse.
 */
internal class CanopyImageCache(
	private val imageLoader: ImageLoader,
) {
	suspend fun invalidate() {
		imageLoader.memoryCache?.clear()

		// Disk eviction touches the filesystem.
		withContext(Dispatchers.IO) {
			try {
				imageLoader.diskCache?.clear()
			} catch (error: Exception) {
				// Never let cache maintenance break the action that triggered it.
				Timber.w(error, "Unable to clear the Canopy image disk cache")
			}
		}
	}
}
