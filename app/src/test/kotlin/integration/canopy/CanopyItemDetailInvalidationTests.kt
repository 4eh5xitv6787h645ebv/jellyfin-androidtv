package org.jellyfin.androidtv.integration.canopy

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage

class CanopyItemDetailInvalidationTests : FunSpec({
	test("only the inert carrier with the exact Canopy config marker invalidates") {
		configMessage(GeneralCommandType.SET_PLAYBACK_ORDER, "config-changed").isExactCanopyConfigInvalidation() shouldBe true
		configMessage(GeneralCommandType.DISPLAY_MESSAGE, "config-changed").isExactCanopyConfigInvalidation() shouldBe false
		configMessage(GeneralCommandType.SET_PLAYBACK_ORDER, "native-catalog-changed").isExactCanopyConfigInvalidation() shouldBe false
		configMessage(GeneralCommandType.SET_PLAYBACK_ORDER, null).isExactCanopyConfigInvalidation() shouldBe false
	}

	test("only a noninitial transition back to connected invalidates") {
		isCanopyReconnect(null, SocketApiState.Connected) shouldBe false
		isCanopyReconnect(SocketApiState.Connected, SocketApiState.Connected) shouldBe false
		isCanopyReconnect(SocketApiState.Connecting, SocketApiState.Connected) shouldBe true
		isCanopyReconnect(SocketApiState.Disconnected(), SocketApiState.Connected) shouldBe true
		isCanopyReconnect(SocketApiState.Connected, SocketApiState.Disconnected()) shouldBe false
	}

	test("the visible provider fallback is conservatively pinned to two minutes") {
		CanopyItemDetailInvalidation.PERIODIC_REFRESH_MILLIS shouldBe 120_000L
	}

	test("each fresh resumed-style collection immediately revalidates") {
		runBlocking {
			val socket = FakeSocketApi()
			val signals = CanopyItemDetailInvalidation(socket, periodicRefreshMillis = 10_000L, coalesceMillis = 5L)
				.signals()

			repeat(2) {
				withTimeout(1_000L) { signals.take(1).toList() }.size shouldBe 1
			}
		}
	}

	test("a burst of exact config invalidations is coalesced") {
		runBlocking {
			val socket = FakeSocketApi()
			val emissions = mutableListOf<Unit>()
			val initial = CompletableDeferred<Unit>()
			val afterBurst = CompletableDeferred<Unit>()
			val collection = CanopyItemDetailInvalidation(socket, periodicRefreshMillis = 10_000L, coalesceMillis = 20L)
				.signals()
				.onEach {
					emissions += Unit
					initial.complete(Unit)
					if (emissions.size == 2) afterBurst.complete(Unit)
				}
				.launchIn(this)
			withTimeout(1_000L) { initial.await() }
			repeat(3) { socket.messages.emit(configMessage()) }
			withTimeout(1_000L) { afterBurst.await() }

			emissions.size shouldBe 2
			collection.cancel()
		}
	}

	test("the periodic fallback emits while collected") {
		runBlocking {
			val socket = FakeSocketApi()
			val result = async {
				CanopyItemDetailInvalidation(socket, periodicRefreshMillis = 20L, coalesceMillis = 5L)
					.signals()
					.take(2)
					.toList()
			}

			withTimeout(1_000L) { result.await() }.size shouldBe 2
		}
	}
})

private fun configMessage(
	command: GeneralCommandType = GeneralCommandType.SET_PLAYBACK_ORDER,
	marker: String? = CanopyItemDetailInvalidation.CONFIG_CHANGED,
) = GeneralCommandMessage(
	data = GeneralCommand(
		name = command,
		controllingUserId = UUID(0L, 0L),
		arguments = marker?.let { mapOf(CanopyItemDetailInvalidation.MARKER_KEY to it) } ?: emptyMap(),
	),
	messageId = UUID.randomUUID(),
)

private class FakeSocketApi : SocketApi {
	override val state = MutableStateFlow<SocketApiState>(SocketApiState.Connected)
	val messages = MutableSharedFlow<OutboundWebSocketMessage>(replay = 8, extraBufferCapacity = 8)

	override fun subscribeAll(): Flow<OutboundWebSocketMessage> = messages

	@Suppress("UNCHECKED_CAST")
	override fun <T : OutboundWebSocketMessage> subscribe(messageType: KClass<T>): Flow<T> = messages
		.filter(messageType::isInstance)
		.map { it as T }
}
