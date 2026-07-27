package org.jellyfin.androidtv.fork

import org.jellyfin.sdk.model.ServerVersion

/**
 * Static configuration for this fork.
 *
 * Anything that identifies the fork or gates its behavior belongs here, so it can be changed in one
 * place instead of being scattered through upstream files.
 */
object ForkConfig {
	/**
	 * Minimum Jellyfin server version this client supports.
	 *
	 * This fork is deliberately Jellyfin 12+ only. Rather than deleting upstream's compatibility
	 * code for older servers -- which would conflict on every single upstream merge -- we raise the
	 * floor that upstream already checks against and leave the legacy paths in place, unused.
	 *
	 * Note that the stable SDK pinned in `gradle/libs.versions.toml` targets the 10.x API
	 * (`jellyfin-core` 1.8.12 reports 10.11.11), so this floor rejects every server that SDK was
	 * generated against. Building against a v12 server means switching `sdk.version` in
	 * `gradle.properties` to `unstable-snapshot`.
	 *
	 * Consumed by `ServerRepository.minimumServerVersion`.
	 */
	val MINIMUM_SERVER_VERSION = ServerVersion(major = 12, minor = 0, patch = 0)
}
