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
	 * This fork is deliberately 10.12+ only. Rather than deleting upstream's compatibility code for
	 * older servers -- which would conflict on every single upstream merge -- we raise the floor
	 * that upstream already checks against and leave the legacy paths in place, unused.
	 *
	 * Consumed by `ServerRepository.minimumServerVersion`.
	 */
	val MINIMUM_SERVER_VERSION = ServerVersion(major = 10, minor = 12, patch = 0)
}
