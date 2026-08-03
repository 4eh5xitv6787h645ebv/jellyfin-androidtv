package org.jellyfin.androidtv.integration.canopy

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage

/**
 * Feature-neutral invalidations for an open native item-detail contribution.
 *
 * The two-minute visible-only fallback covers provider state that has no Jellyfin
 * websocket event. Canopy remains responsible for provider caching, so this is a
 * conservative revalidation rather than client-side provider polling.
 */
internal class CanopyItemDetailInvalidation(
	private val socketApi: SocketApi,
	private val periodicRefreshMillis: Long = PERIODIC_REFRESH_MILLIS,
	private val coalesceMillis: Long = COALESCE_MILLIS,
) {
	@OptIn(FlowPreview::class)
	fun signals(): Flow<Unit> = merge(
		// repeatOnLifecycle creates a fresh collection on every RESUMED transition.
		// Revalidate immediately so events missed while stopped cannot leave a stale row.
		flowOf(Unit),
		socketApi.subscribe<UserDataChangedMessage>().map { Unit },
		socketApi.subscribe<LibraryChangedMessage>().map { Unit },
		socketApi.subscribeGeneralCommand(GeneralCommandType.SET_PLAYBACK_ORDER)
			.filter(GeneralCommandMessage::isExactCanopyConfigInvalidation)
			.map { Unit },
		reconnectionSignals(socketApi.state),
		periodicSignals(periodicRefreshMillis),
	).debounce(coalesceMillis)

	companion object {
		internal const val PERIODIC_REFRESH_MILLIS = 2L * 60L * 1_000L
		internal const val COALESCE_MILLIS = 500L
		internal const val MARKER_KEY = "JellyfinCanopy"
		internal const val CONFIG_CHANGED = "config-changed"
	}
}

internal fun GeneralCommandMessage.isExactCanopyConfigInvalidation(): Boolean {
	val command = data ?: return false
	return command.name == GeneralCommandType.SET_PLAYBACK_ORDER &&
		command.arguments[CanopyItemDetailInvalidation.MARKER_KEY] == CanopyItemDetailInvalidation.CONFIG_CHANGED
}

internal fun isCanopyReconnect(previous: SocketApiState?, current: SocketApiState): Boolean =
	previous != null && previous !is SocketApiState.Connected && current is SocketApiState.Connected

private fun reconnectionSignals(states: Flow<SocketApiState>): Flow<Unit> = flow {
	var previous: SocketApiState? = null
	states.collect { current ->
		if (isCanopyReconnect(previous, current)) emit(Unit)
		previous = current
	}
}

private fun periodicSignals(intervalMillis: Long): Flow<Unit> = flow {
	require(intervalMillis > 0L)
	while (currentCoroutineContext().isActive) {
		delay(intervalMillis)
		emit(Unit)
	}
}
