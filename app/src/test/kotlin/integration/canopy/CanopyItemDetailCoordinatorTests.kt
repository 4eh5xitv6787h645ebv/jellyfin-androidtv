package org.jellyfin.androidtv.integration.canopy

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CanopyItemDetailCoordinatorTests : FunSpec({
	test("form initializes every supported field from defaults and returns typed answers") {
		val action = preparedAction()
		val initial = CanopyActionForm.create(action)

		initial.isChecked("confirm") shouldBe false
		initial.isChecked("enabled") shouldBe true
		initial.singleSelection("quality") shouldBe "balanced"
		initial.multiSelection("targets") shouldBe setOf("one")
		initial.validationErrors() shouldBe setOf("confirm")

		val completed = initial
			.setChecked("confirm", true)
			.selectOne("quality", "fast")
			.setSelected("targets", "two", true)
		completed.validationErrors() shouldBe emptySet()
		completed.answers() shouldContainExactly listOf(
			CanopyAnswer.Confirmation("confirm", true),
			CanopyAnswer.BooleanValue("enabled", true),
			CanopyAnswer.SingleSelect("quality", "fast"),
			CanopyAnswer.MultiSelect("targets", listOf("one", "two")),
		)
	}

	test("form ignores unknown and disabled updates and enforces cardinality") {
		val initial = CanopyActionForm.create(preparedAction()).setChecked("confirm", true)

		initial.selectOne("quality", "disabled") shouldBe initial
		initial.setSelected("targets", "disabled", true) shouldBe initial
		initial.setSelected("missing", "one", true) shouldBe initial
		initial.setSelected("targets", "one", false).validationErrors() shouldBe setOf("targets")
		initial
			.setSelected("targets", "two", true)
			.setSelected("targets", "three", true)
			.validationErrors() shouldBe setOf("targets")
	}

	test("coordinator follows discovery negotiation resolve and exposes only usable actions") {
		val gateway = FakeGateway()
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)

		coordinator.bind(ITEM_ONE)

		gateway.calls shouldContainExactly listOf("discover", "negotiate", "resolve:$ITEM_ONE")
		val surface = (events.last() as CanopyItemDetailEvent.Surface).value!!
		surface.itemId shouldBe ITEM_ONE
		surface.actions.map { it.id } shouldContainExactly listOf("usable")
		surface.statuses.map { it.label } shouldContainExactly listOf("Available")
	}

	test("action layout counts overflow within the five visible tile bound") {
		val actions = (1..8).map { index -> usableAction("action-$index", "Action $index") }

		val short = CanopyActionLayout.create(actions.take(5))
		short.direct.size shouldBe 5
		short.overflow shouldBe emptyList()
		short.visibleCount shouldBe 5

		val overflowed = CanopyActionLayout.create(actions)
		overflowed.direct.map { it.id } shouldContainExactly listOf("action-1", "action-2", "action-3", "action-4")
		overflowed.overflow.map { it.id } shouldContainExactly listOf("action-5", "action-6", "action-7", "action-8")
		overflowed.visibleCount shouldBe 5
	}

	test("absent and unauthorized discovery quietly leave the surface empty") {
		listOf<CanopyCallResult<CanopyDiscovery>>(CanopyCallResult.Absent, CanopyCallResult.Unauthorized).forEach { result ->
			val gateway = FakeGateway().apply { discovery = result }
			val events = mutableListOf<CanopyItemDetailEvent>()

			coordinator(gateway, events).bind(ITEM_ONE)

			gateway.calls shouldContainExactly listOf("discover")
			events shouldContainExactly listOf(CanopyItemDetailEvent.Surface(null))
		}
	}

	test("an item switch prevents a late old generation from replacing the new surface") {
		val firstResolution = CompletableDeferred<CanopyCallResult<CanopyResolvedSurface>>()
		val gateway = FakeGateway().apply {
			resolve = { itemId ->
				if (itemId == ITEM_ONE) firstResolution.await()
				else CanopyCallResult.Success(surface("second"))
			}
		}
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)

		coordinator.bind(ITEM_ONE)
		coordinator.bind(ITEM_TWO)
		firstResolution.complete(CanopyCallResult.Success(surface("first")))

		val shown = events.filterIsInstance<CanopyItemDetailEvent.Surface>().mapNotNull { it.value }
		shown.map { it.itemId } shouldContainExactly listOf(ITEM_TWO)
		shown.single().actions.single().label shouldBe "second"
	}

	test("stop invalidates a pending generation") {
		val resolution = CompletableDeferred<CanopyCallResult<CanopyResolvedSurface>>()
		val gateway = FakeGateway().apply { resolve = { resolution.await() } }
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)

		coordinator.bind(ITEM_ONE)
		coordinator.stop()
		resolution.complete(CanopyCallResult.Success(surface("late")))

		events.filterIsInstance<CanopyItemDetailEvent.Surface>().map { it.value } shouldContainExactly listOf(null, null)
	}

	test("expired preparation never exposes a form") {
		val gateway = FakeGateway().apply {
			prepareResponse = { CanopyCallResult.Success(preparedAction(expiresAt = NOW)) }
		}
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)
		coordinator.bind(ITEM_ONE)

		coordinator.prepare("usable")

		events.filterIsInstance<CanopyItemDetailEvent.Form>() shouldBe emptyList()
		(events.last() as CanopyItemDetailEvent.Message).fallback shouldBe
			CanopyItemDetailEvent.Message.Fallback.ACTION_EXPIRED
	}

	test("an item switch suppresses a late prepared-form event") {
		val preparation = CompletableDeferred<CanopyCallResult<CanopyPreparedAction>>()
		val gateway = FakeGateway().apply { prepareResponse = { preparation.await() } }
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)
		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")

		coordinator.bind(ITEM_TWO)
		preparation.complete(CanopyCallResult.Success(preparedAction()))

		events.filterIsInstance<CanopyItemDetailEvent.Form>() shouldBe emptyList()
		events.filterIsInstance<CanopyItemDetailEvent.Surface>().mapNotNull { it.value?.itemId } shouldContainExactly
			listOf(ITEM_ONE, ITEM_TWO)
	}

	test("invalid input is reported without invoking") {
		val gateway = FakeGateway()
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)
		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val form = (events.last() as CanopyItemDetailEvent.Form).value

		coordinator.submit(form, form.form)

		(events.last() as CanopyItemDetailEvent.InvalidForm).fieldIds shouldBe setOf("confirm")
		gateway.calls.none { it == "invoke" } shouldBe true
	}

	test("double submit invokes once with one fresh idempotency key and emits exact refresh targets") {
		val invocation = CompletableDeferred<CanopyCallResult<CanopyActionResult>>()
		val gateway = FakeGateway().apply { invokeResponse = { _, _, _ -> invocation.await() } }
		val events = mutableListOf<CanopyItemDetailEvent>()
		val keys = ArrayDeque(listOf(IDEMPOTENCY_ONE, IDEMPOTENCY_TWO))
		val coordinator = coordinator(gateway, events) { keys.removeFirst() }
		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = (events.last() as CanopyItemDetailEvent.Form).value
		val completed = prepared.form.setChecked("confirm", true)

		coordinator.submit(prepared, completed)
		coordinator.submit(prepared, completed)
		invocation.complete(
			CanopyCallResult.Success(
				CanopyActionResult(
					outcome = CanopyActionOutcome.SUCCEEDED,
					message = CanopyMessage("Done", CanopyTone.POSITIVE),
					catalogRevision = "r2",
					refreshTargets = setOf(CanopyRefreshTarget.JELLYFIN_ITEM, CanopyRefreshTarget.ITEM_DETAIL_SURFACE),
				),
			),
		)

		gateway.invocationKeys shouldContainExactly listOf(IDEMPOTENCY_ONE)
		events.filterIsInstance<CanopyItemDetailEvent.Submitting>().size shouldBe 1
		val refresh = events.filterIsInstance<CanopyItemDetailEvent.Refresh>().single()
		refresh.itemId shouldBe ITEM_ONE
		refresh.targets shouldBe setOf(CanopyRefreshTarget.JELLYFIN_ITEM, CanopyRefreshTarget.ITEM_DETAIL_SURFACE)
		(events.filterIsInstance<CanopyItemDetailEvent.Message>().single()).text shouldBe "Done"
	}

	test("retry of the same prepared form reuses its idempotency key") {
		var invocation = 0
		val gateway = FakeGateway().apply {
			invokeResponse = { _, _, _ ->
				invocation++
				if (invocation == 1) CanopyCallResult.Failure(CanopyFailureKind.TRANSPORT)
				else CanopyCallResult.Success(CanopyActionResult(CanopyActionOutcome.SUCCEEDED, null, null, emptySet()))
			}
		}
		val events = mutableListOf<CanopyItemDetailEvent>()
		val keys = ArrayDeque(listOf(IDEMPOTENCY_ONE, IDEMPOTENCY_TWO))
		val coordinator = coordinator(gateway, events) { keys.removeFirst() }
		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().single().value
		val completed = prepared.form.setChecked("confirm", true)

		coordinator.submit(prepared, completed)
		coordinator.submit(prepared, completed)

		gateway.invocationKeys shouldContainExactly listOf(IDEMPOTENCY_ONE, IDEMPOTENCY_ONE)
		events.filterIsInstance<CanopyItemDetailEvent.Message>().map { it.fallback } shouldContainExactly listOf(
			CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE,
			CanopyItemDetailEvent.Message.Fallback.ACTION_SUCCEEDED,
		)
	}

	test("prepare is suppressed while an invocation is in flight") {
		val invocation = CompletableDeferred<CanopyCallResult<CanopyActionResult>>()
		val gateway = FakeGateway().apply { invokeResponse = { _, _, _ -> invocation.await() } }
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)
		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().single().value

		coordinator.submit(prepared, prepared.form.setChecked("confirm", true))
		coordinator.prepare("usable")

		gateway.calls.count { it == "prepare" } shouldBe 1
		gateway.calls.count { it == "invoke" } shouldBe 1
		invocation.complete(CanopyCallResult.Success(CanopyActionResult(CanopyActionOutcome.SUCCEEDED, null, null, emptySet())))
	}
})

private class FakeGateway : CanopyGateway {
	val calls = mutableListOf<String>()
	val invocationKeys = mutableListOf<UUID>()
	var discovery: CanopyCallResult<CanopyDiscovery> = CanopyCallResult.Success(CanopyDiscovery(1, 1))
	var negotiation: CanopyCallResult<CanopyNegotiation> = CanopyCallResult.Success(CanopyNegotiation(true, 1, 1, 1))
	var resolve: suspend (UUID) -> CanopyCallResult<CanopyResolvedSurface> = { CanopyCallResult.Success(surface("Use action")) }
	var prepareResponse: suspend () -> CanopyCallResult<CanopyPreparedAction> = {
		CanopyCallResult.Success(preparedAction())
	}
	var invokeResponse: suspend (CanopyPreparedAction, UUID, List<CanopyAnswer>) -> CanopyCallResult<CanopyActionResult> = { _, _, _ ->
		CanopyCallResult.Success(
			CanopyActionResult(CanopyActionOutcome.SUCCEEDED, null, null, emptySet()),
		)
	}

	override suspend fun discover(): CanopyCallResult<CanopyDiscovery> {
		calls += "discover"
		return discovery
	}

	override suspend fun negotiate(protocolMinimum: Int, protocolMaximum: Int): CanopyCallResult<CanopyNegotiation> {
		calls += "negotiate"
		return negotiation
	}

	override suspend fun resolveItemDetail(itemId: UUID, locale: Locale): CanopyCallResult<CanopyResolvedSurface> {
		calls += "resolve:$itemId"
		return resolve(itemId)
	}

	override suspend fun prepare(prepareHandle: CanopyPrepareHandle): CanopyCallResult<CanopyPreparedAction> {
		calls += "prepare"
		return prepareResponse()
	}

	override suspend fun invoke(
		preparedAction: CanopyPreparedAction,
		idempotencyKey: UUID,
		answers: List<CanopyAnswer>,
	): CanopyCallResult<CanopyActionResult> {
		calls += "invoke"
		invocationKeys += idempotencyKey
		return invokeResponse(preparedAction, idempotencyKey, answers)
	}
}

private fun coordinator(
	gateway: CanopyGateway,
	events: MutableList<CanopyItemDetailEvent>,
	newKey: () -> UUID = { IDEMPOTENCY_ONE },
) = CanopyItemDetailCoordinator(
	scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
	gateway = gateway,
	clock = Clock.fixed(NOW, ZoneOffset.UTC),
	newIdempotencyKey = newKey,
	onEvent = events::add,
)

private fun surface(actionLabel: String) = CanopyResolvedSurface(
	catalogRevision = "r1",
	contributions = listOf(
		usableAction("usable", actionLabel),
		CanopyContribution.Action("disabled", "Disabled", null, CanopySemanticIcon.DEFAULT, false, null),
		CanopyContribution.Status("status", "Available", CanopyTone.POSITIVE),
	),
)

private fun usableAction(id: String, label: String) = CanopyContribution.Action(
	id = id,
	label = label,
	description = null,
	icon = CanopySemanticIcon.DEFAULT,
	enabled = true,
	prepareHandle = CanopyPrepareHandle("handle-$id"),
)

private fun preparedAction(expiresAt: Instant = NOW.plusSeconds(60)) = CanopyPreparedAction(
	capability = CanopyCapability("capability"),
	expiresAt = expiresAt,
	title = "Action",
	submitLabel = "Apply",
	cancelLabel = "Cancel",
	fields = listOf(
		CanopyField.Confirmation("confirm", "Confirm", null, true, false),
		CanopyField.BooleanValue("enabled", "Enabled", null, false, true),
		CanopyField.SingleSelect(
			"quality",
			"Quality",
			null,
			true,
			listOf(
				CanopyOption("balanced", "Balanced", null, false),
				CanopyOption("fast", "Fast", null, false),
				CanopyOption("disabled", "Disabled", null, true),
			),
			"balanced",
		),
		CanopyField.MultiSelect(
			"targets",
			"Targets",
			null,
			true,
			listOf(
				CanopyOption("one", "One", null, false),
				CanopyOption("two", "Two", null, false),
				CanopyOption("three", "Three", null, false),
				CanopyOption("disabled", "Disabled", null, true),
			),
			setOf("one"),
			minimumSelections = 1,
			maximumSelections = 2,
		),
	),
)

private val NOW = Instant.parse("2026-08-03T00:00:00Z")
private val ITEM_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001")
private val ITEM_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002")
private val IDEMPOTENCY_ONE = UUID.fromString("10000000-0000-0000-0000-000000000001")
private val IDEMPOTENCY_TWO = UUID.fromString("10000000-0000-0000-0000-000000000002")
