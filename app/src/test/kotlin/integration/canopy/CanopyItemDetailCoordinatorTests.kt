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
			events shouldContainExactly listOf(
				CanopyItemDetailEvent.InvalidateForm,
				CanopyItemDetailEvent.Surface(null),
			)
		}
	}

	test("same-item rebind preserves form ownership and reattaches the cached surface") {
		val gateway = FakeGateway()
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)

		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().single().value
		val callsBeforeRebind = gateway.calls.toList()
		coordinator.bind(ITEM_ONE)
		coordinator.submit(prepared, prepared.form.setChecked("confirm", true))

		gateway.calls.take(callsBeforeRebind.size) shouldContainExactly callsBeforeRebind
		gateway.calls.count { it == "invoke" } shouldBe 1
		events.filterIsInstance<CanopyItemDetailEvent.InvalidateForm>().size shouldBe 1
	}

	test("external refresh queues behind an owned form and runs when the dialog dismisses it") {
		val gateway = FakeGateway()
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)

		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().single().value
		val resolveCallsBeforeRefresh = gateway.calls.count { it.startsWith("resolve:") }

		coordinator.requestSurfaceRefresh()
		gateway.calls.count { it.startsWith("resolve:") } shouldBe resolveCallsBeforeRefresh
		coordinator.dismissForm(prepared)

		gateway.calls.count { it.startsWith("resolve:") } shouldBe resolveCallsBeforeRefresh + 1
		events.filterIsInstance<CanopyItemDetailEvent.InvalidateForm>().size shouldBe 1
		events.filterIsInstance<CanopyItemDetailEvent.Surface>().count { it.value == null } shouldBe 1
	}

	test("explicit authority rejection on refresh revokes the row and every old action handle") {
		val explicitRejections: List<(FakeGateway) -> Unit> = listOf(
			{ it.discovery = CanopyCallResult.Absent },
			{ it.discovery = CanopyCallResult.Unauthorized },
			{ it.discovery = CanopyCallResult.Forbidden },
			{ it.negotiation = CanopyCallResult.Absent },
			{ it.negotiation = CanopyCallResult.Unauthorized },
			{ it.negotiation = CanopyCallResult.Forbidden },
			{ it.negotiation = CanopyCallResult.Success(CanopyNegotiation(false, null, 1, 1)) },
			{ it.resolve = { CanopyCallResult.Absent } },
			{ it.resolve = { CanopyCallResult.Unauthorized } },
			{ it.resolve = { CanopyCallResult.Forbidden } },
			{
				it.resolve = {
					CanopyCallResult.Failure(
						kind = CanopyFailureKind.HTTP,
						status = 404,
						error = platformError("not_found"),
					)
				}
			},
			{ it.discovery = CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE) },
			{ it.negotiation = CanopyCallResult.Failure(CanopyFailureKind.UNSUPPORTED_CONTRACT) },
			{ it.resolve = { CanopyCallResult.Failure(CanopyFailureKind.BUFFERED_RESPONSE_TOO_LARGE) } },
		)

		explicitRejections.forEach { reject ->
			val gateway = FakeGateway()
			val events = mutableListOf<CanopyItemDetailEvent>()
			val coordinator = coordinator(gateway, events)
			coordinator.bind(ITEM_ONE)

			reject(gateway)
			coordinator.requestSurfaceRefresh()
			coordinator.prepare("usable")

			events.takeLast(2) shouldContainExactly listOf(
				CanopyItemDetailEvent.InvalidateForm,
				CanopyItemDetailEvent.Surface(null),
			)
			gateway.preparedHandles shouldBe emptyList()
		}
	}

	test("explicit rejection stays queued until the owned form is dismissed then revokes it") {
		val gateway = FakeGateway()
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)
		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().single().value
		gateway.discovery = CanopyCallResult.Absent

		coordinator.requestSurfaceRefresh()
		events.last() shouldBe CanopyItemDetailEvent.Form(prepared)
		coordinator.dismissForm(prepared)
		coordinator.prepare("usable")
		coordinator.submit(prepared, prepared.form.setChecked("confirm", true))

		events.takeLast(2) shouldContainExactly listOf(
			CanopyItemDetailEvent.InvalidateForm,
			CanopyItemDetailEvent.Surface(null),
		)
		gateway.preparedHandles shouldContainExactly listOf("handle-usable")
		gateway.calls.count { it == "invoke" } shouldBe 0
	}

	test("transient transport and server refresh failures preserve the last bounded authority") {
		val transientFailures: List<(FakeGateway) -> Unit> = listOf(
			{ it.discovery = CanopyCallResult.Failure(CanopyFailureKind.TRANSPORT) },
			{
				it.resolve = {
					CanopyCallResult.Failure(
						kind = CanopyFailureKind.HTTP,
						status = 503,
						error = platformError("unavailable"),
					)
				}
			},
		)

		transientFailures.forEach { fail ->
			val gateway = FakeGateway()
			val events = mutableListOf<CanopyItemDetailEvent>()
			val coordinator = coordinator(gateway, events)
			coordinator.bind(ITEM_ONE)
			fail(gateway)

			coordinator.requestSurfaceRefresh()
			coordinator.prepare("usable")

			events.filterIsInstance<CanopyItemDetailEvent.Surface>().map { it.value }.count { it == null } shouldBe 1
			events.filterIsInstance<CanopyItemDetailEvent.InvalidateForm>().size shouldBe 1
			gateway.preparedHandles shouldContainExactly listOf("handle-usable")
		}
	}

	test("refresh suppresses only an exactly unchanged resolve representation and ETag") {
		val etagOne = "\"sha256-${"a".repeat(64)}\""
		val etagTwo = "\"sha256-${"b".repeat(64)}\""
		var response = CanopyCallResult.Success(surface("Use action"), etag = etagOne)
		val gateway = FakeGateway().apply { resolve = { response } }
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)

		coordinator.bind(ITEM_ONE)
		coordinator.requestSurfaceRefresh()
		events.filterIsInstance<CanopyItemDetailEvent.Surface>().mapNotNull { it.value }.size shouldBe 1

		response = CanopyCallResult.Success(surface("Use action"), etag = etagTwo)
		coordinator.requestSurfaceRefresh()
		events.filterIsInstance<CanopyItemDetailEvent.Surface>().mapNotNull { it.value }.size shouldBe 2

		response = CanopyCallResult.Success(
			surface("Use action", prepareHandle = "replacement-handle"),
			etag = etagTwo,
		)
		coordinator.requestSurfaceRefresh()
		coordinator.prepare("usable")

		events.filterIsInstance<CanopyItemDetailEvent.Surface>().mapNotNull { it.value }.size shouldBe 3
		gateway.preparedHandles shouldContainExactly listOf("replacement-handle")
	}

	test("an item switch invalidates form ownership and the stale form cannot submit") {
		val gateway = FakeGateway()
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)

		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().single().value
		coordinator.bind(ITEM_TWO)
		coordinator.submit(prepared, prepared.form.setChecked("confirm", true))

		gateway.calls.count { it == "invoke" } shouldBe 0
		events.filterIsInstance<CanopyItemDetailEvent.InvalidateForm>().size shouldBe 2
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

	test("an absent prepared action fails closed as unavailable") {
		val gateway = FakeGateway().apply { prepareResponse = { CanopyCallResult.Absent } }
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)
		coordinator.bind(ITEM_ONE)

		coordinator.prepare("usable")

		events.filterIsInstance<CanopyItemDetailEvent.Form>() shouldBe emptyList()
		(events.last() as CanopyItemDetailEvent.Message).fallback shouldBe
			CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE
	}

	test("an absent invoke emits unavailable and permits an exact retry") {
		var invocation = 0
		val gateway = FakeGateway().apply {
			invokeResponse = { _, _, _ ->
				invocation++
				if (invocation == 1) CanopyCallResult.Absent
				else CanopyCallResult.Success(CanopyActionResult(CanopyActionOutcome.SUCCEEDED, null, null, emptySet()))
			}
		}
		val events = mutableListOf<CanopyItemDetailEvent>()
		val coordinator = coordinator(gateway, events)
		coordinator.bind(ITEM_ONE)
		coordinator.prepare("usable")
		val prepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().single().value
		val completed = prepared.form.setChecked("confirm", true)

		coordinator.submit(prepared, completed)
		coordinator.submit(prepared, completed)

		gateway.calls.count { it == "invoke" } shouldBe 2
		gateway.invocationKeys.distinct().size shouldBe 1
		gateway.invocationAnswers shouldContainExactly listOf(completed.answers(), completed.answers())
		events.filterIsInstance<CanopyItemDetailEvent.Message>().map { it.fallback } shouldContainExactly listOf(
			CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE,
			CanopyItemDetailEvent.Message.Fallback.ACTION_SUCCEEDED,
		)
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

	test("retry reuses the first submitted answers and key even if a changed form is supplied") {
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
		val firstSubmission = prepared.form
			.setChecked("confirm", true)
			.selectOne("quality", "fast")
		val changedAfterFailure = firstSubmission
			.setChecked("enabled", false)
			.selectOne("quality", "balanced")
			.setSelected("targets", "two", true)

		coordinator.submit(prepared, firstSubmission)
		coordinator.submit(prepared, changedAfterFailure)

		gateway.invocationKeys shouldContainExactly listOf(IDEMPOTENCY_ONE, IDEMPOTENCY_ONE)
		gateway.invocationAnswers shouldContainExactly listOf(firstSubmission.answers(), firstSubmission.answers())
		events.filterIsInstance<CanopyItemDetailEvent.Message>().map { it.fallback } shouldContainExactly listOf(
			CanopyItemDetailEvent.Message.Fallback.ACTION_UNAVAILABLE,
			CanopyItemDetailEvent.Message.Fallback.ACTION_SUCCEEDED,
		)
	}

	test("re-preparing allocates a new key and permits a different answer snapshot") {
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
		val firstPrepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().last().value
		val firstAnswers = firstPrepared.form.setChecked("confirm", true).selectOne("quality", "fast")
		coordinator.submit(firstPrepared, firstAnswers)

		coordinator.prepare("usable")
		val secondPrepared = events.filterIsInstance<CanopyItemDetailEvent.Form>().last().value
		val secondAnswers = secondPrepared.form.setChecked("confirm", true).setChecked("enabled", false)
		coordinator.submit(secondPrepared, secondAnswers)

		gateway.invocationKeys shouldContainExactly listOf(IDEMPOTENCY_ONE, IDEMPOTENCY_TWO)
		gateway.invocationAnswers shouldContainExactly listOf(firstAnswers.answers(), secondAnswers.answers())
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
	val preparedHandles = mutableListOf<String>()
	val invocationKeys = mutableListOf<UUID>()
	val invocationAnswers = mutableListOf<List<CanopyAnswer>>()
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
		preparedHandles += prepareHandle.wireValue()
		return prepareResponse()
	}

	override suspend fun invoke(
		preparedAction: CanopyPreparedAction,
		idempotencyKey: UUID,
		answers: List<CanopyAnswer>,
	): CanopyCallResult<CanopyActionResult> {
		calls += "invoke"
		invocationKeys += idempotencyKey
		invocationAnswers += answers.toList()
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

private fun surface(
	actionLabel: String,
	catalogRevision: String = "r1",
	prepareHandle: String = "handle-usable",
) = CanopyResolvedSurface(
	catalogRevision = catalogRevision,
	contributions = listOf(
		usableAction("usable", actionLabel, prepareHandle),
		CanopyContribution.Action("disabled", "Disabled", null, CanopySemanticIcon.DEFAULT, false, null),
		CanopyContribution.Status("status", "Available", CanopyTone.POSITIVE),
	),
)

private fun usableAction(id: String, label: String, prepareHandle: String = "handle-$id") = CanopyContribution.Action(
	id = id,
	label = label,
	description = null,
	icon = CanopySemanticIcon.DEFAULT,
	enabled = true,
	prepareHandle = CanopyPrepareHandle(prepareHandle),
)

private fun platformError(code: String) = CanopyPlatformError(
	code = code,
	message = "Platform request failed.",
	retryable = code == "unavailable",
	correlationId = "0123456789abcdef0123456789abcdef",
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
